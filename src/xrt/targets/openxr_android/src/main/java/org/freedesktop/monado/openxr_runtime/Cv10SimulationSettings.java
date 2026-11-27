// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.content.Context;
import android.content.SharedPreferences;

final class Cv10SimulationSettings {
    private static final float DEFAULT_HEIGHT = 1.50f;
    private static final long REQUEST_MS = 90_000L;
    private static final String HEIGHT = "height", HEIGHT_CORRECTION = "height_correction";
    private static final String ARMED = "armed", ARMED_ADDRESS = "armed_address";
    private static final String YAW = "yaw", CALIBRATED_ADDRESS = "calibrated_address";
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
        return calibrated(prefs, address) ? prefs.getFloat(YAW, 0f) : 0f;
    }

    static synchronized boolean complete(SharedPreferences prefs, String address, long token,
                                         Cv10SimulationCalibration.Offset offset) {
        if (pending(prefs, address) != token) return false;
        prefs.edit().remove(ARMED).remove(ARMED_ADDRESS).putString(CALIBRATED_ADDRESS, address)
                .putFloat(YAW, offset.yaw).putFloat(HEIGHT_CORRECTION, offset.heightCorrection).apply();
        return true;
    }

    /** JNI entry point, called from the CV10 USB IMU worker. */
    static float headSample(Context context, long ns, float x, float y, float z, float w) {
        head = new Cv10SimulationCalibration.Orientation(ns, x, y, z, w);
        SharedPreferences prefs = preferences(context);
        return height(prefs) + prefs.getFloat(HEIGHT_CORRECTION, 0f);
    }
}
