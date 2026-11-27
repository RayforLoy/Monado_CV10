// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Foreground diagnostics for the original USB 6DoF locator. */
public final class Cv10KitActivity extends Activity {
    private static final int USB_CAMERA_PERMISSION = 1;
    private static final int CAMERA_PREVIEW_PERMISSION = 2;
    private static final int IMPORT_SLAM_MODULE = 3;
    private static final int REPLAY_SLAM_PARAMETERS = 4;
    private static final int IMPORT_HAND_SDK = 5;
    private static final int IMPORT_HAND_CONFIG = 6;
    private static final long SAMPLE_MS = 5000;
    private static final long HEAD_SLAM_SAMPLE_MS = 8000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private UsbManager usb;
    private TextView inventory, hidStatus, cameraStatus, slamStatus;
    private TextView trackingStatus;
    private String permissionAction;
    private UsbDevice waitingForUsb;
    private enum HidAction { SAMPLE, CALIBRATION, HEAD_SLAM, OPENXR, OPENXR_KIT }
    private HidAction requestedHid;
    private Thread hidWorker;
    private Cv10KitCamera camera;
    private int cameraFrames, metadataFrames;
    private Cv10KitReport.StereoFrame firstStereo;
    private long firstImageTimestampNs, firstCallbackNs;
    private Thread cameraSave;
    private Thread slamWorker;
    private HeadSample headSample;
    private final Runnable endCamera = this::finishCameraSample;
    private final Runnable refreshTracking = new Runnable() {
        @Override
        public void run() {
            trackingStatus.setText(Cv10KitTrackingService.status());
            main.postDelayed(this, 500);
        }
    };

    private static final class HeadSample {
        final AtomicReference<Cv10KitReport.StereoFrame> frame = new AtomicReference<>();
        volatile String cameraFailure;
    }

    private final BroadcastReceiver permission = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice selected = waitingForUsb;
            if (!permissionAction.equals(intent.getAction()) || selected == null) return;
            waitingForUsb = null;
            if (usb.hasPermission(selected)) {
                beginHid(selected, requestedHid);
            } else {
                hidStatus.setText("Android USB permission was not granted.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("Huawei 6DoF Kit · 定位交互套件");
        usb = getSystemService(UsbManager.class);
        permissionAction = getPackageName() + ".KIT_USB_PERMISSION";
        IntentFilter filter = new IntentFilter(permissionAction);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permission, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permission, filter);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        addButton(content, "Refresh USB and camera devices · 刷新 USB 与相机", this::refresh);
        inventory = addOutput(content);
        addButton(content, "Sample locator HID · 采样定位器 HID", () -> requestHid(HidAction.SAMPLE));
        addButton(content, "Read kit calibration · 读取套件校准", () -> requestHid(HidAction.CALIBRATION));
        addButton(content, "Sample live head SLAM · 实时采样头显 SLAM", () -> requestHid(HidAction.HEAD_SLAM));
        hidStatus = addOutput(content);
        addButton(content, "Sample OV580 camera · 采样 OV580 相机", this::requestCamera);
        cameraStatus = addOutput(content);
        addButton(content, "Import Huawei SLAM module · 导入原厂头显 SLAM 模块",
                () -> selectSlamFile(IMPORT_SLAM_MODULE));
        addButton(content, "Replay head SLAM · 离线回放头显 SLAM",
                () -> selectSlamFile(REPLAY_SLAM_PARAMETERS));
        addButton(content, "Import Huawei hand tracking SDK · 导入原厂 6DoF 手柄模块",
                () -> selectSlamFile(IMPORT_HAND_SDK));
        addButton(content, "Import the hand replay configuration · 导入双手回放配置",
                () -> selectSlamFile(IMPORT_HAND_CONFIG));
        addButton(content, "Replay both 6DoF controllers · 离线回放左右 6DoF 手柄", this::replayHands);
        slamStatus = addOutput(content);
        if (Cv10KitTrackingService.available(this)) {
            addButton(content, "Start OpenXR head tracking · 启用 OpenXR 头显定位", () -> requestHid(HidAction.OPENXR));
            addButton(content, "Start OpenXR 6DoF kit · 启用 OpenXR 双手与头显定位",
                    () -> requestHid(HidAction.OPENXR_KIT));
            addButton(content, "Stop OpenXR head tracking · 停止 OpenXR 头显定位",
                    () -> stopService(new Intent(this, Cv10KitTrackingService.class)));
            trackingStatus = addOutput(content);
        }
        if (Cv10KitSlam.module(this).isFile()) slamStatus.setText("Huawei SLAM module imported.");
        refresh();
    }

