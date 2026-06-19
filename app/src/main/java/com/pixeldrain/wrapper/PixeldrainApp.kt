package com.pixeldrain.wrapper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.google.android.material.color.DynamicColors

/**
 * Application entry point. Creates the notification channel used by download
 * progress notifications once, at process start, and opts every activity into
 * Material You dynamic color on Android 12+ (no-op on older devices).
 */
class PixeldrainApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        val downloads = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.download_channel_desc) }

        val uploads = NotificationChannel(
            UPLOAD_CHANNEL_ID,
            getString(R.string.upload_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.upload_channel_desc) }

        manager.createNotificationChannel(downloads)
        manager.createNotificationChannel(uploads)
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "pixeldrain_downloads"
        const val UPLOAD_CHANNEL_ID = "pixeldrain_uploads"
    }
}
