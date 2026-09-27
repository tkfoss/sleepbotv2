package com.sleepbot.app.ui.main

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sleepbot.app.alarm.ui.AlarmEditScreen
import com.sleepbot.app.alarm.ui.AlarmListScreen
import com.sleepbot.app.help.HtmlScreen
import com.sleepbot.app.settings.SettingsScreen
import com.sleepbot.app.tracking.ZoomedSensorScreen
import com.sleepbot.app.ui.entries.EntryEditScreen
import com.sleepbot.app.ui.overview.GraphsScreen

object Routes {
    const val MAIN = "main"
    const val ENTRY = "entry/{id}"          // id -1 = new entry
    const val GRAPHS = "graphs"
    const val SENSORS = "sensors/{id}"
    const val SETTINGS = "settings"
    const val ALARMS = "alarms"
    const val ALARM = "alarm/{id}"
    const val HTML = "html/{file}"
    fun entry(id: Long) = "entry/$id"
    fun sensors(id: Long) = "sensors/$id"
    fun alarm(id: Long) = "alarm/$id"
    fun html(file: String) = "html/$file"
}

/** Locks the host activity's orientation while this composable is on screen. */
@Composable
fun LockOrientation(orientation: Int = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(orientation) {
        val prev = activity.requestedOrientation
        activity.requestedOrientation = orientation
        onDispose { activity.requestedOrientation = prev }
    }
}

@Composable
fun SleepBotNav() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val idArg = listOf(navArgument("id") { type = NavType.LongType })
    NavHost(nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenAlarms = { nav.navigate(Routes.ALARMS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenEntry = { nav.navigate(Routes.entry(it)) },
                onOpenGraphs = { nav.navigate(Routes.GRAPHS) },
                onOpenHtml = { nav.navigate(Routes.html(it)) },
            )
        }
        composable(Routes.ENTRY, idArg) {
            EntryEditScreen(
                entryId = it.arguments!!.getLong("id"),
                onBack = back,
                onOpenSensors = { id -> nav.navigate(Routes.sensors(id)) },
            )
        }
        composable(Routes.GRAPHS) { GraphsScreen(onBack = back) }
        composable(Routes.SENSORS, idArg) { ZoomedSensorScreen(it.arguments!!.getLong("id"), back) }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back, onAlarmDefaults = { nav.navigate(Routes.alarm(-1)) }, onOpenHtml = { nav.navigate(Routes.html(it)) })
        }
        composable(Routes.ALARMS) { AlarmListScreen(onBack = back, onEdit = { nav.navigate(Routes.alarm(it)) }) }
        composable(Routes.ALARM, idArg) { AlarmEditScreen(it.arguments!!.getLong("id"), back) }
        composable(Routes.HTML, listOf(navArgument("file") { type = NavType.StringType })) {
            HtmlScreen(it.arguments!!.getString("file")!!, back)
        }
    }
}
