// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

/** Huawei CF20 input notification, as decoded by Huawei's DefaultController. */
final class Cv10ControllerReport {
    static final int SIZE = 20;
    static final int TRIGGER = 0x01;
    static final int CONFIRM = 0x02;
    static final int VOLUME_DOWN = 0x04;
    static final int VOLUME_UP = 0x08;
    static final int HOME = 0x10;
    static final int BACK = 0x20;

    final int buttons;
    final float x, y, z, w;
    final float accelX, accelY, accelZ;
    final float gyroX, gyroY, gyroZ;
    final int touchX, touchY, battery;
    final boolean orientationValid;

    private Cv10ControllerReport(byte[] data) {
        buttons = data[0] & 0xff;
        x = s16be(data, 1) / 32767f;
        y = -s16be(data, 3) / 32767f;
        z = s16be(data, 7) / 32767f;
        w = -s16be(data, 5) / 32767f;
        float normSquared = x * x + y * y + z * z + w * w;
        orientationValid = normSquared >= 0.81f && normSquared <= 1.21f;
        accelX = data[9] * 16f / 127f;
        accelY = data[10] * 16f / 127f;
        accelZ = data[11] * 16f / 127f;
        gyroX = data[12] * 2000f / 127f;
        gyroY = data[13] * 2000f / 127f;
        gyroZ = data[14] * 2000f / 127f;
        touchX = data[15] & 0xff;
        touchY = data[16] & 0xff;
        battery = data[17];
    }

    private static short s16be(byte[] data, int offset) {
        return (short) ((data[offset] & 0xff) << 8 | (data[offset + 1] & 0xff));
    }

    static Cv10ControllerReport decode(byte[] data) {
        return data != null && data.length == SIZE ? new Cv10ControllerReport(data) : null;
    }

    boolean isPressed(int mask) {
        return (buttons & mask) != 0;
    }

    boolean isTouched() {
        return touchX != 0 || touchY != 0;
    }
}
