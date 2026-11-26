// SPDX-License-Identifier: BSL-1.0
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import dalvik.system.PathClassLoader;

import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class PocketShellProbe {
    private static final String PREVIEW = "org.cv10.monado.out_of_process.launcher_preview";
    private static final String SERVICE = "org.freedesktop.monado.auxiliary.Cv10PocketXrDisplayService";

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || Process.myUid() != 2000) {
            throw new IllegalArgumentException("Usage as adb shell: PocketShellProbe MODE OUTPUT.png [SECOND.png]");
        }
        int mode = Integer.parseInt(args[0]);
        if (mode < 0 || mode > 2) throw new IllegalArgumentException("Unknown Pocket XR mode");

        Context systemContext = createSystemContext();
        Context shell = systemContext.createPackageContext("com.android.shell", 0);
        Context preview = systemContext.createPackageContext(PREVIEW, 0);
        var loader = new PathClassLoader(
                preview.getApplicationInfo().sourceDir, PocketShellProbe.class.getClassLoader());
        var type = Class.forName(SERVICE, true, loader);
        var constructor = type.getConstructor(Context.class);
        Method start = type.getMethod("start", Surface.class, IBinder.class, int.class);
        Method stop = type.getMethod("stop", IBinder.class);
        Object productionOwner = constructor.newInstance(preview);
        try {
            start.invoke(productionOwner, null, null, mode);
            throw new AssertionError("Preview-owned service accepted a shell caller");
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof SecurityException)) throw error;
            System.out.println("Preview-owned instance rejected shell UID 2000");
        }

        Object service = constructor.newInstance(shell);
        DisplayManager displays = shell.getSystemService(DisplayManager.class);
        Binder lifetime = new Binder();
        int width = mode == 2 ? 1920 : 3840;
        try (ImageReader stream = ImageReader.newInstance(width, 1080, PixelFormat.RGBA_8888, 3)) {
            int id = (Integer) start.invoke(service, stream.getSurface(), lifetime, mode);
            System.out.println("Accepted mode " + mode + " on display " + id + ": " + displays.getDisplay(id));
            boolean captured;
            try {
                stop.invoke(service, new Binder());
                if (displays.getDisplay(id) == null) throw new AssertionError("Wrong token stopped the lease");
                System.out.println("Wrong token left display " + id + " active");
                captured = saveFrame(stream, args[1]);
                if (captured && args.length == 3 && !saveFrame(stream, args[2])) {
                    throw new IllegalStateException("Pocket XR did not render a second frame");
                }
            } finally {
                stop.invoke(service, lifetime);
            }
            long deadline = SystemClock.uptimeMillis() + 2500;
            while (displays.getDisplay(id) != null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
            if (displays.getDisplay(id) != null) throw new AssertionError("Display " + id + " is still active");
            System.out.println("Correct token released display " + id);
            if (!captured) {
                System.out.println("No frame followed the accepted launch within 5500 ms");
                if (mode != 2) throw new IllegalStateException("Pocket XR space did not draw");
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

    private static boolean saveFrame(ImageReader stream, String path) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5500;
        while (SystemClock.uptimeMillis() < deadline) {
            try (Image frame = stream.acquireLatestImage()) {
                // Drain Android's initial buffers for two seconds while Pocket XR starts its renderer.
                if (frame != null && SystemClock.uptimeMillis() + 3500 >= deadline) {
                    Image.Plane plane = frame.getPlanes()[0];
                    int rowPixels = plane.getRowStride() / 4;
                    Bitmap padded = Bitmap.createBitmap(rowPixels, frame.getHeight(), Bitmap.Config.ARGB_8888);
                    padded.copyPixelsFromBuffer(plane.getBuffer());
                    Bitmap image = Bitmap.createBitmap(padded, 0, 0, frame.getWidth(), frame.getHeight());
                    try (FileOutputStream out = new FileOutputStream(path)) {
                        if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IllegalStateException("PNG write failed");
                    }
                    System.out.println("Captured " + frame.getWidth() + "x" + frame.getHeight() + " to " + path);
                    return true;
                }
            }
            SystemClock.sleep(100);
        }
        return false;
    }
}
