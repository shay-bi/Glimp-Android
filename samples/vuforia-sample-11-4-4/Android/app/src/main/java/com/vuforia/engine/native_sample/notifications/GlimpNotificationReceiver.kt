package com.vuforia.engine.native_sample.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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

        val nm = NotificationManagerCompat.from(context)
        val channelId = "glimp_marketing"
        createChannel(context, channelId)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.applogo)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(id.hashCode(), notification)
        } catch (e: SecurityException) {
            // Permission might have been revoked
        }

        // Chaining: If this was a recurring reminder, schedule the next one
        if (id == "recurring_reminder") {
            val prefs = NotificationPrefsStore.load(context)
            if (prefs.allowReminderNotifications) {
                GlimpNotificationManager.scheduleRecurringReminders(context, prefs.reminderFrequency)
            }
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
