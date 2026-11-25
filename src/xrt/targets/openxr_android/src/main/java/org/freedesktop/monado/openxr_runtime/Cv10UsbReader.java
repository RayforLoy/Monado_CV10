// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import org.freedesktop.monado.android_common.Cv10UsbAccess;
import android.content.Context;
import android.hardware.usb.*;
import android.os.SystemClock;
import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeoutException;

/** Synchronous, single-worker USB source. All methods are called by the same native thread. */
public final class Cv10UsbReader {
    private static final String TAG = "MonadoCV10";
    private final Context context;
    private final UsbManager manager;
    private final Cv10PanelWake panel = new Cv10PanelWake();
    private UsbDeviceConnection port;
    private UsbInterface iface;
    private UsbRequest request;
    private ByteBuffer packet;
    private boolean queued, enabled;
    private int askedId = -1;
    private long nextOpen, deadline, frames;

    public Cv10UsbReader(Context ctx) {
        context = ctx.getApplicationContext();
        manager = context.getSystemService(UsbManager.class);
    }

    private int setSensor(int id) {
        byte[] b = {(byte) id, 2, 0, 0, 0};
        return port.controlTransfer(0x21, 9, 0x300|id, iface.getId(), b, b.length, 500);
    }

    private boolean open() {
        UsbDevice glass = Cv10UsbAccess.find(manager);
        if (glass==null) { askedId=-1; return false; }
        if (!manager.hasPermission(glass)) {
            if (askedId!=glass.getDeviceId()) {
                askedId=glass.getDeviceId();
                Cv10UsbAccess.request(context, manager, glass);
                Log.i(TAG, "Requested CV10 USB permission");
            }
            return false;
        }
        panel.request(manager, glass);
        for (int i=0; i<glass.getInterfaceCount(); i++) {
            UsbInterface hid=glass.getInterface(i);
            if (hid.getId()!=3 || hid.getAlternateSetting()!=0 || hid.getInterfaceClass()!=3 ||
                hid.getInterfaceSubclass()!=0 || hid.getInterfaceProtocol()!=0) continue;
            for (int j=0; j<hid.getEndpointCount(); j++) {
                UsbEndpoint ep=hid.getEndpoint(j);
                if (ep.getType()!=UsbConstants.USB_ENDPOINT_XFER_INT || ep.getAddress()!=0x84 ||
                    ep.getMaxPacketSize()<60) continue;
                port=manager.openDevice(glass);
                if (port==null) return false;
                if (!port.claimInterface(hid,false) && !port.claimInterface(hid,true)) { close(); return false; }
                iface=hid;
                request=new UsbRequest();
                if (!request.initialize(port,ep)) { close(); return false; }
                packet=ByteBuffer.allocateDirect(ep.getMaxPacketSize()).order(ByteOrder.LITTLE_ENDIAN);
                enabled=setSensor(5)==5;
                if (!enabled) { close(); return false; }
                frames=0;
                deadline=SystemClock.elapsedRealtime()+5000;
                Log.i(TAG,"CV10 IMU enabled, interrupt IN 0x84");
                return true;
            }
        }
        return false;
    }

    /** Device nanoseconds, zero for no sample, minus one for a disconnected stream. */
    public long read(float[] imu) {
        try {
            if (!enabled) {
                if (manager==null || SystemClock.elapsedRealtime()<nextOpen) {
                    SystemClock.sleep(100);
                    return 0;
                }
                nextOpen=SystemClock.elapsedRealtime()+500;
                if (!open()) return 0;
            }
            if (!queued) {
                packet.clear();
                queued=request.queue(packet);
                if (!queued) { close(); return -1; }
            }
            try {
                if (port.requestWait(200)!=request) { close(); return -1; }
            } catch (TimeoutException waiting) {
                if (SystemClock.elapsedRealtime()>=deadline) { close(); return -1; }
                return 0;
            }
            queued=false;
            if (packet.position()!=60 || packet.get(0)!=1 || packet.getInt(12)!=0 || packet.getInt(28)!=1) {
                if (SystemClock.elapsedRealtime()>=deadline) { close(); return -1; }
                return 0;
            }
            long ns=((long)packet.getInt(4)<<32) | (packet.getInt(8)&0xffffffffL);
            for (int i=0;i<3;i++) {
                imu[i]=packet.getFloat(16+4*i)*9.8f;
                imu[i+3]=(float)Math.toRadians(packet.getFloat(32+4*i));
                if (!Float.isFinite(imu[i]) || !Float.isFinite(imu[i+3])) return 0;
            }
            deadline=SystemClock.elapsedRealtime()+2500;
            if (frames++==0 || frames%5000==0) Log.i(TAG,"CV10 valid IMU samples="+frames+" deviceNs="+ns);
            return ns;
        } catch (RuntimeException failure) {
            Log.w(TAG,"CV10 read failed",failure); close(); return -1;
        }
    }

    public void close() {
        try {
            if (request!=null && queued) {
                request.cancel();
                try { port.requestWait(200); } catch (TimeoutException ignored) {}
            }
        } catch (RuntimeException ignored) {}
        queued=false;
        if (request!=null) { request.close(); request=null; }
        if (port!=null) {
            try { if (enabled) Log.i(TAG,"CV10 IMU disable bytes="+setSensor(6)); }
            finally {
                try { if (iface!=null) port.releaseInterface(iface); }
                finally { port.close(); port=null; iface=null; enabled=false; }
            }
        }
    }
}
