// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

/** Estimates one short move between two stationary poses, in the OpenXR floor frame. */
final class Cv10OneShotCalibration {
    static final int DURATION_SECONDS = 8;
    private static final long MAX_GAP_NS = 150_000_000L;
    private static final long REST_NS = 120_000_000L;
    private static final float GRAVITY = 9.80665f;
    private static final float CONTACT_RIGHT_M = 0.09f;
    private static final float ACCEL_NOISE = 1.35f; // The CF20 scale is about 1.24 m/s² per count.
    private static final float STILL_GYRO = 40f; // degrees per second

    static final class Position {
        final float x, y, z;

        Position(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Position add(Position other) {
            return new Position(x + other.x, y + other.y, z + other.z);
        }

        Position scale(float factor) {
            return new Position(x * factor, y * factor, z * factor);
        }

        float length() {
            return (float) Math.sqrt(x*x + y*y + z*z);
        }
    }

    private static final Position ZERO = new Position(0f, 0f, 0f);
    private long started, last, lastMotion, lastAcceleration;
    private float yaw;
    private Rotation orientation;
    private Position origin, gravity, gyroBias, acceleration, velocity, displacement;

    boolean running() {
        return started != 0;
    }

    void reset() {
        started = 0;
    }

    float yaw() {
        return yaw;
    }

    boolean start(long ns, Cv10ControllerReport report, Cv10SimulationCalibration.Orientation head, float height) {
        Position restingGyro = gyro(report);
        if (head == null || Math.abs(head.ns - ns) > MAX_GAP_NS || !head.level() ||
                !report.orientationValid || restingGyro.length() > STILL_GYRO) return false;
        Cv10SimulationCalibration.Orientation hand =
                new Cv10SimulationCalibration.Orientation(ns, report.x, report.y, report.z, report.w);
        if (!hand.level()) return false;
        yaw = (float) Math.IEEEremainder(head.yaw() - hand.yaw(), 2 * Math.PI);
        orientation = Rotation.yaw(yaw).multiply(Rotation.from(report));
        gravity = orientation.rotate(specificForce(report));
        if (Math.abs(gravity.y) < 0.75f * GRAVITY || gravity.length() > 1.25f * GRAVITY ||
                Math.hypot(gravity.x, gravity.z) > 0.35f * GRAVITY) return false;
        origin = new Position(CONTACT_RIGHT_M * (float) Math.cos(head.yaw()), height,
                -CONTACT_RIGHT_M * (float) Math.sin(head.yaw()));
        gyroBias = restingGyro;
        acceleration = velocity = displacement = ZERO;
        started = last = lastMotion = lastAcceleration = ns;
        return true;
    }

    boolean sample(long ns, Cv10ControllerReport report) {
        if (!running() || ns <= last || ns - last > MAX_GAP_NS ||
                ns - started > DURATION_SECONDS * 1_000_000_000L || !report.orientationValid) return false;
        float dt = seconds(ns - last);
        Position omega = gyro(report).add(gyroBias.scale(-1f));
        Rotation predicted = orientation.multiply(Rotation.step(omega, dt));
        Rotation observed = Rotation.yaw(yaw).multiply(Rotation.from(report));
        orientation = predicted.blend(observed, 1f - (float) Math.exp(-dt / 0.18f));
        Position next = orientation.rotate(specificForce(report)).add(gravity.scale(-1f));
        if (next.length() >= ACCEL_NOISE) {
            lastAcceleration = lastMotion = ns;
        } else {
            next = ZERO;
            if (omega.length() > STILL_GYRO) lastMotion = ns;
        }
        Position nextVelocity = velocity.add(acceleration.add(next).scale(0.5f * dt));
        displacement = displacement.add(velocity.add(nextVelocity).scale(0.5f * dt));
        velocity = nextVelocity;
        acceleration = next;
        last = ns;
        return true;
    }

    Position finish() {
        if (!running() || last - lastMotion < REST_NS || lastAcceleration == started) return null;
        // Remove velocity remaining after the move. The controller starts and ends at rest.
        float correctionTime = seconds(last - lastAcceleration) + 0.5f * seconds(lastAcceleration - started);
        Position moved = displacement.add(velocity.scale(-correctionTime));
        float distance = moved.length();
        return distance >= 0.015f && distance <= 1.5f ? origin.add(moved) : null;
    }

    private static float seconds(long durationNs) {
        return durationNs * 1e-9f;
    }

    private static Position specificForce(Cv10ControllerReport report) {
        // Match the 180-degree Y basis change in the decoded report quaternion.
        return new Position(-report.accelX * GRAVITY, report.accelY * GRAVITY, -report.accelZ * GRAVITY);
    }

    private static Position gyro(Cv10ControllerReport report) {
        return new Position(-report.gyroX, report.gyroY, -report.gyroZ);
    }

    private static final class Rotation {
        final float x, y, z, w;

        Rotation(float x, float y, float z, float w) {
            float inverseNorm = 1f / (float) Math.sqrt(x*x + y*y + z*z + w*w);
            this.x = x * inverseNorm;
            this.y = y * inverseNorm;
            this.z = z * inverseNorm;
            this.w = w * inverseNorm;
        }

        static Rotation from(Cv10ControllerReport report) {
            return new Rotation(report.x, report.y, report.z, report.w);
        }

        static Rotation yaw(float radians) {
            return new Rotation(0f, (float) Math.sin(radians / 2f), 0f, (float) Math.cos(radians / 2f));
        }

        static Rotation step(Position degreesPerSecond, float dt) {
            float speed = degreesPerSecond.length();
            if (speed == 0f) return new Rotation(0f, 0f, 0f, 1f);
            float angle = (float) Math.toRadians(speed) * dt / 2f;
            float scale = (float) (Math.sin(angle) / speed);
            return new Rotation(degreesPerSecond.x * scale, degreesPerSecond.y * scale, degreesPerSecond.z * scale,
                    (float) Math.cos(angle));
        }

        Rotation multiply(Rotation right) {
            return new Rotation(w*right.x + x*right.w + y*right.z - z*right.y,
                    w*right.y - x*right.z + y*right.w + z*right.x,
                    w*right.z + x*right.y - y*right.x + z*right.w,
                    w*right.w - x*right.x - y*right.y - z*right.z);
        }

        Rotation blend(Rotation measured, float weight) {
            float dot = x*measured.x + y*measured.y + z*measured.z + w*measured.w;
            float sign = dot < 0f ? -weight : weight;
            return new Rotation((1f - weight)*x + sign*measured.x, (1f - weight)*y + sign*measured.y,
                    (1f - weight)*z + sign*measured.z, (1f - weight)*w + sign*measured.w);
        }

        Position rotate(Position v) {
            float tx = 2f * (y*v.z - z*v.y);
            float ty = 2f * (z*v.x - x*v.z);
            float tz = 2f * (x*v.y - y*v.x);
            return new Position(v.x + w*tx + y*tz - z*ty, v.y + w*ty + z*tx - x*tz,
                    v.z + w*tz + x*ty - y*tx);
        }
    }
}
