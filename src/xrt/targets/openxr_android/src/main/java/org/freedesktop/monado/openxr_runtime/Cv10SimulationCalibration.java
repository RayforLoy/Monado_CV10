// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

/** Capture two fresh, steady orientations while the controller points along the head. */
final class Cv10SimulationCalibration {
    private static final long HOLD_NS = 800_000_000L;
    private static final long MAX_GAP_NS = 100_000_000L;
    private static final float STEADY_DOT = 0.99863f; // six degrees

    static final class Orientation {
        final long ns;
        final float x, y, z, w;

        Orientation(long ns, float x, float y, float z, float w) {
            this.ns = ns;
            float scale = 1f / (float) Math.sqrt(x*x + y*y + z*z + w*w);
            this.x = x * scale;
            this.y = y * scale;
            this.z = z * scale;
            this.w = w * scale;
        }

        float yaw() {
            return (float) Math.atan2(2f * (x*z + w*y), 1f - 2f * (x*x + y*y));
        }

        boolean hasHeading() {
            return Math.abs(2f * (w*x - y*z)) < 0.98f;
        }

        boolean level() {
            // The user holds both devices level and points them forward, buttons up.
            return 1f - 2f * (x*x + z*z) > 0.94f;
        }

        boolean near(Orientation other) {
            return Math.abs(x*other.x + y*other.y + z*other.z + w*other.w) >= STEADY_DOT;
        }

        float neckDeltaY() {
            // Y component of Q * (0, 0.075, -0.0805) - (0, 0.075, -0.0805).
            return -0.15f * (x*x + z*z) - 0.161f * (y*z - w*x);
        }
    }

    /** Align the CF20's independent horizontal reference with the HMD once per connection. */
    static final class Heading {
        private int connection = -1, handled;
        private float saved = Float.NaN, offset = Float.NaN;

        float resolve(int link, int recenter, float savedYaw, Orientation head, long ns, Cv10ControllerReport hand) {
            if (link != connection || Float.compare(saved, savedYaw) != 0) {
                handled = link == connection ? recenter : 0;
                connection = link;
                saved = offset = savedYaw;
            }
            if ((!Float.isNaN(offset) && handled == recenter) || !hand.orientationValid ||
                    head == null || Math.abs(head.ns - ns) > MAX_GAP_NS || !head.hasHeading()) return offset;
            Orientation raw = new Orientation(ns, hand.x, hand.y, hand.z, hand.w);
            if (raw.hasHeading()) {
                offset = (float) Math.IEEEremainder(head.yaw() - raw.yaw(), 2 * Math.PI);
                handled = recenter;
            }
            return offset;
        }
    }

    static final class Offset {
        final float yaw, heightCorrection;

        Offset(Orientation head, Orientation hand) {
            yaw = (float) Math.IEEEremainder(head.yaw() - hand.yaw(), 2 * Math.PI);
            heightCorrection = -head.neckDeltaY();
        }
    }

    private long request, lastNs;
    private Orientation startHead, startHand;

    void reset() {
        request = lastNs = 0;
        startHead = startHand = null;
    }

    Offset sample(long token, Orientation head, Orientation hand) {
        if (token == 0 || head == null || Math.abs(head.ns - hand.ns) > MAX_GAP_NS ||
                !head.level() || !hand.level()) {
            reset();
            return null;
        }
        if (token != request || hand.ns - lastNs > MAX_GAP_NS ||
                !head.near(startHead) || !hand.near(startHand)) {
            request = token;
            startHead = head;
            startHand = hand;
        }
        lastNs = hand.ns;
        return hand.ns - startHand.ns >= HOLD_NS ? new Offset(head, hand) : null;
    }
}
