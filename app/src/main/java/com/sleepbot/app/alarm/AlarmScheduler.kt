package com.sleepbot.app.alarm

import android.content.Context

/** STUB — owned by the alarm-clock agent. Hooks called by SleepSession / tracking. */
object AlarmScheduler {
    /** Auto Alarm creation + arm smart-window movement tracking. */
    fun onPunchIn(context: Context, sleepStart: Long) {}
    /** Undo auto alarm, clear smart-alarm override. */
    fun onPunchOut(context: Context) {}
    /** Light sleep detected inside the smart window: ring the next alarm now, skip its original occurrence. */
    fun smartTrigger(context: Context) {}
}
