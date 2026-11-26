// SPDX-License-Identifier: BSL-1.0
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.Method;
import org.freedesktop.monado.auxiliary.Cv10PocketXrDisplayService;
import org.freedesktop.monado.auxiliary.ICv10PocketXrDisplay;

public final class Cv10PocketClientDisplaySmoke {
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != Process.SHELL_UID) throw new IllegalStateException("Run from adb shell");
        Method systemContext = Cv10PocketFocusProbe.class.getDeclaredMethod("createSystemContext");
        systemContext.setAccessible(true);
        Context shell = ((Context) systemContext.invoke(null)).createPackageContext("com.android.shell", 0);
        DisplayManager displays = shell.getSystemService(DisplayManager.class);
        Cv10PocketXrDisplayService service = new Cv10PocketXrDisplayService(shell);
        Binder lease = new Binder();
        Handler main = new Handler(Looper.getMainLooper());
        try (ImageReader space = ImageReader.newInstance(3840, 1080, PixelFormat.RGBA_8888, 2)) {
            space.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
            }, main);
            try {
                int spaceId = service.start(space.getSurface(), lease, ICv10PocketXrDisplay.MODE_SPACE);
                System.out.println("Pocket Space display: " + spaceId);
                int first = service.openClientDisplay(lease);
                System.out.println("Client display: " + displays.getDisplay(first));
                if (first == spaceId || displays.getDisplay(first) == null) throw new AssertionError("Missing client");
                service.closeClientDisplay(new Binder());
                if (displays.getDisplay(first) == null) throw new AssertionError("Wrong lease closed the client");
                int second = service.openClientDisplay(lease);
                System.out.println("Replacement client display: " + second);
                if (first == second || displays.getDisplay(first) != null) throw new AssertionError("Client not replaced");
                service.closeClientDisplay(lease);
                if (displays.getDisplay(second) != null) throw new AssertionError("Client was not released");
                if (displays.getDisplay(spaceId) == null) throw new AssertionError("Closing client closed the Space");
                System.out.println("Client reopen and independent close passed");
                int third = service.openClientDisplay(lease);
                service.stop(lease);
                if (displays.getDisplay(spaceId) != null || displays.getDisplay(third) != null) {
                    throw new AssertionError("Stop retained a display");
                }
                System.out.println("Closing the Space released both displays");
            } finally {
                service.stop(lease);
            }
        }
    }
}
