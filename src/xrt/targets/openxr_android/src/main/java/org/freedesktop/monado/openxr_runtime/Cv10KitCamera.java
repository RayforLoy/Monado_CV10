// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.util.Size;
import java.util.Arrays;
import java.util.Collections;

/** The locator's only supported Camera2 stream. Open, callbacks and close share one handler. */
final class Cv10KitCamera implements AutoCloseable {
    interface Frames {
        void onImage(Image image);
        void onFailure(String message);
    }

    private static final Size SIZE = new Size(Cv10KitReport.FRAME_WIDTH, Cv10KitReport.FRAME_HEIGHT);
    private final Handler handler;
    private final Frames frames;
    private ImageReader images;
    private CameraDevice camera;
    private CameraCaptureSession capture;

    Cv10KitCamera(Handler handler, Frames frames) {
        this.handler = handler;
        this.frames = frames;
    }

    static String find(Context context) throws CameraAccessException {
        CameraManager manager = context.getSystemService(CameraManager.class);
        if (manager == null) return null;
        String found = null;
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics flags = manager.getCameraCharacteristics(id);
            Integer facing = flags.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraCharacteristics.LENS_FACING_EXTERNAL) continue;
            StreamConfigurationMap streams = flags.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = streams == null ? null : streams.getOutputSizes(Cv10KitReport.FRAME_FORMAT);
            if (sizes == null || !Arrays.asList(sizes).contains(SIZE)) continue;
            if (found != null) return null;
            found = id;
        }
        return found;
    }

    void open(Context context) throws CameraAccessException {
        String id = find(context);
        if (id == null) throw new IllegalStateException("Android does not expose a unique external 1280×481 Y8 camera.");
        CameraManager manager = context.getSystemService(CameraManager.class);
        final ImageReader reader = ImageReader.newInstance(SIZE.getWidth(), SIZE.getHeight(),
                Cv10KitReport.FRAME_FORMAT, 2);
        images = reader;
        reader.setOnImageAvailableListener(source -> {
            if (images != source) return;
            try (Image image = source.acquireLatestImage()) {
                if (image != null && image.getPlanes().length == 1) frames.onImage(image);
            } catch (RuntimeException failure) {
                fail("Camera read failed: " + failure.getMessage());
            }
        }, handler);
        try {
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice opened) {
                    if (images != reader) {
                        opened.close();
                        return;
                    }
                    camera = opened;
                    startCapture(opened, reader);
                }

                @Override
                public void onDisconnected(CameraDevice opened) { onError(opened, -1); }

                @Override
                public void onError(CameraDevice opened, int code) {
                    opened.close();
                    if (images == reader) fail("External camera disconnected or failed (" + code + ").");
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void startCapture(CameraDevice opened, ImageReader reader) {
        try {
            CaptureRequest.Builder request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(reader.getSurface());
            opened.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (images != reader) {
                                session.close();
                                return;
                            }
                            capture = session;
                            try {
                                session.setRepeatingRequest(request.build(), null, handler);
                            } catch (CameraAccessException | RuntimeException failure) {
                                fail("Camera capture failed: " + failure.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            session.close();
                            if (images == reader) fail("Camera rejected the external Y8 capture session.");
                        }
                    }, handler);
        } catch (CameraAccessException | RuntimeException failure) {
            fail("Camera session failed: " + failure.getMessage());
        }
    }

    private void fail(String message) {
        close();
        frames.onFailure(message);
    }

    @Override
    public void close() {
        if (capture != null) capture.close();
        if (camera != null) camera.close();
        if (images != null) images.close();
        capture = null;
        camera = null;
        images = null;
    }
}
