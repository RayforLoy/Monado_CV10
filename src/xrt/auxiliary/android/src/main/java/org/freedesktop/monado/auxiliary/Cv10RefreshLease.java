// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Objects;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/** One serialized coordinator per runtime process; never instantiated in OpenXR client apps. */
public final class Cv10RefreshLease implements DisplayManager.DisplayListener {
    public static final String PREFS = "cv10_refresh";
    public static final String ENABLED = "shizuku_enabled";
    public static final String PENDING = "restore_pending";
    private static final String ORIGINAL = "original_value";
    private static final String RATE = "min_refresh_rate";
    private static final String TARGET = "120.0";
    private static final String TAG = "Cv10RefreshLease";
    private static Cv10RefreshLease instance;
    private final Context context;
    private final DisplayManager displays;
    private final SharedPreferences prefs;
    private final Handler work;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Binder lifetime = new Binder();
    private final Shizuku.UserServiceArgs args;
    private ServiceConnection binding;
    private ICv10Refresh remote;
    private boolean bound, connected, suppressed, owned, warnedPermission;
    private IBinder server;
    private long nextAttach;
    private int failures;
    private String lastStatus = "";
    private String displayStatus = "";
    private final Runnable retry = this::refresh;
    private final Runnable timeout = () -> { if (binding != null && remote == null) failed("UserService callback timeout"); };

    private void status(String message) {
        if (!message.equals(lastStatus)) Log.i(TAG, message);
        lastStatus = message;
    }


    public static boolean supportedPhone(Context context) {
        return "org.cv10.monado.out_of_process".equals(context.getPackageName())
                && "samsung".equalsIgnoreCase(Build.MANUFACTURER) && "SM-F9660".equals(Build.MODEL)
                && context.getSystemService(UserManager.class).isSystemUser();
    }

    public static synchronized Cv10RefreshLease get(Service service) {
        if (instance == null) instance = new Cv10RefreshLease(service);
        return instance;
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener options =
            (p, key) -> { if (ENABLED.equals(key)) schedule(); };

    private Cv10RefreshLease(Service service) {
        context = service.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        displays = context.getSystemService(DisplayManager.class);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        work = new Handler(thread.getLooper());
        args = new Shizuku.UserServiceArgs(new ComponentName(context, Cv10RefreshUserService.class))
                .daemon(false).processNameSuffix("cv10_refresh").tag("cv10_refresh").version(2);
        if (!supportedPhone(context)) return;
        prefs.registerOnSharedPreferenceChangeListener(options);
        displays.registerDisplayListener(this, work);
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(RATE), false,
                new ContentObserver(work) {
                    @Override public void onChange(boolean self) { observe(); }
                });
        Shizuku.addBinderReceivedListenerSticky(() -> work.post(this::serverChanged));
        Shizuku.addBinderDeadListener(() -> work.post(this::serverChanged));
        Shizuku.addRequestPermissionResultListener((code, result) -> schedule());
    }

    private void serverChanged() {
        IBinder now = Shizuku.getBinder();
        if (now != null && !now.pingBinder()) now = null;
        if (!Objects.equals(now, server)) {
            if (owned && remote != null && remote.asBinder().pingBinder()) restore();
            detach(false);
            server = now;
            failures = 0;
            nextAttach = 0;
            status(now == null ? "Shizuku server disconnected" : "New Shizuku server binder received");
        }
        refresh();
    }

    public static void recoverFromLauncher(Service service) { get(service).schedule(); }

    private void schedule() { work.post(this::refresh); }

    public void onBound() { work.post(() -> { bound = true; Log.i(TAG, "Android XR service bound"); }); }

    /** Native server successfully accepted the client's IPC fd. */
    public void onAccepted() { work.post(() -> {
        if (bound) { connected = true; status("OpenXR IPC accepted"); refresh(); }
        else Log.w(TAG, "Ignoring late IPC acceptance after Android XR service unbound");
    }); }

    public void onUnbound() { work.post(() -> {
        Log.i(TAG, "Android XR service unbound");
        bound = connected = false;
        suppressed = false;
        warnedPermission = false;
        refresh();
    }); }

