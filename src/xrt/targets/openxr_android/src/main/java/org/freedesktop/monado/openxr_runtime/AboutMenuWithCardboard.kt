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
import javax.inject.Inject
import org.freedesktop.monado.android_common.AboutMenuProvider
import org.freedesktop.monado.auxiliary.Cv10ExternalAppCompatibility
import org.freedesktop.monado.auxiliary.ScannerProvider

/** An about menu that has a QR code scanner to set up Cardboard parameters. */
class AboutMenuWithCardboard @Inject constructor(private val scanner: ScannerProvider) :
    AboutMenuProvider {

    override fun onCreateOptionsMenu(menuInflater: MenuInflater, menu: Menu?) {
        menuInflater.inflate(R.menu.menu_runtime, menu)
        menu ?: return
        menu.findItem(R.id.qrscan).icon = scanner.getIcon()
        menu.findItem(R.id.cv10_app_launcher).isVisible = !BuildConfig.inProcess
        menu.findItem(R.id.cv10_external_compatibility).apply {
            setTitle(org.freedesktop.monado.auxiliary.R.string.cv10_external_compatibility_title)
            isVisible = !BuildConfig.inProcess
        }
        menu.findItem(R.id.cv10_pocket_preview).isVisible = BuildConfig.cv10LauncherPreview && !BuildConfig.inProcess
    }

    override fun onOptionsItemSelected(context: Context, item: MenuItem): Boolean {
        if (item.itemId == R.id.cv10_app_launcher) {
            context.startActivity(Intent(context, Cv10LauncherActivity::class.java))
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
}
