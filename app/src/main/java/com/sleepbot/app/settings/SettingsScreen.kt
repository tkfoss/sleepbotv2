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
import androidx.compose.ui.res.stringArrayResource
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

private enum class Page(val title: Int) {
    ROOT(R.string.settings), REMINDER(R.string.sleep_reminder), TRACKING(R.string.pref_sc_sleep_tracking),
    APPEARANCE(R.string.pref_sc_look_and_feel), BACKUP(R.string.pref_cat_adv_data), ADVANCED(R.string.pref_adv_main_title),
    ALARM(R.string.pref_alarm_klock_settings_title),
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
        NamebarHeader(stringResource(page.title), onBack = goBack)
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
        Toast.makeText(ctx, R.string.error_intent_empty_msg, Toast.LENGTH_SHORT).show()
    }
}

// ---- pages -------------------------------------------------------------------------------

private val OPTIMAL_VALUES = (6..22).map { it / 2.0 }.map { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

@Composable
private fun s(id: Int) = stringResource(id)

@Composable
private fun a(id: Int) = stringArrayResource(id).toList()

@Composable
private fun RootPage(ui: PrefUi, open: (Page) -> Unit, onOpenHtml: (String) -> Unit) {
    val ctx = ui.ctx
    ListPref(
        ui, "optimal_hours", s(R.string.options_hours_title), s(R.string.options_hours_summary),
        a(R.array.hour_entries), OPTIMAL_VALUES, "8",
        onChanged = { reschedReminders(ctx); ctx.app.alarms.refresh() },
    )
    ListPref(
        ui, "punch_in_delay", s(R.string.options_delay_min_title), s(R.string.options_delay_min_summary),
        a(R.array.delay_min_entries), listOf("0", "5", "10", "15", "30", "45", "60", "80", "100", "120"), "0",
    )
    SubPage(s(R.string.sleep_reminder), s(R.string.pref_reminder_summary)) { open(Page.REMINDER) }
    SubPage(s(R.string.pref_sc_sleep_tracking), s(R.string.pref_tracking_summary)) { open(Page.TRACKING) }
    CategoryHeader(s(R.string.setting_title_app))
    SubPage(s(R.string.pref_sc_look_and_feel)) { open(Page.APPEARANCE) }
    SubPage(s(R.string.pref_alarm_klock_settings_title)) { open(Page.ALARM) }
    SubPage(s(R.string.pref_cat_adv_data)) { open(Page.BACKUP) }
    SubPage(s(R.string.pref_adv_main_title)) { open(Page.ADVANCED) }
    CategoryHeader(s(R.string.about))
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    PrefRow(stringResource(R.string.pref_version, version), s(R.string.pref_view_update_notice), onClick = { onOpenHtml("update.html") })
    val rate = s(R.string.rate_sleepbot)
    PrefRow(rate, onClick = {
        safeStart(ctx, Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ctx.packageName)), rate))
    })
}

@Composable
private fun ReminderPage(ui: PrefUi) {
    val ctx = ui.ctx
    val r = { _: Any -> reschedReminders(ctx); Unit }
    CategoryHeader(s(R.string.sleep_reminder))
    CheckPref(ui, "reminder1", s(R.string.sleep_reminder_title), s(R.string.sleep_reminder_summary), onChanged = r)
    ListPref(
        ui, "reminder1_offset", s(R.string.times_before_offset), s(R.string.times_before_offset_summary),
        a(R.array.sleep_reminder_offsets), listOf("15", "30", "45", "60", "120"), "30",
        enabled = ui.ps.bool("reminder1", false), onChanged = r,
    )
    CheckPref(ui, "reminder2", s(R.string.sleep_reminder_title_2nd), s(R.string.sleep_reminder_summary_2nd), onChanged = r)
    ListPref(
        ui, "reminder2_offset", s(R.string.times_before_offset_2nd), s(R.string.times_before_offset_summary_2nd),
        a(R.array.sleep_reminder_offsets_2nd), listOf("-15", "-30", "-45", "-60", "-120"), "-15",
        enabled = ui.ps.bool("reminder2", false), onChanged = r,
    )
    CheckPref(ui, "reminder_no_later", s(R.string.no_reminder_snooze), s(R.string.no_reminder_summary), onChanged = r)
    CheckPref(ui, "reminder_muted", s(R.string.reminder_mute_title), s(R.string.reminder_mute_summary), onChanged = r)
}

