package com.example.noisedetector.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.noisedetector.MainActivity
import com.example.noisedetector.R

object AppNotifications {

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)

        val listener = NotificationChannel(
            NotificationChannels.LISTENER,
            context.getString(R.string.channel_listener_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_listener_desc)
            setShowBadge(false)
        }

        val monitor = NotificationChannel(
            NotificationChannels.CONTROLLER_MONITOR,
            context.getString(R.string.channel_monitor_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_monitor_desc)
            setShowBadge(false)
        }

        val alert = NotificationChannel(
            NotificationChannels.CONTROLLER_ALERT,
            context.getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_alert_desc)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            setBypassDnd(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(false)
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setSound(null, attrs)
            }
        }

        nm.createNotificationChannel(listener)
        nm.createNotificationChannel(monitor)
        nm.createNotificationChannel(alert)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    fun listenerForeground(
        context: Context,
        clientCount: Int,
        db: Float
    ): Notification {
        val open = openAppPendingIntent(context)
        val text = context.getString(R.string.listener_notif_text, db, clientCount)
        return NotificationCompat.Builder(context, NotificationChannels.LISTENER)
            .setContentTitle(context.getString(R.string.listener_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun controllerForeground(context: Context, summaryLine: String, db: Float): Notification {
        val open = openAppPendingIntent(context)
        val text = context.getString(R.string.controller_notif_text, summaryLine, db)
        return NotificationCompat.Builder(context, NotificationChannels.CONTROLLER_MONITOR)
            .setContentTitle(context.getString(R.string.controller_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun thresholdAlert(context: Context, db: Float, threshold: Float) {
        ensureChannels(context)
        val open = openAppPendingIntent(context)
        val text = context.getString(R.string.alert_notif_text, db, threshold)
        val n = NotificationCompat.Builder(context, NotificationChannels.CONTROLLER_ALERT)
            .setContentTitle(context.getString(R.string.alert_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 600))
            .setOnlyAlertOnce(false)
            .build()
        NotificationManagerCompat.from(context).notify(NotificationIds.ALERT, n)
    }
}
