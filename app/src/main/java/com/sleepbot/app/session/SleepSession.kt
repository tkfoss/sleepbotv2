package com.sleepbot.app.session

import android.content.Context
import android.content.Intent
import com.sleepbot.app.alarm.AlarmScheduler
import com.sleepbot.app.app
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.tracking.Reminders
import com.sleepbot.app.tracking.TrackingService
import com.sleepbot.app.util.Notifications
import com.sleepbot.app.widget.SleepBotWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone

/** Result of a punch-out, used by the "Sleep Entry Created!" dialog. */
data class PunchOutResult(val entryId: Long, val sleep: Long, val awake: Long)

sealed interface PunchResult {
    data object PunchedIn : PunchResult
    data class PunchedOut(val result: PunchOutResult) : PunchResult
    /** Sleep start + punch-in offset is in the future; caller must ask how to resolve. */
    data object OffsetInFuture : PunchResult
    data object Blocked : PunchResult
}

/**
 * Punch-in / punch-out state machine (legacy PushButton + Behaviors). All entry points (home
 * button, night screen, widget, notification, alarm dismiss, intent API) go through here.
 */
class SleepSession(private val context: Context) {
    private val prefs get() = context.app.prefs
    private val _asleep = MutableStateFlow(!prefs.isAwake)
    /** True while punched in. */
    val asleep: StateFlow<Boolean> = _asleep.asStateFlow()

    /** Legacy punch-in time (ms) while asleep, else 0. */
    val sleepStart: Long get() = prefs.sleepState.coerceAtLeast(0)

    enum class Restrict { NONE, SLEEP_ONLY, WAKE_ONLY }

    suspend fun toggle(restrict: Restrict = Restrict.NONE, overrides: Overrides = Overrides()): PunchResult {
        return if (prefs.isAwake) {
            if (restrict == Restrict.WAKE_ONLY) return PunchResult.Blocked
            if (Math.abs(System.currentTimeMillis() - prefs.lastPunchOut) < prefs.repunchGuardMs) return PunchResult.Blocked
            punchIn()
            PunchResult.PunchedIn
        } else {
            if (restrict == Restrict.SLEEP_ONLY) return PunchResult.Blocked
            val delay = prefs.punchInDelayMin * 60_000L
            if (prefs.sleepState + delay > System.currentTimeMillis() && overrides.sleep == null) return PunchResult.OffsetInFuture
            PunchResult.PunchedOut(punchOut(overrides))
        }
    }

    fun punchIn(now: Long = System.currentTimeMillis()) {
        prefs.sleepState = now
        prefs.punchToken = now
        prefs.smartFiredFor = 0
        _asleep.value = true
        Notifications.showAsleep(context, now)
        AlarmScheduler.onPunchIn(context, now)
        TrackingService.onPunchIn(context)
        Reminders.reschedule(context)
        SleepBotWidget.update(context)
        broadcast("com.sleepbot.app.SLEEP", now, 0f)
    }

    data class Overrides(val sleep: Long? = null, val awake: Long? = null, val note: String? = null)

    suspend fun punchOut(overrides: Overrides = Overrides()): PunchOutResult {
        val now = System.currentTimeMillis()
        val sleep = overrides.sleep ?: (prefs.sleepState + prefs.punchInDelayMin * 60_000L)
        val awake = overrides.awake ?: now
        val token = prefs.punchToken
        val entry = SleepEntry(
            sleep = sleep,
            awake = awake,
            note = overrides.note ?: "",
            punchToken = token,
            hasMovement = prefs.trackMotion || prefs.smartAlarm,
            hasSound = prefs.recordSound,
            utcOffsetSec = TimeZone.getDefault().getOffset(awake) / 1000L,
        )
        TrackingService.onPunchOut(context)
        val id = context.app.db.entries().insert(entry)
        finishAwake(now)
        broadcast("com.sleepbot.app.AWAKE", awake, (awake + prefs.optimalHours * 3_600_000f))
        return PunchOutResult(id, sleep, awake)
    }

    /** "No Record" choice: go back to awake without inserting an entry. */
    fun abandon() {
        TrackingService.onPunchOut(context)
        finishAwake(System.currentTimeMillis())
    }

    /** "Reset" in the current-session dialog. Keeps the punch token so sensor data stays attached. */
    fun restartSession(now: Long = System.currentTimeMillis()) {
        prefs.sleepState = now
        Notifications.showAsleep(context, now)
    }

    private fun finishAwake(now: Long) {
        prefs.sleepState = -now
        prefs.lastPunchOut = now
        _asleep.value = false
        Notifications.showAwake(context)
        AlarmScheduler.onPunchOut(context)
        Reminders.reschedule(context)
        SleepBotWidget.update(context)
    }

    /** Re-sync the in-memory flag after something outside the session changed prefs. */
    fun refresh() { _asleep.value = !prefs.isAwake }

    private fun broadcast(action: String, time: Long, optimal: Float) {
        if (!prefs.allowIntegration) return
        context.sendBroadcast(Intent(action).putExtra("time", time).putExtra("optimal", optimal))
    }
}
