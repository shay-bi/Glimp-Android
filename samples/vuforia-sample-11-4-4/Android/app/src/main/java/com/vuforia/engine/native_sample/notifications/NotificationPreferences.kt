package com.vuforia.engine.native_sample.notifications

import org.json.JSONObject

enum class ReminderFrequency(val key: String, val intervalDays: Int, val displayName: String) {
    MONTHLY("monthly", 30, "Monthly"),
    QUARTERLY("quarterly", 90, "Every 3 Months"),
    BIANNUALLY("biannually", 180, "Every 6 Months"),
    OFF("off", 0, "Off");

    companion object {
        fun fromKey(k: String) = entries.find { it.key == k } ?: MONTHLY
    }
}

data class NotificationPreferences(
    var allowHolidayNotifications: Boolean = true,
    var allowReminderNotifications: Boolean = true,
    var reminderFrequency: ReminderFrequency = ReminderFrequency.MONTHLY
)

object NotificationPrefsStore {
    private const val PREF = "notification_preferences"
    private const val KEY_JSON = "json"

    fun load(ctx: android.content.Context): NotificationPreferences {
        val sp = ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
        val s = sp.getString(KEY_JSON, null) ?: return NotificationPreferences()
        return try {
            val o = org.json.JSONObject(s)
            NotificationPreferences(
                allowHolidayNotifications = o.optBoolean("allowHolidayNotifications", true),
                allowReminderNotifications = o.optBoolean("allowReminderNotifications", true),
                reminderFrequency = ReminderFrequency.fromKey(o.optString("reminderFrequency", "monthly"))
            )
        } catch (_: Exception) {
            NotificationPreferences()
        }
    }

    fun save(ctx: android.content.Context, p: NotificationPreferences) {
        val o = JSONObject().apply {
            put("allowHolidayNotifications", p.allowHolidayNotifications)
            put("allowReminderNotifications", p.allowReminderNotifications)
            put("reminderFrequency", p.reminderFrequency.key)
        }
        ctx.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, o.toString()).apply()
    }
}
