// SPDX-License-Identifier: BSL-1.0
package org.cv10.diagnostics.pocket_nodelete;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Announces the probe to the Broker. The client library connects to stock Pocket XR. */
public final class RuntimeDiscoveryService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
