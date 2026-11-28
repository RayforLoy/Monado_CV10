// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.freedesktop.monado.auxiliary.Cv10ControllerRoles;

/** JNI entry point used by the out-of-process runtime. */
public final class Cv10ControllerReader {
    private static final long REPORT_TIMEOUT_NS = 250_000_000L;
    private final Cv10ControllerLauncherInput launcherInput;
    private final Cv10ControllerLink link;
    private final Context context;
    private final String address;
    private final SharedPreferences simulation;
    private final Cv10SimulationCalibration calibration = new Cv10SimulationCalibration();
    private final Cv10SimulationCalibration.Heading heading = new Cv10SimulationCalibration.Heading();
    private long lastHeadingLog;
    private long lastGyroLog;
    private final Cv10OneShotCalibration positionCalibration = new Cv10OneShotCalibration();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable positionTimeout = this::abortPositionCalibration;
    private long positionRevision;
    private ToneGenerator tones;
    private long lastCalibrationReport;
    private boolean suppressTrigger;

    private Cv10ControllerReader(Context context, String address) {
        this.context = context;
        this.address = address;
        simulation = Cv10SimulationSettings.preferences(context);
        launcherInput = address != null ? new Cv10ControllerLauncherInput(context,
                this::oneShotEnabled, this::togglePositionCalibration) : null;
        link = address != null ? new Cv10ControllerLink(context, address, new Cv10ControllerLink.Listener() {
            @Override
            public void onReady(boolean ready) {
                if (!ready) abortPositionCalibration();
                launcherInput.onReady(ready);
            }

            @Override
            public void onInput(Cv10ControllerLink.Sample sample) {
                if (positionCalibration.running() && (!oneShotEnabled() ||
                        positionRevision != Cv10SimulationSettings.positionRevision(simulation) ||
                        !positionCalibration.sample(sample.arrivalNs, sample.report))) {
                    abortPositionCalibration();
                }
                if (Cv10ControllerRoles.mode(context) != Cv10ControllerRoles.PHONE_ONLY) launcherInput.onInput(sample);
            }
        }) : null;
    }

    private boolean oneShotEnabled() {
        return Cv10SimulationSettings.positionMode(simulation) == Cv10SimulationSettings.ONE_SHOT &&
                !Cv10KitTrackingService.trackingRequested();
    }

    private void togglePositionCalibration(Cv10ControllerLink.Sample sample) {
        if (positionCalibration.running()) {
            Cv10OneShotCalibration.Position position = positionCalibration.finish();
            if (position == null || !Cv10SimulationSettings.saveShot(simulation, positionRevision, address,
                    position, positionCalibration.yaw())) {
                abortPositionCalibration();
                return;
            }
            resetPositionCalibration();
            playTone(ToneGenerator.TONE_PROP_ACK);
            Log.i("Cv10Controller", "Saved CF20 single-move position");
            return;
        }
        positionRevision = Cv10SimulationSettings.positionRevision(simulation);
        if (positionCalibration.start(sample.arrivalNs, sample.report, Cv10SimulationSettings.head,
                Cv10SimulationSettings.height(simulation))) {
            Cv10SimulationSettings.positionStatus(simulation, positionRevision, Cv10SimulationSettings.POSITION_RECORDING);
            handler.postDelayed(positionTimeout, Cv10OneShotCalibration.DURATION_SECONDS * 1000L);
            playTone(ToneGenerator.TONE_PROP_BEEP);
        } else {
            reportPositionFailure();
        }
    }

    private void abortPositionCalibration() {
        if (!positionCalibration.running()) return;
        resetPositionCalibration();
        reportPositionFailure();
    }

    private void resetPositionCalibration() {
        positionCalibration.reset();
        handler.removeCallbacks(positionTimeout);
    }

    private void reportPositionFailure() {
        Cv10SimulationSettings.positionStatus(simulation, positionRevision, Cv10SimulationSettings.POSITION_FAILED);
        playTone(ToneGenerator.TONE_PROP_NACK);
        Log.i("Cv10Controller", "CF20 single-move position calibration did not complete");
    }

