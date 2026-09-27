package com.sleepbot.app.ui.entries

import androidx.compose.runtime.Composable

@Composable
fun EntriesTab(onOpenEntry: (Long) -> Unit) {}

@Composable
fun EntryEditScreen(entryId: Long, onBack: () -> Unit, onOpenSensors: (Long) -> Unit) {}
