// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.content.Context;
import android.content.SharedPreferences;

/** Runtime-private configuration; clients obtain their pad over their existing Monado connection. */
public final class Cv10ControllerRoles {
    public static final int AUTO = 0, PHONE_ONLY = 1, SWAPPED = 2;
    private Cv10ControllerRoles() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("cv10_controller_roles", Context.MODE_PRIVATE);
    }
    public static int storedMode(Context context) { return prefs(context).getInt("mode", AUTO); }
    public static int mode(Context context) {
        int mode = storedMode(context);
        // Disabling the phone also makes a paired CF20 available after a phone-only session.
        return mode == PHONE_ONLY && !enabled(context) ? AUTO : mode;
    }
    public static boolean enabled(Context context) { return prefs(context).getBoolean("phone", true); }
    public static void save(Context context, boolean enabled, int mode) {
        if (mode < AUTO || mode > SWAPPED) throw new IllegalArgumentException("Invalid controller role");
        prefs(context).edit().putBoolean("phone", enabled).putInt("mode", mode).apply();
    }
}
