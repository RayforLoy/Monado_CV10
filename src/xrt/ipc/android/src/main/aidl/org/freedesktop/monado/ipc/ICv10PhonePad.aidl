// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.ipc;

/** Per-client input capability, valid only for the process that opened the XR connection. */
interface ICv10PhonePad {
    boolean activate(int portraitRotation);
    void hide();
    void buttons(int buttons);
    void recenter();
    void close();
}
