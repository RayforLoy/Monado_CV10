// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;

/** CF20 connection and launcher gestures outside the Khronos Simple Controller profile. */
final class Cv10ControllerLauncherInput implements Cv10ControllerLink.Listener {
    static final String EVENT = "org.freedesktop.monado.cv10.LAUNCHER_CONTROLLER_EVENT";
    private static final String REQUEST = "org.freedesktop.monado.cv10.LAUNCHER_CONTROLLER_REQUEST";
    static final String KIND = "kind";
    static final String START_TIME = "start_time";
    static final int CONNECTED = 1;
    static final int DISCONNECTED = 2;
    static final int HOME = 3;
    static final int RECENTER = 4;
    static final int SWIPE_LEFT = 5;
    static final int SWIPE_RIGHT = 6;
    static final long HOME_HOLD_MILLIS = 1000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver request = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            // A launcher opened after the runtime can recover the current subscription state.
            send(ready ? CONNECTED : DISCONNECTED, 0);
        }
    };
    private boolean ready;
    private boolean receivedReport;
    private boolean homeDown;
    private long homeStart;
    private long touchStart;
    private int touchStartX, touchStartY, touchX, touchY;
    private final Runnable finishHomePress = () -> {
        if (ready && homeStart != 0) {
            send(RECENTER, homeStart);
            homeStart = 0;
        }
    };

    Cv10ControllerLauncherInput(Context context) {
        this.context = context.getApplicationContext();
        ContextCompat.registerReceiver(this.context, request, new IntentFilter(REQUEST),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    static void requestState(Context context) {
        context.sendBroadcast(new Intent(REQUEST).setPackage(context.getPackageName()));
    }

    @Override
    public void onReady(boolean ready) {
        this.ready = ready;
        receivedReport = false;
        homeDown = false;
        homeStart = 0;
        touchStart = 0;
        handler.removeCallbacks(finishHomePress);
        send(ready ? CONNECTED : DISCONNECTED, 0);
    }

    @Override
    public void onInput(Cv10ControllerReport report) {
        boolean pressed = report.isPressed(Cv10ControllerReport.HOME);
        if (receivedReport && pressed != homeDown) {
            if (pressed) {
                homeStart = SystemClock.uptimeMillis();
                handler.postDelayed(finishHomePress, HOME_HOLD_MILLIS);
            } else if (homeStart != 0) {
                handler.removeCallbacks(finishHomePress);
                boolean held = SystemClock.uptimeMillis() - homeStart >= HOME_HOLD_MILLIS;
                send(held ? RECENTER : HOME, homeStart);
                homeStart = 0;
            }
        }
        homeDown = pressed;
        receivedReport = true;

        if (report.isTouched()) {
            if (touchStart == 0) {
                touchStart = SystemClock.uptimeMillis();
                touchStartX = report.touchX;
                touchStartY = report.touchY;
            }
            touchX = report.touchX;
            touchY = report.touchY;
        } else if (touchStart != 0) {
            float dx = (touchX - touchStartX) / 255f;
            float dy = (touchY - touchStartY) / 255f;
            if (dx * dx + dy * dy >= 0.04f && Math.abs(dx) > Math.abs(dy)) {
                send(dx < 0 ? SWIPE_LEFT : SWIPE_RIGHT, touchStart);
            }
            touchStart = 0;
        }
    }

    void close() {
        // Serialize receiver teardown with GATT's final onReady(false).
        handler.post(() -> context.unregisterReceiver(request));
    }

    private void send(int kind, long started) {
        context.sendBroadcast(new Intent(EVENT).setPackage(context.getPackageName())
                .putExtra(KIND, kind).putExtra(START_TIME, started));
    }
}
