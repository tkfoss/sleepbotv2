package com.sleepbot.app

import android.app.Application
import android.content.Context
import com.sleepbot.app.alarm.AlarmRepository
import com.sleepbot.app.data.Prefs
import com.sleepbot.app.data.SleepDatabase
import com.sleepbot.app.session.SleepSession
import com.sleepbot.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class SleepBotApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db by lazy { SleepDatabase.get(this) }
    val prefs by lazy { Prefs(this) }
    val alarms by lazy { AlarmRepository(this) }
    val session by lazy { SleepSession(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

val Context.app: SleepBotApp get() = applicationContext as SleepBotApp
