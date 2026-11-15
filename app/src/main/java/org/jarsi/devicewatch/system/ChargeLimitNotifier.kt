package org.jarsi.devicewatch.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.jarsi.devicewatch.MainActivity
import org.jarsi.devicewatch.R

object ChargeLimitNotifier {
    private const val CHANNEL_ID = "battery_full_channel_v3" // 🎯 再换个ID
    private const val NOTIFICATION_ID = 1003

    @SuppressLint("MissingPermission")
    fun show(context: Context, levelPercent: Int, playSound: Boolean, vibrate: Boolean): Boolean {
        if (!canPostNotifications(context)) return false
        createChannel(context)
        if (!canReachUser(context)) return false

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                context.getString(R.string.charge_limit_notification_title, levelPercent)
            )
            .setContentText(context.getString(R.string.charge_limit_notification_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // 🎯 根据开关状态，决定是否响铃和震动
        when {
            playSound && vibrate -> builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            playSound -> builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
            vibrate -> builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            else -> builder.setDefaults(0) // 静音且不震动
        }

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canReachUser(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        return NotificationDelivery.canReachUser(
            appNotificationsEnabled = manager.areNotificationsEnabled(),
            channelImportance = manager.getNotificationChannel(CHANNEL_ID)?.importance,
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.battery_full_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.battery_full_channel_description)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
