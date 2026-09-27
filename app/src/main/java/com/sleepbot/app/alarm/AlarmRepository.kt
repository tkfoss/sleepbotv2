package com.sleepbot.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.tracking.Reminders
import com.sleepbot.app.util.Notifications
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Alarm storage (JSON in the private `alarms` SharedPreferences file) plus the pending-alarm
 * engine of the legacy AlarmClockService: computes each enabled alarm's next ring time, arms one
 * exact `setAlarmClock` per alarm, keeps the "next alarm" status notification and the smart-alarm
 * window alarm in sync.
 */
class AlarmRepository(context: Context) {
    private val ctx = context.applicationContext
    private val sp = ctx.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _alarms = MutableStateFlow(load())
    /** All alarms ordered by time of day (legacy list order). */
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _pending = MutableStateFlow<Map<Long, Long>>(emptyMap())
    /** alarm id → pending ring time (ms). */
    val pending: StateFlow<Map<Long, Long>> = _pending.asStateFlow()

    private val _next = MutableStateFlow<Long?>(null)
    /** Epoch ms of the earliest pending (enabled) alarm occurrence, or null. */
    val nextAlarm: StateFlow<Long?> = _next.asStateFlow()

    private val _defaults = MutableStateFlow(loadDefaults())
    val defaults: StateFlow<AlarmSettings> = _defaults.asStateFlow()

    private val refreshLock = Any()
    private var refreshing = false

    init {
        // Side-effect-free initial values; refresh() (MainActivity.onResume, boot, changes) arms alarms.
        val now = System.currentTimeMillis()
        val map = _alarms.value.mapNotNull { a -> pendingTime(a, now)?.let { a.id to it } }.toMap()
        _pending.value = map
        _next.value = map.values.minOrNull()
    }

    // ---- storage ------------------------------------------------------------------------

