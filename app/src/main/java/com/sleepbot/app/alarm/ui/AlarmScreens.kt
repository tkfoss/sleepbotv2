package com.sleepbot.app.alarm.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** STUB — owned by the alarm-clock agent. */
@Composable
fun AlarmListScreen(onBack: () -> Unit, onEdit: (Long) -> Unit) { Text("Alarms") }

/** STUB — alarmId -1 edits the default alarm settings. */
@Composable
fun AlarmEditScreen(alarmId: Long, onBack: () -> Unit) { Text("Alarm $alarmId") }
