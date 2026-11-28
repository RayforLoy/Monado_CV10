// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

interface ICv10Refresh {
    void destroy() = 16777114;
    boolean acquire(String expectedOriginal, IBinder runtimeLife) = 1;
    boolean restore(String recordedOriginal) = 2;
    void relinquish() = 3;
    boolean setPersistent(String expectedOriginal) = 4;
}
