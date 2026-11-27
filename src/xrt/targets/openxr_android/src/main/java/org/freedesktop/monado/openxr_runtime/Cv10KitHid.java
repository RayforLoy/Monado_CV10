// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

/** Owns the locator interface and serializes its HID input and output. */
final class Cv10KitHid implements Cv10KitCalibration.Link, AutoCloseable {
    private static final int USB_TIMEOUT_MS = 300;
    private static final int HID_CLASS_INTERFACE_OUT = 0x21;
    private static final int SET_REPORT = 9;
    private static final int OUTPUT_REPORT_ID_2 = 0x0202;

    private final UsbDeviceConnection port;
    private final UsbInterface iface;
    private final UsbRequest input = new UsbRequest();
    final int endpointAddress;
    private final UsbEndpoint output;
    private final ByteBuffer incoming;
    private boolean queued;

    private Cv10KitHid(UsbDeviceConnection port, UsbInterface iface, UsbEndpoint input, UsbEndpoint output)
            throws IOException {
        this.port = port;
        this.iface = iface;
        this.output = output;
        endpointAddress = input.getAddress();
        incoming = ByteBuffer.allocateDirect(Math.max(Cv10KitReport.HID_REPORT_SIZE, input.getMaxPacketSize()));
        if (!this.input.initialize(port, input)) {
            this.input.close();
            throw new IOException("Android could not open the HID input endpoint");
        }
    }

    static Cv10KitHid open(UsbManager usb, UsbDevice device) throws IOException {
        for (int i = 0; i < device.getInterfaceCount(); ++i) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getId() != Cv10KitReport.HID_INTERFACE || iface.getAlternateSetting() != 0 ||
                    iface.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            UsbEndpoint input = null, output = null;
            for (int j = 0; j < iface.getEndpointCount(); ++j) {
                UsbEndpoint endpoint = iface.getEndpoint(j);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    if (input == null) input = endpoint;
                } else if (output == null) {
                    output = endpoint;
                }
            }
            if (input == null) continue;
            UsbDeviceConnection port = usb.openDevice(device);
            if (port == null) throw new IOException("Android could not open the locator.");
            boolean claimed = false, opened = false;
            try {
                claimed = port.claimInterface(iface, false) || port.claimInterface(iface, true);
                if (!claimed) throw new IOException("Android could not claim locator HID interface 2.");
                Cv10KitHid hid = new Cv10KitHid(port, iface, input, output);
                opened = true;
                return hid;
            } finally {
                if (!opened) release(port, iface, claimed);
            }
        }
        throw new IOException("Interface 2 has no HID interrupt IN endpoint.");
    }

    @Override
    public byte[] read(int timeoutMs) throws IOException {
        if (!queued) {
            incoming.clear();
            if (!input.queue(incoming)) throw new IOException("Could not queue locator HID input");
            queued = true;
        }
        try {
            if (port.requestWait(timeoutMs) != input) throw new IOException("HID input request failed");
        } catch (TimeoutException timeout) {
            return null;
        }
        queued = false;
        byte[] report = new byte[incoming.position()];
        incoming.flip();
        incoming.get(report);
        return report;
    }

    @Override
    public void write(byte[] report) throws IOException {
        discardPendingInput();
        if (output == null) {
            int size = port.controlTransfer(HID_CLASS_INTERFACE_OUT, SET_REPORT, OUTPUT_REPORT_ID_2, iface.getId(),
                    report, report.length, USB_TIMEOUT_MS);
            if (size != report.length) throw new IOException("HID SET_REPORT returned " + size + " bytes");
            return;
        }
        UsbRequest request = new UsbRequest();
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(report.length);
            buffer.put(report).flip();
            if (!request.initialize(port, output) || !request.queue(buffer)) {
                throw new IOException("Could not queue locator HID output");
            }
            try {
                if (port.requestWait(USB_TIMEOUT_MS) != request || buffer.position() != report.length) {
                    throw new IOException("HID output report was not sent in full");
                }
            } catch (TimeoutException timeout) {
                request.cancel();
                awaitCancelledRequest(request);
                throw new IOException("HID output report timed out", timeout);
            }
        } finally {
            request.close();
        }
    }

    private void discardPendingInput() throws IOException {
        if (!queued) return;
        input.cancel();
        awaitCancelledRequest(input);
        queued = false;
    }

    private void awaitCancelledRequest(UsbRequest request) throws IOException {
        try {
            if (port.requestWait(USB_TIMEOUT_MS) != request) throw new IOException("Could not drain HID request");
        } catch (TimeoutException timeout) {
            throw new IOException("Could not drain timed-out HID request", timeout);
        }
    }

    @Override
    public void close() {
        try {
            discardPendingInput();
        } catch (IOException ignored) {
            // USB disconnect can invalidate the pending request before it is drained.
        } finally {
            try {
                input.close();
            } finally {
                release(port, iface, true);
            }
        }
    }

    private static void release(UsbDeviceConnection port, UsbInterface iface, boolean claimed) {
        try {
            if (claimed) port.releaseInterface(iface);
        } finally {
            port.close();
        }
    }
}
