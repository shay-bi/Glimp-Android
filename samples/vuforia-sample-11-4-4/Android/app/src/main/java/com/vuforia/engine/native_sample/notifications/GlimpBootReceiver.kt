package com.vuforia.engine.native_sample.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class GlimpBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("GlimpBootReceiver", "Boot completed, rescheduling notifications")
            GlimpNotificationManager.rescheduleNotifications(context)
        }
    }
}
