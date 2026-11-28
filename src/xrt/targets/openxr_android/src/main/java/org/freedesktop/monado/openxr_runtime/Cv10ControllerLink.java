// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.UUID;

/** One direct BLE connection. GATT operations and callbacks run on the main looper. */
@SuppressLint("MissingPermission")
final class Cv10ControllerLink implements AutoCloseable {
    static final UUID SERVICE = UUID.fromString("622f5b2a-60da-4484-929a-69e3a9140258");
    private static final UUID INPUT = UUID.fromString("ffb4a651-c224-420b-a134-13d3aba313fb");
    private static final UUID CONTROL_WRITE = UUID.fromString("233827e4-09cc-45a4-a59b-4d024138cbbe");
    private static final UUID CONTROL_NOTIFY = UUID.fromString("233827e5-09cc-45a4-a59b-4d024138cbbe");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String TAG = "Cv10Controller";
    private static final long CONNECT_TIMEOUT_MS = 20000;
    private static final long DISCOVERY_TIMEOUT_MS = 20000;
    private static final long DISCOVERY_DELAY_MS = 1000;
    private static final long SUBSCRIPTION_DELAY_MS = 500;
    private static final long SUBSCRIPTION_TIMEOUT_MS = 5000;
    private static final long FIRST_INPUT_TIMEOUT_MS = 5000;
    private static final long FIRMWARE_TIMEOUT_MS = 3000;
    private static final long COOLDOWN_MS = 15000;
    private static final int FAST_RETRIES = 3;
    private static final int GATT_ERROR_133 = 133;
    static final long PAIR_VERIFICATION_MS = CONNECT_TIMEOUT_MS + DISCOVERY_DELAY_MS + DISCOVERY_TIMEOUT_MS +
            2 * SUBSCRIPTION_TIMEOUT_MS + SUBSCRIPTION_DELAY_MS + FIRST_INPUT_TIMEOUT_MS;

    private enum Phase {
        IDLE("idle"),
        CONNECT("connect"),
        DISCOVERY_DELAY("settle"),
        DISCOVER("discover"),
        CONTROL_CCCD("control CCCD"),
        SUBSCRIPTION_DELAY("notify gap"),
        INPUT_CCCD("input CCCD"),
        FIRST_INPUT("first input"),
        STREAMING("streaming"),
        RETRY("retry"),
        BLUETOOTH_OFF("Bluetooth unavailable");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    interface Listener {
        void onReady(boolean ready);

        void onInput(Sample sample);
    }

    static final class Sample {
        final long arrivalNs = System.nanoTime();
        final byte[] bytes;
        final Cv10ControllerReport report;

        Sample(byte[] value, boolean input) {
            bytes = value.clone();
            report = input ? Cv10ControllerReport.decode(bytes) : null;
        }
    }

