// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object Cv10RuntimeBroker {
    const val POCKET_CLIENT_PACKAGE = "org.cv10.monado.pocket_client"
    private const val INSTALLABLE_BROKER = "org.khronos.openxr.runtime_broker"
    private const val SYSTEM_BROKER = "org.khronos.openxr.system_runtime_broker"
    private const val TAG = "Cv10RuntimeBroker"

    fun activePackage(context: Context): String? {
        // Match the Android OpenXR loader's installable-broker-first order.
        val abi = Build.SUPPORTED_ABIS.first()
        return listOf(INSTALLABLE_BROKER, SYSTEM_BROKER).firstNotNullOfOrNull { broker ->
            val uri = Uri.parse("content://$broker/openxr/1/abi/$abi/runtimes/active")
            try {
                context.contentResolver.query(uri, arrayOf("package_name"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (error: RuntimeException) {
                Log.d(TAG, "Could not read $broker", error)
                null
            }
        }
    }

    fun open(activity: Activity): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).setClassName(INSTALLABLE_BROKER, "$INSTALLABLE_BROKER.MainActivity")
        return try {
            activity.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "OpenXR Runtime Broker activity is unavailable", error)
            false
        }
    }
}

internal class Cv10DirectLaunch(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun select(app: Cv10AppEntry, launch: () -> Unit) {
        if (!app.isXr) {
            cancel()
            launch()
            return
        }
        if (pending != null) return
        val timeout = Runnable {
            pending = null
            launch()
        }
        pending = timeout
        main.postDelayed(timeout, BROKER_QUERY_TIMEOUT_MILLIS)
        val context = activity.applicationContext
        brokerWorker.execute {
            val selected = Cv10RuntimeBroker.activePackage(context)
            main.post {
                if (pending !== timeout) return@post
                cancel()
                if (selected == Cv10PocketHeadLinkService.POCKET_PACKAGE ||
                    selected == Cv10RuntimeBroker.POCKET_CLIENT_PACKAGE
                ) {
                    showPocketWarning(launch)
                } else {
                    launch()
                }
            }
        }
    }

    fun cancel() {
        pending?.let(main::removeCallbacks)
        pending = null
    }

    private fun showPocketWarning(launch: () -> Unit) {
        AlertDialog.Builder(activity)
            .setMessage(R.string.cv10_direct_pocket_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.cv10_launch_anyway) { _, _ -> launch() }
            .setPositiveButton(R.string.cv10_spatial_open_broker) { _, _ ->
                if (!Cv10RuntimeBroker.open(activity)) {
                    Toast.makeText(activity, R.string.cv10_spatial_broker_missing, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private companion object {
        private const val BROKER_QUERY_TIMEOUT_MILLIS = 1000L

        // A frozen broker must neither block the UI nor queue obsolete selections.
        private val brokerWorker = ThreadPoolExecutor(
            0, 1, 30, TimeUnit.SECONDS, SynchronousQueue(), ThreadPoolExecutor.DiscardPolicy(),
        )
    }
}
