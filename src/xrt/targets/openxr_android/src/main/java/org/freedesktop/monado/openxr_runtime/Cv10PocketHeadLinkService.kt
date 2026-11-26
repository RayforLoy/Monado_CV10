// SPDX-License-Identifier: BSL-1.0
package org.freedesktop.monado.openxr_runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import android.widget.Toast
import java.net.InetAddress
import java.net.NetworkInterface

class Cv10PocketHeadLinkService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var headLink: Cv10PocketHeadLink? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            release()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action != START || !BuildConfig.cv10LauncherPreview || BuildConfig.inProcess) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (headLink != null) return START_NOT_STICKY
        try {
            startForeground(NOTIFICATION_ID, notification())
            check(prepare(this) == null) { "Android VPN access was revoked" }
            check(!vpnVisible(this)) { "Another Android VPN is active" }
            val address = InetAddress.getByName(VPN_ADDRESS)
            check(NetworkInterface.getByInetAddress(address) == null) { "$VPN_ADDRESS is already assigned" }

            // Publish the fixed headset address on this phone. No TUN forwarding, DNS, or default route.
            tunnel = checkNotNull(
                Builder()
                    .setSession(getString(R.string.cv10_pocket_head_link))
                    .addAllowedApplication(POCKET_PACKAGE)
                    .addAddress(address, 32)
                    .allowFamily(OsConstants.AF_INET6)
                    .allowBypass()
                    .establish(),
            ) { "Android did not open the local interface" }
            check(NetworkInterface.getByInetAddress(address) != null) { "Android did not assign $VPN_ADDRESS" }
            headLink = Cv10PocketHeadLink(address) { Log.i(TAG, it) }
            Log.i(TAG, "Listening on $VPN_ADDRESS/32 for local Pocket XR head tracking")
        } catch (error: Exception) {
            Log.w(TAG, "Pocket XR head link did not start", error)
            Toast.makeText(this, R.string.cv10_pocket_head_link_unavailable, Toast.LENGTH_LONG).show()
            release()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Handler(Looper.getMainLooper()).post {
            release()
            stopSelf()
        }
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        headLink?.close()
        headLink = null
        runCatching { tunnel?.close() }.onFailure { Log.w(TAG, "Could not close the local interface", it) }
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(): Notification {
        val label = getString(R.string.cv10_pocket_head_link)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, label, NotificationManager.IMPORTANCE_LOW),
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(label)
            .setContentText(getString(R.string.cv10_pocket_head_link_running))
            .setOngoing(true)
            .build()
    }

    companion object {
        internal const val POCKET_PACKAGE = "com.hayeco.flipspaces"
        private const val START = "org.cv10.monado.pocket.HEAD_LINK"
        private const val STOP = "org.cv10.monado.pocket.STOP_HEAD_LINK"
        private const val VPN_ADDRESS = "169.254.2.1"
        private const val TAG = "Cv10PocketHeadLink"
        private const val CHANNEL = "pocket_head_link"
        private const val NOTIFICATION_ID = 52998

        @Suppress("DEPRECATION")
        internal fun vpnVisible(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            return manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }

        internal fun intent(context: Context) = Intent(context, Cv10PocketHeadLinkService::class.java).setAction(START)

        internal fun stop(context: Context) {
            // Android keeps the service bound until its VPN interface closes.
            val request = Intent(context, Cv10PocketHeadLinkService::class.java).setAction(STOP)
            try {
                context.startService(request)
            } catch (error: IllegalStateException) {
                Log.d(TAG, "VPN stop command was rejected", error)
                context.stopService(request)
            }
        }
    }
}
