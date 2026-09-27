package com.sleepbot.app.settings

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.sleepbot.app.R
import com.sleepbot.app.backup.BackupSection
import com.sleepbot.app.app
import com.sleepbot.app.tracking.Reminders
import com.sleepbot.app.ui.theme.SB
import com.sleepbot.app.util.Notifications
import java.io.File
import java.util.Calendar

/** Snapshot handle: reading [version] subscribes the caller to any preference change. */
private class PrefState(val sp: SharedPreferences, private val versionState: androidx.compose.runtime.MutableIntState) {
    val version get() = versionState.intValue
    fun bool(key: String, def: Boolean): Boolean { version; return sp.getBoolean(key, def) }
    fun str(key: String, def: String): String { version; return sp.getString(key, def) ?: def }
}

@Composable
private fun rememberPrefState(sp: SharedPreferences): PrefState {
    val v = remember { mutableIntStateOf(0) }
    val listener = remember { SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> v.intValue++ } }
    DisposableEffect(sp) {
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(sp) { PrefState(sp, v) }
}

private enum class Page(val title: String) {
    ROOT("Settings"), REMINDER("Bedtime Reminder"), TRACKING("Sleep Tracking"), APPEARANCE("Appearance"),
    BACKUP("Backup & Sync"), ADVANCED("Advanced Settings"), ALARM("Alarm Clock Settings"),
}

/** Legacy SettingActivity (res/xml/options.xml), sub-screens as nested in-composable navigation. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onAlarmDefaults: () -> Unit, onOpenHtml: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.app.prefs
    val ps = rememberPrefState(prefs.sp)
    var stack by rememberSaveable { mutableStateOf(listOf(Page.ROOT.name)) }
    val page = Page.valueOf(stack.last())
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val close = { dialog = null }
    val goBack = { if (stack.size > 1) stack = stack.dropLast(1) else onBack() }
    val open = { p: Page -> stack = stack + p.name }
    BackHandler { goBack() }

    val show = { d: @Composable () -> Unit -> dialog = d }
    val ui = PrefUi(ctx, ps, show, close)

    Column(Modifier.fillMaxSize().background(SB.EditorBg).windowInsetsPadding(WindowInsets.systemBars)) {
        NamebarHeader(page.title, onBack = goBack)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            when (page) {
                Page.ROOT -> RootPage(ui, open, onOpenHtml)
                Page.REMINDER -> ReminderPage(ui)
                Page.TRACKING -> TrackingPage(ui)
                Page.APPEARANCE -> AppearancePage(ui)
                Page.BACKUP -> BackupPage(ui)
                Page.ADVANCED -> AdvancedPage(ui)
                Page.ALARM -> AlarmPage(ui, onAlarmDefaults)
            }
        }
    }
    dialog?.invoke()
}

/** Bundles what pref rows need. */
private class PrefUi(
    val ctx: Context,
    val ps: PrefState,
    val show: (@Composable () -> Unit) -> Unit,
    val close: () -> Unit,
) {
    val sp get() = ps.sp
}

// ---- generic preference rows -------------------------------------------------------------

@Composable
private fun CheckPref(
    ui: PrefUi, key: String, title: String, summary: String? = null, default: Boolean = false,
    summaryOn: String? = null, summaryOff: String? = null, enabled: Boolean = true,
    beforeEnable: ((commit: () -> Unit) -> Unit)? = null,
    onChanged: (Boolean) -> Unit = {},
) {
    val v = ui.ps.bool(key, default)
    val set = { nv: Boolean -> ui.sp.edit { putBoolean(key, nv) }; onChanged(nv) }
    val toggle = {
        if (!v && beforeEnable != null) beforeEnable { set(true) } else set(!v)
    }
    PrefRow(title, (if (v) summaryOn else summaryOff) ?: summary, enabled, onClick = toggle) {
        SbCheckbox(v, if (enabled) { _ -> toggle() } else null, enabled)
    }
}

@Composable
private fun ListPref(
    ui: PrefUi, key: String, title: String, summary: String?, entries: List<String>, values: List<String>,
    default: String, enabled: Boolean = true, onChanged: (String) -> Unit = {},
) {
    val v = ui.ps.str(key, default)
    val idx = values.indexOf(v)
    val current = entries.getOrNull(idx)
    val text = listOfNotNull(summary, current?.let { "▸ $it" }).joinToString("\n")
    PrefRow(title, text, enabled, onClick = {
        ui.show {
            SingleChoiceDialog(title, entries, idx, onSelect = { i ->
                ui.sp.edit { putString(key, values[i]) }
                ui.close()
                onChanged(values[i])
            }, onDismiss = ui.close)
        }
    })
}

