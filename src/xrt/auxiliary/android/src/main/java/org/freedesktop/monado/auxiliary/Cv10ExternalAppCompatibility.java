// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.auxiliary;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

/** Checks whether Android can launch the installed Pocket XR app on the CV10 display. */
public final class Cv10ExternalAppCompatibility {
    private static final String TAG = "Cv10ExternalApp";
    private static final String POCKET_XR_PACKAGE = "com.hayeco.flipspaces";
    // Settings.ACTION_EXTERNAL_DISPLAY_SETTINGS was added after this module's compile SDK.
    private static final String EXTERNAL_DISPLAY_SETTINGS = "android.settings.EXTERNAL_DISPLAY_SETTINGS";
    private static final int CV10_WIDTH = 3200;
    private static final int CV10_HEIGHT = 1600;

    private Cv10ExternalAppCompatibility() {}

    public static void show(Activity activity) {
        PackageManager packages = activity.getPackageManager();
        Intent phoneLaunch = packages.getLaunchIntentForPackage(POCKET_XR_PACKAGE);
        Intent glassesLaunch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_SECONDARY_HOME)
                .setPackage(POCKET_XR_PACKAGE);
        ResolveInfo entry = packages.resolveActivity(glassesLaunch, PackageManager.MATCH_DEFAULT_ONLY);
        if (entry != null && entry.activityInfo.exported) {
            glassesLaunch.setComponent(new ComponentName(entry.activityInfo.packageName, entry.activityInfo.name));
        }

        Display display = MonadoView.findCv10(activity);
        boolean canStartOnGlasses = false;
        StringBuilder status = new StringBuilder();
        if (display == null) {
            status.append(activity.getString(R.string.cv10_external_display_unavailable));
        } else {
            Display.Mode mode = display.getMode();
            status.append(activity.getString(R.string.cv10_external_display_mode,
                    display.getDisplayId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(),
                    mode.getRefreshRate()));
            if (entry != null && entry.activityInfo.exported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    canStartOnGlasses = activity.getSystemService(ActivityManager.class)
                            .isActivityStartAllowedOnDisplay(activity, display.getDisplayId(), glassesLaunch);
                    status.append("\n\n").append(activity.getString(canStartOnGlasses
                            ? R.string.cv10_external_display_allowed : R.string.cv10_external_display_denied));
                } catch (IllegalArgumentException | SecurityException e) {
                    status.append("\n\n").append(activity.getString(R.string.cv10_external_display_unchecked));
                    Log.d(TAG, "CV10 stopped being available while checking the external app launch", e);
                }
                Log.i(TAG, "Display " + display.getDisplayId() + " active " + mode
                        + ", launch on display allowed=" + canStartOnGlasses);
            }
            if (!hasNativeSize(mode)) {
                boolean supportsNativeSize = false;
                for (Display.Mode supported : display.getSupportedModes()) {
                    if (hasNativeSize(supported)) {
                        supportsNativeSize = true;
                        break;
                    }
                }
                status.append("\n\n").append(activity.getString(supportsNativeSize
                        ? R.string.cv10_external_non_native_mode : R.string.cv10_external_native_mode_unavailable));
            }
        }

        if (phoneLaunch == null) {
            status.append("\n\n").append(activity.getString(R.string.cv10_external_pocket_missing));
        } else if (entry == null || !entry.activityInfo.exported) {
            status.append("\n\n").append(activity.getString(R.string.cv10_external_pocket_no_entry));
        }
        status.append("\n\n").append(activity.getString(R.string.cv10_external_pocket_tracking));

        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.cv10_external_compatibility_title)
                .setMessage(status)
                .setNeutralButton(R.string.cv10_external_display_settings, (d, which) -> openDisplaySettings(activity));
        if (canStartOnGlasses) {
            dialog.setPositiveButton(R.string.cv10_external_open_glasses,
                    (d, which) -> launch(activity, glassesLaunch, display.getDisplayId()));
        }
        if (phoneLaunch != null && canStartOnGlasses) {
            dialog.setNegativeButton(R.string.cv10_external_open_phone,
                    (d, which) -> launch(activity, phoneLaunch, Display.DEFAULT_DISPLAY));
        } else {
            dialog.setNegativeButton(android.R.string.cancel, null);
            if (phoneLaunch != null) {
                dialog.setPositiveButton(R.string.cv10_external_open_phone,
                        (d, which) -> launch(activity, phoneLaunch, Display.DEFAULT_DISPLAY));
            }
        }
        dialog.show();
    }

    private static boolean hasNativeSize(Display.Mode mode) {
        return mode.getPhysicalWidth() == CV10_WIDTH && mode.getPhysicalHeight() == CV10_HEIGHT;
    }

    private static void launch(Activity activity, Intent intent, int displayId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId);
                activity.startActivity(intent, options.toBundle());
            } else {
                activity.startActivity(intent);
            }
        } catch (ActivityNotFoundException | IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Pocket XR launch failed on display " + displayId, e);
            Toast.makeText(activity, R.string.cv10_external_launch_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static void openDisplaySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(EXTERNAL_DISPLAY_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException e) {
            activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }
}
