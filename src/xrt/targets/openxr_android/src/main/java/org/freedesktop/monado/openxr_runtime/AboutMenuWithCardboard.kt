// Copyright 2020-2025, Collabora, Ltd.
// SPDX-License-Identifier: BSL-1.0
/*!
 * @file
 * @brief  Menu with QR code scanner for Cardboard.
 * @author Rylie Pavlik <rylie.pavlik@collabora.com>
 * @author Simon Zeni <simon.zeni@collabora.com>
 */

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import javax.inject.Inject
import org.freedesktop.monado.android_common.AboutMenuProvider
import org.freedesktop.monado.auxiliary.Cv10ExternalAppCompatibility
import org.freedesktop.monado.auxiliary.Cv10IpdSettings
import org.freedesktop.monado.auxiliary.ScannerProvider

/** An about menu that has a QR code scanner to set up Cardboard parameters. */
class AboutMenuWithCardboard @Inject constructor(private val scanner: ScannerProvider) :
    AboutMenuProvider {

    override fun onCreateOptionsMenu(menuInflater: MenuInflater, menu: Menu?) {
        menuInflater.inflate(R.menu.menu_runtime, menu)
        menu ?: return
        menu.findItem(R.id.qrscan).icon = scanner.getIcon()
        menu.findItem(R.id.cv10_app_launcher).isVisible = !BuildConfig.inProcess
        menu.findItem(R.id.cv10_controller).isVisible = !BuildConfig.inProcess
        menu.findItem(R.id.cv10_ipd).isVisible = !BuildConfig.inProcess
        menu.findItem(R.id.cv10_external_compatibility).apply {
            setTitle(org.freedesktop.monado.auxiliary.R.string.cv10_external_compatibility_title)
            isVisible = !BuildConfig.inProcess
        }
        menu.findItem(R.id.cv10_pocket_preview).isVisible = BuildConfig.cv10LauncherPreview && !BuildConfig.inProcess
    }

    override fun onOptionsItemSelected(context: Context, item: MenuItem): Boolean {
        if (item.itemId == R.id.cv10_controller) {
            context.startActivity(Intent(context, Cv10ControllerActivity::class.java))
            return true
        }
        if (item.itemId == R.id.cv10_app_launcher) {
            context.startActivity(Intent(context, Cv10LauncherActivity::class.java))
            return true
        }
        if (item.itemId == R.id.cv10_ipd && context is Activity) {
            showCv10Ipd(context)
            return true
        }
        if (item.itemId == R.id.cv10_external_compatibility && context is Activity) {
            Cv10ExternalAppCompatibility.show(context)
            return true
        }
        if (item.itemId == R.id.cv10_pocket_preview) {
            context.startActivity(Cv10PocketXrPreviewActivity.resumeIntent(context))
            return true
        }
        if (item.itemId == R.id.qrscan) {
            val scannerIntent: Intent = scanner.makeScannerIntent()
            context.startActivity(scannerIntent)
            return true
        }
        return false
    }

    private fun showCv10Ipd(context: Activity) {
        val preferences = Cv10IpdSettings.preferences(context)
        val override = Cv10IpdSettings.readOverride(preferences)
        val current = if (override == 0) Cv10IpdSettings.DEFAULT_MM else override
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val value = TextView(context).apply {
            text = context.getString(R.string.cv10_ipd_value, current)
        }
        val slider = SeekBar(context).apply {
            max = Cv10IpdSettings.MAX_MM - Cv10IpdSettings.MIN_MM
            progress = current - Cv10IpdSettings.MIN_MM
            contentDescription = context.getText(R.string.cv10_ipd_title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text =
                        context.getString(R.string.cv10_ipd_value, progress + Cv10IpdSettings.MIN_MM)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {}

                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        content.addView(TextView(context).apply { setText(R.string.cv10_ipd_description) })
        content.addView(value)
        content.addView(slider)
        AlertDialog.Builder(context)
            .setTitle(R.string.cv10_ipd_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.cv10_ipd_reset) { _, _ ->
                preferences.edit().remove(Cv10IpdSettings.IPD_MM).apply()
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ipdMm = slider.progress + Cv10IpdSettings.MIN_MM
                preferences.edit().putInt(Cv10IpdSettings.IPD_MM, ipdMm).apply()
            }
            .show()
    }
}
