// SPDX-License-Identifier: GPL-3.0-or-later
package org.freedesktop.monado.openxr_runtime;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@SuppressLint("MissingPermission")
public class Cv10ControllerActivity extends Activity {
    private static final String PREFS = "cv10_controller";
    private static final String ADDRESS = "address";
    private static final int SCAN_PERMISSION = 1;
    private static final int SAVE_PERMISSION = 2;
    private static final int DIAGNOSTICS_PERMISSION = 3;
    private static final long SCAN_MS = 15000;
    private static final long PAIR_MS = 45000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private EditText address;
    private EditText eyeHeight;
    private TextView simulationStatus;
    private TextView scanStatus, probeStatus;
    private final Runnable scanTimeout = () -> {
        stopScan();
        scanStatus.setText(seen.isEmpty() ? "No CF20 detected. Wake the controller and scan again." : "Scan complete.");
    };
    private LinearLayout results;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothDevice scanSelection, pairingDevice;
    private long pairingDeadline;
    private boolean sawBonding;
    private Cv10ControllerLink probe;
    private String verifyingAddress;
    private long verifyDeadline;
    private long lastCount, lastRefreshNs;

    static String savedAddress(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(ADDRESS, "");
    }

    protected boolean probeMode() {
        return false;
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(probeMode() ? "CV10 Controller Probe" : "CV10 Bluetooth Controller");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        address.setHint("Controller Bluetooth address");
        address.setText(savedAddress(this));
        layout.addView(address);
        addButton(layout, "Scan for CF20", this::requestScan);
        scanStatus = new TextView(this);
        layout.addView(scanStatus);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        layout.addView(results);

        addButton(layout, probeMode() ? "Pair and connect · 配对并连接" : "Pair and use · 配对并选用",
                () -> pairSelection(false));
        addButton(layout, "Pair in Android · Android 系统配对", () -> pairSelection(true));
        addButton(layout, "CF20 link and calibration · 连接与校准诊断", this::startDiagnostics);
        addButton(layout, probeMode() ? "Disconnect" : "Disable controller", () -> {
            stopPairing();
            stopProbe();
            if (probeMode()) {
                probeStatus.setText("Disconnected.");
            } else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(ADDRESS).apply();
                address.setText("");
                probeStatus.setText("Controller disabled. Restart the OpenXR session to apply.");
            }
        });
        probeStatus = new TextView(this);
        probeStatus.setTextIsSelectable(true);
        layout.addView(probeStatus);
        if (!probeMode()) addSimulationControls(layout);
        addButton(layout, "Huawei 6DoF Kit · 定位交互套件",
                () -> startActivity(new Intent(this, Cv10KitActivity.class)));
    }

    private void addSimulationControls(LinearLayout layout) {
        SharedPreferences prefs = Cv10SimulationSettings.preferences(this);
        eyeHeight = new EditText(this);
        eyeHeight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        eyeHeight.setSingleLine(true);
        eyeHeight.setHint("离地眼高（米） · Eye height (m)");
        eyeHeight.setText(String.format(Locale.ROOT, "%.2f", Cv10SimulationSettings.height(prefs)));
        layout.addView(eyeHeight);
        addButton(layout, "保存模拟眼高 · Save eye height", () -> saveSimulationHeight(false));
        addButton(layout, "准备右侧贴合校准 · Prepare right-side calibration", () -> saveSimulationHeight(true));
        addButton(layout, "清除贴合偏移 · Clear alignment", () -> {
            Cv10SimulationSettings.clearOffset(prefs);
            refreshSimulationStatus();
        });
        simulationStatus = new TextView(this);
        layout.addView(simulationStatus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSimulationStatus();
    }

    private void refreshSimulationStatus() {
        if (simulationStatus == null) return;
        SharedPreferences prefs = Cv10SimulationSettings.preferences(this);
        String saved = savedAddress(this);
        if (Cv10SimulationSettings.pending(prefs, saved) != 0) {
            simulationStatus.setText("90 秒内进入 OpenXR。平视，把 CF20 平贴头显右侧，手柄前端朝前、按键朝上，稳住并按住扳机约 1 秒。");
        } else if (Cv10SimulationSettings.calibrated(prefs, saved)) {
            simulationStatus.setText("右侧贴合偏移已保存 · Right-side alignment saved");
        } else {
            simulationStatus.setText("可单独保存眼高。贴合校准需要已选用的 CF20 和 CV10 模拟定位模式。");
        }
    }

    private void saveSimulationHeight(boolean arm) {
        float meters;
        try {
            meters = Float.parseFloat(eyeHeight.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException invalid) {
            meters = Float.NaN;
        }
        if (!Cv10SimulationSettings.validHeight(meters)) {
            eyeHeight.setError("请输入 0.50 到 2.50 米的离地眼高");
            return;
        }
        String saved = savedAddress(this);
        if (arm && !BluetoothAdapter.checkBluetoothAddress(saved)) {
            simulationStatus.setText("请先配对并选用 CF20。");
            return;
        }
        if (arm && Cv10KitTrackingService.trackingRequested()) {
            simulationStatus.setText("请先关闭定位交互套件的头显追踪，再准备模拟定位校准。");
            return;
        }
        if (arm) {
            stopPairing();
            stopProbe();
        }
        Cv10SimulationSettings.saveHeight(Cv10SimulationSettings.preferences(this), meters, arm ? saved : null);
        if (arm) refreshSimulationStatus();
        else simulationStatus.setText("模拟眼高已保存 · Eye height saved");
    }

    private void addButton(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
    }

    private void requestScan() {
        if (Build.VERSION.SDK_INT >= 31 &&
                (!Cv10ControllerLink.canConnect(this) ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                    SCAN_PERMISSION);
        } else if (Build.VERSION.SDK_INT < 31 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, SCAN_PERMISSION);
        } else {
            startScan();
        }
    }

    private void startScan() {
        stopScan();
        seen.clear();
        results.removeAllViews();
        try {
            BluetoothAdapter adapter = Cv10ControllerLink.adapter(this);
            scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                scanStatus.setText("Turn Bluetooth on to scan.");
                return;
            }
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (scanCallback != this) return;
                    ScanRecord record = result.getScanRecord();
                    String name = record == null ? null : record.getDeviceName();
                    try {
                        if (name == null) name = result.getDevice().getName();
                    } catch (SecurityException ignored) {
                        return;
                    }
                    boolean serviceMatch = record != null && record.getServiceUuids() != null &&
                            record.getServiceUuids().contains(new ParcelUuid(Cv10ControllerLink.SERVICE));
                    if (!serviceMatch && (name == null || !(name.startsWith("HUAWEI CF20") ||
                            name.startsWith("Huawei Controller") || name.startsWith("Testmotion")))) return;
                    BluetoothDevice device = result.getDevice();
                    String found = device.getAddress();
                    if (seen.add(found)) {
                        addButton(results, (name == null ? "CF20 service" : name) + "\n" + found, () -> {
                            scanSelection = device;
                            address.setText(found);
                            stopScan();
                            scanStatus.setText("Address selected.");
                        });
                    }
                }

                @Override
                public void onScanFailed(int error) {
                    if (scanCallback != this) return;
                    stopScan();
                    scanStatus.setText("BLE scan failed (" + error + ").");
                }
            };
            scanner.startScan(scanCallback);
            scanStatus.setText("Scanning for 15 seconds…");
            handler.postDelayed(scanTimeout, SCAN_MS);
        } catch (SecurityException error) {
            stopScan();
            scanStatus.setText("Bluetooth scan permission missing.");
        }
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
            }
        }
        scanCallback = null;
        scanner = null;
    }

    private boolean bondAfterPermission;

    private void pairSelection(boolean systemBond) {
        String selected = address.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(selected)) {
            address.setError("Enter an address in XX:XX:XX:XX:XX:XX format.");
            return;
        }
        if (!Cv10ControllerLink.canConnect(this)) {
            bondAfterPermission = systemBond;
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, SAVE_PERMISSION);
            return;
        }
        stopScan();
        stopPairing();
        stopProbe();
        try {
            BluetoothAdapter adapter = Cv10ControllerLink.adapter(this);
            if (adapter == null || !adapter.isEnabled()) {
                probeStatus.setText("Turn Bluetooth on to pair.");
                return;
            }
            BluetoothDevice device = scanSelection != null && selected.equals(scanSelection.getAddress())
                    ? scanSelection : adapter.getRemoteDevice(selected);
            if (!systemBond) {
                verifyController(device);
                return;
            }
            int state = device.getBondState();
            if (state == BluetoothDevice.BOND_BONDED) {
                verifyController(device);
                return;
            }
            if (state != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                state = device.getBondState();
                if (state == BluetoothDevice.BOND_NONE) {
                    probeStatus.setText("Android could not start pairing. Wake the controller and try again.");
                    return;
                }
            }
            pairingDevice = device;
            pairingDeadline = SystemClock.elapsedRealtime() + PAIR_MS;
            sawBonding = state == BluetoothDevice.BOND_BONDING;
            probeStatus.setText("Pairing… Accept the Android Bluetooth pairing prompt if it appears.");
            pollPairing.run();
        } catch (SecurityException error) {
            probeStatus.setText("Bluetooth connect permission is missing.");
        }
    }

    private final Runnable pollPairing = new Runnable() {
        @Override
        public void run() {
            BluetoothDevice device = pairingDevice;
            if (device == null) return;
            int state;
            try {
                state = device.getBondState();
            } catch (SecurityException error) {
                stopPairing();
                probeStatus.setText("Bluetooth connect permission is missing.");
                return;
            }
            if (state == BluetoothDevice.BOND_BONDED) {
                stopPairing();
                verifyController(device);
                return;
            }
            if (state == BluetoothDevice.BOND_NONE && sawBonding) {
                stopPairing();
                probeStatus.setText("Pairing ended. Wake the controller and try again.");
                return;
            }
            if (SystemClock.elapsedRealtime() >= pairingDeadline) {
                stopPairing();
                probeStatus.setText("Pairing timed out. Keep the controller awake and try again.");
                return;
            }
            sawBonding |= state == BluetoothDevice.BOND_BONDING;
            handler.postDelayed(this, 300);
        }
    };

    private void stopPairing() {
        handler.removeCallbacks(pollPairing);
        pairingDevice = null;
    }

    private void verifyController(BluetoothDevice device) {
        startProbe(device, true);
    }

    private void startDiagnostics() {
        String selected = address.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(selected)) {
            address.setError("Enter an address in XX:XX:XX:XX:XX:XX format.");
            return;
        }
        if (!Cv10ControllerLink.canConnect(this)) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, DIAGNOSTICS_PERMISSION);
            return;
        }
        stopScan();
        stopPairing();
        try {
            BluetoothAdapter adapter = Cv10ControllerLink.adapter(this);
            if (adapter == null) {
                probeStatus.setText("Bluetooth is unavailable.");
                return;
            }
            BluetoothDevice device = scanSelection != null && selected.equals(scanSelection.getAddress())
                    ? scanSelection : adapter.getRemoteDevice(selected);
            startProbe(device, false);
        } catch (SecurityException error) {
            probeStatus.setText("Bluetooth connect permission is missing.");
        }
    }

    private void startProbe(BluetoothDevice device, boolean verify) {
        stopProbe();
        if (verify) {
            verifyingAddress = device.getAddress();
            verifyDeadline = SystemClock.elapsedRealtime() + Cv10ControllerLink.PAIR_VERIFICATION_MS + 1000;
        }
        probe = new Cv10ControllerLink(this, device);
        lastCount = 0;
        lastRefreshNs = System.nanoTime();
        refreshProbe.run();
    }

    private final Runnable refreshProbe = new Runnable() {
        @Override
        public void run() {
            if (probe == null) return;
            long now = System.nanoTime(), count = probe.inputCount;
            double rate = 1e9 * (count - lastCount) / Math.max(1, now - lastRefreshNs);
            lastRefreshNs = now;
            lastCount = count;
            Cv10ControllerLink.Sample sample = probe.latestInput;
            Cv10ControllerLink.Sample control = probe.latestControl;
            if (verifyingAddress != null) {
                if (sample != null && sample.report != null) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(ADDRESS, verifyingAddress).apply();
                    verifyingAddress = null;
                    if (!probeMode()) {
                        stopProbe();
                        probeStatus.setText("Paired and selected. Start a new OpenXR session to use the controller.");
                        return;
                    }
                } else if (SystemClock.elapsedRealtime() >= verifyDeadline) {
                    String reason = probe.status;
                    stopProbe();
                    probeStatus.setText("CF20 did not send a 20-byte input report: " + reason +
                            "\nWake the controller and try again, or use Android system pairing.");
                    return;
                }
            }
            StringBuilder status = new StringBuilder(probe.status);
            if (!probe.setupTimings.isEmpty()) status.append("\nLink timings · ").append(probe.setupTimings);
            status.append('\n').append(probe.firmwareStatus)
                    .append('\n').append(Cv10ControllerCalibration.describe(
                            probe.firmwareVersion, sample == null ? null : sample.report));
            status.append(String.format(Locale.ROOT, "\nInput reports %d · %.1f/s", count, rate));
            Cv10ControllerLink.Sample malformed = probe.malformedInput;
            if (malformed != null) status.append("\nLast malformed input · ").append(malformed.bytes.length).append(" bytes");
            if (sample != null) {
                status.append("\nInput ").append(hex(sample.bytes));
                Cv10ControllerReport report = sample.report;
                status.append(String.format(Locale.ROOT,
                        "\nButtons 0x%02X%s\nTouch %d, %d (%s)\nBattery field %d\n" +
                        "Quaternion XYZW %.4f, %.4f, %.4f, %.4f (%s)\n" +
                        "Accel %.2f, %.2f, %.2f\nGyro %.2f, %.2f, %.2f",
                        report.buttons, buttons(report), report.touchX, report.touchY,
                        report.isTouched() ? "contact" : "idle", report.battery,
                        report.x, report.y, report.z, report.w,
                        report.orientationValid ? "valid norm" : "invalid norm",
                        report.accelX, report.accelY, report.accelZ, report.gyroX, report.gyroY, report.gyroZ));
            }
            if (control != null) status.append("\nControl ").append(hex(control.bytes));
            probeStatus.setText(status);
            handler.postDelayed(this, 200);
        }
    };

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) builder.append(String.format(Locale.ROOT, "%02X ", value & 0xff));
        return builder.toString();
    }

    private static String buttons(Cv10ControllerReport report) {
        StringBuilder labels = new StringBuilder();
        if (report.isPressed(Cv10ControllerReport.TRIGGER)) labels.append(" Trigger");
        if (report.isPressed(Cv10ControllerReport.CONFIRM)) labels.append(" Confirm");
        if (report.isPressed(Cv10ControllerReport.BACK)) labels.append(" Back");
        if (report.isPressed(Cv10ControllerReport.HOME)) labels.append(" Home");
        if (report.isPressed(Cv10ControllerReport.VOLUME_UP)) labels.append(" Vol+");
        if (report.isPressed(Cv10ControllerReport.VOLUME_DOWN)) labels.append(" Vol−");
        return labels.toString();
    }

    private void stopProbe() {
        handler.removeCallbacks(refreshProbe);
        verifyingAddress = null;
        if (probe != null) {
            probe.close();
            probe = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean granted = results.length != 0;
        for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            scanStatus.setText("Allow Bluetooth permissions to use the controller.");
        } else if (requestCode == SCAN_PERMISSION) {
            startScan();
        } else if (requestCode == SAVE_PERMISSION) {
            pairSelection(bondAfterPermission);
        } else if (requestCode == DIAGNOSTICS_PERMISSION) {
            startDiagnostics();
        }
    }

    @Override
    protected void onStop() {
        if (scanCallback != null) scanStatus.setText("Scan stopped.");
        if (probe != null) probeStatus.setText("Disconnected.");
        stopScan();
        stopProbe();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopPairing();
        super.onDestroy();
    }
}