@Composable
private fun EditPref(ui: PrefUi, key: String, title: String, summary: String, default: String, onChanged: () -> Unit = {}) {
    val v = ui.ps.str(key, default)
    PrefRow(title, "$summary\n▸ $v", onClick = {
        ui.show {
            TextInputDialog(title, v, onOk = { ui.sp.edit { putString(key, it) }; ui.close(); onChanged() }, onDismiss = ui.close)
        }
    })
}

@Composable
private fun SubPage(title: String, summary: String? = null, onClick: () -> Unit) =
    PrefRow(title, summary, onClick = onClick, trailing = { Text("›", color = SummaryColor, fontSize = 24.sp) })

private fun refreshSessionNotification(ctx: Context) {
    val app = ctx.app
    if (app.prefs.isAwake) Notifications.showAwake(ctx)
    else if (app.prefs.notificationMode == 2) NotificationManagerCompat.from(ctx).cancel(Notifications.ID_PUNCH)
    else Notifications.showAsleep(ctx, app.prefs.sleepState)
}

private fun reschedReminders(ctx: Context) = runCatching { Reminders.reschedule(ctx) }

private fun startOfToday() = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun safeStart(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No activity found to perform this action.", Toast.LENGTH_SHORT).show()
    }
}

// ---- pages -------------------------------------------------------------------------------

private val OPTIMAL_VALUES = (6..22).map { it / 2.0 }.map { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
private val OPTIMAL_LABELS = OPTIMAL_VALUES.map { "$it Hours" }

@Composable
private fun RootPage(ui: PrefUi, open: (Page) -> Unit, onOpenHtml: (String) -> Unit) {
    val ctx = ui.ctx
    ListPref(
        ui, "optimal_hours", "Optimal Hour", "Your ideal goal for sleep duration",
        OPTIMAL_LABELS, OPTIMAL_VALUES, "8", onChanged = { reschedReminders(ctx); ctx.app.alarms.refresh() },
    )
    ListPref(
        ui, "punch_in_delay", "Punch-in time offset",
        "Lets you punch-in before you go to sleep/fall asleep to create more accurate sleep time.",
        listOf("No offset", "+5 minutes", "+10 minutes", "+15 minutes", "+30 minutes", "+45 minutes", "+1 hour",
            "+1 hour 20 minutes", "+1 hour 40 minutes", "+2 hours"),
        listOf("0", "5", "10", "15", "30", "45", "60", "80", "100", "120"), "0",
    )
    SubPage("Bedtime Reminder", "Get reminded when it is time to go to sleep") { open(Page.REMINDER) }
    SubPage("Sleep Tracking", "Silence, auto alarm, smart alarm, movement and sound options") { open(Page.TRACKING) }
    CategoryHeader("Application")
    SubPage("Appearance") { open(Page.APPEARANCE) }
    SubPage("Alarm Clock Settings") { open(Page.ALARM) }
    SubPage("Backup & Sync") { open(Page.BACKUP) }
    SubPage("Advanced Settings") { open(Page.ADVANCED) }
    CategoryHeader("About")
    PrefRow("SleepBot 4.0.0", "View update notice.", onClick = { onOpenHtml("update.html") })
    PrefRow("Rate SleepBot!", onClick = {
        safeStart(ctx, Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ctx.packageName)), "Rate SleepBot!"))
    })
}

@Composable
private fun ReminderPage(ui: PrefUi) {
    val ctx = ui.ctx
    val r = { _: Any -> reschedReminders(ctx); Unit }
    CategoryHeader("Bedtime Reminder")
    CheckPref(
        ui, "reminder1", "Enable Bedtime Reminder",
        "Bedtime Reminder will remind you to go to sleep base on your alarm settings if you have not punched in by the time already.",
        onChanged = r,
    )
    ListPref(
        ui, "reminder1_offset", "Remind time offset",
        "Next alarm time - (optimal wake up time) - (remind time offset) = remind time.",
        listOf("15 minutes before", "30 minutes before", "45 minutes before", "1 hour before", "2 hours before"),
        listOf("15", "30", "45", "60", "120"), "30", enabled = ui.ps.bool("reminder1", false), onChanged = r,
    )
    CheckPref(ui, "reminder2", "Enable 2nd Reminder", "Have a second reminder in case you are still not asleep yet.", onChanged = r)
    ListPref(
        ui, "reminder2_offset", "2nd Remind time offset",
        "Next alarm time - (optimal wake up time) - (2nd remind time offset) = 2nd remind time.",
        listOf("15 minutes after", "30 minutes after", "45 minutes after", "1 hour after", "2 hours after"),
        listOf("-15", "-30", "-45", "-60", "-120"), "-15", enabled = ui.ps.bool("reminder2", false), onChanged = r,
    )
    CheckPref(ui, "reminder_no_later", "No Reminder Later", "Do not show remind later dialog.", onChanged = r)
    CheckPref(ui, "reminder_muted", "No Notification Sound", "Do not play a notification sound for the reminder.", onChanged = r)
}

