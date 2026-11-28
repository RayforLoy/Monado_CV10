// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import java.util.WeakHashMap;

/** Override only the client window hosting a visible phone controller, on its UI thread. */
final class Cv10PhonePadBrightness implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private static final float PAD_BRIGHTNESS = 0.10f;
    private static final WeakHashMap<Window, WindowState> windows = new WeakHashMap<>();

    private static final class WindowState {
        int users;
        float previous;
        WindowState(float previous) { this.previous = previous; }
    }

    private final Window window;
    private WindowState active;

    private Cv10PhonePadBrightness(Window window) {
        this.window = window;
    }

    static void bind(View pad, Window window) {
        Cv10PhonePadBrightness lease = new Cv10PhonePadBrightness(window);
        pad.setKeepScreenOn(true);
        pad.addOnAttachStateChangeListener(lease);
        if (pad.isAttachedToWindow()) lease.onViewAttachedToWindow(pad);
    }

    @Override
    public void onViewAttachedToWindow(View pad) {
        if (active != null) return;
        active = windows.get(window);
        if (active == null) {
            active = new WindowState(window.getAttributes().screenBrightness);
            windows.put(window, active);
        }
        active.users++;
        apply();
        pad.getViewTreeObserver().addOnPreDrawListener(this);
    }

    private void apply() {
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.screenBrightness == PAD_BRIGHTNESS) return;
        // Preserve a later brightness request made by the host, e.g. when starting a video.
        active.previous = attributes.screenBrightness;
        attributes.screenBrightness = PAD_BRIGHTNESS;
        window.setAttributes(attributes);
    }

    @Override
    public boolean onPreDraw() {
        if (active != null) apply();
        return true;
    }

    @Override
    public void onViewDetachedFromWindow(View pad) {
        if (active == null) return;
        if (pad.getViewTreeObserver().isAlive()) pad.getViewTreeObserver().removeOnPreDrawListener(this);
        if (--active.users == 0) {
            windows.remove(window);
            WindowManager.LayoutParams attributes = window.getAttributes();
            if (attributes.screenBrightness == PAD_BRIGHTNESS) {
                attributes.screenBrightness = active.previous;
                window.setAttributes(attributes);
            }
        }
        active = null;
    }
}
