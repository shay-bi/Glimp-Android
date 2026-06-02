package com.vuforia.engine.native_sample.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object AlarmScheduler {
    private const val ACTION = "com.vuforia.engine.native_sample.ACTION_SHOW_NOTIFICATION"

    fun scheduleExact(
        context: Context,
        triggerAtMillis: Long,
        requestCode: Int,
        notificationId: String,
        title: String,
        body: String
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingShowIntent(context, requestCode, notificationId, title, body)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setAlarmClock(
                        AlarmManager.AlarmClockInfo(triggerAtMillis, pi),
                        pi
                    )
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (e: SecurityException) {
            // Fallback for Android 14+ if exact alarm permission is revoked/missing
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context, requestCode: Int, notificationId: String, title: String, body: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingShowIntent(context, requestCode, notificationId, title, body)
        am.cancel(pi)
        pi.cancel()
    }

    private fun pendingShowIntent(
        context: Context,
        requestCode: Int,
        notificationId: String,
        title: String,
        body: String
    ): PendingIntent {
        val intent = Intent(context, GlimpNotificationReceiver::class.java).apply {
            action = ACTION
            putExtra("id", notificationId)
            putExtra("title", title)
            putExtra("body", body)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }
}
