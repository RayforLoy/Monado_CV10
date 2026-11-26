// SPDX-License-Identifier: BSL-1.0
package org.cv10.monado.pocket_client;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Broker discovery entry. The client library binds the rendering service in Pocket XR. */
public final class RuntimeDiscoveryService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