    private void playTone(int tone) {
        try {
            if (tones == null) tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 65);
            tones.startTone(tone, 140);
        } catch (RuntimeException unavailable) {
            Log.d("Cv10Controller", "Calibration tone unavailable");
        }
    }

    private static boolean hasCf20(Context context) {
        return BluetoothAdapter.checkBluetoothAddress(Cv10ControllerActivity.savedAddress(context))
                && Cv10ControllerLink.canConnect(context);
    }

    public static Cv10ControllerReader open(Context context) {
        String address = hasCf20(context) ? Cv10ControllerActivity.savedAddress(context) : null;
        return new Cv10ControllerReader(context, address);
    }

    /** Reopen only when the configured peripheral or Bluetooth permission has changed. */
    public boolean isCurrent() {
        String saved = hasCf20(context) ? Cv10ControllerActivity.savedAddress(context) : null;
        return java.util.Objects.equals(saved, address);
    }

    /** Returns zero without an input report; otherwise returns the local monotonic receipt time. */
    public long read(float[] values) {
        Cv10ControllerLink.Sample sample = link == null ? null : link.latestInput;
        if (sample == null || sample.report == null || System.nanoTime() - sample.arrivalNs > REPORT_TIMEOUT_NS) {
            calibration.reset();
            suppressTrigger = false;
            return 0;
        }
        final int mode = Cv10ControllerRoles.mode(context);
        if (mode == Cv10ControllerRoles.PHONE_ONLY) return 0;
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
        Cv10SimulationSettings.writeHandPoseSettings(simulation, address, values);
        Cv10SimulationCalibration.Orientation head = Cv10SimulationSettings.head;
        int linkGeneration = launcherInput.connectionGeneration;
        int homeGeneration = launcherInput.recenterGeneration;
        // Low 16 bits round-trip exactly in the JNI float report, including on long-running devices.
        values[13] = linkGeneration & 0xffff;
        values[14] = homeGeneration & 0xffff;
        values[15] = report.battery;
        values[16] = suppressTrigger ? (report.buttons & ~Cv10ControllerReport.TRIGGER) : report.buttons;
        values[17] = report.touchX;
        values[18] = report.touchY;
        values[19] = mode;
        if (Log.isLoggable("Cv10Cf20Gyro", Log.DEBUG) && lastGyroLog != sample.arrivalNs) {
            lastGyroLog = sample.arrivalNs;
            Log.d("Cv10Cf20Gyro", "ns=" + sample.arrivalNs + " q=" + report.x + "," + report.y + "," +
                    report.z + "," + report.w + " sdkGyro=" + report.gyroX + "," + report.gyroY + "," +
                    report.gyroZ + " calibration=" + report.stillnessDone + "," + report.figureEightDone);
        }
        float yaw = heading.resolve(linkGeneration, homeGeneration,
                values[8], head, sample.arrivalNs, report);
        // Don't export an unrelated absolute bearing while waiting for the first usable pair of poses.
        if (Float.isNaN(yaw)) values[4] = 0f;
        values[8] = Float.isNaN(yaw) ? 0f : yaw;
        if (Log.isLoggable("Cv10HandPose", Log.DEBUG) && head != null && report.orientationValid &&
                sample.arrivalNs - lastHeadingLog > 250_000_000L) {
            lastHeadingLog = sample.arrivalNs;
            Log.d("Cv10HandPose", "link=" + launcherInput.connectionGeneration + " home=" +
                    launcherInput.recenterGeneration + " valid=" + values[4] + " offset=" + yaw +
                    " head=" + head.x + "," + head.y + "," + head.z + "," + head.w +
                    " hand=" + report.x + "," + report.y + "," + report.z + "," + report.w);
        }
        return sample.arrivalNs;
    }

    public void close() {
        if (link != null) link.close();
        if (launcherInput != null) launcherInput.close();
        handler.post(() -> {
            if (tones != null) tones.release();
        });
    }
}
