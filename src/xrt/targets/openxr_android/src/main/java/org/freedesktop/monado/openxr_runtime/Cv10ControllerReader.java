// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/** JNI entry point used by the out-of-process runtime. */
public final class Cv10ControllerReader {
    private static final long REPORT_TIMEOUT_NS = 250_000_000L;
    private final Cv10ControllerLauncherInput launcherInput;
    private final Cv10ControllerLink link;
    private final String address;
    private final SharedPreferences simulation;
    private final Cv10SimulationCalibration calibration = new Cv10SimulationCalibration();
    private long lastCalibrationReport;
    private boolean suppressTrigger;

    private Cv10ControllerReader(Context context, String address) {
        this.address = address;
        simulation = Cv10SimulationSettings.preferences(context);
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
            calibration.reset();
            suppressTrigger = false;
            return Cv10PhoneControllerService.read(values);
        }
        Cv10ControllerReport report = sample.report;
        boolean trigger = report.isPressed(Cv10ControllerReport.TRIGGER);
        if (!trigger) {
            suppressTrigger = false;
            calibration.reset();
        } else if (sample.arrivalNs != lastCalibrationReport) {
            long token = Cv10SimulationSettings.pending(simulation, address);
            if (token != 0) {
                suppressTrigger = true;
                if (report.orientationValid) {
                    Cv10SimulationCalibration.Offset offset = calibration.sample(token, Cv10SimulationSettings.head,
                            new Cv10SimulationCalibration.Orientation(sample.arrivalNs,
                                    report.x, report.y, report.z, report.w));
                    if (offset != null && Cv10SimulationSettings.complete(simulation, address, token, offset)) {
                        calibration.reset();
                        Log.i("Cv10Controller", "Saved CF20 right-side alignment and eye height");
                    }
                } else {
                    calibration.reset();
                }
            }
            lastCalibrationReport = sample.arrivalNs;
        }
        values[0] = report.x;
        values[1] = report.y;
        values[2] = report.z;
        values[3] = report.w;
        values[4] = report.orientationValid ? 1f : 0f;
        values[5] = (trigger && !suppressTrigger) || report.isPressed(Cv10ControllerReport.CONFIRM) ? 1f : 0f;
        values[6] = report.isPressed(Cv10ControllerReport.BACK) ? 1f : 0f;
        values[7] = 0f;
        values[8] = Cv10SimulationSettings.yaw(simulation, address);
        return sample.arrivalNs;
    }

    public void close() {
        if (link != null) link.close();
        if (launcherInput != null) launcherInput.close();
    }
}
