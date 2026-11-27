// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.android_common;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.widget.Toast;

public final class Cv10UsbAccess {
    private Cv10UsbAccess() {}

    public static UsbDevice find(UsbManager manager) {
        if (manager == null) return null;
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == 0x12d1 && d.getProductId() == 0x107e) return d;
        }
        return null;
    }

    public static void request(Context context, UsbManager manager, UsbDevice glass) {
        Intent grant = new Intent(context.getPackageName() + ".CV10_USB").setPackage(context.getPackageName());
        manager.requestPermission(glass, PendingIntent.getBroadcast(context, 0, grant, PendingIntent.FLAG_IMMUTABLE));
    }

    public static boolean readyForLaunch(Context context, boolean requestIfMissing) {
        UsbManager manager = context.getSystemService(UsbManager.class);
        UsbDevice glass = find(manager);
        // Keep the launcher's regular fallback when no CV10 USB device is present.
        if (glass == null || manager.hasPermission(glass)) return true;
        if (requestIfMissing) request(context, manager, glass);
        return false;
    }

    static void requestFromSettings(Context context) {
        UsbManager manager = context.getSystemService(UsbManager.class);
        UsbDevice glass = find(manager);
        if (glass == null || manager.hasPermission(glass)) {
            String status = glass == null ? "CV10 USB device not connected" : "CV10 USB access granted";
            Toast.makeText(context, status, Toast.LENGTH_SHORT).show();
        } else {
            request(context, manager, glass);
        }
    }
}
