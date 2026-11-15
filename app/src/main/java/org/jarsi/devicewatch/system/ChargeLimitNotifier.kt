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
    // 🎯 终极流氓大招：ID换成 v6！绕开所有之前的系统缓存！
    private const val CHANNEL_ID = "battery_full_channel_v6" 
    private const val NOTIFICATION_ID = 1003

    @SuppressLint("MissingPermission")
    fun show(context: Context, levelPercent: Int, playSound: Boolean, vibrate: Boolean): Boolean {
        if (!canPostNotifications(context)) return false
        
        createChannel(context, playSound, vibrate)

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 🎯 强制全部开启，不走系统设置
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(
                context.getString(R.string.charge_limit_notification_title, levelPercent)
            )
            .setContentText(context.getString(R.string.charge_limit_notification_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) 
            .setPriority(NotificationCompat.PRIORITY_MAX) // 最高优先级
            .setCategory(NotificationCompat.CATEGORY_ALARM) 
            .setDefaults(NotificationCompat.DEFAULT_ALL) // 强制声音+震动

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
            
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 200, 500) 
            
            if (playSound) {
                // 🎯 终极杀招：借用“媒体音量”（听歌的通道）！手机静音它也能响！
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC) // 音乐类型
                    .setUsage(AudioAttributes.USAGE_MEDIA) // 媒体通道！
                    .build()
                setSound(soundUri, audioAttributes)
            }
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
