// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.content.Context;
import android.content.SharedPreferences;

/** The size controls rendering before lens distortion, independently of the physical output mode. */
public final class Cv10RenderSettings {
    public static final int BASELINE_EYE_SIZE = 1600;
    // Huawei High: 1280 pixels across 0.4 of the full tangent extent.
    public static final int HUAWEI_CENTER_EYE_SIZE = 3200;
    public static final String SUPERSAMPLING_EYE_SIZE = "supersampling_eye_size";
    public static final String ENABLE_ENHANCED_RENDER_MODE = "enableEnhancedRenderMode";

    private Cv10RenderSettings() {}

    public static SharedPreferences preferences(Context context) {
        return Cv10IpdSettings.preferences(context);
    }

    public static boolean isSupportedEyeSize(int size) {
        return size >= BASELINE_EYE_SIZE && size <= HUAWEI_CENTER_EYE_SIZE;
    }

    /** Returns zero when supersampling is disabled. */
    public static int readSupersamplingEyeSize(SharedPreferences preferences) {
        int size = preferences.getInt(SUPERSAMPLING_EYE_SIZE, 0);
        return isSupportedEyeSize(size) ? size : 0;
    }

    public static boolean readEnhancedRenderMode(SharedPreferences preferences) {
        return preferences.getBoolean(ENABLE_ENHANCED_RENDER_MODE, false);
    }
}
