// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.function.BooleanSupplier;
import org.freedesktop.monado.auxiliary.Cv10PhonePadHost;

/** The capability belongs to one accepted XR connection. No exported touch receivers. */
final class Cv10PhonePadConnection extends ICv10PhonePad.Stub implements IBinder.DeathRecipient {
    private final int uid = Binder.getCallingUid(), pid = Binder.getCallingPid();
    private final IBinder life;
    private final Cv10PhonePadHost host;
    private final BooleanSupplier foreground;
    private boolean closed, visible;

    Cv10PhonePadConnection(IBinder life, Cv10PhonePadHost host, BooleanSupplier foreground) throws RemoteException {
        this.life = life;
        this.host = host;
        this.foreground = foreground;
        life.linkToDeath(this, 0);
    }
    private boolean allowed() {
        return !closed && Binder.getCallingUid() == uid && Binder.getCallingPid() == pid;
    }
    @Override public synchronized boolean activate(int rotation) {
        visible = allowed() && foreground.getAsBoolean() && host.activate(this, rotation);
        return visible;
    }
    @Override public synchronized void buttons(int buttons) {
        if (allowed() && visible) host.buttons(this, buttons & 3);
    }
    @Override public synchronized void touch(boolean active, float x, float y) {
        if (allowed() && visible && Float.isFinite(x) && Float.isFinite(y)) {
            host.touch(this, active, Math.max(-1f, Math.min(x, 1f)), Math.max(-1f, Math.min(y, 1f)));
        }
    }
    @Override public synchronized void recenter() {
        if (allowed() && visible) host.recenter(this);
    }
    @Override public synchronized void hide() {
        if (allowed()) { visible = false; host.release(this); }
    }
    @Override public synchronized void close() {
        if (!allowed()) return;
        life.unlinkToDeath(this, 0);
        binderDied();
    }
    @Override public synchronized void binderDied() {
        closed = true;
        visible = false;
        host.release(this);
    }
}
