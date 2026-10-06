package com.myfriendkennyagent.museportal.store

import android.content.Context
import java.util.Calendar

/** User preferences, kept in SharedPreferences. */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

  /** The name Muse uses for this device. */
  var displayName: String
    get() = prefs.getString("display_name", null) ?: "Portal"
    set(value) = prefs.edit().putString("display_name", value.trim().ifEmpty { "Portal" }).apply()

  /** Speak Muse's own messages (late task results, check-ins) when no turn is active. */
  var announcements: Boolean
    get() = prefs.getBoolean("announcements", true)
    set(value) = prefs.edit().putBoolean("announcements", value).apply()

  /** Quiet hours, as hours of the day; equal values turn them off. */
  var quietStartHour: Int
    get() = prefs.getInt("quiet_start", 22)
    set(value) = prefs.edit().putInt("quiet_start", value.coerceIn(0, 23)).apply()

  var quietEndHour: Int
    get() = prefs.getInt("quiet_end", 7)
    set(value) = prefs.edit().putInt("quiet_end", value.coerceIn(0, 23)).apply()

  /** Speech rate for replies, 1.0 = normal. */
  var speechRate: Float
    get() = prefs.getFloat("speech_rate", 1.0f)
    set(value) = prefs.edit().putFloat("speech_rate", value.coerceIn(0.5f, 2.0f)).apply()

  /** Speak replies aloud; off means captions only. */
  var speakReplies: Boolean
    get() = prefs.getBoolean("speak_replies", true)
    set(value) = prefs.edit().putBoolean("speak_replies", value).apply()

  /** Post to a side chat instead of the main chat (a fixed id per device), or null. */
  var sideChatId: String?
    get() = prefs.getString("side_chat", null)
    set(value) = prefs.edit().putString("side_chat", value).apply()

  fun inQuietHours(now: Calendar = Calendar.getInstance()): Boolean {
    val start = quietStartHour
    val end = quietEndHour
    if (start == end) return false
    val h = now.get(Calendar.HOUR_OF_DAY)
    return if (start < end) h in start until end else h >= start || h < end
  }
}
