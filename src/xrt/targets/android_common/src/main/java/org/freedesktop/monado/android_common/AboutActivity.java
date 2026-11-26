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
    private final Shizuku.OnBinderReceivedListener binderReady = this::invalidateOptionsMenu;
    private final Shizuku.OnBinderDeadListener binderLost = this::invalidateOptionsMenu;

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
                && refreshPrefs().getBoolean(Cv10RefreshLease.PENDING, false)) {
            try {
                startService(new Intent().setClassName(this,
                        "org.freedesktop.monado.ipc.Cv10RefreshRecoveryService"));
            } catch (RuntimeException e) {
                android.util.Log.w("Cv10RefreshLease", "Recovery service unavailable", e);
            }
        }
        invalidateOptionsMenu();
    }

    private void changeRefreshOption() {
        new AlertDialog.Builder(this)
                .setTitle("开启独立 120 Hz")
                .setMessage("连接 CV10 并运行 OpenXR 应用时，临时将手机最低刷新率设为 120 Hz。"
                        + "退出或拔线后恢复。需授权 Shizuku，且可能增加耗电。")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    try {
                        if (!Shizuku.pingBinder() || Shizuku.getVersion() < 13) {
                            Toast.makeText(this, "请先在 Shizuku 13 中通过无线调试启动服务。", Toast.LENGTH_LONG).show();
                        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                            enableRefresh();
                        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                            Toast.makeText(this, "请在 Shizuku 的已授权应用中允许 Monado CV10。", Toast.LENGTH_LONG).show();
                        } else {
                            Shizuku.requestPermission(CV10_REFRESH);
                        }
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Shizuku 当前不可用。", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void enableRefresh() {
        if (Shizuku.getUid() != 2000) {
            Toast.makeText(this, "请通过无线调试启动 Shizuku。", Toast.LENGTH_LONG).show();
            return;
        }
        refreshPrefs().edit().putBoolean(Cv10RefreshLease.ENABLED, true).apply();
        invalidateOptionsMenu();
        Toast.makeText(this, "已开启 Shizuku 独立 120 Hz", Toast.LENGTH_SHORT).show();
    }

    private final Shizuku.OnRequestPermissionResultListener refreshPermission = (code, result) -> {
        if (code == CV10_REFRESH && result == PackageManager.PERMISSION_GRANTED) enableRefresh();
    };

    @Override protected void onDestroy() {
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
        menu.add(Menu.NONE, CV10_USB_ACCESS, Menu.NONE, "CV10 USB access");
        menu.add(Menu.NONE, CV10_DP_STATUS, Menu.NONE,
                org.freedesktop.monado.auxiliary.R.string.cv10_dp_title);
        if (Cv10RefreshLease.supportedPhone(this)) {
            boolean enabled = refreshPrefs().getBoolean(Cv10RefreshLease.ENABLED, false);
            if (!enabled) menu.add(Menu.NONE, CV10_REFRESH, Menu.NONE, "开启独立 120 Hz");
            else {
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
            changeRefreshOption();
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