@Composable
private fun TrackingPage(ui: PrefUi) {
    val ctx = ui.ctx
    CategoryHeader(s(R.string.setting_title_general))
    val silenceTitle = s(R.string.options_soundmode_title)
    ListPref(
        ui, "auto_silence", silenceTitle, s(R.string.options_soundmode_summary),
        a(R.array.sound_mode), listOf("0", "1", "2"), "0",
        onChanged = { v ->
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (v != "0" && !nm.isNotificationPolicyAccessGranted) {
                ui.show {
                    MessageDialog(
                        silenceTitle, s(R.string.dnd_access_msg),
                        confirm = s(R.string.alarm_grant), onConfirm = {
                            ui.close(); safeStart(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        }, dismiss = s(R.string.cancel), onDismiss = ui.close,
                    )
                }
            }
        },
    )
    CheckPref(ui, "auto_alarm", s(R.string.options_auto_alarm_title), s(R.string.options_auto_alarm_summary))
    CategoryHeader(s(R.string.pref_cat_movement_tracking))
    CheckPref(
        ui, "screen_off_tracking", s(R.string.pref_allow_screen_off_title), s(R.string.pref_allow_screen_off_summary),
        default = true,
        beforeEnable = { commit ->
            ui.show {
                MessageDialog(
                    null, s(R.string.pref_allow_screen_off_warning),
                    confirm = s(R.string.agree), onConfirm = { ui.close(); commit() }, onDismiss = ui.close,
                )
            }
        },
    )
    CategoryHeader(s(R.string.pref_cat_smart_alarm))
    ListPref(
        ui, "smart_window", s(R.string.pref_time_alarm_range_title), s(R.string.pref_time_alarm_range_summary),
        a(R.array.alarm_range_entries), listOf("15", "30", "45", "60", "90"), "30", onChanged = { ctx.app.alarms.refresh() },
    )
    ListPref(
        ui, "motion_sensitivity", s(R.string.pref_movement_sensitivity_title), s(R.string.pref_movement_sensitivity_summary),
        a(R.array.movement_sensitivity_entries), listOf("0", "1", "2", "3", "4"), "2",
    )
    CategoryHeader(s(R.string.pref_cat_sound_recording))
    ListPref(
        ui, "sound_sensitivity", s(R.string.pref_sound_sensitivity_title), s(R.string.pref_sound_sensitivity_summary),
        a(R.array.sound_sensitivity_entries_v4), listOf("0", "1", "2", "3", "4"), "2",
    )
    val deleteTitle = s(R.string.delete_older_sound_files)
    PrefRow(deleteTitle, s(R.string.delete_older_sound_files_7), onClick = {
        ui.show {
            MessageDialog(
                deleteTitle, s(R.string.delete_older_sound_files_message),
                confirm = s(R.string.confirm), onConfirm = {
                    ui.close()
                    val cutoff = System.currentTimeMillis() - 7 * 86_400_000L
                    val n = File(ctx.filesDir, "sounds").listFiles()?.count { f ->
                        val ts = f.name.substringBefore('.').toLongOrNull() ?: f.lastModified()
                        ts < cutoff && f.delete()
                    } ?: 0
                    Toast.makeText(ctx, ctx.getString(R.string.files_deleted, n), Toast.LENGTH_SHORT).show()
                }, dismiss = s(R.string.cancel), onDismiss = ui.close,
            )
        }
    })
}

@Composable
private fun AppearancePage(ui: PrefUi) {
    val ctx = ui.ctx
    CategoryHeader(s(R.string.pref_sc_look_and_feel))
    CheckPref(
        ui, "show_alt_number", s(R.string.pref_alternate_display_show_title), s(R.string.pref_alternate_display_show_summary),
        default = true,
    )
    ListPref(
        ui, "entry_hour_format", s(R.string.pref_hour_format_title), s(R.string.pref_hour_format_summary),
        a(R.array.entry_list_hour_format_entries), listOf("decimal", "time"), "decimal",
    )
    ListPref(
        ui, "home_display", s(R.string.pref_default_display_title), s(R.string.pref_default_display_summary),
        a(R.array.default_display_entries), listOf("today", "debt"), "today",
    )
    ListPref(
        ui, "overview_graph", s(R.string.options_default_graph_title), s(R.string.options_default_graph_summary),
        a(R.array.default_graph_entries), listOf("0", "1"), "0",
    )
    CheckPref(ui, "hide_charging_warning", s(R.string.pref_no_warning_title), s(R.string.pref_no_warning_summary))
    ListPref(
        ui, "notification_mode", s(R.string.pref_notification_mode_title), s(R.string.pref_notification_mode_summary),
        a(R.array.notification_mode_entries), listOf("0", "1", "2"), "1",
        onChanged = { refreshSessionNotification(ctx) },
    )
    EditPref(
        ui, "sleep_notification_text", s(R.string.options_notification_sleep_title), s(R.string.options_notification_sleep_summary),
        s(R.string.pref_sleep_notification_default),
    ) { refreshSessionNotification(ctx) }
    EditPref(
        ui, "wake_notification_text", s(R.string.options_notification_awake_title), s(R.string.options_notification_awake_summary),
        s(R.string.pref_awake_notification_default),
    ) { refreshSessionNotification(ctx) }
    CheckPref(
        ui, "disconnected_lines", s(R.string.pref_title_show_disconnected_lines), s(R.string.pref_summary_show_disconnected_lines),
        default = true,
    )
}

@Composable
private fun BackupPage(ui: PrefUi) {
    CategoryHeader(s(R.string.pref_cat_adv_data))
    BackupSection(ui.show, ui.close)
    CategoryHeader(s(R.string.pref_android_backup))
    Text(
        s(R.string.pref_android_backup_text),
        Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = SummaryColor, fontSize = 14.sp,
    )
}

@Composable
private fun AdvancedPage(ui: PrefUi) {
    val ctx = ui.ctx
    CategoryHeader(s(R.string.pref_adv_cat_others))
    PrefRow(s(R.string.reset_debt_title), s(R.string.pref_reset_debt_summary), onClick = {
        ui.show {
            MessageDialog(
                s(R.string.reset_debt_title), s(R.string.reset_debt_msg),
                confirm = s(R.string.reset), onConfirm = {
                    ui.close()
                    ctx.app.prefs.debtResetTime = startOfToday()
                    Toast.makeText(ctx, R.string.reset_debt_done, Toast.LENGTH_LONG).show()
                }, dismiss = s(R.string.cancel), onDismiss = ui.close,
            )
        }
    })
    ListPref(
        ui, "debt_range", s(R.string.pref_adv_length_title), s(R.string.pref_adv_length_summary),
        listOf("7", "10", "14"), listOf("7", "10", "14"), "10",
    )
    CheckPref(
        ui, "allow_integration", s(R.string.pref_adv_security_third_party_title), s(R.string.pref_adv_security_third_party_summary),
        default = true,
    )
    val guard = listOf("0.0s", "0.5s", "1.0s", "2.0s", "3.0s", "4.0s", "5.0s")
    ListPref(
        ui, "repunch_guard", s(R.string.pref_adv_variable_block_title), s(R.string.pref_adv_variable_block_summary),
        guard, guard.map { it.dropLast(1) }, "1.0",
    )
}

@Composable
private fun AlarmPage(ui: PrefUi, onAlarmDefaults: () -> Unit) {
    val ctx = ui.ctx
    CategoryHeader(s(R.string.pref_alarm_klock_settings_title))
    CheckPref(
        ui, "alarm_notification_icon", s(R.string.notification_icon_title), default = true,
        summaryOn = s(R.string.notification_icon_on), summaryOff = s(R.string.notification_icon_off),
        onChanged = { ctx.app.alarms.refresh() },
    )
    ListPref(
        ui, "alarm_timeout", s(R.string.alarm_time_out), s(R.string.time_out_summary),
        listOf("1", "5", "10", "30", "60"), listOf("1", "5", "10", "30", "60"), "10",
    )
    PrefRow(s(R.string.alarm_default_settings), s(R.string.alarm_default_settings_summary), onClick = onAlarmDefaults)
}
