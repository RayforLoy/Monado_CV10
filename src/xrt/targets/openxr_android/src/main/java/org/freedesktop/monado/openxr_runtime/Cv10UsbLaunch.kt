// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.hardware.usb.UsbManager
import android.util.Log
import org.freedesktop.monado.android_common.Cv10UsbAccess

internal class Cv10UsbLaunch(private val activity: Activity, private val onUnavailable: () -> Unit) {
    private var pending: (() -> Unit)? = null

    val isWaiting: Boolean
        get() = pending != null

    /** Returns true if the caller must wait for permission or let the user retry. */
    fun requestIfMissing(onGranted: () -> Unit): Boolean {
        val usb = activity.getSystemService(UsbManager::class.java)
        val glass = Cv10UsbAccess.find(usb)
        if (usb == null || glass == null || usb.hasPermission(glass)) return false

        // SystemUI grants access before it returns window focus.
        pending = {
            val attached = usb.deviceList[glass.deviceName]
            if (attached == glass && usb.hasPermission(attached)) onGranted() else onUnavailable()
        }
        try {
            Cv10UsbAccess.request(activity, usb, glass)
        } catch (error: RuntimeException) {
            cancel()
            Log.w(TAG, "Could not request CV10 USB access", error)
            onUnavailable()
            return true
        }
        if (activity.hasWindowFocus() && usb.hasPermission(glass)) onWindowFocused()
        return true
    }

    fun onWindowFocused() {
        val resume = pending ?: return
        cancel()
        resume()
    }

    fun cancel() {
        pending = null
    }

    private companion object {
        const val TAG = "Cv10UsbLaunch"
    }
}
