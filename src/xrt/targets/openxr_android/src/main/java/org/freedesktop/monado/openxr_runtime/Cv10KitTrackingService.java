// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraAccessException;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicReference;

/** User-started camera access for the runtime's optional locator tracking. */
public final class Cv10KitTrackingService extends Service implements Cv10KitCamera.Frames {
    private static final String CHANNEL = "cv10_locator_tracking";
    private static final String STOP = "cv10.locator.STOP";
    static final String EXTRA_HANDS = "cv10.locator.EXTRA_HANDS";
    private static final int NOTIFICATION = 610;
    private static final long STALE_NS = 250_000_000L;
    private static final long INPUT_TIMEOUT_NS = 5_000_000_000L;

    private static volatile Thread trackingWorker;
    private static volatile String status = "OpenXR locator tracking is stopped.";
    private static volatile TimedPose latest;
    private static volatile boolean trackingHands;
    private static volatile HandInput leftInput, rightInput;
    private static volatile TimedPose leftPose, rightPose;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<Cv10KitReport.StereoFrame> stereo = new AtomicReference<>();
    private Cv10KitCamera camera;
    private Thread worker;
    private volatile String cameraFailure;

    private static final class TimedPose {
        final long timeNs;
        final Cv10KitReport.HmdPose pose;

        TimedPose(long timeNs, Cv10KitReport.HmdPose pose) {
            this.timeNs = timeNs;
            this.pose = pose;
        }
    }

    private static final class HandInput {
        final long arrivalNs;
        final Cv10KitReport.Hand data;

        HandInput(long arrivalNs, Cv10KitReport.Hand data) {
            this.arrivalNs = arrivalNs;
            this.data = data;
        }
    }

