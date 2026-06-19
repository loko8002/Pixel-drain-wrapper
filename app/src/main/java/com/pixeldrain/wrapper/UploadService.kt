package com.pixeldrain.wrapper

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * A short-lived foreground service that runs only while the WebView has one or
 * more file uploads in flight. Its sole job is to keep the app's process alive
 * (and out of the frozen "cached" state) so the in-page JavaScript upload can
 * finish even when the user leaves the app. It does no work itself.
 *
 * On Android 14+ it declares the `dataSync` foreground-service type, which is the
 * correct category for user-initiated file transfers.
 */
class UploadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tapIntent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, PixeldrainApp.UPLOAD_CHANNEL_ID)
            .setContentTitle(getString(R.string.upload_notif_title))
            .setContentText(getString(R.string.upload_notif_text))
            .setSmallIcon(R.drawable.ic_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // If the system kills us mid-upload we don't want an automatic restart with
        // no upload to guard — the activity re-arms the service when needed.
        return START_NOT_STICKY
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, UploadService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UploadService::class.java))
        }
    }
}
