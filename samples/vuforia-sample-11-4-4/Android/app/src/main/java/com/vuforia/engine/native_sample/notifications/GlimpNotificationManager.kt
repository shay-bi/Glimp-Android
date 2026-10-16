package com.vuforia.engine.native_sample.notifications

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

object GlimpNotificationManager {
    private const val TAG = "GlimpNotificationMgr"
    private const val PREFS_NAME = "glimp_notifications_internal"
    private const val KEY_SETUP_COMPLETED = "notifications_setup_photobook_v1"
    private const val KEY_FIRST_LAUNCH_DATE = "first_launch_date"

    private val monthlyMessages = listOf(
        "Scan. Watch. Relive." to "Your photobook is on the table. When you are ready for the next one, start it at glimp.app.",
        "A cover for every story" to "Every story deserves a hardcover. Design another book at glimp.app.",
        "The trip that replays" to "Turn this year's photos into a photobook you can scan and watch again.",
        "A hardcover for the table" to "Not another folder on the phone. Start a new Glimp at glimp.app."
    )

    fun setupNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

        NotificationManagerCompat.from(context).cancelAll()

        AlarmScheduler.cancel(context, 1001, "initial_24h", "", "")
        AlarmScheduler.cancel(context, 1002, "initial_7d", "", "")
        AlarmScheduler.cancel(context, 2001, "recurring_reminder", "", "")

        for (i in 0 until 20) {
            AlarmScheduler.cancel(context, 3000 + i, "", "", "")
        }
    }

    private fun scheduleAllNotifications(context: Context) {
        val prefs = NotificationPrefsStore.load(context)

        if (prefs.allowReminderNotifications) {
            scheduleInitialNotifications(context)
            scheduleRecurringReminders(context, prefs.reminderFrequency)
        }

        if (prefs.allowHolidayNotifications) {
            scheduleOptimizedHolidayNotifications(context)
        }
    }

    private fun scheduleInitialNotifications(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH_DATE, System.currentTimeMillis())

        val trigger24h = firstLaunch + 24 * 60 * 60 * 1000
        if (trigger24h > System.currentTimeMillis()) {
            AlarmScheduler.scheduleExact(
                context, trigger24h, 1001, "initial_24h",
                "Scan. Watch. Relive.",
                "Your photobook is on the table. Open a page when you miss it, or start the next one at glimp.app."
            )
        }

        val trigger7d = firstLaunch + 7 * 24 * 60 * 60 * 1000
        if (trigger7d > System.currentTimeMillis()) {
            AlarmScheduler.scheduleExact(
                context, trigger7d, 1002, "initial_7d",
                "A cover for every story",
                "Every story deserves a hardcover. Design the next photobook at glimp.app."
            )
        }
    }

    internal fun scheduleRecurringReminders(context: Context, frequency: ReminderFrequency) {
        if (frequency == ReminderFrequency.OFF) return

        val now = LocalDateTime.now()
        var nextTrigger = now.withDayOfMonth(1).withHour(14).withMinute(0).withSecond(0).withNano(0)

        if (!nextTrigger.isAfter(now)) {
            val monthsToAdd = when (frequency) {
                ReminderFrequency.MONTHLY -> 1L
                ReminderFrequency.QUARTERLY -> 3L
                ReminderFrequency.BIANNUALLY -> 6L
                else -> 1L
            }
            nextTrigger = nextTrigger.plusMonths(monthsToAdd)
        }

        val message = monthlyMessages.random()
        val triggerMillis = nextTrigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        AlarmScheduler.scheduleExact(
            context, triggerMillis, 2001, "recurring_reminder",
            message.first, message.second
        )
    }

    private fun scheduleOptimizedHolidayNotifications(context: Context) {
        val currentYear = LocalDate.now().year
        val holidays = (holidayTemplates(currentYear) + holidayTemplates(currentYear + 1))
            .sortedByDescending { it.priority }
        val now = LocalDateTime.now()

        holidays
            .mapNotNull { it.toNotificationTime() }
            .filter { it.date.isAfter(now) }
            .take(20)
            .forEachIndexed { index, holiday ->
                val triggerMillis = holiday.date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                AlarmScheduler.scheduleExact(
                    context, triggerMillis, 3000 + index, "holiday_${holiday.name}",
                    holiday.title, holiday.body
                )
            }
    }

    private data class HolidayTemplate(
        val name: String,
        val title: String,
        val body: String,
        val daysBeforeToNotify: Long,
        val priority: Int,
        val holidayDate: LocalDate
    ) {
        fun toNotificationTime(): Holiday? {
            val notifyDate = holidayDate.minusDays(daysBeforeToNotify).atTime(14, 0)
            return Holiday(name, notifyDate, title, body)
        }
    }

    data class Holiday(val name: String, val date: LocalDateTime, val title: String, val body: String)

    private fun holidayTemplates(year: Int): List<HolidayTemplate> {
        return listOf(
            HolidayTemplate(
                name = "Christmas",
                title = "A photobook they can open again",
                body = "This year, give a hardcover they can hold, and scan to watch the moment play. Start one at glimp.app.",
                daysBeforeToNotify = 10,
                priority = 10,
                holidayDate = LocalDate.of(year, 12, 25)
            ),
            HolidayTemplate(
                name = "Valentine",
                title = "A story you can open again",
                body = "A photobook they can hold, and scan when they miss it. Design one at glimp.app.",
                daysBeforeToNotify = 7,
                priority = 9,
                holidayDate = LocalDate.of(year, 2, 14)
            ),
            HolidayTemplate(
                name = "BlackFriday",
                title = "A hardcover for the table",
                body = "A hardcover for the table, not another folder on the phone. Start a new photobook at glimp.app.",
                daysBeforeToNotify = 3,
                priority = 8,
                holidayDate = blackFriday(year)
            ),
            HolidayTemplate(
                name = "MotherDay",
                title = "The days worth keeping",
                body = "A photobook for Mom. Photos she can hold, videos she can watch again. Start one at glimp.app.",
                daysBeforeToNotify = 7,
                priority = 7,
                holidayDate = mothersDay(year)
            ),
            HolidayTemplate(
                name = "FatherDay",
                title = "The trip that replays",
                body = "A photobook for Dad. Photos he can hold, videos he can watch again. Design the next one at glimp.app.",
                daysBeforeToNotify = 7,
                priority = 6,
                holidayDate = fathersDay(year)
            ),
            HolidayTemplate(
                name = "NewYear",
                title = "The days worth keeping",
                body = "A new year. A new photobook. Design the next one at glimp.app.",
                daysBeforeToNotify = 5,
                priority = 3,
                holidayDate = LocalDate.of(year, 1, 1)
            )
        )
    }

    private fun blackFriday(year: Int): LocalDate {
        val thanksgiving = LocalDate.of(year, 11, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY))
        return thanksgiving.plusDays(1)
    }

    private fun mothersDay(year: Int): LocalDate {
        return LocalDate.of(year, 5, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.SUNDAY))
    }

    private fun fathersDay(year: Int): LocalDate {
        return LocalDate.of(year, 6, 1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.SUNDAY))
    }
}
