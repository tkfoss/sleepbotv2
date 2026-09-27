package com.sleepbot.app.data

import android.content.Context
import android.content.SharedPreferences
import android.text.format.DateFormat
import androidx.core.content.edit
import com.sleepbot.app.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * App preferences. Keys are descriptive rather than the legacy numeric ids; the legacy id is
 * noted next to each for cross-reference with the 3.2.8 decompile.
 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    val sp: SharedPreferences = app.getSharedPreferences("sleepbot", Context.MODE_PRIVATE)

    /** "8": >0 = punched in at that ms; <=0 = awake, value is -(last punch-out). */
    var sleepState: Long
        get() = sp.getLong("sleep_state", 0)
        set(v) = sp.edit { putLong("sleep_state", v) }

    /** "8_asToken": links the hours row with its sensor records. */
    var punchToken: Long
        get() = sp.getLong("punch_token", 0)
        set(v) = sp.edit { putLong("punch_token", v) }

    val isAwake: Boolean get() = sleepState <= 0

    /** "44" */
    var lastPunchOut: Long
        get() = sp.getLong("last_punch_out", 0)
        set(v) = sp.edit { putLong("last_punch_out", v) }

    /** "63" / "61" / "62" */
    var smartAlarm: Boolean
        get() = sp.getBoolean("smart_alarm", false)
        set(v) = sp.edit { putBoolean("smart_alarm", v) }
    var trackMotion: Boolean
        get() = sp.getBoolean("track_motion", false)
        set(v) = sp.edit { putBoolean("track_motion", v) }
    var recordSound: Boolean
        get() = sp.getBoolean("record_sound", false)
        set(v) = sp.edit { putBoolean("record_sound", v) }

    /** "1": optimal sleep hours. */
    val optimalHours: Float get() = str("optimal_hours", "8").toFloatOrNull() ?: 8f
    /** "41" */
    val debtRangeDays: Int get() = str("debt_range", "10").toIntOrNull() ?: 10
    /** "25" */
    var debtResetTime: Long
        get() = sp.getLong("debt_reset_time", 0)
        set(v) = sp.edit { putLong("debt_reset_time", v) }
    /** "75": smart alarm window, minutes. */
    val smartWindowMin: Int get() = str("smart_window", "30").toIntOrNull() ?: 30
    /** "73": movement sensitivity index 0..4. */
    val motionSensitivity: Int get() = str("motion_sensitivity", "2").toIntOrNull() ?: 2
    /** "74": sound sensitivity index 0..4 (0 = least sensitive, labels fixed vs. legacy). */
    val soundSensitivity: Int get() = str("sound_sensitivity", "2").toIntOrNull() ?: 2
    /** "24": fall-asleep offset minutes subtracted... added to the punch-in time at punch-out. */
    val punchInDelayMin: Int get() = str("punch_in_delay", "0").toIntOrNull() ?: 0
    /** "45": re-punch guard seconds. */
    val repunchGuardMs: Long get() = ((str("repunch_guard", "1.0").toDoubleOrNull() ?: 1.0) * 1000).toLong()
    /** "21": 0 = always, 1 = while asleep, 2 = off. */
    val notificationMode: Int get() = str("notification_mode", "1").toIntOrNull() ?: 1
    /** "77" / "78" */
    val showAltNumber: Boolean get() = sp.getBoolean("show_alt_number", true)
    var homeShowsDebt: Boolean
        get() = str("home_display", "today") == "debt"
        set(v) = sp.edit { putString("home_display", if (v) "debt" else "today") }
    /** "81" */
    val decimalHours: Boolean get() = str("entry_hour_format", "decimal") == "decimal"
    /** "68" */
    val showDisconnectedLines: Boolean get() = sp.getBoolean("disconnected_lines", true)
    /** "55" */
    var graphRatings: Boolean
        get() = sp.getBoolean("graph_ratings", true)
        set(v) = sp.edit { putBoolean("graph_ratings", v) }
    /** "26": 0 trend, 1 pattern. */
    val overviewGraph: Int get() = str("overview_graph", "0").toIntOrNull() ?: 0
    /** "79" */
    val screenOffTracking: Boolean get() = sp.getBoolean("screen_off_tracking", true)
    /** "82" */
    val hideChargingWarning: Boolean get() = sp.getBoolean("hide_charging_warning", false)
    /** "56" */
    val autoAlarm: Boolean get() = sp.getBoolean("auto_alarm", false)
    /** "85"-"90" bedtime reminders. */
    val reminder1: Boolean get() = sp.getBoolean("reminder1", false)
    val reminder2: Boolean get() = sp.getBoolean("reminder2", false)
    val reminder1Offset: Int get() = str("reminder1_offset", "30").toIntOrNull() ?: 30
    val reminder2Offset: Int get() = str("reminder2_offset", "-15").toIntOrNull() ?: -15
    val reminderMuted: Boolean get() = sp.getBoolean("reminder_muted", false)
    /** "22" / "23" */
    val sleepNotificationText: String get() = str("sleep_notification_text", app.getString(R.string.pref_sleep_notification_default))
    val wakeNotificationText: String get() = str("wake_notification_text", app.getString(R.string.pref_awake_notification_default))
    /** "40" */
    val allowIntegration: Boolean get() = sp.getBoolean("allow_integration", true)

    /** Smart-alarm bookkeeping: occurrence (ms) of the alarm that was fired early, to be skipped. */
    var smartFiredFor: Long
        get() = sp.getLong("smart_fired_for", 0)
        set(v) = sp.edit { putLong("smart_fired_for", v) }

    var tutorialSeen: Boolean
        get() = sp.getBoolean("tutorial_seen", false)
        set(v) = sp.edit { putBoolean("tutorial_seen", v) }
    var firstEntryHintSeen: Boolean
        get() = sp.getBoolean("first_entry_hint", false)
        set(v) = sp.edit { putBoolean("first_entry_hint", v) }

    fun is24h(): Boolean = DateFormat.is24HourFormat(app)

    /** Legacy Preferences.getDateFormat: system date order → dd/MM/yy etc. */
    fun dateFormat(): String {
        val order = DateFormat.getDateFormatOrder(app).joinToString("")
        return when {
            order.startsWith("dM") -> "dd/MM/yy"
            order.startsWith("dy") -> "dd/yy/MM"
            order.startsWith("My") -> "MM/yy/dd"
            order.startsWith("yM") -> "yy/MM/dd"
            order.startsWith("yd") -> "yy/dd/MM"
            else -> "MM/dd/yy"
        }
    }

    fun shortDateFormat(): String = dateFormat().let { if (it.startsWith("MM") || it.startsWith("dd")) it.take(5) else it.substring(3) }
    fun dayFirst(): Boolean = dateFormat().startsWith("dd")

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def

    fun changes(): Flow<String?> = callbackFlow {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> trySend(k) }
        sp.registerOnSharedPreferenceChangeListener(l)
        trySend(null)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
}
