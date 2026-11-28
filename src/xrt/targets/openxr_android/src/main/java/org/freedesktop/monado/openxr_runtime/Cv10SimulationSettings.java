// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.content.Context;
import android.content.SharedPreferences;

final class Cv10SimulationSettings {
    static final int ARM_MODEL = 0, FIXED = 1, ONE_SHOT = 2;
    static final int POSITION_IDLE = 0, POSITION_RECORDING = 1, POSITION_SAVED = 2, POSITION_FAILED = 3;
    private static final float DEFAULT_HEIGHT = 1.50f;
    private static final long REQUEST_MS = 90_000L;
    private static final String HEIGHT = "height", HEIGHT_CORRECTION = "height_correction";
    private static final String ARMED = "armed", ARMED_ADDRESS = "armed_address";
    private static final String YAW = "yaw", CALIBRATED_ADDRESS = "calibrated_address";
    private static final String POSITION_MODE = "position_mode", POSITION_REVISION = "position_revision";
    private static final String FIXED_X = "fixed_x", FIXED_Y = "fixed_y", FIXED_Z = "fixed_z";
    private static final String SHOT_ADDRESS = "shot_address", SHOT_YAW = "shot_yaw";
    private static final String SHOT_X = "shot_x", SHOT_Y = "shot_y", SHOT_Z = "shot_z";
    private static final String POSITION_STATUS = "position_status";
    static volatile Cv10SimulationCalibration.Orientation head;

