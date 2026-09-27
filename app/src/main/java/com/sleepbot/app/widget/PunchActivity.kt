package com.sleepbot.app.widget

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.sleepbot.app.MainActivity
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.session.PunchResult
import kotlinx.coroutines.launch

/** Extra (Long) telling MainActivity to show the "Sleep Entry Created!" dialog for this entry. */
const val EXTRA_SHOW_WAKE_DIALOG_ENTRY_ID = "show_wake_dialog_entry_id"

/** Legacy punch-in toast (`Behaviors.getModeMessage`). */
fun modeMessage(context: Context): Int {
    val p = context.app.prefs
    return when {
        p.smartAlarm && !p.trackMotion -> R.string.mode_smart
        p.smartAlarm && !p.recordSound -> R.string.mode_smart_motion
        p.smartAlarm -> R.string.mode_complete
        p.trackMotion && !p.recordSound -> R.string.mode_motion
        p.trackMotion -> R.string.mode_motion_sound
        !p.recordSound -> R.string.mode_classic
        else -> R.string.mode_sound
    }
}

/** Translucent, UI-less widget tap target (legacy `WidgetUpdater`): toggles the punch state. */
class PunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        lifecycleScope.launch {
            when (val r = app.session.toggle()) {
                PunchResult.PunchedIn -> Toast.makeText(applicationContext, modeMessage(this@PunchActivity), Toast.LENGTH_LONG).show()
                is PunchResult.PunchedOut -> startActivity(
                    Intent(this@PunchActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_SHOW_WAKE_DIALOG_ENTRY_ID, r.result.entryId),
                )
                PunchResult.OffsetInFuture -> startActivity(
                    Intent(this@PunchActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                PunchResult.Blocked -> Unit
            }
            finish()
        }
    }
}
