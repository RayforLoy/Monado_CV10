// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.view.Surface;

interface ICv10PocketXrDisplay {
    const int MODE_SPACE = 0;
    const int MODE_TEST_CARD = 1;
    const int MODE_PC_SCREEN_SBS = 2;
    const int PC_SCREEN_WIDTH = 1920;

    void destroy() = 16777114;
    int start(in Surface surface, IBinder clientLife, int mode) = 1;
    void stop(IBinder clientLife) = 2;
}
