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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.UUID;

/** One direct BLE connection. GATT operations and callbacks run on the main looper. */
@SuppressLint("MissingPermission")
final class Cv10ControllerLink implements AutoCloseable {
    static final UUID SERVICE = UUID.fromString("622f5b2a-60da-4484-929a-69e3a9140258");
    private static final UUID INPUT = UUID.fromString("ffb4a651-c224-420b-a134-13d3aba313fb");
    private static final UUID CONTROL_NOTIFY = UUID.fromString("233827e5-09cc-45a4-a59b-4d024138cbbe");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String TAG = "Cv10Controller";
    private static final long RETRY_MS = 3000;
    private static final long CONNECT_TIMEOUT_MS = 12000;

    static final class Sample {
        final long arrivalNs = System.nanoTime();
        final byte[] bytes;
        final Cv10ControllerReport report;

        Sample(byte[] value) {
            bytes = value.clone();
            report = Cv10ControllerReport.decode(bytes);
        }
    }

    private final Context context;
    private final String address;
    private final BluetoothDevice discoveredDevice;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable connectTask = this::connect;
    private final Runnable timeoutTask = () -> retry("GATT setup timeout");
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic input;
    private BluetoothGattDescriptor pending;
    private volatile boolean closed;
    volatile Sample latestInput, latestControl;
    volatile String status = "Connecting";
    volatile long inputCount;

    Cv10ControllerLink(Context context, String address) {
        this(context, address, null);
    }

    Cv10ControllerLink(Context context, BluetoothDevice device) {
        this(context, device.getAddress(), device);
    }

    private Cv10ControllerLink(Context context, String address, BluetoothDevice device) {
        this.context = context.getApplicationContext();
        this.address = address;
        this.discoveredDevice = device;
        handler.post(connectTask);
    }

    static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    static boolean canConnect(Context context) {
        return Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void connect() {
        if (closed) return;
        try {
            BluetoothAdapter adapter = adapter(context);
            if (!canConnect(context) || adapter == null || !adapter.isEnabled()) {
                retry("Bluetooth unavailable or permission missing");
                return;
            }
            status = "Connecting";
            BluetoothDevice device = discoveredDevice == null ? adapter.getRemoteDevice(address) : discoveredDevice;
            gatt = device.connectGatt(context, false, callback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler);
            if (gatt == null) {
                retry("connectGatt failed");
                return;
            }
            handler.postDelayed(timeoutTask, CONNECT_TIMEOUT_MS);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException error) {
            retry(error.getClass().getSimpleName());
        }
    }

    private boolean isCurrent(BluetoothGatt source) {
        return !closed && gatt == source;
    }

    private void release() {
        handler.removeCallbacks(timeoutTask);
        BluetoothGatt previous = gatt;
        gatt = null;
        input = null;
        pending = null;
        latestInput = null;
        latestControl = null;
        if (previous != null) {
            // Bluetooth permission may have been revoked since connectGatt.
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

    private void retry(String reason) {
        release();
        if (closed) return;
        if (!reason.equals(status)) Log.i(TAG, reason);
        status = reason;
        handler.removeCallbacks(connectTask);
        handler.postDelayed(connectTask, RETRY_MS);
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

    private void accept(BluetoothGatt source, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!isCurrent(source) || value == null) return;
        UUID uuid = characteristic.getUuid();
        if (INPUT.equals(uuid)) {
            latestInput = new Sample(value);
            inputCount++;
        } else if (CONTROL_NOTIFY.equals(uuid)) {
            latestControl = new Sample(value);
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt source, int result, int state) {
            if (!isCurrent(source)) return;
            try {
                if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    retry("GATT disconnected (" + result + ")");
                } else if (state == BluetoothProfile.STATE_CONNECTED && !source.discoverServices()) {
                    retry("Service discovery failed to start");
                }
            } catch (SecurityException error) {
                retry("Bluetooth permission missing");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt source, int result) {
            if (!isCurrent(source)) return;
            try {
                BluetoothGattService service = result == BluetoothGatt.GATT_SUCCESS ? source.getService(SERVICE) : null;
                if (service == null || (input = service.getCharacteristic(INPUT)) == null) {
                    retry("CF20 service or input characteristic missing (" + result + ")");
                    return;
                }
                if (!subscribe(service.getCharacteristic(CONTROL_NOTIFY)) && !subscribe(input)) {
                    retry("Input subscription failed to start");
                }
            } catch (SecurityException error) {
                retry("Bluetooth permission missing");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int result) {
            if (!isCurrent(source) || descriptor != pending) return;
            pending = null;
            try {
                if (INPUT.equals(descriptor.getCharacteristic().getUuid())) {
                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        retry("Input CCCD rejected (" + result + ")");
                        return;
                    }
                    handler.removeCallbacks(timeoutTask);
                    status = "Input notifications enabled";
                    Log.i(TAG, status);
                } else if (!subscribe(input)) {
                    retry("Input subscription failed to start");
                }
            } catch (SecurityException error) {
                retry("Bluetooth permission missing");
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
        handler.removeCallbacks(connectTask);
        if (Looper.myLooper() == handler.getLooper()) {
            release();
        } else {
            handler.post(this::release);
        }
    }
}
