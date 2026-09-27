package com.sleepbot.app.alarm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** STUB — owned by the alarm-clock agent. */
class AlarmRepository(private val context: Context) {
    private val _next = MutableStateFlow<Long?>(null)
    /** Epoch ms of the earliest pending (enabled) alarm occurrence, or null. */
    val nextAlarm: StateFlow<Long?> = _next

    /** Recompute [nextAlarm] and reschedule system alarms. */
    fun refresh() {}
}
