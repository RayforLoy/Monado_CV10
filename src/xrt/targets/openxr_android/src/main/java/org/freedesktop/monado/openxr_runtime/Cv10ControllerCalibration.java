// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** The original CF20 version query and the calibration flags it gates. */
final class Cv10ControllerCalibration {
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}");

    private Cv10ControllerCalibration() {}

    static byte[] requestVersion() {
        return new byte[] {1, 7, 7, 0};
    }

    static String parseVersionResponse(byte[] value) {
        if (value == null || value.length < 4 || value[0] != 1 || value[1] != 7) return null;
        String version = null;
        for (int offset = 2; offset < value.length;) {
            if (value.length - offset < 2) return null;
            int type = value[offset++] & 0xff;
            int part = value[offset++] & 0xff;
            int length = part & 0x7f;
            for (int extra = 0; (part & 0x80) != 0; extra++) {
                if (extra == 2 || offset == value.length) return null;
                part = value[offset++] & 0xff;
                length = (length << 7) | (part & 0x7f);
            }
            if (length > value.length - offset) return null;
            if (type == 7) {
                version = new String(value, offset, length, StandardCharsets.US_ASCII);
                if (!VERSION.matcher(version).matches()) return null;
            }
            offset += length;
        }
        return version;
    }

    static boolean supportsFlags(String version) {
        if (version == null || !VERSION.matcher(version).matches()) return false;
        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        int patch = Integer.parseInt(parts[2]);
        return major > 4 || (major == 4 && (minor > 0 || patch >= 5));
    }

    static String describe(String version, Cv10ControllerReport report) {
        if (version == null) return "Calibration flags: unavailable until firmware is known";
        if (!supportsFlags(version)) return "Calibration flags: unsupported by this firmware";
        if (report == null) return "Calibration flags: waiting for input";
        return "Calibration flags · Figure eight: " + (report.figureEightDone ? "complete" : "pending") +
                " · Stillness: " + (report.stillnessDone ? "complete" : "pending");
    }
}
