package com.sleepbot.app.tracking

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.sleepbot.app.data.SleepEntry

/** STUB — owned by the tracking agent. Movement + Sound 88dp mini graphs for the entry editor; renders nothing when no data. */
@Composable
fun SensorMiniGraphs(entry: SleepEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {}

/** STUB — landscape zoomed Movement/Sound graphs with tap-to-play. */
@Composable
fun ZoomedSensorScreen(entryId: Long, onBack: () -> Unit) { Text("Sensors") }
