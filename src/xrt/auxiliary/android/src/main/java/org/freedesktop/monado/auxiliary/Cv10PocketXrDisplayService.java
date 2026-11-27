// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import androidx.annotation.Keep;
import androidx.annotation.RequiresApi;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Private Shizuku entry point for an explicitly requested Pocket XR display. */
@Keep
public final class Cv10PocketXrDisplayService extends ICv10PocketXrDisplay.Stub {
    private static final String TAG = "Cv10PocketXrDisplay";
    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final ComponentName POCKET_SPACE = ComponentName.createRelative(
            "com.hayeco.flipspaces", ".spatial.SpaceActivity");
    public static final ComponentName POCKET_PC_SCREEN = ComponentName.createRelative(
            "com.hayeco.flipspaces", ".pcscreen.PcScreenActivity");
    private static final int SPACE_WIDTH = 3840;
    private static final int HEIGHT = 1080;
    private static final int DENSITY_DPI = 240;
    private static final int CLIENT_WIDTH = 1280;
    private static final int CLIENT_HEIGHT = 720;
    private static final int SYSTEM_USER = 0;

    // DisplayManager's hidden flag values. These match scrcpy v5.0.1's Android 14+ new display
    // with --no-vd-system-decorations and default destroy-on-removal.
    private static final int SUPPORTS_TOUCH = 1 << 6;
    private static final int ROTATES_WITH_CONTENT = 1 << 7;
    private static final int DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int TRUSTED = 1 << 10;
    private static final int OWN_DISPLAY_GROUP = 1 << 11;
    private static final int ALWAYS_UNLOCKED = 1 << 12;
    private static final int TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int OWN_FOCUS = 1 << 14;
    private static final int DEVICE_DISPLAY_GROUP = 1 << 15;
    private static final int STEAL_TOP_FOCUS_DISABLED = 1 << 16;
    private static final int FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL
            | TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED | TOUCH_FEEDBACK_DISABLED
            | OWN_FOCUS | DEVICE_DISPLAY_GROUP;

    private final Context context;
    private final int ownerUid;
    private Lease currentLease;

    /** Shizuku supplies its package Context for Monado, even though this process is shell. */
    @Keep
    public Cv10PocketXrDisplayService(Context context) {
        this.context = context;
        ownerUid = context.getApplicationInfo().uid;
    }

    private void verifyCaller() {
        if (Process.myUid() != Process.SHELL_UID
                || !Process.myUserHandle().equals(UserHandle.getUserHandleForUid(ownerUid))
                || Binder.getCallingUid() != ownerUid) {
            throw new SecurityException("Pocket XR display requires the Monado caller and Shizuku shell in the owner profile");
        }
    }

