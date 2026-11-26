// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import static org.freedesktop.monado.auxiliary.Cv10DisplayPortLink.Evidence.TRAINED;
import static org.freedesktop.monado.auxiliary.Cv10DisplayPortLink.Evidence.TYPE_C_PIN_ASSIGNMENT;
import static org.freedesktop.monado.auxiliary.Cv10DisplayPortLink.Evidence.UNREADABLE;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class Cv10DisplayPortLinkTest {
    @Rule public TemporaryFolder sys = new TemporaryFolder();
    private File drm;
    private File dev;
    private File typeC;

    @Before
    public void createDirectories() throws IOException {
        drm = sys.newFolder("drm");
        dev = sys.newFolder("dev");
        typeC = sys.newFolder("typec");
    }

    @Test
    public void readsTrainedLanesAndPrefersThemOverPinAssignment() throws IOException {
        connectMonitor("HUAWEIAV03");
        connectTypeC("C D [E]", "1");
        for (int lanes : new int[] {1, 2, 4}) {
            writeDpcd(lanes, 0, 0x77, 0x77, 1);
            Cv10DisplayPortLink.Result result = read(true);
            assertEquals(TRAINED, result.evidence);
            assertEquals(lanes, result.lanes);
        }
    }

    @Test
    public void incompleteTrainingFallsBackToPinAssignment() throws IOException {
        connectMonitor("HUAWEIAV03");
        connectTypeC("C [D] E", "1");
        writeDpcd(2, 0, 0x17, 0, 1);

        Cv10DisplayPortLink.Result result = read(true);
        assertEquals(TYPE_C_PIN_ASSIGNMENT, result.evidence);
        assertEquals(2, result.lanes);
        assertEquals("D", result.source);
    }

    @Test
    public void activeTrainingPatternAndMissingAlignmentAreUnreadable() throws IOException {
        connectMonitor("HUAWEIAV03");
        writeDpcd(2, 2, 0x77, 0, 1);
        assertEquals(UNREADABLE, read(true).evidence);
        writeDpcd(2, 0, 0x77, 0, 0);
        assertEquals(UNREADABLE, read(true).evidence);
    }

    @Test
    public void maximumLaneCapabilityDoesNotCountAsTraining() throws IOException {
        connectMonitor("HUAWEIAV03");
        writeDpcd(0, 0, 0x77, 0x77, 1);

        assertEquals(UNREADABLE, read(true).evidence);
    }

    @Test
    public void ignoresAnUnrelatedMonitorAndUnmappedAuxNodes() throws IOException {
        connectMonitor("Other Monitor");
        writeDpcd(2, 0, 0x77, 0, 1);
        assertEquals(UNREADABLE, read(true).evidence);

        Path connector = connectMonitor("HUAWEIAV03");
        Files.delete(connector.resolve("drm_dp_aux0"));
        assertEquals(UNREADABLE, read(true).evidence);
    }

    @Test
    public void fourLanePinAssignmentIsAnIndirectClue() throws IOException {
        connectTypeC("[C] D E", "1");
        Cv10DisplayPortLink.Result result = read(true);
        assertEquals(TYPE_C_PIN_ASSIGNMENT, result.evidence);
        assertEquals(4, result.lanes);
        assertEquals(UNREADABLE, read(false).evidence);
    }

    @Test
    public void ignoresInactiveAndAmbiguousPinAssignments() throws IOException {
        connectTypeC("C D E", "1");
        assertEquals(UNREADABLE, read(true).evidence);
        connectTypeC("[C] [D] E", "1");
        assertEquals(UNREADABLE, read(true).evidence);
        connectTypeC("C [D] E", "0");
        assertEquals(UNREADABLE, read(true).evidence);
        Path altMode = connectTypeC("C [D] E", "1");
        writeLine(altMode.resolve("configuration"), "USB [source] sink");
        assertEquals(UNREADABLE, read(true).evidence);
        writeLine(altMode.resolve("configuration"), "USB source [sink]");
        Files.createDirectory(typeC.toPath().resolve("port1-partner"));
        assertEquals(UNREADABLE, read(true).evidence);
    }

    private Cv10DisplayPortLink.Result read(boolean cv10Display) {
        return Cv10DisplayPortLink.read(drm, dev, typeC, cv10Display);
    }

    private Path connectMonitor(String displayName) throws IOException {
        Path connector = drm.toPath().resolve("card0-DP-1");
        Files.createDirectories(connector.resolve("drm_dp_aux0"));
        writeLine(connector.resolve("status"), "connected");
        writeLine(connector.resolve("enabled"), "enabled");

        byte[] edid = new byte[128];
        Arrays.fill(edid, 1, 7, (byte) 0xff);
        edid[57] = (byte) 0xfc;
        byte[] name = displayName.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, edid, 59, name.length);
        Files.write(connector.resolve("edid"), edid);
        return connector;
    }

    private Path connectTypeC(String pins, String hpd) throws IOException {
        Path partner = typeC.toPath().resolve("port0-partner");
        Path altMode = typeC.toPath().resolve("port0-partner.0/displayport");
        Files.createDirectories(partner);
        Files.createDirectories(altMode);
        writeLine(altMode.resolve("configuration"), "USB source [sink]");
        writeLine(altMode.resolve("pin_assignment"), pins);
        writeLine(altMode.resolve("hpd"), hpd);
        return altMode;
    }

    private void writeDpcd(int lanes, int pattern, int status01, int status23, int align)
            throws IOException {
        byte[] dpcd = new byte[0x205];
        dpcd[0x002] = 4;
        dpcd[0x101] = (byte) (0x80 | lanes);
        dpcd[0x102] = (byte) pattern;
        dpcd[0x202] = (byte) status01;
        dpcd[0x203] = (byte) status23;
        dpcd[0x204] = (byte) align;
        Files.write(dev.toPath().resolve("drm_dp_aux0"), dpcd);
    }

    private static void writeLine(Path path, String line) throws IOException {
        Files.write(path, (line + "\n").getBytes(StandardCharsets.US_ASCII));
    }
}