    private Cv10SimulationSettings() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("cv10_simulation", Context.MODE_PRIVATE);
    }

    static float height(SharedPreferences prefs) {
        return prefs.getFloat(HEIGHT, DEFAULT_HEIGHT);
    }

    static boolean validHeight(float meters) {
        return meters >= 0.50f && meters <= 2.50f;
    }

    static synchronized void saveHeight(SharedPreferences prefs, float meters, String address) {
        SharedPreferences.Editor update = prefs.edit().putFloat(HEIGHT, meters);
        if (positionMode(prefs) == ONE_SHOT) {
            update.putLong(POSITION_REVISION, positionRevision(prefs) + 1).remove(POSITION_STATUS);
        }
        if (address == null) {
            update.remove(ARMED).remove(ARMED_ADDRESS).remove(HEIGHT_CORRECTION);
        } else {
            update.putLong(ARMED, System.currentTimeMillis()).putString(ARMED_ADDRESS, address);
        }
        update.apply();
    }

    static synchronized void clearOffset(SharedPreferences prefs) {
        prefs.edit().remove(ARMED).remove(ARMED_ADDRESS).remove(YAW).remove(HEIGHT_CORRECTION)
                .remove(CALIBRATED_ADDRESS).apply();
    }

    static long pending(SharedPreferences prefs, String address) {
        long token = prefs.getLong(ARMED, 0);
        long age = System.currentTimeMillis() - token;
        return token != 0 && age >= 0 && age < REQUEST_MS && address != null &&
                address.equals(prefs.getString(ARMED_ADDRESS, "")) ? token : 0;
    }

    static boolean calibrated(SharedPreferences prefs, String address) {
        return address != null && !address.isEmpty() && address.equals(prefs.getString(CALIBRATED_ADDRESS, ""));
    }

    static float yaw(SharedPreferences prefs, String address) {
        if (positionMode(prefs) == ONE_SHOT && hasShot(prefs, address)) {
            return prefs.getFloat(SHOT_YAW, 0f);
        }
        return calibrated(prefs, address) ? prefs.getFloat(YAW, 0f) : 0f;
    }

    static synchronized boolean complete(SharedPreferences prefs, String address, long token,
                                         Cv10SimulationCalibration.Offset offset) {
        if (pending(prefs, address) != token) return false;
        prefs.edit().remove(ARMED).remove(ARMED_ADDRESS).putString(CALIBRATED_ADDRESS, address)
                .putFloat(YAW, offset.yaw).putFloat(HEIGHT_CORRECTION, offset.heightCorrection).apply();
        return true;
    }

    static int positionMode(SharedPreferences prefs) {
        return prefs.getInt(POSITION_MODE, ARM_MODEL);
    }

    static long positionRevision(SharedPreferences prefs) {
        return prefs.getLong(POSITION_REVISION, 0);
    }

    static int positionStatus(SharedPreferences prefs) {
        return prefs.getInt(POSITION_STATUS, POSITION_IDLE);
    }

    static synchronized void selectPositionMode(SharedPreferences prefs, int mode) {
        modeChange(prefs, mode).apply();
    }

    static synchronized void preparePositionCalibration(SharedPreferences prefs, float height) {
        modeChange(prefs, ONE_SHOT).putFloat(HEIGHT, height).remove(ARMED).remove(ARMED_ADDRESS).apply();
    }

    static synchronized void saveFixedPosition(SharedPreferences prefs, Cv10OneShotCalibration.Position position) {
        modeChange(prefs, FIXED).putFloat(FIXED_X, position.x).putFloat(FIXED_Y, position.y)
                .putFloat(FIXED_Z, position.z).apply();
    }

    private static SharedPreferences.Editor modeChange(SharedPreferences prefs, int mode) {
        return prefs.edit().putInt(POSITION_MODE, mode)
                .putLong(POSITION_REVISION, positionRevision(prefs) + 1).remove(POSITION_STATUS);
    }

    static Cv10OneShotCalibration.Position fixedPosition(SharedPreferences prefs) {
        return new Cv10OneShotCalibration.Position(prefs.getFloat(FIXED_X, 0.20f),
                prefs.getFloat(FIXED_Y, -0.30f), prefs.getFloat(FIXED_Z, -0.35f));
    }

    private static boolean hasShot(SharedPreferences prefs, String address) {
        return address != null && address.equals(prefs.getString(SHOT_ADDRESS, ""));
    }

    static Cv10OneShotCalibration.Position shotPosition(SharedPreferences prefs, String address) {
        return hasShot(prefs, address) ? new Cv10OneShotCalibration.Position(
                prefs.getFloat(SHOT_X, 0f), prefs.getFloat(SHOT_Y, 0f), prefs.getFloat(SHOT_Z, 0f)) : null;
    }

    static synchronized void writeHandPoseSettings(SharedPreferences prefs, String address, float[] values) {
        values[8] = yaw(prefs, address);
        int mode = positionMode(prefs);
        Cv10OneShotCalibration.Position position = mode == FIXED ? fixedPosition(prefs) :
                mode == ONE_SHOT ? shotPosition(prefs, address) : null;
        values[9] = position != null ? 1f : 0f;
        if (position != null) {
            values[10] = position.x;
            values[11] = position.y;
            values[12] = position.z;
        }
    }

    static synchronized void positionStatus(SharedPreferences prefs, long revision, int status) {
        if (positionMode(prefs) == ONE_SHOT && positionRevision(prefs) == revision) {
            prefs.edit().putInt(POSITION_STATUS, status).apply();
        }
    }

    static synchronized boolean saveShot(SharedPreferences prefs, long revision, String address,
                                         Cv10OneShotCalibration.Position position, float yaw) {
        if (positionMode(prefs) != ONE_SHOT || positionRevision(prefs) != revision) return false;
        prefs.edit().putString(SHOT_ADDRESS, address).putFloat(SHOT_X, position.x).putFloat(SHOT_Y, position.y)
                .putFloat(SHOT_Z, position.z).putFloat(SHOT_YAW, yaw)
                .putInt(POSITION_STATUS, POSITION_SAVED).apply();
        return true;
    }

    /** JNI entry point, called from the CV10 USB IMU worker. */
    static float headSample(Context context, long ns, float x, float y, float z, float w) {
        head = new Cv10SimulationCalibration.Orientation(ns, x, y, z, w);
        SharedPreferences prefs = preferences(context);
        // The sign tells the USB worker whether to apply the neck model or use (0, height, 0).
        return positionMode(prefs) == ONE_SHOT ? -height(prefs) :
                height(prefs) + prefs.getFloat(HEIGHT_CORRECTION, 0f);
    }
}
