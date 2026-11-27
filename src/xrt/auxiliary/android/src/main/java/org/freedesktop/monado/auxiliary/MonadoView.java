// Copyright 2020, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Class to inject a custom surface into an activity.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @ingroup aux_android_java
 */

package org.freedesktop.monado.auxiliary;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.annotation.GuardedBy;
import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.TreeSet;
import java.util.WeakHashMap;

@Keep
public class MonadoView extends SurfaceView
        implements SurfaceHolder.Callback, SurfaceHolder.Callback2 {
    private static final String TAG = "MonadoView";

    private final Object currentSurfaceHolderSync = new Object();

    public int width = -1;
    public int height = -1;
    public int format = -1;

    private NativeCounterpart nativeCounterpart;

    @GuardedBy("currentSurfaceHolderSync") @Nullable private SurfaceHolder currentSurfaceHolder = null;

    private SystemUiController systemUiController = null;
    // Only accessed on the UI thread. Android dismisses presentations on display removal.
    private Presentation presentation;

    public MonadoView(Context context) {
        super(context);

        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            systemUiController = new SystemUiController(activity.getWindow().getDecorView());
            systemUiController.hide();
        }
        SurfaceHolder surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);
    }

    private MonadoView(Context context, long nativePointer) {
        this(context);

        nativeCounterpart = new NativeCounterpart(nativePointer);
    }

    /**
     * Construct and start attaching a MonadoView to a client application.
     *
     * @param activity The activity to attach to.
     * @return The MonadoView instance created and asynchronously attached.
     */
    @NonNull @Keep
    public static MonadoView attachToActivity(@NonNull final Activity activity) {
        Display cv10 = findCv10(activity);
        if (cv10 != null) return attachCv10(activity, cv10, 0);
        final MonadoView view = new MonadoView(activity);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.flags =
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        attachToWindow(activity, view, lp);
        return view;
    }

    /**
     * Construct and start attaching a MonadoView to window.
     *
     * @param displayContext Display context used for looking for target window.
     * @param nativePointer The native android_custom_surface pointer, cast to a long.
     * @param lp Layout parameters associated with view.
     * @return The MonadoView instance created and asynchronously attached.
     */
    @NonNull @Keep
    public static MonadoView attachToWindow(
            @NonNull final Context displayContext,
            long nativePointer,
            WindowManager.LayoutParams lp)
            throws IllegalArgumentException {
        Display cv10 = findCv10(displayContext);
        if (cv10 != null) return attachCv10(displayContext, cv10, nativePointer);
        final MonadoView view = new MonadoView(displayContext, nativePointer);
        attachToWindow(displayContext, view, lp);
        return view;
    }

    @Nullable
    public static Display findCv10(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display fallback = null;
        for (Display display : manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (!display.isValid()) continue;
            // Some phones (e.g. XREAL Beam Pro) report the sink name as "HDMI Screen" and only
            // expose HUAWEIAV03 through the EDID product info.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.hardware.display.DeviceProductInfo info = display.getDeviceProductInfo();
                if (info != null
                        && Cv10DisplayPortLink.DISPLAY_NAME.equalsIgnoreCase(info.getName().trim())) {
                    return display;
                }
            }
            if (Cv10DisplayPortLink.DISPLAY_NAME.equalsIgnoreCase(display.getName().trim())) return display;
            if (fallback == null && hasCv10Mode(display)) fallback = display;
        }
        return fallback;
    }

    private static boolean hasCv10Mode(Display display) {
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == 3200 && mode.getPhysicalHeight() == 1600) return true;
        }
        return false;
    }

    @Keep
    public static boolean canPresentOnCv10(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && findCv10(context) != null;
    }

    // Accessed only on the UI thread. Overlapping sessions share the original window values.
    private static final WeakHashMap<Window, PrimaryVote> primaryVotes = new WeakHashMap<>();

    private static final class PrimaryVote {
        final int oldModeId, requestedModeId;
        final float oldRate, requestedRate, oldViewRate;
        int sessions = 1;

        PrimaryVote(Window window, View content, Display.Mode best) {
            WindowManager.LayoutParams lp = window.getAttributes();
            oldModeId = lp.preferredDisplayModeId;
            oldRate = lp.preferredRefreshRate;
            requestedModeId = best.getModeId();
            requestedRate = best.getRefreshRate();
            oldViewRate = Build.VERSION.SDK_INT >= 35 ? content.getRequestedFrameRate() : 0;
            lp.preferredDisplayModeId = requestedModeId;
            lp.preferredRefreshRate = requestedRate;
            window.setAttributes(lp);
            if (Build.VERSION.SDK_INT >= 35) {
                content.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
            }
            Log.i(TAG, "CV10 primary window requested mode=" + requestedModeId + " Hz=" + requestedRate
                    + " previousMode=" + oldModeId + " previousHz=" + oldRate);
        }

        void restore(Window window, View content) {
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp.preferredDisplayModeId == requestedModeId) lp.preferredDisplayModeId = oldModeId;
            if (lp.preferredRefreshRate == requestedRate) lp.preferredRefreshRate = oldRate;
            window.setAttributes(lp);
            if (Build.VERSION.SDK_INT >= 35
                    && content.getRequestedFrameRate() == View.REQUESTED_FRAME_RATE_CATEGORY_HIGH) {
                content.setRequestedFrameRate(oldViewRate);
            }
            Log.i(TAG, "CV10 primary window released mode=" + lp.preferredDisplayModeId
                    + " Hz=" + lp.preferredRefreshRate);
        }
    }

    /** Acquires a rate vote on the main thread. The returned action releases it on that thread. */
    @Nullable
    public static Runnable requestCv10PrimaryRate(Activity owner) {
        Window window = owner.getWindow();
        View content = window.findViewById(android.R.id.content);
        if (content == null) return null;
        Display primary = content.getDisplay();
        if (primary == null || primary.getDisplayId() != Display.DEFAULT_DISPLAY) return null;
        PrimaryVote current = primaryVotes.get(window);
        if (current == null) {
            Display.Mode active = primary.getMode();
            Display.Mode best = active;
            for (Display.Mode mode : primary.getSupportedModes()) {
                if (mode.getPhysicalWidth() == active.getPhysicalWidth()
                        && mode.getPhysicalHeight() == active.getPhysicalHeight()
                        && mode.getRefreshRate() > best.getRefreshRate()) {
                    best = mode;
                }
            }
            primaryVotes.put(window, new PrimaryVote(window, content, best));
        } else {
            current.sessions++;
        }
        return () -> {
            PrimaryVote vote = primaryVotes.get(window);
            if (vote != null && --vote.sessions == 0) {
                primaryVotes.remove(window);
                vote.restore(window, content);
            }
        };
    }

    private static MonadoView attachCv10(Context owner, Display display, long pointer) {
        Context target = owner.createDisplayContext(display);
        final boolean activityOwner = owner instanceof Activity;
        final boolean usePresentation = activityOwner || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        if (!usePresentation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target = target.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        final MonadoView view = pointer == 0 ? new MonadoView(target) : new MonadoView(target, pointer);
        Display.Mode preferred = null;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == 3200 && mode.getPhysicalHeight() == 1600
                    && (preferred == null || mode.getRefreshRate() > preferred.getRefreshRate())) {
                preferred = mode;
            }
        }
        final int modeId = preferred == null ? 0 : preferred.getModeId();
        if (modeId != 0) view.getHolder().setFixedSize(3200, 1600);
        String path = usePresentation ? "presentation" : "overlay";
        Log.i(TAG, "CV10 target id=" + display.getDisplayId() + " name=" + display.getName()
                + " active=" + display.getMode() + " preferred=" + preferred
                + " owner=" + (activityOwner ? "activity" : "runtime") + " path=" + path);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (!display.isValid()) return;
                int flags = WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                if (usePresentation) {
                    Presentation dialog = new Presentation(owner, display);
                    Window window = dialog.getWindow();
                    window.addFlags(flags);
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                    lp.height = WindowManager.LayoutParams.MATCH_PARENT;
                    lp.preferredDisplayModeId = modeId;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.setFitInsetsTypes(0);
                    window.setAttributes(lp);
                    dialog.setContentView(view);
                    dialog.show();
                    view.presentation = dialog;
                    if (activityOwner) {
                        Runnable releaseVote = requestCv10PrimaryRate((Activity) owner);
                        if (releaseVote != null) dialog.setOnDismissListener(ignored -> releaseVote.run());
                    }
                } else {
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags,
                            android.graphics.PixelFormat.OPAQUE);
                    lp.preferredDisplayModeId = modeId;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.setFitInsetsTypes(0);
                    lp.setTitle("Monado CV10");
                    view.getContext().getSystemService(WindowManager.class).addView(view, lp);
                }
                new SystemUiController(view).hide();
                Cv10DisplayPortDiagnostics.warnIfTwoLanes(owner, display);
            } catch (WindowManager.InvalidDisplayException | WindowManager.BadTokenException
                    | SecurityException error) {
                Log.e(TAG, "CV10 window unavailable on display " + display.getDisplayId(), error);
            }
        });
        return view;
    }

    private static void attachToWindow(
            @NonNull final Context context,
            @NonNull MonadoView view,
            @NonNull WindowManager.LayoutParams lp) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(
                () -> {
                    Log.d(TAG, "Start adding view to window");
                    WindowManager wm =
                            (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                    wm.addView(view, lp);

                    SystemUiController systemUiController = new SystemUiController(view);
                    systemUiController.hide();
                });
    }

    /**
     * Remove given MonadoView from window.
     *
     * @param view The view to remove.
     */
    @Keep
    public static void removeFromWindow(@NonNull MonadoView view) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(
                () -> {
                    Log.d(TAG, "Start removing view from window");
                    WindowManager wm =
                            (WindowManager)
                                    view.getContext().getSystemService(Context.WINDOW_SERVICE);
                    if (view.presentation != null) {
                        view.presentation.dismiss();
                        view.presentation = null;
                    } else if (view.isAttachedToWindow()) {
                        wm.removeView(view);
                    }
                });
    }

    @NonNull @Keep
    public static DisplayMetrics getDisplayMetrics(@NonNull Context context) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display external = findCv10(context);
        Display display = external == null ? wm.getDefaultDisplay() : external;
        display.getRealMetrics(displayMetrics);
        if (external != null) {
            displayMetrics.widthPixels = display.getMode().getPhysicalWidth();
            displayMetrics.heightPixels = display.getMode().getPhysicalHeight();
        }

        // getRealMetrics returns the metrics dependent on screen rotation, but we expect them
        // to be landscape; ensure that the returned metrics represent the landscape orientation.
        if (displayMetrics.widthPixels < displayMetrics.heightPixels) {
            int tmp = displayMetrics.widthPixels;
            displayMetrics.widthPixels = displayMetrics.heightPixels;
            displayMetrics.heightPixels = tmp;
        }

        return displayMetrics;
    }

    @Keep
    public static float getDisplayRefreshRate(@NonNull Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display external = findCv10(context);
        return (external == null ? wm.getDefaultDisplay() : external).getRefreshRate();
    }

    /**
     * Get the width of the specified display mode on the specified display ID
     *
     * <p>If the specified mode ID is not in the list of supported mode IDs for the specified
     * display ID, then a value of 0 is returned.
     *
     * @param context Display context used for looking for target window.
     * @param display The display ID for which the mode is to be queried.
     * @param displayModeId The display mode ID for which the width is returned. This is a
     *     zero-indexed mode ID.
     * @return The width in pixels for the specified mode ID on the specified display.
     */
    @Keep
    public static int getDisplayModeIdWidth(
            @NonNull final Context context, int display, int displayModeId) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display dp = dm.getDisplay(display);
        Display.Mode[] modes = dp.getSupportedModes();
        if (modes.length > displayModeId) {
            return modes[displayModeId].getPhysicalWidth();
        }
        return 0;
    }

    /**
     * Get the height of the specified display mode on the specified display ID
     *
     * <p>If the specified mode ID is not in the list of supported mode IDs for the specified
     * display ID, then a value of 0 is returned.
     *
     * @param context Display context used for looking for target window.
     * @param display The display ID for which the mode is to be queried.
     * @param displayModeId The display mode ID for which the height is returned. This is a
     *     zero-indexed mode ID.
     * @return The height in pixels for the specified mode ID on the specified display.
     */
    @Keep
    public static int getDisplayModeIdHeight(
            @NonNull final Context context, int display, int displayModeId) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display dp = dm.getDisplay(display);
        Display.Mode[] modes = dp.getSupportedModes();
        if (modes.length > displayModeId) {
            return modes[displayModeId].getPhysicalHeight();
        }
        return 0;
    }

    @Keep
    public static float[] getSupportedRefreshRates(@NonNull Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        TreeSet<Float> rateSet = new TreeSet<>();
        Display external = findCv10(context);
        Display.Mode[] modes = (external == null ? wm.getDefaultDisplay() : external).getSupportedModes();
        for (Display.Mode mode : modes) {
            if (external == null || (mode.getPhysicalWidth() == external.getMode().getPhysicalWidth()
                    && mode.getPhysicalHeight() == external.getMode().getPhysicalHeight())) {
                rateSet.add(mode.getRefreshRate());
            }
        }
        float[] rates = new float[rateSet.size()];
        int i = 0;
        for (Float f : rateSet) {
            rates[i++] = f;
        }
        return rates;
    }

    @Keep
    public long getNativePointer() {
        if (nativeCounterpart == null) {
            return 0;
        }
        return nativeCounterpart.getNativePointer();
    }

    /**
     * Block up to a specified amount of time, waiting for the surfaceCreated callback to be fired
     * and populate the currentSurfaceHolder.
     *
     * <p>If it returns a SurfaceHolder, the `usedByNativeCode` flag will be set.
     *
     * <p>Called by native code!
     *
     * @param wait_ms Max duration you prefer to wait, in milliseconds. Spurious wakeups mean this
     *     not be totally precise.
     * @return A SurfaceHolder or null.
     */
    @Keep
    public @Nullable SurfaceHolder waitGetSurfaceHolder(int wait_ms) {
        long currentTime = SystemClock.uptimeMillis();
        long timeout = currentTime + wait_ms;
        SurfaceHolder ret = null;
        synchronized (currentSurfaceHolderSync) {
            ret = currentSurfaceHolder;
            while (currentSurfaceHolder == null && SystemClock.uptimeMillis() < timeout) {
                try {
                    currentSurfaceHolderSync.wait(wait_ms, 0);
                    ret = currentSurfaceHolder;
                } catch (InterruptedException e) {
                    // stop waiting
                    break;
                }
            }
        }
        if (ret != null) {
            if (nativeCounterpart != null) nativeCounterpart.markAsUsedByNativeCode();
        }
        return ret;
    }

    /**
     * Change the flag and notify those waiting on it, to indicate that native code is done with
     * this object.
     *
     * <p>Called by native code!
     */
    @Keep
    public void markAsDiscardedByNative() {
        if (nativeCounterpart != null) nativeCounterpart.markAsDiscardedByNative(TAG);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder surfaceHolder) {
        Display display = getDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && display != null
                && display.getDisplayId() != Display.DEFAULT_DISPLAY
                && "HUAWEIAV03".equalsIgnoreCase(display.getName().trim())) {
            Surface surface = surfaceHolder.getSurface();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    surface.setFrameRate(90.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ALWAYS);
                } else {
                    surface.setFrameRate(90.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                }
                Log.i(TAG, "CV10 Surface.setFrameRate requested 90 Hz, display=" + display.getDisplayId());
            } catch (IllegalArgumentException | IllegalStateException e) {
                Log.w(TAG, "CV10 Surface.setFrameRate unavailable", e);
            }
        }
        synchronized (currentSurfaceHolderSync) {
            currentSurfaceHolder = surfaceHolder;
            currentSurfaceHolderSync.notifyAll();
        }
        Log.i(TAG, "surfaceCreated: Got a surface holder!");
    }

    @Override
    public void surfaceChanged(
            @NonNull SurfaceHolder surfaceHolder, int format, int width, int height) {

        synchronized (currentSurfaceHolderSync) {
            currentSurfaceHolder = surfaceHolder;
            this.format = format;
            this.width = width;
            this.height = height;
            currentSurfaceHolderSync.notifyAll();
        }
        Log.i(
                TAG,
                "surfaceChanged, w = "
                        + this.width
                        + " h = "
                        + this.height
                        + " holder "
                        + currentSurfaceHolder.toString());
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder surfaceHolder) {
        Log.i(TAG, "surfaceDestroyed: Lost our surface.");
        boolean lost = false;
        synchronized (currentSurfaceHolderSync) {
            if (surfaceHolder == currentSurfaceHolder) {
                currentSurfaceHolder = null;
                lost = true;
            }
        }
        if (lost) {
            // ! @todo this function should notify native code that the surface is gone.
            if (nativeCounterpart != null && !nativeCounterpart.blockUntilNativeDiscard(TAG)) {
                Log.i(
                        TAG,
                        "Interrupted in surfaceDestroyed while waiting for native code to finish"
                                + " up.");
            }
        }
    }

    @Override
    public void surfaceRedrawNeeded(@NonNull SurfaceHolder surfaceHolder) {
        //        currentSurfaceHolder = surfaceHolder;
        Log.i(TAG, "surfaceRedrawNeeded");
    }
}
