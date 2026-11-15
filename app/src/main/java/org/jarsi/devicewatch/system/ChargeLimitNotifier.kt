package org.jarsi.devicewatch.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.jarsi.devicewatch.MainActivity
import org.jarsi.devicewatch.R

object ChargeLimitNotifier {
    // 🎯 核心大招：ID换成了 v4！这样系统会把咱们当成一个全新的通道，绝不会继承你之前设置为“无声”的烂设置！
    private const val CHANNEL_ID = "battery_full_channel_v4" 
    private const val NOTIFICATION_ID = 1003

    @SuppressLint("MissingPermission")
    fun show(context: Context, levelPercent: Int, playSound: Boolean, vibrate: Boolean): Boolean {
        if (!canPostNotifications(context)) return false
        
        // 强行创建全新的带声音和震动的通道
        createChannel(context, playSound, vibrate)

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
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 最高优先级，保证弹出来
            .setCategory(NotificationCompat.CATEGORY_ALARM) // 伪装成闹钟，防止被系统深度休眠屏蔽

        // 兼容老的 setDefaults 写法
        when {
            playSound && vibrate -> builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            playSound -> builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
            vibrate -> builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            else -> builder.setDefaults(0) 
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

    private fun createChannel(context: Context, playSound: Boolean, vibrate: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.battery_full_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.battery_full_channel_description)
            
            // 🎯 强制写死震动！
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500) // 震一下，停一下，再震一下
            
            // 🎯 强制写死声音！
            if (playSound) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
