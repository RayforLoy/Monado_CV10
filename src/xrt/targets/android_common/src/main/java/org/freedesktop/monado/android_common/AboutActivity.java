// Copyright 2020-2025, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Simple main activity for Android.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @author Simon Zeni <simon.zeni@collabora.com>
 */

package org.freedesktop.monado.android_common;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;
import android.os.Bundle;
import android.widget.Toast;
import org.freedesktop.monado.auxiliary.Cv10DisplayPortDiagnostics;
import org.freedesktop.monado.auxiliary.Cv10RefreshLease;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.Optional;
import javax.inject.Inject;
import org.freedesktop.monado.auxiliary.NameAndLogoProvider;
import org.freedesktop.monado.auxiliary.UiProvider;

@AndroidEntryPoint
public class AboutActivity extends AppCompatActivity {
    private static final int CV10_USB_ACCESS = 0x43563130;

    private static final int CV10_REFRESH = 0x43563131;

    private static final int CV10_DISABLE = 0x43563132;
    private static final int CV10_DP_STATUS = 0x43563133;
    private static final int CV10_MANUAL = 0x43563134;
    private static final int CV10_MANUAL_RESTORE = 0x43563135;
    private static final int CV10_IMPORT_MODEL = 0x43563136;
    private static final int PICK_CF20_GLB = 0x4332;
    private final SharedPreferences.OnSharedPreferenceChangeListener refreshChanged =
            (prefs, key) -> { if (Cv10RefreshLease.MANUAL.equals(key)) invalidateOptionsMenu(); };
    private final Shizuku.OnBinderReceivedListener binderReady = this::invalidateOptionsMenu;
    private final Shizuku.OnBinderDeadListener binderLost = this::invalidateOptionsMenu;


    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_CF20_GLB || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        new Thread(() -> {
            try {
                org.freedesktop.monado.auxiliary.Cv10ModelStore.importModel(this, uri);
                runOnUiThread(() -> Toast.makeText(this, "CF20 模型已导入，重新打开 XR 应用即可使用", Toast.LENGTH_LONG).show());
            } catch (java.io.IOException | SecurityException e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "CV10-model-import").start();
    }

    private boolean authorized() {
        try { return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }

    private SharedPreferences refreshPrefs() {
        return getSharedPreferences(Cv10RefreshLease.PREFS, MODE_PRIVATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Cv10RefreshLease.supportedPhone(this)
                && (refreshPrefs().getBoolean(Cv10RefreshLease.PENDING, false)
                    || refreshPrefs().contains(Cv10RefreshLease.MANUAL))) {
            try {
                startService(new Intent().setClassName(this,
                        "org.freedesktop.monado.ipc.Cv10RefreshRecoveryService"));
            } catch (RuntimeException e) {
                android.util.Log.w("Cv10RefreshLease", "Recovery service unavailable", e);
            }
        }
        invalidateOptionsMenu();
    }

    private void changeRefreshOption(boolean manual) {
        new AlertDialog.Builder(this)
                .setTitle(manual ? "离线保持 120 Hz" : "开启独立 120 Hz")
                .setMessage(manual ? "将手机最低刷新率设为 120 Hz，取代按会话提频。生效后可停止 Shizuku。"
                        + "日常亮屏也会受影响，可能增加耗电。结束时启动 Shizuku，回到这里恢复原设置。"
                        : "连接 CV10 并运行 OpenXR 应用时，临时将手机最低刷新率设为 120 Hz。"
                        + "退出或拔线后恢复。需授权 Shizuku，且可能增加耗电。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    try {
                        if (!Shizuku.pingBinder() || Shizuku.getVersion() < 13) {
                            Toast.makeText(this, "请先在 Shizuku 13 中通过无线调试启动服务。", Toast.LENGTH_LONG).show();
                        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                            enableRefresh(manual);
                        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                            Toast.makeText(this, "请在 Shizuku 的已授权应用中允许 Monado CV10。", Toast.LENGTH_LONG).show();
                        } else {
                            Shizuku.requestPermission(manual ? CV10_MANUAL : CV10_REFRESH);
                        }
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Shizuku 当前不可用。", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void enableRefresh(boolean manual) {
        if (Shizuku.getUid() != 2000) {
            Toast.makeText(this, "请通过无线调试启动 Shizuku。", Toast.LENGTH_LONG).show();
            return;
        }
        if (manual) Cv10RefreshLease.get(this).setManual(true);
        else refreshPrefs().edit().putBoolean(Cv10RefreshLease.ENABLED, true).apply();
        invalidateOptionsMenu();
        Toast.makeText(this, manual ? "正在设置，可以在菜单查看状态" : "已开启 Shizuku 独立 120 Hz", Toast.LENGTH_SHORT).show();
    }

    private final Shizuku.OnRequestPermissionResultListener refreshPermission = (code, result) -> {
        if ((code == CV10_REFRESH || code == CV10_MANUAL) && result == PackageManager.PERMISSION_GRANTED)
            enableRefresh(code == CV10_MANUAL);
        if (code == CV10_MANUAL_RESTORE && result == PackageManager.PERMISSION_GRANTED)
            Cv10RefreshLease.get(this).setManual(false);
    };

    @Override protected void onDestroy() {
        refreshPrefs().unregisterOnSharedPreferenceChangeListener(refreshChanged);
        Shizuku.removeRequestPermissionResultListener(refreshPermission);
        Shizuku.removeBinderReceivedListener(binderReady);
        Shizuku.removeBinderDeadListener(binderLost);
        super.onDestroy();
    }

    @Inject NoticeFragmentProvider noticeFragmentProvider;

    @Inject UiProvider uiProvider;

    @Inject NameAndLogoProvider nameAndLogoProvider;

    /**
     * @noinspection OptionalUsedAsFieldOrParameterType
     */
    @Inject Optional<AboutMenuProvider> aboutMenuProvider;

    private boolean isInProcessBuild() {
        try {
            getClassLoader().loadClass("org/freedesktop/monado/ipc/Client");
            return false;
        } catch (ClassNotFoundException e) {
            // ok, we're in-process.
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addRequestPermissionResultListener(refreshPermission);
        Shizuku.addBinderReceivedListenerSticky(binderReady);
        Shizuku.addBinderDeadListener(binderLost);
        refreshPrefs().registerOnSharedPreferenceChangeListener(refreshChanged);
        setContentView(R.layout.activity_about);

        // Default to dark mode universally?
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);

        setSupportActionBar(findViewById(R.id.toolbar));

        // Make our Monado link clickable
        ((TextView) findViewById(R.id.textPowered))
                .setMovementMethod(LinkMovementMethod.getInstance());

        // Branding from the branding provider
        ((TextView) findViewById(R.id.textName))
                .setText(nameAndLogoProvider.getLocalizedRuntimeName());
        ((ImageView) findViewById(R.id.imageView))
                .setImageDrawable(nameAndLogoProvider.getLogoDrawable());

        boolean isInProcess = isInProcessBuild();
        if (!isInProcess) {
            ShutdownProcess.Companion.setupRuntimeShutdownButton(this);
        }

        // Start doing fragments
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();

        @VrModeStatus.Status
        int status =
                VrModeStatus.detectStatus(
                        this, getApplicationContext().getApplicationInfo().packageName);

        VrModeStatus statusFrag = VrModeStatus.newInstance(status);
        fragmentTransaction.add(R.id.statusFrame, statusFrag, null);

        if (!isInProcess) {
            findViewById(R.id.drawOverOtherAppsFrame).setVisibility(View.VISIBLE);
            DisplayOverOtherAppsStatusFragment drawOverFragment =
                    new DisplayOverOtherAppsStatusFragment();
            fragmentTransaction.replace(R.id.drawOverOtherAppsFrame, drawOverFragment, null);
        }

        if (noticeFragmentProvider != null) {
            Fragment noticeFragment = noticeFragmentProvider.makeNoticeFragment();
            fragmentTransaction.add(R.id.aboutFrame, noticeFragment, null);
        }

        fragmentTransaction.commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, CV10_IMPORT_MODEL, Menu.NONE, "导入已校准的 CF20 手柄模型");
        menu.add(Menu.NONE, CV10_USB_ACCESS, Menu.NONE, "CV10 USB access");
        menu.add(Menu.NONE, CV10_DP_STATUS, Menu.NONE,
                org.freedesktop.monado.auxiliary.R.string.cv10_dp_title);
        if (Cv10RefreshLease.supportedPhone(this)) {
            boolean enabled = refreshPrefs().getBoolean(Cv10RefreshLease.ENABLED, false);
            String manual = refreshPrefs().getString(Cv10RefreshLease.MANUAL, "");
            if (manual.isEmpty()) {
                menu.add(Menu.NONE, CV10_MANUAL, Menu.NONE, "离线保持 120 Hz");
                if (!enabled) menu.add(Menu.NONE, CV10_REFRESH, Menu.NONE, "开启独立 120 Hz");
            } else {
                String label = "正在设置 120 Hz · 取消";
                if (Cv10RefreshLease.HELD.equals(manual)) label = "已保持 120 Hz · 恢复原刷新率";
                if (Cv10RefreshLease.RESTORING.equals(manual)) label = "等待恢复原刷新率 · 需要 Shizuku";
                menu.add(Menu.NONE, CV10_MANUAL, Menu.NONE, label);
            }
            if (enabled && manual.isEmpty()) {
                if (!authorized())
                    menu.add(Menu.NONE, CV10_REFRESH, Menu.NONE, "重新授权 Shizuku · 独立 120 Hz");
                menu.add(Menu.NONE, CV10_DISABLE, Menu.NONE, "关闭独立 120 Hz");
            }
        }
        aboutMenuProvider.ifPresent(
                menuProvider -> menuProvider.onCreateOptionsMenu(getMenuInflater(), menu));
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == CV10_IMPORT_MODEL) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(picker, PICK_CF20_GLB);
            return true;
        }
        if (item.getItemId() == CV10_DP_STATUS) {
            Cv10DisplayPortDiagnostics.showStatus(this);
            return true;
        }
        if (item.getItemId() == CV10_DISABLE) {
            refreshPrefs().edit().putBoolean(Cv10RefreshLease.ENABLED, false).apply();
            invalidateOptionsMenu();
            return true;
        }
        if (item.getItemId() == CV10_REFRESH) {
            changeRefreshOption(false);
            return true;
        }
        if (item.getItemId() == CV10_MANUAL) {
            String phase = refreshPrefs().getString(Cv10RefreshLease.MANUAL, "");
            if (phase.isEmpty()) changeRefreshOption(true);
            else {
                Cv10RefreshLease.get(this).setManual(false);
                if (!authorized()) {
                    Toast.makeText(this, "请启动 Shizuku 并授权 Monado CV10，再回到设置完成恢复。", Toast.LENGTH_LONG).show();
                    try {
                        if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale())
                            Shizuku.requestPermission(CV10_MANUAL_RESTORE);
                    } catch (RuntimeException ignored) { /* The saved restore request survives a disconnect. */ }
                }
            }
            return true;
        }
        if (item.getItemId() == CV10_USB_ACCESS) {
            Cv10UsbAccess.requestFromSettings(this);
            return true;
        }
        Optional<AboutMenuProvider> aboutMenu = aboutMenuProvider;
        if (aboutMenu.isPresent()) {
            if (aboutMenu.get().onOptionsItemSelected(this, item)) {
                // handled
                return true;
            }
        }

        return super.onOptionsItemSelected(item);
    }
}
