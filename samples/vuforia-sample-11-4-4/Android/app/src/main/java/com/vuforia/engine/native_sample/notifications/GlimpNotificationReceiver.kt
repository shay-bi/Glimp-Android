package com.vuforia.engine.native_sample.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vuforia.engine.native_sample.R

class GlimpNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title") ?: return
        val body = intent.getStringExtra("body") ?: return

        if (id == "recurring_reminder") {
            val prefs = NotificationPrefsStore.load(context)
            if (prefs.allowReminderNotifications) {
                GlimpNotificationManager.scheduleRecurringReminders(context, prefs.reminderFrequency)
            }
        }

        if (GlimpAppForeground.isForeground) {
            return
        }

        val channelId = "glimp_marketing"
        createChannel(context, channelId)

        val shopIntent = Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.shop_url))).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(context, id.hashCode(), shopIntent, flags)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.applogo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission might have been revoked
        }
    }

    private fun createChannel(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Reminders"
            val descriptionText = "App reminders and holiday notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
