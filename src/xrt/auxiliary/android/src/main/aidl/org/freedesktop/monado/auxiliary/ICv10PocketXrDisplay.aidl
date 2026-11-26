// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.view.Surface;

interface ICv10PocketXrDisplay {
    void destroy() = 16777114;
    int start(in Surface surface, IBinder clientLife, boolean showTestCard) = 1;
    void stop(IBinder clientLife) = 2;
}
