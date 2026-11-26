// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the DP link state where the kernel exposes it to the app. */
final class Cv10DisplayPortLink {
    static final String DISPLAY_NAME = "HUAWEIAV03";

    private static final Pattern DP_CONNECTOR = Pattern.compile("card\\d+-DP-\\d+");
    private static final Pattern AUX_DEVICE = Pattern.compile("drm_dp_aux\\d+");
    private static final Pattern TYPE_C_PARTNER = Pattern.compile("port\\d+-partner");
    private static final Pattern PIN_ASSIGNMENT = Pattern.compile("\\[([CDE])\\]");
    private static final int DP_LANE_COUNT_SET = 0x101;
    private static final int DP_LANE0_1_STATUS = 0x202;
    private static final int DP_LANE_COUNT_MASK = 0x1f;
    private static final int DP_TRAINING_PATTERN_MASK = 0x0f;
    private static final int DP_CHANNEL_EQ_BITS = 0x07;
    private static final int DP_INTERLANE_ALIGN_DONE = 0x01;

    enum Evidence {
        TRAINED,
        TYPE_C_PIN_ASSIGNMENT,
        UNREADABLE
    }

    static final class Result {
        final Evidence evidence;
        final int lanes;
        final String source;

        Result(Evidence evidence, int lanes, String source) {
            this.evidence = evidence;
            this.lanes = lanes;
            this.source = source;
        }
    }

    static final Result UNREADABLE = new Result(Evidence.UNREADABLE, 0, "");

    private Cv10DisplayPortLink() {}

    static Result read(File drm, File dev, File typeC, boolean hasCv10Display) {
        Result trained = readTrainedLink(drm, dev);
        if (trained != UNREADABLE) return trained;
        return hasCv10Display ? readPinAssignment(typeC) : UNREADABLE;
    }

    private static Result readTrainedLink(File drm, File dev) {
        try {
            File[] connectors =
                    drm.listFiles((dir, name) -> DP_CONNECTOR.matcher(name).matches());
            if (connectors == null) return UNREADABLE;

            File cv10 = null;
            for (File connector : connectors) {
                if (!isEnabled(connector) || !hasCv10Edid(connector)) continue;
                if (cv10 != null) return UNREADABLE;
                cv10 = connector;
            }
            if (cv10 == null) return UNREADABLE;

            // This sysfs child ties the AUX device to the connector with the CV10 EDID.
            File[] aux = cv10.listFiles((dir, name) -> AUX_DEVICE.matcher(name).matches());
            if (aux == null || aux.length != 1) return UNREADABLE;
            File node = new File(dev, aux[0].getName());
            try (RandomAccessFile dpcd = new RandomAccessFile(node, "r")) {
                int lanes = readTrainedLanes(dpcd);
                if (lanes != 0 && isEnabled(cv10) && hasCv10Edid(cv10)) {
                    return new Result(Evidence.TRAINED, lanes, node.getPath());
                }
            }
        } catch (IOException | SecurityException ignored) {
            // The AUX node is optional and may be inaccessible or disappear on unplug.
        }
        return UNREADABLE;
    }

    private static boolean isEnabled(File connector) {
        return "connected".equals(readLine(new File(connector, "status")))
                && "enabled".equals(readLine(new File(connector, "enabled")));
    }

    private static boolean hasCv10Edid(File connector) {
        byte[] base = new byte[128];
        try (DataInputStream edid =
                new DataInputStream(new FileInputStream(new File(connector, "edid")))) {
            edid.readFully(base);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
        if (base[0] != 0 || base[7] != 0) return false;
        for (int i = 1; i < 7; i++) {
            if (base[i] != (byte) 0xff) return false;
        }
        for (int offset = 54; offset < 126; offset += 18) {
            if (base[offset] != 0
                    || base[offset + 1] != 0
                    || base[offset + 2] != 0
                    || base[offset + 3] != (byte) 0xfc
                    || base[offset + 4] != 0) continue;
            String name = new String(base, offset + 5, 13, StandardCharsets.US_ASCII).trim();
            if (DISPLAY_NAME.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static int readTrainedLanes(RandomAccessFile dpcd) throws IOException {
        // LANE_COUNT_SET is distinct from the sink's MAX_LANE_COUNT capability.
        byte[] configuration = new byte[2];
        dpcd.seek(DP_LANE_COUNT_SET);
        dpcd.readFully(configuration);
        byte laneCountSet = configuration[0];
        int lanes = laneCountSet & DP_LANE_COUNT_MASK;
        if ((lanes != 1 && lanes != 2 && lanes != 4)
                || (configuration[1] & DP_TRAINING_PATTERN_MASK) != 0) return 0;

        byte[] status = new byte[3];
        dpcd.seek(DP_LANE0_1_STATUS);
        dpcd.readFully(status);
        if ((status[2] & DP_INTERLANE_ALIGN_DONE) == 0) return 0;
        for (int lane = 0; lane < lanes; lane++) {
            int laneStatus = status[lane / 2] >> (4 * (lane % 2));
            if ((laneStatus & DP_CHANNEL_EQ_BITS) != DP_CHANNEL_EQ_BITS) return 0;
        }
        // Reject a snapshot if link training restarted during the read.
        dpcd.seek(DP_LANE_COUNT_SET);
        dpcd.readFully(configuration);
        return configuration[0] == laneCountSet && (configuration[1] & DP_TRAINING_PATTERN_MASK) == 0
                ? lanes
                : 0;
    }

    private static Result readPinAssignment(File typeC) {
        try {
            File[] partners =
                    typeC.listFiles((dir, name) -> TYPE_C_PARTNER.matcher(name).matches());
            if (partners == null || partners.length != 1) return UNREADABLE;

            Pattern partnerMode = Pattern.compile(Pattern.quote(partners[0].getName()) + "\\.\\d+");
            File[] altModes = typeC.listFiles((dir, name) -> partnerMode.matcher(name).matches());
            if (altModes == null) return UNREADABLE;

            Result hint = UNREADABLE;
            for (File altMode : altModes) {
                File dp = new File(altMode, "displayport");
                String configuration = readLine(new File(dp, "configuration"));
                String hpd = readLine(new File(dp, "hpd"));
                String pins = readLine(new File(dp, "pin_assignment"));
                // This configuration is for the partner, which acts as the DP sink.
                if (configuration == null
                        || !configuration.contains("[sink]")
                        || (hpd != null && !"1".equals(hpd))
                        || pins == null) continue;
                Matcher active = PIN_ASSIGNMENT.matcher(pins);
                if (!active.find()) continue;
                String pin = active.group(1);
                if (hint != UNREADABLE || active.find()) return UNREADABLE;
                hint = new Result(Evidence.TYPE_C_PIN_ASSIGNMENT, "D".equals(pin) ? 2 : 4, pin);
            }
            return hint;
        } catch (SecurityException ignored) {
            return UNREADABLE;
        }
    }

    private static String readLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }
}
