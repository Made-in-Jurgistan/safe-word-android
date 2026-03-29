package com.safeword.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.safeword.android.MainActivity
import com.safeword.android.R
import timber.log.Timber

/**
 * RecordingService — foreground service for microphone access.
 * Required on Android 10+ to record audio when app is in background.
 * Mirrors the always-available recording capability of desktop Safe Word.
 */
class RecordingService : Service() {

    companion object {
        const val CHANNEL_ID = "safeword_recording"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("[LIFECYCLE] RecordingService.onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("[SERVICE] RecordingService.onStartCommand | startId=%d flags=%d", startId, flags)
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Timber.d("[SERVICE] RecordingService startForeground | notificationId=%d channelId=%s", NOTIFICATION_ID, CHANNEL_ID)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        Timber.d("[SERVICE] RecordingService.onBind")
        return null
    }

    override fun onDestroy() {
        Timber.i("[LIFECYCLE] RecordingService.onDestroy")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        Timber.d("[INIT] RecordingService notification channel created | channelId=%s", CHANNEL_ID)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setSmallIcon(com.safeword.android.R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
