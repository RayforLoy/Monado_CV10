// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Wakes the headset panels through their runtime PCUI port without blocking IMU acquisition. */
final class Cv10PanelWake {
    private static final String TAG = "MonadoCV10";
    private int requestedId = -1;

    synchronized void request(UsbManager usb, UsbDevice glass) {
        if (glass.getVendorId() != 0x12d1 || glass.getProductId() != 0x107e
                || requestedId == glass.getDeviceId() || !usb.hasPermission(glass)) return;
        requestedId = glass.getDeviceId();
        new Thread(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    UsbDevice attached = usb.getDeviceList().get(glass.getDeviceName());
                    if (attached == null || attached.getDeviceId() != glass.getDeviceId()
                            || !usb.hasPermission(attached)) return;
                    wake(usb, attached);
                    return;
                } catch (IOException error) {
                    Log.w(TAG, "CV10 PCUI attempt " + (attempt + 1) + ": " + error.getMessage());
                    if (attempt == 0) SystemClock.sleep(600);
                } catch (RuntimeException error) {
                    Log.w(TAG, "CV10 PCUI unavailable", error);
                    return;
                }
            }
        }, "CV10-PCUI").start();
    }

    private static void wake(UsbManager usb, UsbDevice glass) throws IOException {
        for (int i = 0; i < glass.getInterfaceCount(); i++) {
            UsbInterface iface = glass.getInterface(i);
            if (iface.getId() != 0 || iface.getAlternateSetting() != 0 || iface.getInterfaceClass() != 0xff
                    || iface.getInterfaceSubclass() != 0x13 || iface.getInterfaceProtocol() != 0x12) continue;
            UsbEndpoint in = null, out = null;
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (ep.getAddress() == 0x81) in = ep;
                if (ep.getAddress() == 0x01) out = ep;
            }
            if (in == null || out == null) break;
            UsbDeviceConnection port = usb.openDevice(glass);
            if (port == null) throw new IOException("open failed");
            try {
                if (!port.claimInterface(iface, false)) throw new IOException("interface busy");
                try {
                    String model = query(port, in, out, "AT^DEVMODEL?");
                    boolean cv10 = hasLine(model, "^DEVMODEL:CV10");
                    if (!cv10) {
                        String version = query(port, in, out, "AT^SWVER");
                        for (String line : version.split("[\r\n]+")) {
                            if (line.trim().matches("MAINVER:CV10AC[0-9.]+")) cv10 = true;
                        }
                    }
                    if (!cv10) {
                        Log.w(TAG, "PCUI identity did not confirm CV10");
                        return;
                    }
                    String reply = query(port, in, out, "AT^SWITCHSCREEN=1");
                    if (!hasLine(reply, "OK")) throw new IOException("panel command rejected");
                    Log.i(TAG, "CV10 PCUI AT^SWITCHSCREEN=1 OK");
                    return;
                } finally {
                    port.releaseInterface(iface);
                }
            } finally {
                port.close();
            }
        }
        Log.w(TAG, "CV10 PCUI runtime interface or endpoints missing");
    }

    private static boolean hasLine(String reply, String expected) {
        for (String line : reply.split("[\r\n]+")) {
            if (expected.equals(line.trim())) return true;
        }
        return false;
    }

    private static String query(UsbDeviceConnection port, UsbEndpoint in, UsbEndpoint out, String command)
            throws IOException {
        byte[] request = (command + "\r\n").getBytes(StandardCharsets.US_ASCII);
        if (port.bulkTransfer(out, request, request.length, 1000) != request.length) {
            throw new IOException(command + " send incomplete");
        }
        long end = SystemClock.elapsedRealtime() + 2000;
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] packet = new byte[in.getMaxPacketSize()];
        while (SystemClock.elapsedRealtime() < end && collected.size() < 4096) {
            int n = port.bulkTransfer(in, packet, packet.length, 200);
            if (n <= 0) continue;
            collected.write(packet, 0, n);
            String reply = collected.toString("US-ASCII");
            if (hasLine(reply, "OK") || hasLine(reply, "ERROR")) return reply;
        }
        throw new IOException(command + " reply timeout");
    }
}
