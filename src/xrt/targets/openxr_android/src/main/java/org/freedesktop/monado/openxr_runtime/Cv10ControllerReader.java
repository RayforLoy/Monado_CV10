// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;

/** JNI entry point used by the out-of-process runtime. */
public final class Cv10ControllerReader {
    private static final long REPORT_TIMEOUT_NS = 250_000_000L;
    private final Cv10ControllerLauncherInput launcherInput;
    private final Cv10ControllerLink link;

    private Cv10ControllerReader(Context context, String address) {
        launcherInput = address != null ? new Cv10ControllerLauncherInput(context) : null;
        link = address != null ? new Cv10ControllerLink(context, address, launcherInput) : null;
    }

    public static boolean isConfigured(Context context) {
        return hasCf20(context) || Cv10PhoneControllerService.isRunning();
    }

    private static boolean hasCf20(Context context) {
        return BluetoothAdapter.checkBluetoothAddress(Cv10ControllerActivity.savedAddress(context))
                && Cv10ControllerLink.canConnect(context);
    }

    public static Cv10ControllerReader open(Context context) {
        String address = hasCf20(context) ? Cv10ControllerActivity.savedAddress(context) : null;
        return new Cv10ControllerReader(context, address);
    }

    /** Returns zero without an input report; otherwise returns the local monotonic receipt time. */
    public long read(float[] values) {
        Cv10ControllerLink.Sample sample = link == null ? null : link.latestInput;
        if (sample == null || sample.report == null || System.nanoTime() - sample.arrivalNs > REPORT_TIMEOUT_NS) {
            return Cv10PhoneControllerService.read(values);
        }
        Cv10ControllerReport report = sample.report;
        values[0] = report.x;
        values[1] = report.y;
        values[2] = report.z;
        values[3] = report.w;
        values[4] = report.orientationValid ? 1f : 0f;
        values[5] = report.isPressed(Cv10ControllerReport.TRIGGER | Cv10ControllerReport.CONFIRM) ? 1f : 0f;
        values[6] = report.isPressed(Cv10ControllerReport.BACK) ? 1f : 0f;
        values[7] = 0f;
        return sample.arrivalNs;
    }

    public void close() {
        if (link != null) link.close();
        if (launcherInput != null) launcherInput.close();
    }
}