@Composable
private fun TrackingPage(ui: PrefUi) {
    val ctx = ui.ctx
    CategoryHeader("General")
    ListPref(
        ui, "auto_silence", "AutoSilence", "Silence your phone while you sleep",
        listOf("Do nothing", "Silent+Vibrate", "Silent without vibrate"), listOf("0", "1", "2"), "0",
        onChanged = { v ->
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (v != "0" && !nm.isNotificationPolicyAccessGranted) {
                ui.show {
                    MessageDialog(
                        "AutoSilence",
                        "To silence your phone while you sleep, SleepBot needs \"Do Not Disturb\" access. Alarms will still ring.",
                        confirm = "Grant", onConfirm = {
                            ui.close(); safeStart(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }, dismiss = stringResourceSafe(ctx, R.string.cancel), onDismiss = ui.close,
                    )
                }
            }
        },
    )
    CheckPref(
        ui, "auto_alarm", "Auto Alarm On",
        "Creates an alarm that automatically rings the optimal number of hours (including offset) after punching in.",
    )
    CategoryHeader("Movement Tracking")
    CheckPref(
        ui, "screen_off_tracking", "Screen Off Tracking", "Some phones may support movement tracking with screen turned off.",
        default = true,
        beforeEnable = { commit ->
            ui.show {
                MessageDialog(
                    null,
                    "Please note that if movement graphs shows all 0's, it means that your phone does not support screen off tracking and you should leave this option unchecked.",
                    confirm = "Agree", onConfirm = { ui.close(); commit() }, onDismiss = ui.close,
                )
            }
        },
    )
    CategoryHeader("Smart Alarm")
    ListPref(
        ui, "smart_window", "Alarm Range", "Time range before first alarm for optimal wake-up.",
        listOf("15 minutes before", "30 minutes before", "45 minutes before", "60 minutes before", "90 minutes before"),
        listOf("15", "30", "45", "60", "90"), "30", onChanged = { ctx.app.alarms.refresh() },
    )
    ListPref(
        ui, "motion_sensitivity", "Movement Sensitivity", "Adjust to optimize smart alarm for your bed and phone.",
        listOf("Very Low (soft bed + sensitive phones)", "Low", "Normal", "High", "Very High (for very hard beds)"),
        listOf("0", "1", "2", "3", "4"), "2",
    )
    CategoryHeader("Sound Recording")
    ListPref(
        ui, "sound_sensitivity", "Recording Sensitivity",
        "Sound recording begins when volume is greater than the threshold",
        listOf("Very Low (record only very loud sounds)", "Low", "Normal", "High", "Very High (record nearly all sounds)"),
        listOf("0", "1", "2", "3", "4"), "2",
    )
    PrefRow(
        "Delete Older Files",
        "Running out of space? Delete local sound files that are older than 7 days.",
        onClick = {
            ui.show {
                MessageDialog(
                    "Delete Older Files", "Deleting older sound files is irreversible.",
                    confirm = "Confirm", onConfirm = {
                        ui.close()
                        val cutoff = System.currentTimeMillis() - 7 * 86_400_000L
                        val n = File(ctx.filesDir, "sounds").listFiles()?.count { f ->
                            val ts = f.name.substringBefore('.').toLongOrNull() ?: f.lastModified()
                            ts < cutoff && f.delete()
                        } ?: 0
                        Toast.makeText(ctx, "$n files deleted", Toast.LENGTH_SHORT).show()
                    }, dismiss = "Cancel", onDismiss = ui.close,
                )
            }
        },
    )
}

private fun stringResourceSafe(ctx: Context, id: Int) = ctx.getString(id)

@Composable
private fun AppearancePage(ui: PrefUi) {
    val ctx = ui.ctx
    CategoryHeader("Appearance")
    CheckPref(ui, "show_alt_number", "Show alternate display", "Un-check if you want to hide the alternate information.", default = true)
    ListPref(
        ui, "entry_hour_format", "Entry List Hours Format",
        "For phones with big screens, you can use non-decimal format to display total hour and debt numbers.",
        listOf("decimal (3.5)", "time (03:30)"), listOf("decimal", "time"), "decimal",
    )
    ListPref(
        ui, "home_display", "Default Display", "Set what to show on home tab's main display number.",
        listOf("Today's Sleep", "Current Debt"), listOf("today", "debt"), "today",
    )
    ListPref(
        ui, "overview_graph", "Default Graph", "Choose the graph displayed on the tracking tab.",
        listOf("Sleep Duration Trend", "Sleep Distribution Pattern"), listOf("0", "1"), "0",
    )
    CheckPref(
        ui, "hide_charging_warning", "No warning in Screen Off mode",
        "Do not show charging warning if Screen Off mode is checked.",
    )
    ListPref(
        ui, "notification_mode", "Status notification", "When to show SleepBot's punch-in status in the notification bar.",
        listOf("Always", "Only while asleep", "Never"), listOf("0", "1", "2"), "1",
        onChanged = { refreshSessionNotification(ctx) },
    )
    EditPref(ui, "sleep_notification_text", "Sleep Reminder Text", "Customize the sleep message shown in notifications.",
        "Touch to punch out.") { refreshSessionNotification(ctx) }
    EditPref(ui, "wake_notification_text", "Wake Reminder Text", "Customize the wake message shown in notifications.",
        "Touch to punch in.") { refreshSessionNotification(ctx) }
    CheckPref(ui, "disconnected_lines", "Dotted Lines", "Connect disconnected points using dotted lines on trend graph.", default = true)
}

@Composable
private fun BackupPage(ui: PrefUi) {
    CategoryHeader("Backup & Sync")
    BackupSection(ui.show, ui.close)
    ListPref(
        ui, "overview_graph", "Default Graph", "Choose the graph displayed on the tracking tab.",
        listOf("Sleep Duration Trend", "Sleep Distribution Pattern"), listOf("0", "1"), "0",
    )
    CategoryHeader("Android Backup")
    Text(
        "Your sleep entries and settings are also backed up automatically by Android's device backup " +
            "(Google account backup) and restored when you set up a new phone.",
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = SummaryColor, fontSize = 14.sp,
    )
}

@Composable
private fun AdvancedPage(ui: PrefUi) {
    val ctx = ui.ctx
    CategoryHeader("Other Settings")
    PrefRow("Reset Debt", "Restart the sleep debt calculation from today.", onClick = {
        ui.show {
            MessageDialog(
                stringResourceSafe(ctx, R.string.reset_debt_title), stringResourceSafe(ctx, R.string.reset_debt_msg),
                confirm = stringResourceSafe(ctx, R.string.reset), onConfirm = {
                    ui.close()
                    ctx.app.prefs.debtResetTime = startOfToday()
                    Toast.makeText(ctx, R.string.reset_debt_done, Toast.LENGTH_LONG).show()
                }, dismiss = stringResourceSafe(ctx, R.string.cancel), onDismiss = ui.close,
            )
        }
    })
    ListPref(
        ui, "debt_range", "Current Debt Range",
        "Choose 7, 10, or 14 days for the date range used to calculate your Current Debt shown on the home tab.",
        listOf("7", "10", "14"), listOf("7", "10", "14"), "10",
    )
    CheckPref(ui, "allow_integration", "Enable Integration", "Allow third party applications to sleep/wake up SleepBot.", default = true)
    val guard = listOf("0.0s", "0.5s", "1.0s", "2.0s", "3.0s", "4.0s", "5.0s")
    ListPref(
        ui, "repunch_guard", "Re-Punch-in Guard",
        "Prevent accidental punch-ins by blocking any attempts within a certain period of time.",
        guard, guard.map { it.dropLast(1) }, "1.0",
    )
}

@Composable
private fun AlarmPage(ui: PrefUi, onAlarmDefaults: () -> Unit) {
    val ctx = ui.ctx
    CategoryHeader("Alarm Clock Settings")
    CheckPref(
        ui, "alarm_notification_icon", "Display notification icon", default = true,
        summaryOn = "Display an icon when there are pending alarms",
        summaryOff = "Do not display an icon when there are pending alarms",
        onChanged = { ctx.app.alarms.refresh() },
    )
    ListPref(
        ui, "alarm_timeout", "Alarm time out", "A firing alarm will be automatically dismissed after this many minutes.",
        listOf("1", "5", "10", "30", "60"), listOf("1", "5", "10", "30", "60"), "10",
    )
    PrefRow(stringResource(R.string.alarm_default_settings), "Default tone, snooze, vibrate and volume fade for new alarms", onClick = onAlarmDefaults)
}
