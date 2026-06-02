package com.vuforia.engine.native_sample.notifications

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.time.*
import java.time.temporal.TemporalAdjusters
import java.util.*

object GlimpNotificationManager {
    private const val TAG = "GlimpNotificationMgr"
    private const val PREFS_NAME = "glimp_notifications_internal"
    private const val KEY_SETUP_COMPLETED = "notifications_setup_completed"
    private const val KEY_FIRST_LAUNCH_DATE = "first_launch_date"

    fun setupNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Record first launch date if not exists
        if (!prefs.contains(KEY_FIRST_LAUNCH_DATE)) {
            prefs.edit().putLong(KEY_FIRST_LAUNCH_DATE, System.currentTimeMillis()).apply()
        }

        if (prefs.getBoolean(KEY_SETUP_COMPLETED, false)) {
            Log.d(TAG, "Notifications already setup.")
            return
        }

        scheduleAllNotifications(context)
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
    }

    fun rescheduleNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, false).apply()
        
        cancelAllNotifications(context)
        scheduleAllNotifications(context)
        
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
    }

    fun cancelAllNotifications(context: Context) {
        Log.d(TAG, "Cancelling all notifications and alarms")
        
        // Cancel active notifications
        NotificationManagerCompat.from(context).cancelAll()

        // Cancel scheduled alarms
        // Initial ones
        AlarmScheduler.cancel(context, 1001, "initial_24h", "", "")
        AlarmScheduler.cancel(context, 1002, "initial_7d", "", "")
        
        // Recurring
        AlarmScheduler.cancel(context, 2001, "recurring_reminder", "", "")
        
        // Holidays (up to 20)
        for (i in 0 until 20) {
            AlarmScheduler.cancel(context, 3000 + i, "", "", "")
        }
    }

    private fun scheduleAllNotifications(context: Context) {
        val prefs = NotificationPrefsStore.load(context)
        
        scheduleInitialNotifications(context)
        
        if (prefs.allowReminderNotifications) {
            scheduleRecurringReminders(context, prefs.reminderFrequency)
        }
        
        if (prefs.allowHolidayNotifications) {
            scheduleOptimizedHolidayNotifications(context)
        }
    }

    private fun scheduleInitialNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH_DATE, System.currentTimeMillis())

        // 24 hours later
        val trigger24h = firstLaunch + 24 * 60 * 60 * 1000
        if (trigger24h > System.currentTimeMillis()) {
            AlarmScheduler.scheduleExact(
                context, trigger24h, 1001, "initial_24h",
                "Ready to explore?", "Come back and see what's new in AR!"
            )
        }

        // 7 days later
        val trigger7d = firstLaunch + 7 * 24 * 60 * 60 * 1000
        if (trigger7d > System.currentTimeMillis()) {
            AlarmScheduler.scheduleExact(
                context, trigger7d, 1002, "initial_7d",
                "We miss you!", "Check out the latest AR experiences."
            )
        }
    }

    internal fun scheduleRecurringReminders(context: Context, frequency: ReminderFrequency) {
        if (frequency == ReminderFrequency.OFF) return

        val now = LocalDateTime.now()
        // Schedule for the 1st of the month at 14:00
        var nextTrigger = now.withDayOfMonth(1).withHour(14).withMinute(0).withSecond(0).withNano(0)
        
        if (!nextTrigger.isAfter(now)) {
            val monthsToAdd = when(frequency) {
                ReminderFrequency.MONTHLY -> 1L
                ReminderFrequency.QUARTERLY -> 3L
                ReminderFrequency.BIANNUALLY -> 6L
                else -> 1L
            }
            nextTrigger = nextTrigger.plusMonths(monthsToAdd)
        }

        val triggerMillis = nextTrigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        AlarmScheduler.scheduleExact(
            context, triggerMillis, 2001, "recurring_reminder",
            "Monthly Update", "Time to check your AR targets!"
        )
    }

    private fun scheduleOptimizedHolidayNotifications(context: Context) {
        val currentYear = LocalDate.now().year
        val holidays = getHolidaysForYear(currentYear) + getHolidaysForYear(currentYear + 1)
        val now = LocalDateTime.now()
        
        holidays
            .filter { it.date.isAfter(now) }
            .sortedBy { it.date }
            .take(20) // Cap like iOS
            .forEachIndexed { index, holiday ->
                val triggerMillis = holiday.date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                AlarmScheduler.scheduleExact(
                    context, triggerMillis, 3000 + index, "holiday_${holiday.name}",
                    holiday.title, holiday.body
                )
            }
    }

    data class Holiday(val name: String, val date: LocalDateTime, val title: String, val body: String)

    private fun getHolidaysForYear(year: Int): List<Holiday> {
        val list = mutableListOf<Holiday>()
        
        // New Year's
        list.add(Holiday("new_year", LocalDateTime.of(year, 1, 1, 10, 0), "Happy New Year!", "Start your year with some AR magic."))
        
        // Black Friday (Friday after 4th Thursday of Nov)
        try {
            val nov4thThursday = LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
            val blackFriday = nov4thThursday.plusDays(1).atTime(9, 0)
            list.add(Holiday("black_friday", blackFriday, "Black Friday Deals", "Check out special AR content for today!"))
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating Black Friday for $year", e)
        }

        // Christmas
        list.add(Holiday("christmas", LocalDateTime.of(year, 12, 25, 10, 0), "Merry Christmas!", "Enjoy these festive AR models."))

        return list
    }
}
