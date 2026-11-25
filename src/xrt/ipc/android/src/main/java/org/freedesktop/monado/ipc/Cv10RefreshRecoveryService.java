// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import org.freedesktop.monado.auxiliary.Cv10RefreshLease;

/** Recovers a journal after a stopped process when the runtime launcher is reopened. */
public final class Cv10RefreshRecoveryService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Cv10RefreshLease.recoverFromLauncher(this);
        stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