    private final Context context;
    private final String address;
    private final BluetoothDevice discoveredDevice;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connectTask = this::connect;
    private final Runnable versionTimeout = () -> firmwareStatus = "Firmware query timed out";
    private final StringBuilder timings = new StringBuilder();
    private final BroadcastReceiver adapterState = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (closed || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                if (gatt != null) waitForBluetooth();
            } else if (state == BluetoothAdapter.STATE_ON && phase == Phase.BLUETOOTH_OFF) {
                handler.removeCallbacks(connectTask);
                handler.post(connectTask);
            }
        }
    };

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic input, controlWrite;
    private BluetoothGattDescriptor pending;
    private Runnable phaseTask;
    private Phase phase = Phase.IDLE;
    private long phaseStartedMs;
    private int fastRetries;
    private boolean controlSubscribed, ready, receiverRegistered;
    private volatile boolean closed;
    volatile Sample latestInput, latestControl, malformedInput;
    volatile String status = "Connecting";
    volatile String setupTimings = "";
    volatile String firmwareVersion;
    volatile String firmwareStatus = "Waiting for the CF20 link";
    volatile long inputCount;

    Cv10ControllerLink(Context context, String address, Listener listener) {
        this(context, address, null, listener);
    }

    Cv10ControllerLink(Context context, BluetoothDevice device) {
        this(context, device.getAddress(), device, null);
    }

    private Cv10ControllerLink(Context context, String address, BluetoothDevice device, Listener listener) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.discoveredDevice = device;
        this.listener = listener;
        handler.post(this::start);
    }

    static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    static boolean canConnect(Context context) {
        return Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void start() {
        if (closed) return;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                // Bluetooth broadcasts can be sent by the Bluetooth app, outside the system UID.
                context.registerReceiver(adapterState, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(adapterState, filter);
            }
            receiverRegistered = true;
        } catch (SecurityException ignored) {
            // The periodic check in waitForBluetooth also handles missing or restored permission.
        }
        connect();
    }

    private void connect() {
        if (closed) return;
        try {
            BluetoothAdapter adapter = adapter(context);
            if (!canConnect(context) || adapter == null || !adapter.isEnabled()) {
                waitForBluetooth();
                return;
            }
            timings.setLength(0);
            setupTimings = "";
            phase = Phase.IDLE;
            enter(Phase.CONNECT, CONNECT_TIMEOUT_MS, () -> retry("Connection timed out", 0));
            BluetoothDevice device = discoveredDevice == null ? adapter.getRemoteDevice(address) : discoveredDevice;
            gatt = device.connectGatt(context, false, callback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler);
            if (gatt == null) retry("connectGatt failed", 0);
        } catch (SecurityException error) {
            waitForBluetooth();
        } catch (IllegalArgumentException | IllegalStateException error) {
            retry(error.getClass().getSimpleName(), 0);
        }
    }

    private boolean isCurrent(BluetoothGatt source) {
        return !closed && gatt == source;
    }

    private void enter(Phase next, long delayMs, Runnable action) {
        cancelPhaseTask();
        long now = SystemClock.elapsedRealtime();
        if (phase.ordinal() >= Phase.CONNECT.ordinal() && phase.ordinal() <= Phase.FIRST_INPUT.ordinal()) {
            if (timings.length() != 0) timings.append(" · ");
            timings.append(phase.label).append(' ').append(now - phaseStartedMs).append(" ms");
            setupTimings = timings.toString();
        }
        phase = next;
        phaseStartedMs = now;
        status = next.label;
        if (action != null) {
            phaseTask = () -> {
                if (!closed && phase == next) action.run();
            };
            handler.postDelayed(phaseTask, delayMs);
        }
    }

    private void cancelPhaseTask() {
        if (phaseTask != null) {
            handler.removeCallbacks(phaseTask);
            phaseTask = null;
        }
    }

    private void release() {
        cancelPhaseTask();
        handler.removeCallbacks(versionTimeout);
        BluetoothGatt previous = gatt;
        gatt = null;
        input = null;
        controlWrite = null;
        pending = null;
        controlSubscribed = false;
        latestInput = null;
        latestControl = null;
        malformedInput = null;
        firmwareVersion = null;
        firmwareStatus = "Waiting for the CF20 link";
        if (ready) {
            ready = false;
            if (listener != null) listener.onReady(false);
        }
        if (previous != null) {
            try {
                previous.disconnect();
            } catch (SecurityException ignored) {
            }
            try {
                previous.close();
            } catch (SecurityException ignored) {
            }
        }
    }

    private void waitForBluetooth() {
        release();
        if (closed) return;
        fastRetries = 0;
        enter(Phase.BLUETOOTH_OFF, 0, null);
        status = "Bluetooth is off or connect permission is missing";
        handler.removeCallbacks(connectTask);
        handler.postDelayed(connectTask, COOLDOWN_MS);
    }

    private void retry(String reason, int gattStatus) {
        release();
        if (closed) return;
        enter(Phase.RETRY, 0, null);
        long delay;
        if (fastRetries < FAST_RETRIES) {
            fastRetries++;
            delay = gattStatus == GATT_ERROR_133 ? 5000 : 1000;
        } else {
            // Keep a configured controller recoverable if it wakes after the OEM quick attempts.
            delay = COOLDOWN_MS;
        }
        status = reason + " · retry in " + delay / 1000 + " s";
        Log.i(TAG, status + " [" + setupTimings + "]");
        handler.removeCallbacks(connectTask);
        handler.postDelayed(connectTask, delay);
    }

    private void discover() {
        enter(Phase.DISCOVER, DISCOVERY_TIMEOUT_MS, () -> retry("Service discovery timed out", 0));
        try {
            if (!gatt.discoverServices()) retry("Service discovery failed to start", 0);
        } catch (SecurityException error) {
            waitForBluetooth();
        }
    }

    private boolean subscribe(BluetoothGattCharacteristic characteristic) {
        if (characteristic == null ||
                (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) return false;
        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD);
        if (cccd == null || !gatt.setCharacteristicNotification(characteristic, true)) return false;
        boolean queued;
        if (Build.VERSION.SDK_INT >= 33) {
            queued = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    == BluetoothGatt.GATT_SUCCESS;
        } else {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            queued = gatt.writeDescriptor(cccd);
        }
        pending = queued ? cccd : null;
        return queued;
    }

    private void subscribeInput() {
        enter(Phase.INPUT_CCCD, SUBSCRIPTION_TIMEOUT_MS, () -> retry("Input CCCD timed out", 0));
        try {
            if (!subscribe(input)) retry("Input subscription failed to start", 0);
        } catch (SecurityException error) {
            waitForBluetooth();
        }
    }

    private void requestFirmware() {
        if (firmwareVersion != null) return;
        if (!controlSubscribed || controlWrite == null) {
            firmwareStatus = "Firmware unavailable · missing control channel";
            return;
        }
        // The factory request is read-only. Its response arrives on the control notification.
        int properties = controlWrite.getProperties();
        int writeType;
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        } else if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        } else {
            firmwareStatus = "Firmware unavailable · control characteristic is not writable";
            return;
        }
        byte[] request = Cv10ControllerCalibration.requestVersion();
        try {
            boolean queued;
            if (Build.VERSION.SDK_INT >= 33) {
                queued = gatt.writeCharacteristic(controlWrite, request, writeType) == BluetoothGatt.GATT_SUCCESS;
            } else {
                controlWrite.setWriteType(writeType);
                controlWrite.setValue(request);
                queued = gatt.writeCharacteristic(controlWrite);
            }
            firmwareStatus = queued ? "Firmware query sent" : "Firmware query could not start";
            if (queued) handler.postDelayed(versionTimeout, FIRMWARE_TIMEOUT_MS);
        } catch (SecurityException error) {
            firmwareStatus = "Firmware query needs Bluetooth permission";
        }
    }

    private void accept(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!isCurrent(source) || value == null) return;
        UUID uuid = characteristic.getUuid();
        if (INPUT.equals(uuid) && (phase == Phase.FIRST_INPUT || phase == Phase.STREAMING)) {
            Sample sample = new Sample(value, true);
            inputCount++;
            if (sample.report == null) {
                malformedInput = sample;
                return;
            }
            if (phase == Phase.FIRST_INPUT) {
                enter(Phase.STREAMING, 0, null);
                fastRetries = 0;
                Log.i(TAG, "CF20 streaming [" + setupTimings + "]");
                ready = true;
                if (listener != null) listener.onReady(true);
            }
            if (listener != null) listener.onInput(sample);
            latestInput = sample;
        } else if (CONTROL_NOTIFY.equals(uuid)) {
            latestControl = new Sample(value, false);
            String version = Cv10ControllerCalibration.parseVersionResponse(value);
            if (version != null) {
                handler.removeCallbacks(versionTimeout);
                firmwareVersion = version;
                firmwareStatus = "Firmware " + version;
                Log.i(TAG, firmwareStatus);
            }
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt source, int result, int state) {
            if (!isCurrent(source)) return;
            if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                retry("GATT disconnected (" + result + ")", result);
            } else if (state == BluetoothProfile.STATE_CONNECTED && phase == Phase.CONNECT) {
                enter(Phase.DISCOVERY_DELAY, DISCOVERY_DELAY_MS, Cv10ControllerLink.this::discover);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt source, int result) {
            if (!isCurrent(source) || phase != Phase.DISCOVER) return;
            try {
                BluetoothGattService service = result == BluetoothGatt.GATT_SUCCESS ? source.getService(SERVICE) : null;
                if (service == null || (input = service.getCharacteristic(INPUT)) == null) {
                    retry("CF20 service or input characteristic missing (" + result + ")", result);
                    return;
                }
                controlWrite = service.getCharacteristic(CONTROL_WRITE);
                BluetoothGattCharacteristic control = service.getCharacteristic(CONTROL_NOTIFY);
                if (subscribe(control)) {
                    enter(Phase.CONTROL_CCCD, SUBSCRIPTION_TIMEOUT_MS, () -> retry("Control CCCD timed out", 0));
                } else {
                    // Preserve input for devices that omit the optional control notification.
                    subscribeInput();
                }
            } catch (SecurityException error) {
                waitForBluetooth();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int result) {
            if (!isCurrent(source) || descriptor != pending) return;
            pending = null;
            if (phase == Phase.INPUT_CCCD) {
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    retry("Input CCCD rejected (" + result + ")", result);
                    return;
                }
                enter(Phase.FIRST_INPUT, FIRST_INPUT_TIMEOUT_MS, () -> retry("No valid input report", 0));
                requestFirmware();
            } else if (phase == Phase.CONTROL_CCCD) {
                controlSubscribed = result == BluetoothGatt.GATT_SUCCESS;
                if (controlSubscribed) {
                    enter(Phase.SUBSCRIPTION_DELAY, SUBSCRIPTION_DELAY_MS, Cv10ControllerLink.this::subscribeInput);
                } else {
                    subscribeInput();
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt source, BluetoothGattCharacteristic characteristic, int result) {
            if (isCurrent(source) && characteristic == controlWrite && firmwareVersion == null &&
                    result != BluetoothGatt.GATT_SUCCESS) {
                handler.removeCallbacks(versionTimeout);
                firmwareStatus = "Firmware query rejected (" + result + ")";
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            accept(source, characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt source, BluetoothGattCharacteristic characteristic) {
            accept(source, characteristic, characteristic.getValue());
        }
    };

    @Override
    public void close() {
        closed = true;
        if (Looper.myLooper() == handler.getLooper()) {
            stop();
        } else {
            handler.post(this::stop);
        }
    }

    private void stop() {
        handler.removeCallbacks(connectTask);
        release();
        if (receiverRegistered) {
            context.unregisterReceiver(adapterState);
            receiverRegistered = false;
        }
    }
}