    private void addButton(LinearLayout layout, String label, Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> onClick.run());
        layout.addView(button);
    }

    private TextView addOutput(LinearLayout layout) {
        TextView view = new TextView(this);
        view.setTextIsSelectable(true);
        layout.addView(view);
        return view;
    }

    private UsbDevice findKit() {
        UsbDevice found = null;
        if (usb != null) {
            for (UsbDevice candidate : usb.getDeviceList().values()) {
                if (candidate.getVendorId() != Cv10KitReport.VENDOR_ID ||
                        candidate.getProductId() != Cv10KitReport.PRODUCT_ID) continue;
                if (found != null) return null;
                found = candidate;
            }
        }
        return found;
    }

    private void refresh() {
        UsbDevice kit = findKit();
        if (kit == null) {
            inventory.setText("Connect one Huawei locator (12D1:3A09) to inspect USB and its camera.");
            return;
        }
        StringBuilder details = new StringBuilder("Huawei locator 12D1:3A09");
        for (int i = 0; i < kit.getInterfaceCount(); ++i) {
            UsbInterface iface = kit.getInterface(i);
            details.append(String.format(Locale.ROOT, "\nIF %d alt %d class %02x", iface.getId(),
                    iface.getAlternateSetting(), iface.getInterfaceClass()));
            for (int j = 0; j < iface.getEndpointCount(); ++j) {
                UsbEndpoint endpoint = iface.getEndpoint(j);
                details.append(String.format(Locale.ROOT, " EP %02x/%d", endpoint.getAddress(),
                        endpoint.getMaxPacketSize()));
            }
        }
        try {
            String cameraId = Cv10KitCamera.find(this);
            details.append(cameraId == null ? "\nCamera2: no unique external 1280×481 Y8 camera."
                    : "\nCamera2: external 1280×481 Y8 camera " + cameraId);
        } catch (CameraAccessException | RuntimeException failure) {
            details.append("\nCamera2: ").append(failure.getMessage());
        }
        inventory.setText(details);
    }

    private void requestHid(HidAction operation) {
        if (hidWorker != null || waitingForUsb != null) return;
        if (Cv10KitTrackingService.busy()) {
            hidStatus.setText("OpenXR locator tracking is using the HID interface.");
            return;
        }
        if (operation == HidAction.OPENXR_KIT && !Cv10KitHands.configured(this)) {
            hidStatus.setText("Import the Huawei hand tracking SDK and replay configuration first.");
            return;
        }
        if (operation == HidAction.HEAD_SLAM || operation == HidAction.OPENXR || operation == HidAction.OPENXR_KIT) {
            if (camera != null || cameraSave != null || slamWorker != null) return;
            if (!Cv10KitSlam.module(this).isFile()) {
                hidStatus.setText("Import the original ARM64 libarvr_slam.so first.");
                return;
            }
        }
        UsbDevice kit = findKit();
        if (kit == null) {
            refresh();
            return;
        }
        requestedHid = operation;
        boolean hasVideo = kit.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO;
        for (int i = 0; i < kit.getInterfaceCount(); ++i) {
            hasVideo |= kit.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO;
        }
        if ((operation == HidAction.HEAD_SLAM || operation == HidAction.OPENXR || operation == HidAction.OPENXR_KIT ||
                hasVideo && Build.VERSION.SDK_INT >= 28) &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, USB_CAMERA_PERMISSION);
            return;
        }
        if (usb.hasPermission(kit)) {
            beginHid(kit, operation);
            return;
        }
        waitingForUsb = kit;
        try {
            PendingIntent result = PendingIntent.getBroadcast(this, 0,
                    new Intent(permissionAction).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
            usb.requestPermission(kit, result);
            hidStatus.setText("Waiting for Android USB permission…");
        } catch (RuntimeException failure) {
            waitingForUsb = null;
            hidStatus.setText("USB permission request failed: " + failure.getMessage());
        }
    }

    private void beginHid(UsbDevice device, HidAction operation) {
        if (hidWorker != null) return;
        if (operation == HidAction.OPENXR || operation == HidAction.OPENXR_KIT) {
            try {
                startForegroundService(new Intent(this, Cv10KitTrackingService.class)
                        .putExtra(UsbManager.EXTRA_DEVICE, device)
                        .putExtra(Cv10KitTrackingService.EXTRA_HANDS, operation == HidAction.OPENXR_KIT));
                hidStatus.setText("");
            } catch (RuntimeException failure) {
                hidStatus.setText("Could not start OpenXR locator tracking: " + failure.getMessage());
            }
            return;
        }
        switch (operation) {
            case CALIBRATION: hidStatus.setText("Reading locator and controller calibration…"); break;
            case HEAD_SLAM: hidStatus.setText("Reading locator calibration for head SLAM…"); break;
            case SAMPLE: hidStatus.setText("Sampling interface 2 interrupt IN for 5 seconds…"); break;
        }
        hidWorker = new Thread(() -> {
            Thread job = Thread.currentThread();
            String result;
            try {
                result = runHid(device, operation);
            } catch (InterruptedException stopped) {
                job.interrupt();
                result = "HID sampling stopped.";
            } catch (IOException | RuntimeException failure) {
                result = "Locator read failed: " + failure.getMessage();
            }
            String message = result;
            main.post(() -> {
                if (hidWorker != job) return;
                hidWorker = null;
                if (!job.isInterrupted()) hidStatus.setText(message);
            });
        }, "CV10 locator HID");
        hidWorker.start();
    }

    private String runHid(UsbDevice device, HidAction operation) throws IOException, InterruptedException {
        try (Cv10KitHid hid = Cv10KitHid.open(usb, device)) {
            if (operation == HidAction.CALIBRATION) return readCalibration(hid);
            if (operation == HidAction.HEAD_SLAM) return sampleHeadSlam(hid);
            return collectReports(hid, hid.endpointAddress);
        }
    }

    private String readCalibration(Cv10KitHid hid) throws InterruptedException {
        File parent = getExternalFilesDir("kit-calibration");
        if (parent == null) parent = getFilesDir();
        File destination = new File(parent, "kit-" + System.currentTimeMillis());
        StringBuilder result = new StringBuilder();
        for (Cv10KitCalibration.Component component : Cv10KitCalibration.Component.values()) {
            result.append(component.filename).append(": ");
            try {
                Cv10KitCalibration.Calibration data = Cv10KitCalibration.read(hid, component);
                if (data == null) {
                    result.append("no calibration reply\n");
                    continue;
                }
                if (!destination.isDirectory() && !destination.mkdirs()) {
                    throw new IOException("Could not create calibration directory");
                }
                result.append(data.data.length).append(" bytes · ").append(data.protocol()).append('\n');
                byte[] slamParameters = null;
                try {
                    if (component == Cv10KitCalibration.Component.LOCATOR) {
                        Cv10KitCalibration.CameraCalibration[] cameras = Cv10KitCalibration.locatorCameras(data.data);
                        for (int i = 0; i < cameras.length; i++) {
                            Cv10KitCalibration.CameraCalibration cam = cameras[i];
                            result.append(String.format(Locale.ROOT,
                                    "Camera %d: %.0f Hz · f=(%.2f, %.2f) · c=(%.2f, %.2f) · IMU delay %.2f ms\n",
                                    i, cam.framesPerSecond, cam.intrinsics[0], cam.intrinsics[1],
                                    cam.intrinsics[2], cam.intrinsics[3], cam.cameraToImuDelaySeconds * 1000));
                        }
                        Cv10KitCalibration.ImuCalibration imu = Cv10KitCalibration.locatorImu(data.data);
                        appendCalibratedImu(result, imu);
                        slamParameters = Cv10KitSlamParameters.encode(cameras, imu);
                    } else {
                        Cv10KitCalibration.HandCalibration hand = Cv10KitCalibration.hand(data.data);
                        result.append(String.format(Locale.ROOT, "%d LEDs · camera to IMU delay %.2f ms\n",
                                hand.ledPositions.length, hand.cameraToImuDelaySeconds * 1000));
                        appendCalibratedImu(result, hand.imu);
                    }
                } finally {
                    saveCalibration(destination, component, data, slamParameters);
                }
                if (slamParameters != null) result.append("Head SLAM parameters: slam-params.bin\n");
            } catch (IOException failure) {
                result.append(failure.getMessage()).append('\n');
            }
        }
        if (destination.isDirectory()) result.append(destination.getAbsolutePath());
        return result.toString();
    }

    private static void appendCalibratedImu(StringBuilder result, Cv10KitCalibration.ImuCalibration imu) {
        result.append(String.format(Locale.ROOT, "IMU gyro %.0f Hz · accelerometer %.0f Hz\n", imu.gyroHz, imu.accelHz));
    }

    private static void saveCalibration(File destination, Cv10KitCalibration.Component component,
            Cv10KitCalibration.Calibration data, byte[] slamParameters) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zipEntry(zip, "calibration.bin", data.data);
            zipEntry(zip, "device.hash", data.deviceHash);
            if (slamParameters != null) zipEntry(zip, "slam-params.bin", slamParameters);
        }
        writeArchive(new File(destination, component.filename + ".zip"), archive);
    }

    private static void zipEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void writeArchive(File destination, ByteArrayOutputStream archive) throws IOException {
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream output = file.startWrite();
        try {
            archive.writeTo(output);
            file.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(output);
            throw failure;
        }
    }

    private String collectReports(Cv10KitHid hid, int endpointAddress) throws IOException {
        byte[] last = new byte[0];
        Cv10KitReport.Head head = null;
        Cv10KitReport.Hand left = null, right = null;
        int count = 0, headCount = 0, leftCount = 0, rightCount = 0;
        long end = SystemClock.elapsedRealtime() + SAMPLE_MS;
        while (SystemClock.elapsedRealtime() < end && !Thread.currentThread().isInterrupted()) {
            byte[] report = hid.read(200);
            if (report == null || report.length == 0) continue;
            last = report;
            count++;
            Cv10KitReport.Head currentHead = Cv10KitReport.head(last);
            Cv10KitReport.Hand currentLeft = Cv10KitReport.hand(last, false);
            Cv10KitReport.Hand currentRight = Cv10KitReport.hand(last, true);
            if (currentHead != null) { head = currentHead; headCount++; }
            if (currentLeft != null) { left = currentLeft; leftCount++; }
            if (currentRight != null) { right = currentRight; rightCount++; }
        }
        StringBuilder result = new StringBuilder(String.format(Locale.ROOT,
                "EP %02X · USB reports %d\nOV580 records: head %d · left %d · right %d",
                endpointAddress, count, headCount, leftCount, rightCount));
        if (head != null) {
            result.append(String.format(Locale.ROOT, "\nHead %d ns · accel %.3f %.3f %.3f · gyro %.3f %.3f %.3f",
                    head.deviceNs, head.ax, head.ay, head.az, head.gx, head.gy, head.gz));
        }
        appendHand(result, "Left", left);
        appendHand(result, "Right", right);
        if (last.length > 0) {
            result.append("\nLast USB report (").append(last.length).append(" bytes)\n");
            for (int i = 0; i < Math.min(last.length, Cv10KitReport.HID_REPORT_SIZE); ++i) {
                result.append(String.format(Locale.ROOT, "%02X ", last[i] & 0xff));
            }
        }
        return result.toString();
    }

    private String sampleHeadSlam(Cv10KitHid hid) throws IOException, InterruptedException {
        Cv10KitCalibration.Calibration locator = Cv10KitCalibration.read(hid, Cv10KitCalibration.Component.LOCATOR);
        if (locator == null) return "The locator did not supply SLAM calibration.";
        byte[] parameters = Cv10KitSlamParameters.encode(
                Cv10KitCalibration.locatorCameras(locator.data), Cv10KitCalibration.locatorImu(locator.data));
        HeadSample sample = new HeadSample();
        Thread job = Thread.currentThread();
        try (Cv10KitSlam slam = new Cv10KitSlam(Cv10KitSlam.module(this), parameters)) {
            main.post(() -> {
                if (job.isInterrupted() || isFinishing() || isDestroyed()) return;
                if (camera != null || cameraSave != null) {
                    sample.cameraFailure = "The camera is being sampled.";
                    return;
                }
                hidStatus.setText("Sampling head IMU and the external stereo stream…");
                headSample = sample;
                openCamera();
            });
            long end = SystemClock.elapsedRealtime() + HEAD_SLAM_SAMPLE_MS;
            long lastImu = 0, lastStereo = 0;
            int imus = 0, frames = 0;
            Cv10KitReport.StereoFrame stereo = null;
            while (SystemClock.elapsedRealtime() < end && !job.isInterrupted() && sample.cameraFailure == null) {
                byte[] report = hid.read(50);
                Cv10KitReport.Head head = report == null ? null : Cv10KitReport.head(report);
                if (head != null && head.deviceNs > lastImu) {
                    slam.push(head);
                    lastImu = head.deviceNs;
                    imus++;
                }
                if (stereo == null) stereo = sample.frame.getAndSet(null);
                // Feed through the camera timestamp only after that instant's IMU reports have arrived.
                if (stereo == null || stereo.frame.deviceNs > lastImu) continue;
                if (stereo.frame.deviceNs > lastStereo) {
                    slam.push(stereo);
                    lastStereo = stereo.frame.deviceNs;
                    frames++;
                }
                stereo = null;
            }
            if (job.isInterrupted()) throw new InterruptedException();
            String message = "Head IMU reports " + imus + " · stereo pairs " + frames;
            if (sample.cameraFailure != null) return message + "\n" + sample.cameraFailure;
            return message + "\n" + slam.poseStatus();
        } finally {
            main.post(() -> {
                if (headSample == sample) {
                    closeCamera();
                    cameraStatus.setText("Live head SLAM camera sample ended.");
                }
            });
        }
    }

    private static void appendHand(StringBuilder text, String label, Cv10KitReport.Hand hand) {
        if (hand == null) return;
        text.append(String.format(Locale.ROOT,
                "\n%s %d ns (%d ticks, multiplier %d) · buttons %02X · touch %02X/%d · battery %d" +
                "\nStick %.3f %.3f · trigger %.0f · grip %.0f\nAccel %.3f %.3f %.3f · gyro %.3f %.3f %.3f",
                label, hand.deviceNs, hand.clockTicks, hand.clockMultiplier, hand.buttons, hand.touches, hand.gripTouch,
                hand.batteryField, hand.stickX, hand.stickY, hand.trigger, hand.grip,
                hand.ax, hand.ay, hand.az, hand.gx, hand.gy, hand.gz));
    }

    private void requestCamera() {
        if (camera != null || cameraSave != null) return;
        if (Cv10KitTrackingService.busy()) {
            cameraStatus.setText("OpenXR locator tracking is using the camera.");
            return;
        }
        if (findKit() == null) {
            refresh();
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PREVIEW_PERMISSION);
            return;
        }
        openCamera();
    }

    private void openCamera() {
        try {
            camera = new Cv10KitCamera(main, new Cv10KitCamera.Frames() {
                @Override
                public void onImage(Image image) { readCameraFrame(image); }

                @Override
                public void onFailure(String message) { failCamera(message); }
            });
            cameraFrames = 0;
            metadataFrames = 0;
            camera.open(this);
            if (headSample == null) main.postDelayed(endCamera, SAMPLE_MS);
            cameraStatus.setText(headSample == null ? "Sampling the external Y8 camera for 5 seconds…"
                    : "Streaming external Y8 frames into head SLAM…");
        } catch (CameraAccessException | RuntimeException failure) {
            failCamera("Camera open failed: " + failure.getMessage());
        }
    }

    private void readCameraFrame(Image frame) {
        cameraFrames++;
        Image.Plane plane = frame.getPlanes()[0];
        Cv10KitReport.Frame decoded;
        if (headSample != null) {
            Cv10KitReport.StereoFrame stereo =
                    Cv10KitReport.stereo(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride());
            if (stereo == null) return;
            headSample.frame.set(stereo);
            decoded = stereo.frame;
        } else if (firstStereo == null) {
            long callbackNs = SystemClock.elapsedRealtimeNanos();
            firstStereo = Cv10KitReport.stereo(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride());
            if (firstStereo == null) return;
            firstImageTimestampNs = frame.getTimestamp();
            firstCallbackNs = callbackNs;
            decoded = firstStereo.frame;
        } else {
            decoded = Cv10KitReport.frame(plane.getBuffer(), plane.getRowStride(), plane.getPixelStride());
        }
        if (decoded != null) {
            metadataFrames++;
            if (metadataFrames == 1 || metadataFrames % 15 == 0) {
                cameraStatus.setText(String.format(Locale.ROOT,
                        "OV580 frame %d · device %d ns · exposure %d µs · marker %02X",
                        decoded.frameNumber, decoded.deviceNs, decoded.exposureUs, decoded.frameMarker));
            }
        }
    }

    private void finishCameraSample() {
        Cv10KitReport.StereoFrame sample = firstStereo;
        closeCamera();
        cameraStatus.setText(cameraStatus.getText() + "\nY8 frames " + cameraFrames +
                " · frames with metadata " + metadataFrames);
        if (sample == null) return;
        long sensorNs = firstImageTimestampNs;
        long callbackNs = firstCallbackNs;
        cameraSave = new Thread(() -> {
            String result;
            try {
                result = "\n" + saveStereo(sample, sensorNs, callbackNs).getAbsolutePath();
            } catch (IOException | RuntimeException failure) {
                result = "\nCould not save stereo images: " + failure.getMessage();
            }
            String message = result;
            main.post(() -> {
                cameraSave = null;
                if (!isDestroyed()) cameraStatus.append(message);
            });
        }, "CV10 stereo sample");
        cameraSave.start();
    }

    private File saveStereo(Cv10KitReport.StereoFrame stereo, long imageTimestampNs, long callbackNs)
            throws IOException {
        File directory = getExternalFilesDir("kit-stereo");
        if (directory == null) directory = getFilesDir();
        File file = new File(directory, "stereo-" + System.currentTimeMillis() + ".zip");
        Cv10KitReport.Frame frame = stereo.frame;
        String metadata = String.format(Locale.ROOT,
                "width=%d\nheight=%d\nformat=Y8\nframe_number=%d\ndevice_ns=%d\nimage_timestamp_ns=%d\n" +
                "callback_elapsed_realtime_ns=%d\nexposure_us=%d\nmarker=%d\n",
                Cv10KitReport.CAMERA_WIDTH, Cv10KitReport.CAMERA_HEIGHT, frame.frameNumber, frame.deviceNs,
                imageTimestampNs, callbackNs, frame.exposureUs, frame.frameMarker);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            zipEntry(zip, "camera0.y8", stereo.camera0);
            zipEntry(zip, "camera1.y8", stereo.camera1);
            zipEntry(zip, "trailer.bin", stereo.trailer);
            zipEntry(zip, "frame.txt", metadata.getBytes(StandardCharsets.UTF_8));
        }
        writeArchive(file, archive);
        return file;
    }

    private void failCamera(String message) {
        if (headSample != null) headSample.cameraFailure = message;
        closeCamera();
        cameraStatus.setText(message);
    }

    private void closeCamera() {
        main.removeCallbacks(endCamera);
        if (camera != null) camera.close();
        camera = null;
        firstStereo = null;
        headSample = null;
    }

    private void selectSlamFile(int operation) {
        if (Cv10KitTrackingService.busy()) {
            slamStatus.setText("Stop OpenXR locator tracking before opening another SLAM session.");
            return;
        }
        if (slamWorker != null || hidWorker != null && requestedHid == HidAction.HEAD_SLAM) return;
        if (operation == REPLAY_SLAM_PARAMETERS && !Cv10KitSlam.module(this).isFile()) {
            slamStatus.setText("Import the original ARM64 libarvr_slam.so first.");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        if (operation == REPLAY_SLAM_PARAMETERS) picker.putExtra(Intent.EXTRA_TITLE, "slam-params.bin");
        if (operation == IMPORT_HAND_CONFIG) picker.putExtra(Intent.EXTRA_TITLE, "hand-config.bin");
        try {
            startActivityForResult(picker, operation);
        } catch (android.content.ActivityNotFoundException unavailable) {
            slamStatus.setText("Android document picker is unavailable.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if ((requestCode != IMPORT_SLAM_MODULE && requestCode != REPLAY_SLAM_PARAMETERS
                && requestCode != IMPORT_HAND_SDK && requestCode != IMPORT_HAND_CONFIG)
                || resultCode != RESULT_OK || result == null || slamWorker != null) return;
        Uri source = result.getData();
        if (source == null) return;
        if (requestCode == IMPORT_SLAM_MODULE) {
            runSlamJob("Verifying the Huawei ARM64 SLAM module…", () -> {
                Cv10KitSlam.importModule(this, source);
                return "Huawei SLAM module imported. Select slam-params.bin to replay.";
            });
        } else if (requestCode == IMPORT_HAND_SDK) {
            runSlamJob("Verifying the hand tracking SDK and model…", () -> {
                Cv10KitHands.importSdk(this, source);
                return "Huawei hand tracking SDK imported.";
            });
        } else if (requestCode == IMPORT_HAND_CONFIG) {
            runSlamJob("Verifying the original hand replay configuration…", () -> {
                Cv10KitHands.importConfig(this, source);
                return "Huawei hand replay configuration imported.";
            });
        } else {
            runSlamJob("Replaying head SLAM…", () -> {
                try (InputStream input = getContentResolver().openInputStream(source)) {
                    return Cv10KitSlam.replayBlankFrames(this, Cv10KitSlam.readParameters(input));
                }
            });
        }
    }

    private void replayHands() {
        if (!Cv10KitHands.configured(this)) {
            slamStatus.setText("Import the Huawei hand tracking SDK and replay configuration first.");
            return;
        }
        runSlamJob("Replaying both Huawei 6DoF controllers…", () -> Cv10KitHands.replay(this));
    }

    private interface SlamJob { String run() throws IOException, InterruptedException; }

    private void runSlamJob(String start, SlamJob job) {
        if (slamWorker != null || hidWorker != null && requestedHid == HidAction.HEAD_SLAM
                || Cv10KitTrackingService.busy()) return;
        slamStatus.setText(start);
        slamWorker = new Thread(() -> {
            String message;
            try {
                message = job.run();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                message = "Tracking replay stopped.";
            } catch (IOException | RuntimeException failure) {
                message = "Huawei tracking: " + failure.getMessage();
            }
            String status = message;
            main.post(() -> {
                slamWorker = null;
                if (!isDestroyed()) slamStatus.setText(status);
            });
        }, "CV10 tracking diagnostic");
        slamWorker.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            (requestCode == USB_CAMERA_PERMISSION ? hidStatus : cameraStatus).setText("Camera permission denied.");
        } else if (requestCode == USB_CAMERA_PERMISSION) {
            requestHid(requestedHid == null ? HidAction.SAMPLE : requestedHid);
        } else if (requestCode == CAMERA_PREVIEW_PERMISSION) {
            requestCamera();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (trackingStatus != null) main.post(refreshTracking);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(refreshTracking);
        closeCamera();
        if (hidWorker != null) {
            hidWorker.interrupt();
            hidStatus.setText("HID sampling stopped.");
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (slamWorker != null) slamWorker.interrupt();
        unregisterReceiver(permission);
        super.onDestroy();
    }
}
