package com.sleepbot.app.help

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** STUB — owned by the extras agent. Help tab accordion; [onOpen] gets an assets/kb file name. */
@Composable
fun HelpTab(onOpen: (String) -> Unit) { Text("Help") }

/** STUB — WebView of assets/kb/[file]. */
@Composable
fun HtmlScreen(file: String, onBack: () -> Unit) { Text(file) }

/** STUB — first-run 4-page tutorial overlay. */
@Composable
fun TutorialOverlay(onFinish: () -> Unit) {}
