// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

import android.view.Surface;

interface ICv10PocketXrDisplay {
    const int MODE_SPACE = 0;
    const int MODE_TEST_CARD = 1;
    const int MODE_PC_SCREEN_SBS = 2;
    const int MODE_CV10_OPTICS = 3;
    const int PC_SCREEN_WIDTH = 1920;

    void destroy() = 16777114;
    int start(in Surface surface, IBinder clientLife, int mode, int opticalEyeSize) = 1;
    void stop(IBinder clientLife) = 2;
    int openClientDisplay(IBinder clientLife) = 3;
    void closeClientDisplay(IBinder clientLife) = 4;
}
