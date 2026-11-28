// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.auxiliary;

/** Server-side touch and sensor ownership. The opaque token identifies one XR client. */
public interface Cv10PhonePadHost {
    boolean activate(Object client, int portraitRotation);
    void release(Object client);
    void buttons(Object client, int buttons);
    void touch(Object client, boolean active, float x, float y);
    void recenter(Object client);
}
