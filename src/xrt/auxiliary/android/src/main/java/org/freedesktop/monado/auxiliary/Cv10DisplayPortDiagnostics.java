// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/** Reports the DisplayPort link state in Monado CV10. */
public final class Cv10DisplayPortDiagnostics {
    private static final String TAG = "Cv10DisplayPort";
    private static final File DRM = new File("/sys/class/drm");
    private static final File DEV = new File("/dev");
    private static final File TYPE_C = new File("/sys/bus/typec/devices");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Cv10DisplayPortDiagnostics() {}

    public static void showStatus(Activity activity) {
        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.cv10_dp_title)
                        .setMessage(R.string.cv10_dp_reading)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
        inspect(
                activity.getApplicationContext(),
                (link, mode) -> {
                    if (!dialog.isShowing()) return;

                    String status =
                            switch (link.evidence) {
                                case TRAINED ->
                                        activity.getString(
                                                R.string.cv10_dp_trained, link.lanes, link.source);
                                case TYPE_C_PIN_ASSIGNMENT ->
                                        activity.getString(
                                                R.string.cv10_dp_pin_assignment,
                                                link.source,
                                                link.lanes);
                                case UNREADABLE -> activity.getString(R.string.cv10_dp_unreadable);
                            };
                    String displayStatus =
                            mode == null
                                    ? activity.getString(R.string.cv10_dp_display_unavailable)
                                    : activity.getString(
                                            R.string.cv10_dp_display_mode,
                                            mode.getPhysicalWidth(),
                                            mode.getPhysicalHeight(),
                                            Float.toString(mode.getRefreshRate()));
                    String message = status + "\n\n" + displayStatus;
                    if (link.lanes == 2) {
                        message += "\n\n" + activity.getString(R.string.cv10_dp_flicker_report);
                    }
                    dialog.setMessage(message);
                });
    }

    static void warnIfTwoLanes(Context context, Display attachedDisplay) {
        Context app = context.getApplicationContext();
        inspect(
                app,
                (link, mode) -> {
                    if (link.lanes != 2 || !attachedDisplay.isValid()) return;
                    int message =
                            link.evidence == Cv10DisplayPortLink.Evidence.TRAINED
                                    ? R.string.cv10_dp_warn_trained
                                    : R.string.cv10_dp_warn_pin_assignment;
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                });
    }

    private static void inspect(
            Context app, BiConsumer<Cv10DisplayPortLink.Result, Display.Mode> onResult) {
        WORKER.execute(
                () -> {
                    Display display = MonadoView.findCv10(app);
                    Cv10DisplayPortLink.Result observed =
                            Cv10DisplayPortLink.read(DRM, DEV, TYPE_C, display != null);
                    Display.Mode mode =
                            display != null && display.isValid() ? display.getMode() : null;
                    boolean lostPinAssignment =
                            mode == null
                                    && observed.evidence
                                            == Cv10DisplayPortLink.Evidence.TYPE_C_PIN_ASSIGNMENT;
                    Cv10DisplayPortLink.Result link =
                            lostPinAssignment ? Cv10DisplayPortLink.UNREADABLE : observed;
                    String reading =
                            switch (link.evidence) {
                                case TRAINED ->
                                        "negotiatedLanes=" + link.lanes + " dpcd=" + link.source;
                                case TYPE_C_PIN_ASSIGNMENT ->
                                        "negotiatedLanes=unknown pinAssignment="
                                                + link.source
                                                + " allocatedDpLanes="
                                                + link.lanes;
                                case UNREADABLE -> "negotiatedLanes=unknown";
                            };
                    Log.i(TAG, reading + " displayMode=" + mode);
                    MAIN.post(() -> onResult.accept(link, mode));
                });
    }
}
