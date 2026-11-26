// SPDX-License-Identifier: BSL-1.0

package org.freedesktop.monado.openxr_runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Keeps the external display client running while the original Pocket XR app controls it. */
class Cv10PocketControlsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = getString(R.string.cv10_pocket_preview_title)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW),
        )
        val resume = Cv10PocketXrPreviewActivity.resumeIntent(this)
        val stop = Intent(STOP_ACTION).setPackage(packageName)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(getString(R.string.cv10_pocket_controls_notification))
            .setContentIntent(PendingIntent.getActivity(this, 0, resume, PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(
                null, getString(R.string.cv10_pocket_preview_stop),
                PendingIntent.getBroadcast(this, 0, stop, PendingIntent.FLAG_IMMUTABLE),
            ).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        internal const val STOP_ACTION = "org.cv10.monado.pocket.STOP_PREVIEW"
        private const val CHANNEL = "pocket_display_controls"
        private const val NOTIFICATION_ID = 52997

        internal fun intent(context: Context) = Intent(context, Cv10PocketControlsService::class.java)
    }
}