    private fun load(): List<Alarm> {
        val arr = runCatching { JSONArray(sp.getString("alarms", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { runCatching { Alarm.fromJson(arr.getJSONObject(it)) }.getOrNull() }
            .sortedBy { it.timeSec }
    }

    private fun loadDefaults(): AlarmSettings =
        sp.getString("defaults", null)?.let { runCatching { AlarmSettings.fromJson(JSONObject(it)) }.getOrNull() }
            ?: AlarmSettings()

    private fun save(list: List<Alarm>) {
        val sorted = list.sortedBy { it.timeSec }
        sp.edit { putString("alarms", JSONArray().apply { sorted.forEach { put(it.toJson()) } }.toString()) }
        _alarms.value = sorted
    }

    private inline fun mutate(block: (MutableList<Alarm>) -> Unit) {
        synchronized(lock) {
            val l = _alarms.value.toMutableList()
            block(l)
            save(l)
        }
    }

    private fun nextId(): Long = synchronized(lock) {
        val id = sp.getLong("next_id", 1).coerceAtLeast((_alarms.value.maxOfOrNull { it.id } ?: 0) + 1)
        sp.edit { putLong("next_id", id + 1) }
        id
    }

    fun get(id: Long): Alarm? = _alarms.value.firstOrNull { it.id == id }

    /** Effective settings: the alarm's own row, else the defaults (id −1). */
    fun settingsFor(id: Long): AlarmSettings =
        if (id == -1L) _defaults.value else get(id)?.settings ?: _defaults.value

    fun hasOwnSettings(id: Long) = get(id)?.settings != null

    fun saveDefaults(s: AlarmSettings) {
        sp.edit { putString("defaults", s.clamped().toJson().toString()) }
        _defaults.value = s.clamped()
    }

    // ---- actions (legacy AlarmClockService) --------------------------------------------

    /** Insert a new alarm; enabled alarms are scheduled immediately. */
    fun create(hour: Int, minute: Int, second: Int = 0, name: String = "", dow: Int = 0, enabled: Boolean = true): Alarm {
        val a = Alarm(nextId(), name, dow, hour * 3600 + minute * 60 + second, enabled)
        mutate { it.add(a) }
        refresh()
        return a
    }

    /** Replace an alarm row (edits from the settings screen). */
    fun update(alarm: Alarm) {
        mutate { l -> l.replaceAll { if (it.id == alarm.id) alarm else it } }
        refresh()
    }

    fun setSettings(id: Long, s: AlarmSettings?) {
        if (id == -1L) { s?.let { saveDefaults(it) }; return }
        mutate { l -> l.replaceAll { if (it.id == id) it.copy(settings = s?.clamped()) else it } }
    }

    /** scheduleAlarm / unscheduleAlarm. */
    fun setEnabled(id: Long, enabled: Boolean) {
        mutate { l -> l.replaceAll { if (it.id == id) it.copy(enabled = enabled, snoozeUntil = 0, ringing = false) else it } }
        refresh()
    }

    fun delete(id: Long) {
        mutate { l -> l.removeAll { it.id == id } }
        cancelSystemAlarm(id)
        refresh()
    }

    fun deleteAll() {
        val ids = _alarms.value.map { it.id }
        mutate { it.clear() }
        ids.forEach { cancelSystemAlarm(it) }
        refresh()
    }

    /** The alarm started ringing: take it out of the pending set until acknowledged/snoozed. */
    fun markRinging(id: Long) {
        mutate { l -> l.replaceAll { if (it.id == id) it.copy(ringing = true, snoozeUntil = 0) else it } }
        refresh()
    }

    /** Dismissed after ringing: re-arm a repeating alarm, else disable it. */
    fun acknowledge(id: Long) {
        mutate { l -> l.replaceAll { if (it.id == id) it.copy(ringing = false, snoozeUntil = 0, enabled = it.repeats && it.enabled) else it } }
        refresh()
    }

    /** snoozeAlarmFor: ring again at now (seconds zeroed) + [minutes]. Stored time unchanged. */
    fun snooze(id: Long, minutes: Int) {
        val t = (System.currentTimeMillis() / 60_000L) * 60_000L + minutes * 60_000L
        setOverride(id, t)
    }

    /** One-shot ring at [at] without touching the stored time (snooze / smart alarm). */
    fun setOverride(id: Long, at: Long) {
        mutate { l -> l.replaceAll { if (it.id == id) it.copy(ringing = false, enabled = true, snoozeUntil = at) else it } }
        refresh()
    }

    /** First-run presets (legacy hint Preset_Alarms): 08:30 Weekdays + 09:00 Weekends, disabled. */
    fun ensurePresets() {
        if (sp.getBoolean("presets_done", false)) return
        sp.edit { putBoolean("presets_done", true) }
        if (_alarms.value.isNotEmpty()) return
        mutate {
            it.add(Alarm(nextId(), "", Week.WEEKDAYS, 8 * 3600 + 30 * 60, false))
            it.add(Alarm(nextId(), "", Week.WEEKENDS, 9 * 3600, false))
        }
    }

    // ---- pending engine ------------------------------------------------------------------

    /** Pending ring time of [a] or null (disabled, ringing, or skipped smart occurrence). */
    fun pendingTime(a: Alarm, now: Long = System.currentTimeMillis()): Long? {
        if (!a.enabled || a.ringing) return null
        if (a.snoozeUntil > 0) return a.snoozeUntil
        val skip = ctx.app.prefs.smartFiredFor
        var t = a.nextOccurrence(now)
        if (skip > 0 && t == skip) {
            if (!a.repeats) return null
            t = a.nextOccurrence(t)
        }
        return t
    }

    /** Earliest pending (alarm, time). */
    fun nextPending(): Pair<Alarm, Long>? {
        val now = System.currentTimeMillis()
        return _alarms.value.mapNotNull { a -> pendingTime(a, now)?.let { a to it } }.minByOrNull { it.second }
    }

    /** Recompute [nextAlarm] and reschedule system alarms, status notification and smart window. */
    fun refresh() {
        // Serialized across threads; re-entrant calls (e.g. from Reminders) are ignored.
        synchronized(refreshLock) {
            if (refreshing) return
            refreshing = true
            try {
                doRefresh()
            } finally {
                refreshing = false
            }
        }
    }

    private fun doRefresh() {
        val prefs = ctx.app.prefs
        val now = System.currentTimeMillis()
        if (prefs.smartFiredFor in 1 until now - 60_000) prefs.smartFiredFor = 0
        // Ringing flags left over from a killed process count as acknowledged.
        if (!AlarmRingService.running && _alarms.value.any { it.ringing }) {
            mutate { l -> l.replaceAll { if (it.ringing) it.copy(ringing = false, enabled = it.repeats && it.enabled) else it } }
        }
        val map = LinkedHashMap<Long, Long>()
        for (a in _alarms.value) pendingTime(a, now)?.let { map[a.id] = it }

        // Cancel previously armed alarms no longer pending, arm the rest.
        val armed = sp.getString("armed", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        armed.filter { it !in map }.forEach { cancelSystemAlarm(it) }
        map.forEach { (id, t) -> armSystemAlarm(id, t) }
        sp.edit { putString("armed", map.keys.joinToString(",")) }

        _pending.value = map
        val old = _next.value
        _next.value = map.values.minOrNull()
        updateStatusNotification()
        armSmartWindow()
        if (old != _next.value) runCatching { Reminders.reschedule(ctx) }
    }

    private fun ringIntent(id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.toInt(),
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE)
                .setData(android.net.Uri.parse("alarm_id:$id")).putExtra(AlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun armSystemAlarm(id: Long, t: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, AlarmClockActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), ringIntent(id))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, ringIntent(id))
        }
    }

    private fun cancelSystemAlarm(id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(ringIntent(id))
    }

    /** Legacy refreshMovementServiceAlarm: start motion tracking at (next alarm − range). */
    private fun armSmartWindow() {
        val prefs = ctx.app.prefs
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, SmartWindowReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        val next = _next.value ?: return
        if (prefs.isAwake || !prefs.smartAlarm) return
        val now = System.currentTimeMillis()
        var t = next - prefs.smartWindowMin * 60_000L
        if (t < now + 5_000) t = now + 15_000
        if (t >= next) return
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    /** Legacy notification id 69 "SleepBot Alarm / Next alarm: …", refreshed each minute. */
    fun updateStatusNotification() {
        val nm = NotificationManagerCompat.from(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val tick = PendingIntent.getBroadcast(
            ctx, 3, Intent(ctx, StatusTickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val next = _next.value
        if (next == null || !ctx.app.prefs.alarmStatusIcon || !Notifications.canPost(ctx)) {
            nm.cancel(Notifications.ID_ALARM_STATUS)
            am.cancel(tick)
            return
        }
        val text = ctx.getString(
            R.string.alarm_status_text, TimeFormat.time(ctx, next), TimeFormat.countdown(ctx, next).trim(),
        )
        val open = PendingIntent.getActivity(
            ctx, 4, Intent(ctx, AlarmClockActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, Notifications.CH_ALARM_STATUS)
            .setSmallIcon(R.drawable.alarmclock_notification)
            .setContentTitle(ctx.getString(R.string.alarm_status_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        try { nm.notify(Notifications.ID_ALARM_STATUS, n) } catch (_: SecurityException) {}
        val nextMinute = (System.currentTimeMillis() / 60_000L + 1) * 60_000L
        am.set(AlarmManager.RTC, nextMinute, tick)
    }
}
