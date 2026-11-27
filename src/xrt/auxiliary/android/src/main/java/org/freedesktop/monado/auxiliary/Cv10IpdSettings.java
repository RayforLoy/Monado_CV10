// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the CV10 OpenXR eye separation independently of the lens geometry. */
public final class Cv10IpdSettings {
    public static final int MIN_MM = 55;
    public static final int MAX_MM = 71;
    public static final int DEFAULT_MM = 63;
    public static final String IPD_MM = "ipd_mm";

    private Cv10IpdSettings() {}

    public static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("cv10_optics", Context.MODE_PRIVATE);
    }

    /** Returns zero when there is no user override. */
    public static int readOverride(SharedPreferences preferences) {
        int value = preferences.getInt(IPD_MM, 0);
        return value >= MIN_MM && value <= MAX_MM ? value : 0;
    }
}