    static boolean available(Context context) {
        try {
            context.getPackageManager().getServiceInfo(new ComponentName(context, Cv10KitTrackingService.class), 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    // The OpenXR service and this service both use the application process.
    static boolean trackingRequested() {
        Thread job = trackingWorker;
        return job != null && !job.isInterrupted();
    }

    static boolean busy() { return trackingWorker != null; }

    static boolean handsRequested() { return trackingHands && trackingRequested(); }

    static String status() { return status; }

    private static boolean fresh(TimedPose sample, long now) {
        return sample != null && sample.timeNs <= now && now - sample.timeNs <= STALE_NS;
    }

    static long readPose(float[] out) {
        TimedPose sample = latest;
        if (!trackingRequested() || !fresh(sample, System.nanoTime())) return 0;
        Cv10KitReport.HmdPose pose = sample.pose;
        out[0] = pose.x;
        out[1] = pose.y;
        out[2] = pose.z;
        out[3] = pose.qx;
        out[4] = pose.qy;
        out[5] = pose.qz;
        out[6] = pose.qw;
        return sample.timeNs;
    }

    static long readHand(boolean right, float[] out, long[] poseTimestamp) {
        HandInput input = right ? rightInput : leftInput;
        long now = System.nanoTime();
        if (!handsRequested() || input == null || now - input.arrivalNs > STALE_NS) return 0;
        TimedPose sample = right ? rightPose : leftPose;
        boolean tracked = fresh(sample, now);
        out[4] = tracked ? 1 : 0;
        poseTimestamp[0] = tracked ? sample.timeNs : 0;
        out[5] = (input.data.buttons & Cv10KitReport.Hand.CONFIRM) != 0 || input.data.trigger > 0 ? 1 : 0;
        out[6] = (input.data.buttons & Cv10KitReport.Hand.BACK) != 0 ? 1 : 0;
        if (tracked) {
            Cv10KitReport.HmdPose pose = sample.pose;
            out[0] = pose.qx;
            out[1] = pose.qy;
            out[2] = pose.qz;
            out[3] = pose.qw;
            out[7] = pose.x;
            out[8] = pose.y;
            out[9] = pose.z;
        }
        return input.arrivalNs;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (worker != null) return START_NOT_STICKY;
        try {
            showNotification();
            if (trackingWorker != null) throw new IOException("Wait for the previous locator session to close.");
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            UsbManager usb = getSystemService(UsbManager.class);
            if (device == null || usb == null || device.getVendorId() != Cv10KitReport.VENDOR_ID ||
                    device.getProductId() != Cv10KitReport.PRODUCT_ID || !usb.hasPermission(device)) {
                throw new IOException("Android USB permission is required for the locator.");
            }
            status = "Starting OpenXR locator tracking…";
            boolean hands = intent.getBooleanExtra(EXTRA_HANDS, false);
            trackingHands = hands;
            worker = new Thread(() -> runTracking(usb, device, hands), "CV10 locator tracking");
            trackingWorker = worker;
            worker.start();
        } catch (IOException | RuntimeException failure) {
            status = "OpenXR locator tracking failed: " + failure.getMessage();
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void showNotification() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "CV10 6DoF locator",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, Cv10KitTrackingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("CV10 6DoF locator")
                .setContentText("OpenXR head tracking is using the external camera.")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIFICATION, notification);
        }
    }

    private void runTracking(UsbManager usb, UsbDevice device, boolean useHands) {
        Thread job = Thread.currentThread();
        try (Cv10KitHid hid = Cv10KitHid.open(usb, device)) {
            Cv10KitCalibration.Calibration locator = Cv10KitCalibration.read(hid, Cv10KitCalibration.Component.LOCATOR);
            if (locator == null) throw new IOException("The locator did not supply SLAM calibration.");
            Cv10KitCalibration.CameraCalibration[] cameras = Cv10KitCalibration.locatorCameras(locator.data);
            Cv10KitCalibration.ImuCalibration imu = Cv10KitCalibration.locatorImu(locator.data);
            byte[] parameters = Cv10KitSlamParameters.encode(cameras, imu);
            byte[] handsParameters = null;
            if (useHands) {
                Cv10KitCalibration.HandCalibration left = readHandCalibration(hid, Cv10KitCalibration.Component.LEFT);
                Cv10KitCalibration.HandCalibration right = readHandCalibration(hid, Cv10KitCalibration.Component.RIGHT);
                handsParameters = Cv10KitHandParameters.encode(Cv10KitHands.template(this), parameters,
                        cameras[0], imu, left, right);
            }
            try (Cv10KitSlam slam = new Cv10KitSlam(Cv10KitSlam.module(this), parameters)) {
                try (Cv10KitHands hands = useHands ? new Cv10KitHands(this, handsParameters) : null) {
                    status = hands == null ? "Starting external Y8 camera for OpenXR head tracking…"
                            : "Hand tracker loaded. Starting external Y8 camera…";
                    main.post(() -> startCamera(job));
                    track(hid, slam, hands);
                }
            }
        } catch (InterruptedException stopped) {
            job.interrupt();
        } catch (IOException | RuntimeException failure) {
            if (!job.isInterrupted()) status = "OpenXR locator tracking failed: " + failure.getMessage();
        } finally {
            latest = null;
            clearHands();
            main.post(() -> {
                if (trackingWorker == job) {
                    trackingHands = false;
                    trackingWorker = null;
                }
                if (worker == job) {
                    worker = null;
                    closeCamera();
                    stopSelf();
                }
            });
        }
    }

    private static Cv10KitCalibration.HandCalibration readHandCalibration(Cv10KitHid hid,
            Cv10KitCalibration.Component side) throws IOException, InterruptedException {
        Cv10KitCalibration.Calibration data = Cv10KitCalibration.read(hid, side);
        if (data == null) throw new IOException("Calibration unavailable for the " + side.filename + " controller.");
        return Cv10KitCalibration.hand(data.data);
    }

    private void track(Cv10KitHid hid, Cv10KitSlam slam, Cv10KitHands hands) throws IOException {
        long lastHidArrival = System.nanoTime(), lastStereoArrival = lastHidArrival;
        long lastImu = 0, lastStereo = 0, offset = 0, lastPoseTime = 0;
        long handFrameTime = 0;
        byte[] poseBuffer = new byte[Cv10KitReport.HeadPose.BYTES];
        ByteBuffer handOutput = hands == null ? null
                : ByteBuffer.allocate(2 * Cv10KitReport.HAND_POSE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        Cv10KitReport.StereoFrame frame = null;
        Cv10KitReport.StereoFrame handPending = null;
        while (!Thread.currentThread().isInterrupted()) {
            if (cameraFailure != null) throw new IOException(cameraFailure);
            byte[] report = hid.read(50);
            long arrival = System.nanoTime();
            if (hands != null && report != null) {
                leftInput = updateHand(hands, report, false, arrival, leftInput);
                rightInput = updateHand(hands, report, true, arrival, rightInput);
            }
            Cv10KitReport.Head head = report == null ? null : Cv10KitReport.head(report);
            if (head != null && head.deviceNs > lastImu) {
                long observed = arrival - head.deviceNs;
                offset = lastImu == 0 ? observed : Math.min(observed, offset + (head.deviceNs - lastImu) / 1000);
                slam.push(head);
                lastImu = head.deviceNs;
                lastHidArrival = arrival;
            } else if (head != null && lastImu - head.deviceNs > STALE_NS) {
                throw new IOException("Locator IMU clock restarted. Start locator tracking again.");
            }
            if (arrival - lastHidArrival > INPUT_TIMEOUT_NS || arrival - lastStereoArrival > INPUT_TIMEOUT_NS) {
                throw new IOException("Timed out waiting for synchronized locator HID and external Y8 frames.");
            }
            if (frame == null) frame = stereo.getAndSet(null);
            if (frame == null || frame.frame.deviceNs > lastImu) continue;
            if (frame.frame.deviceNs > lastStereo) {
                slam.push(frame);
                lastStereo = frame.frame.deviceNs;
                lastStereoArrival = arrival;
                if (hands != null) {
                    if (handFrameTime != 0 && readHandFrame(hands, handOutput, handFrameTime, offset)) {
                        handFrameTime = 0;
                    }
                    // The head worker computes asynchronously. Use the previous image at its own timestamp.
                    if (handPending != null && hands.push(handPending)) handFrameTime = handPending.frame.deviceNs;
                    handPending = frame;
                }
                Cv10KitReport.HeadPose headPose = slam.readPose(poseBuffer);
                if (headPose != null) {
                    long mapped = (long) (headPose.deviceSeconds * 1e9) + offset;
                    if (mapped > lastPoseTime && mapped <= arrival && arrival - mapped <= STALE_NS) {
                        latest = new TimedPose(mapped, headPose.toHmd());
                        lastPoseTime = mapped;
                    }
                }
                status = fresh(latest, arrival)
                        ? "OpenXR locator tracking has a head pose." : "Receiving HID and Y8 · waiting for a head pose.";
                if (hands != null) status += "\nOptical hands · left " + (fresh(leftPose, arrival) ? "tracked" : "waiting")
                        + " · right " + (fresh(rightPose, arrival) ? "tracked" : "waiting");
            }
            frame = null;
        }
    }

    private static HandInput updateHand(Cv10KitHands hands, byte[] report, boolean right, long arrival,
            HandInput previous) throws IOException {
        Cv10KitReport.Hand current = Cv10KitReport.hand(report, right);
        if (current == null || previous != null && current.deviceNs == previous.data.deviceNs) return previous;
        // The u32 clock can roll over or reset if a controller reconnects.
        if (previous == null || current.deviceNs > previous.data.deviceNs || arrival - previous.arrivalNs > STALE_NS) {
            hands.push(current, right);
            return new HandInput(arrival, current);
        }
        return previous;
    }

    private static boolean readHandFrame(Cv10KitHands hands, ByteBuffer output, long deviceNs, long offset) {
        if (hands.poses(deviceNs, output.array()) != 0) return false;
        for (int entry = 0; entry < 2; entry++) {
            int at = entry * Cv10KitReport.HAND_POSE_BYTES;
            boolean right = output.get(at) == '1';
            if (!right && output.get(at) != '0') continue;
            Cv10KitReport.HmdPose pose = Cv10KitReport.handPose(output, at, right);
            TimedPose sample = pose == null ? null : new TimedPose(deviceNs + offset, pose);
            if (right) rightPose = sample;
            else leftPose = sample;
        }
        return true;
    }

    private static void clearHands() {
        leftInput = null;
        rightInput = null;
        leftPose = null;
        rightPose = null;
    }

    private void startCamera(Thread job) {
        if (worker != job || job.isInterrupted()) return;
        camera = new Cv10KitCamera(main, this);
        try {
            camera.open(this);
        } catch (CameraAccessException | RuntimeException failure) {
            onFailure(failure.getMessage());
        }
    }

    @Override
    public void onImage(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        Cv10KitReport.StereoFrame frame =
                Cv10KitReport.stereo(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride());
        if (frame != null) stereo.set(frame);
    }

    @Override
    public void onFailure(String message) { cameraFailure = message == null ? "External Y8 camera failed." : message; }

    private void closeCamera() {
        if (camera != null) camera.close();
        camera = null;
        stereo.set(null);
    }

    @Override
    public void onDestroy() {
        latest = null;
        clearHands();
        if (worker != null) {
            worker.interrupt();
            status = "OpenXR locator tracking is stopped.";
        }
        closeCamera();
        super.onDestroy();
    }
}