    public void close() { onUnbound(); }

    private boolean eligibleDisplays() {
        Display phone = displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (phone == null) { displayStatus = "primary unavailable"; return false; }
        Display.Mode current = phone.getMode();
        int w = current.getPhysicalWidth(), h = current.getPhysicalHeight();
        float peak = 0;
        for (Display.Mode mode : phone.getSupportedModes()) {
            int mw = mode.getPhysicalWidth(), mh = mode.getPhysicalHeight();
            if ((mw == w && mh == h) || (mw == h && mh == w)) peak = Math.max(peak, mode.getRefreshRate());
        }
        boolean cv10 = false;
        StringBuilder external = new StringBuilder();
        for (Display d : displays.getDisplays()) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY) continue;
            external.append(d.getDisplayId()).append('/').append(d.getName().trim()).append(' ');
            if (d.isValid() && "HUAWEIAV03".equalsIgnoreCase(d.getName().trim())) cv10 = true;
        }
        displayStatus = "primary=" + w + "x" + h + " peak=" + peak + " external=[" + external + "]";
        return peak >= 119 && cv10;
    }

    private boolean pending() { return prefs.getBoolean(PENDING, false); }
    private String read() { return Settings.System.getString(context.getContentResolver(), RATE); }
    private void clearJournal() {
        owned = false;
        if (!prefs.edit().remove(PENDING).remove(ORIGINAL).commit())
            Log.w(TAG, "Refresh journal clear failed");
    }

    private void observe() {
        try {
            if (owned && !TARGET.equals(read())) {
                suppressed = true;
                try { if (remote != null) remote.relinquish(); }
                finally { clearJournal(); }
                status("Manual refresh change retained until XR/display disconnect");
                refresh();
            }
        } catch (Exception e) { Log.w(TAG, "Could not inspect refresh change", e); }
    }

    private void retryAfter(long delay) {
        work.removeCallbacks(retry);
        work.postDelayed(retry, delay);
    }

    private void failed(String why) {
        Log.w(TAG, why);
        detach(true);
        failures = Math.min(failures + 1, 5);
        long delay = Math.min(12000, 500L << failures);
        nextAttach = SystemClock.uptimeMillis() + delay;
        retryAfter(delay);
    }

    private void attach() {
        if (binding != null) return;
        long remaining = nextAttach - SystemClock.uptimeMillis();
        if (remaining > 0) { retryAfter(remaining); return; }
        if (!Shizuku.pingBinder()) {
            status("Waiting for Shizuku server"); retryAfter(3000); return;
        }
        if (Shizuku.getVersion() < 13 || Shizuku.getUid() != 2000) {
            status("Waiting for Shizuku 13+ running as wireless-debugging shell"); retryAfter(5000); return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            status("Waiting for Shizuku user authorization");
            if (connected && prefs.getBoolean(ENABLED, false) && !warnedPermission) {
                warnedPermission = true;
                main.post(() -> Toast.makeText(context, "独立 120 Hz 需要授权：打开 Shizuku → 已授权应用 → Monado CV10。", Toast.LENGTH_LONG).show());
            }
            retryAfter(5000); return;
        }
        final IBinder expectedServer = Shizuku.getBinder();
        ServiceConnection c = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                work.post(() -> {
                    if (binding != this || !Objects.equals(expectedServer, Shizuku.getBinder())) return;
                    if (binder == null || !binder.pingBinder()) { failed("Dead UserService on connect"); return; }
                    work.removeCallbacks(timeout);
                    remote = ICv10Refresh.Stub.asInterface(binder);
                    status("Shizuku UserService connected");
                    refresh();
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                work.post(() -> { if (binding == this) failed("Shizuku UserService disconnected"); });
            }
        };
        binding = c;
        status("Binding Shizuku UserService, attempt " + (failures + 1));
        work.removeCallbacks(timeout);
        work.postDelayed(timeout, 6500);
        // SDK 13.1.5 delivers and mutates its unsynchronized connection set on the main looper.
        main.post(() -> {
            try {
                if (Objects.equals(expectedServer, Shizuku.getBinder())) {
                    // A daemon death can prevent an earlier unbind(false) from clearing the SDK cache.
                    try { Shizuku.unbindUserService(args, null, false); }
                    catch (RuntimeException e) { Log.d(TAG, "Previous UserService callback unavailable", e); }
                    Shizuku.bindUserService(args, c);
                }
            } catch (RuntimeException e) {
                work.post(() -> { if (binding == c) failed("Shizuku bind failed: " + e); });
            }
        });
    }

    private void detach(boolean remove) {
        work.removeCallbacks(timeout);
        ServiceConnection old = binding;
        IBinder oldServer = server;
        remote = null;
        binding = null;
        owned = false;
        if (old == null) return;
        main.post(() -> {
            if (!Shizuku.pingBinder()) return;
            try {
                // false unregisters the SDK callback object; true alone only removes the server record.
                Shizuku.unbindUserService(args, old, false);
                if (remove && Objects.equals(oldServer, Shizuku.getBinder()))
                    Shizuku.unbindUserService(args, old, true);
            } catch (RuntimeException e) { Log.w(TAG, "UserService cleanup deferred", e); }
        });
    }

    private void refresh() {
        if (!supportedPhone(context)) return;
        work.removeCallbacks(retry);
        try {
            IBinder now = Shizuku.getBinder();
            if (now != null && !now.pingBinder()) now = null;
            if (!Objects.equals(now, server)) { serverChanged(); return; }
            boolean optedIn = prefs.getBoolean(ENABLED, false);
            boolean displaysReady = connected && optedIn && eligibleDisplays();
            if (!displaysReady) suppressed = false;
            if (owned && remote != null && !remote.asBinder().pingBinder()) {
                failed("UserService binder lost"); return;
            }
            if (owned && displaysReady) { observe(); return; }

            // The shell service may already have restored the floor on death. No privileged RPC
            // is needed to reconcile this case, including a previously absent (null) original.
            if (pending() && !owned) {
                String actual = read(), original = prefs.getString(ORIGINAL, null);
                if (!TARGET.equals(actual)) {
                    if (displaysReady && !Objects.equals(actual, original)) suppressed = true;
                    clearJournal();
                    status(Objects.equals(actual, original) ? "Journal reconciled: original already restored"
                            : "Journal reconciled: external setting retained");
                }
            }
            if (!pending() && (!displaysReady || suppressed)) {
                detach(true);
                if (connected && optedIn && !displaysReady) {
                    status("Waiting for CV10/120 Hz: " + displayStatus); retryAfter(2000);
                }
                return;
            }
            if (remote == null) { attach(); return; }
            if (pending() && !restore()) { failed("Refresh restore pending"); return; }
            if (!displaysReady || suppressed) { detach(true); return; }
            String before = read();
            if (TARGET.equals(before)) { suppressed = true; detach(true); return; }
            if (!prefs.edit().putBoolean(PENDING, true).putString(ORIGINAL, before).commit()) {
                failed("Could not commit refresh journal"); return;
            }
            owned = remote.acquire(before, lifetime);
            if (owned) {
                failures = 0;
                nextAttach = 0;
                status("Shizuku acquired 120 Hz refresh floor");
            } else {
                if (!Objects.equals(before, read())) suppressed = true;
                failed("UserService did not acquire refresh floor");
            }
        } catch (Exception e) { failed("Refresh RPC unavailable; XR continues: " + e); }
    }

    private boolean restore() {
        if (!pending()) return true;
        try {
            if (remote == null || !remote.restore(prefs.getString(ORIGINAL, null))) return false;
            clearJournal();
            Log.i(TAG, "Shizuku restored prior floor or retained external change");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Restore deferred; journal retained", e);
            return false;
        }
    }

    @Override public void onDisplayAdded(int id) { refresh(); }
    @Override public void onDisplayRemoved(int id) { refresh(); }
    @Override public void onDisplayChanged(int id) { refresh(); }
}