    @Override
    public synchronized int start(Surface surface, IBinder clientLife, int mode)
            throws RemoteException {
        verifyCaller();
        if (surface == null) throw new IllegalArgumentException("Missing Pocket XR output Surface");
        Lease lease = null;
        boolean started = false;
        long identity = Binder.clearCallingIdentity();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                throw new IllegalStateException("Pocket XR display requires Android 14 or later");
            }
            if (clientLife == null || !surface.isValid()) {
                throw new IllegalArgumentException("Invalid Pocket XR display lease or Surface");
            }
            if (mode != MODE_SPACE && mode != MODE_TEST_CARD && mode != MODE_PC_SCREEN_SBS) {
                throw new IllegalArgumentException("Unknown Pocket XR display mode");
            }
            int width = mode == MODE_PC_SCREEN_SBS ? PC_SCREEN_WIDTH : SPACE_WIDTH;
            if (currentLease != null) throw new IllegalStateException("Pocket XR display is already running");
            lease = new Lease(clientLife, surface, mode);
            clientLife.linkToDeath(lease, 0);
            currentLease = lease;
            lease.display = createDisplay("Monado CV10 Pocket XR", surface, width, HEIGHT, FLAGS);
            int displayId = lease.display.getDisplay().getDisplayId();
            if (displayId <= Display.DEFAULT_DISPLAY || !clientLife.isBinderAlive()) {
                throw new IllegalStateException("Pocket XR display or caller was lost during startup");
            }
            launchPocketXr(displayId, mode);
            started = true;
            Log.i(TAG, "Pocket XR mode " + mode + " start accepted on " + displayId
                    + " (" + width + "x" + HEIGHT + ", " + DENSITY_DPI + " dpi)");
            return displayId;
        } catch (InvocationTargetException e) {
            throw platformFailure(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw platformFailure(e);
        } finally {
            try {
                if (!started) {
                    if (lease != null && currentLease == lease) release(lease);
                    else surface.release();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @RequiresApi(34)
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private VirtualDisplay createDisplay(String name, Surface surface, int width, int height, int flags)
            throws ReflectiveOperationException {
        // A normal package Context would pass Monado's package name with the shell UID.
        Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        DisplayManager manager = constructor.newInstance(new ShellDisplayContext(context));
        VirtualDisplay display = manager.createVirtualDisplay(name, width, height, DENSITY_DPI, surface, flags);
        if (display == null) throw new IllegalStateException("Android rejected the Pocket XR virtual display");
        return display;
    }

    @Override
    public synchronized int openClientDisplay(IBinder clientLife) {
        verifyCaller();
        Lease lease = currentLease;
        if (lease == null || !lease.clientLife.equals(clientLife) || lease.mode != MODE_SPACE) {
            throw new IllegalStateException("The Pocket XR Space is not active for this caller");
        }
        long identity = Binder.clearCallingIdentity();
        boolean opened = false;
        try {
            lease.releaseClientDisplay();
            lease.clientReader = ImageReader.newInstance(CLIENT_WIDTH, CLIENT_HEIGHT, PixelFormat.RGBA_8888, 2);
            lease.clientReader.setOnImageAvailableListener(reader -> {
                // The game submits its XR images to Pocket XR. Discard its Android window buffers.
                synchronized (Cv10PocketXrDisplayService.this) {
                    if (lease.clientReader != reader) return;
                    try (Image ignored = reader.acquireLatestImage()) {}
                }
            }, new Handler(Looper.getMainLooper()));
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                    | TRUSTED | DESTROY_CONTENT_ON_REMOVAL;
            if (Build.VERSION.SDK_INT >= 36) flags |= OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED;
            lease.clientDisplay = createDisplay("Monado CV10 OpenXR Client", lease.clientReader.getSurface(),
                    CLIENT_WIDTH, CLIENT_HEIGHT, flags);
            int displayId = lease.clientDisplay.getDisplay().getDisplayId();
            if (displayId <= Display.DEFAULT_DISPLAY || !clientLife.isBinderAlive()) {
                throw new IllegalStateException("The OpenXR client display was lost during startup");
            }
            opened = true;
            Log.i(TAG, "OpenXR client display ready: " + displayId);
            return displayId;
        } catch (InvocationTargetException e) {
            throw platformFailure(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw platformFailure(e);
        } finally {
            try {
                if (!opened) lease.releaseClientDisplay();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public synchronized void closeClientDisplay(IBinder clientLife) {
        verifyCaller();
        if (currentLease == null || !currentLease.clientLife.equals(clientLife)) return;
        long identity = Binder.clearCallingIdentity();
        try {
            currentLease.releaseClientDisplay();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private void launchPocketXr(int displayId, int mode) throws ReflectiveOperationException {
        ComponentName target = mode == MODE_PC_SCREEN_SBS ? POCKET_PC_SCREEN : POCKET_SPACE;
        Intent intent = new Intent().setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mode != MODE_PC_SCREEN_SBS) {
            // Pocket XR retains the card mode while its process is alive.
            intent.putExtra("test_card", mode == MODE_TEST_CARD ? "eyes" : "off");
        }
        Bundle options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle();
        Object manager = ActivityManager.class.getDeclaredMethod("getService").invoke(null);
        // The explicit shell package and absent app thread match a shell-originated launch.
        Method startActivity = Class.forName("android.app.IActivityManager").getMethod(
                "startActivityAsUser", Class.forName("android.app.IApplicationThread"), String.class,
                Intent.class, String.class, IBinder.class, String.class, int.class, int.class,
                Class.forName("android.app.ProfilerInfo"), Bundle.class, int.class);
        int status = (int) startActivity.invoke(manager, null, SHELL_PACKAGE, intent, null, null,
                null, 0, 0, null, options, SYSTEM_USER);
        // AOSP ActivityManager reserves 0..99 for successful activity-start results.
        if (status < 0 || status > 99) {
            throw new IllegalStateException("Android rejected Pocket XR's activity start (" + status + ")");
        }
    }

    private IllegalStateException platformFailure(Throwable cause) {
        Log.w(TAG, "System display or activity API failed", cause);
        return new IllegalStateException("Pocket XR shell display service failed: " + cause.getMessage());
    }

    @Override
    public synchronized void stop(IBinder clientLife) {
        verifyCaller();
        if (currentLease != null && currentLease.clientLife.equals(clientLife)) release(currentLease);
    }

    private synchronized void release(Lease lease) {
        if (currentLease != lease) return;
        currentLease = null;
        lease.clientLife.unlinkToDeath(lease, 0);
        long identity = Binder.clearCallingIdentity();
        try {
            lease.releaseClientDisplay();
            if (lease.display != null) lease.display.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "Pocket XR virtual display could not be released", e);
        } finally {
            lease.surface.release();
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public synchronized void destroy() {
        if (Process.myUid() != Process.SHELL_UID || Binder.getCallingUid() != Process.SHELL_UID) {
            throw new SecurityException("Only the Shizuku shell can destroy this UserService");
        }
        if (currentLease != null) release(currentLease);
        System.exit(0);
    }

    private final class Lease implements IBinder.DeathRecipient {
        final IBinder clientLife;
        final Surface surface;
        final int mode;
        VirtualDisplay display;
        VirtualDisplay clientDisplay;
        ImageReader clientReader;

        Lease(IBinder clientLife, Surface surface, int mode) {
            this.clientLife = clientLife;
            this.surface = surface;
            this.mode = mode;
        }

        void releaseClientDisplay() {
            try {
                if (clientDisplay != null) clientDisplay.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "OpenXR client display could not be released", e);
            } finally {
                clientDisplay = null;
                if (clientReader != null) {
                    clientReader.close();
                    clientReader = null;
                }
            }
        }

        @Override
        public void binderDied() {
            release(this);
        }
    }

    @RequiresApi(34)
    private static final class ShellDisplayContext extends ContextWrapper {
        ShellDisplayContext(Context base) {
            super(base);
        }

        @Override public String getPackageName() { return SHELL_PACKAGE; }

        @Override public String getOpPackageName() { return SHELL_PACKAGE; }

        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PACKAGE).build();
        }

        @Override public int getDeviceId() { return 0; }
    }
}
