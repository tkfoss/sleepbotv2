package com.sleepbot.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.sleepbot.app.tracking.NightActivity
import com.sleepbot.app.ui.main.SleepBotNav
import com.sleepbot.app.ui.theme.SleepBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.session.refresh()
        handleIntent(intent)
        setContent {
            SleepBotTheme {
                val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SleepBotNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_WAKE_DIALOG, -1L) ?: -1L
        if (id > 0) {
            app.session.pendingWakeDialog.value = id
            intent?.removeExtra(EXTRA_WAKE_DIALOG)
        }
    }

    companion object {
        /** Long entry id: open on the home tab with the "Sleep Entry Created!" dialog. */
        const val EXTRA_WAKE_DIALOG = "show_wake_dialog_entry_id"
    }

    override fun onResume() {
        super.onResume()
        app.session.refresh()
        app.alarms.refresh()
        // Legacy SensorsActivity.shouldLaunchSensors: while tracking motion, the night screen is the app.
        if (!app.prefs.isAwake && app.prefs.trackMotion && app.session.pendingWakeDialog.value == null) {
            startActivity(NightActivity.intent(this))
        }
    }
}
