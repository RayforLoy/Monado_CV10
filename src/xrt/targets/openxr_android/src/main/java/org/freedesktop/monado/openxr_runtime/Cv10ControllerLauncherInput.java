// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import org.freedesktop.monado.auxiliary.Cv10ControllerRoles;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.core.content.ContextCompat;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** CF20 connection and launcher gestures outside the Khronos Simple Controller profile. */
final class Cv10ControllerLauncherInput {
    private static native void nativeRequestViewRecenter(long timeNs);
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
    private static final long DOUBLE_HOME_MILLIS = 350;

    private final Context context;
    private final BooleanSupplier doubleHomeEnabled;
    private final Consumer<Cv10ControllerLink.Sample> doubleHome;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver request = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            // A launcher opened after the runtime can recover the current subscription state.
            send(ready ? CONNECTED : DISCONNECTED, 0);
        }
    };
    private boolean ready;
    volatile int connectionGeneration;
    volatile int recenterGeneration;
    private boolean receivedReport;
    private boolean homeDown;
    private long homeStart;
    private long pendingHome;
    private long pendingHomeUntil;
    private boolean secondHome;
    private long touchStart;
    private int touchStartX, touchStartY, touchX, touchY;
    private final Runnable finishHomePress = () -> {
        if (ready && homeStart != 0) {
            clearPendingHome();
            send(RECENTER, homeStart);
            homeStart = 0;
        }
    };
    private final Runnable sendPendingHome = () -> {
        send(HOME, pendingHome);
        pendingHome = 0;
    };

    Cv10ControllerLauncherInput(Context context, BooleanSupplier doubleHomeEnabled,
                                Consumer<Cv10ControllerLink.Sample> doubleHome) {
        this.context = context.getApplicationContext();
        this.doubleHomeEnabled = doubleHomeEnabled;
        this.doubleHome = doubleHome;
        ContextCompat.registerReceiver(this.context, request, new IntentFilter(REQUEST),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    static void requestState(Context context) {
        context.sendBroadcast(new Intent(REQUEST).setPackage(context.getPackageName()));
    }

    void onReady(boolean ready) {
        this.ready = ready;
        if (ready) {
            recenterGeneration = 0;
            connectionGeneration++;
        }
        receivedReport = false;
        homeDown = false;
        homeStart = 0;
        touchStart = 0;
        handler.removeCallbacks(finishHomePress);
        clearPendingHome();
        send(ready ? CONNECTED : DISCONNECTED, 0);
    }

    void onInput(Cv10ControllerLink.Sample sample) {
        Cv10ControllerReport report = sample.report;
        boolean pressed = report.isPressed(Cv10ControllerReport.HOME);
        if (receivedReport && pressed != homeDown) {
            if (pressed) {
                homeStart = SystemClock.uptimeMillis();
                if (pendingHome != 0 && homeStart >= pendingHomeUntil) {
                    handler.removeCallbacks(sendPendingHome);
                    sendPendingHome.run();
                }
                secondHome = pendingHome != 0 && doubleHomeEnabled.getAsBoolean();
                if (secondHome) handler.removeCallbacks(sendPendingHome);
                handler.postDelayed(finishHomePress, HOME_HOLD_MILLIS);
            } else if (homeStart != 0) {
                handler.removeCallbacks(finishHomePress);
                long duration = SystemClock.uptimeMillis() - homeStart;
                if (duration >= HOME_HOLD_MILLIS) {
                    clearPendingHome();
                    send(RECENTER, homeStart);
                } else if (secondHome && duration <= DOUBLE_HOME_MILLIS && doubleHomeEnabled.getAsBoolean()) {
                    clearPendingHome();
                    doubleHome.accept(sample);
                } else {
                    if (pendingHome != 0) {
                        send(HOME, pendingHome);
                        clearPendingHome();
                    }
                    if (doubleHomeEnabled.getAsBoolean() && duration <= DOUBLE_HOME_MILLIS) {
                        pendingHome = homeStart;
                        pendingHomeUntil = SystemClock.uptimeMillis() + DOUBLE_HOME_MILLIS;
                        handler.postDelayed(sendPendingHome, DOUBLE_HOME_MILLIS);
                    } else {
                        send(HOME, homeStart);
                    }
                }
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

    private void clearPendingHome() {
        handler.removeCallbacks(sendPendingHome);
        pendingHome = 0;
        secondHome = false;
    }

    void close() {
        // Serialize receiver teardown with GATT's final onReady(false).
        handler.post(() -> context.unregisterReceiver(request));
    }

    private void send(int kind, long started) {
        Intent event = new Intent(EVENT).setPackage(context.getPackageName())
                .putExtra(KIND, kind).putExtra(START_TIME, started);
        if (kind != RECENTER) {
            context.sendBroadcast(event);
            return;
        }
        int link = connectionGeneration;
        // A focused spatial launcher owns its panel and pointer recenter. Other clients use runtime yaw.
        context.sendOrderedBroadcast(event, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent received) {
                if (getResultCode() != Activity.RESULT_OK && ready && connectionGeneration == link &&
                        Cv10ControllerRoles.mode(context) != Cv10ControllerRoles.PHONE_ONLY) {
                    recenterGeneration++;
                    Cv10PhoneControllerService.recenterToHead();
                    nativeRequestViewRecenter(System.nanoTime());
                }
            }
        }, handler, Activity.RESULT_CANCELED, null, null);
    }
}
