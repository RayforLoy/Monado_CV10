// SPDX-License-Identifier: BSL-1.0
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class Cv10PocketFocusProbe {
    // Android 16 DisplayManager VirtualDisplay flags.
    private static final int TRUSTED = 1 << 10;
    private static final int OWN_FOCUS = 1 << 14;
    private static final int STEAL_TOP_FOCUS_DISABLED = 1 << 16;

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || args.length != 2) {
            throw new IllegalArgumentException("Use adb shell with a stop file and the display focus mode");
        }
        Context shell = createSystemContext().createPackageContext("com.android.shell", 0);
        DisplayManager manager = shell.getSystemService(DisplayManager.class);
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        switch (args[1]) {
            case "public": break;
            case "trusted": flags |= TRUSTED; break;
            case "own-focus": flags |= TRUSTED | OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED; break;
            default: throw new IllegalArgumentException("Unexpected display focus mode " + args[1]);
        }
        File stop = new File(args[0]);
        try (ImageReader reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)) {
            var display = manager.createVirtualDisplay("CV10 XR Own Focus Probe", 1280, 720, 240,
                    reader.getSurface(), flags);
            if (display == null) {
                throw new IllegalStateException("DisplayManager did not create the display");
            }
            try {
                System.out.println("CV10 requested flags 0x" + Integer.toHexString(flags));
                System.out.println("CV10 focus probe display id=" + display.getDisplay().getDisplayId());
                System.out.println(display.getDisplay());
                long deadline = SystemClock.uptimeMillis() + 55000;
                while (!stop.exists() && SystemClock.uptimeMillis() < deadline) {
                    // Drain the auxiliary output. The tested game's image is sent to Pocket XR.
                    Image pending = reader.acquireLatestImage();
                    if (pending != null) pending.close();
                    SystemClock.sleep(25);
                }
            } finally {
                display.release();
                System.out.println("CV10 focus probe display released");
            }
        }
    }

    private static Context createSystemContext() throws ReflectiveOperationException {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Constructor<?> construct = thread.getDeclaredConstructor();
        construct.setAccessible(true);
        Object instance = construct.newInstance();
        Field current = thread.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, instance);
        // Samsung's display configuration queries this controller while creating the system Context.
        Constructor<?> configuration = Class.forName("android.app.ConfigurationController")
                .getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
        configuration.setAccessible(true);
        Field controller = thread.getDeclaredField("mConfigurationController");
        controller.setAccessible(true);
        controller.set(instance, configuration.newInstance(instance));
        Method context = thread.getDeclaredMethod("getSystemContext");
        context.setAccessible(true);
        return (Context) context.invoke(instance);
    }
}
