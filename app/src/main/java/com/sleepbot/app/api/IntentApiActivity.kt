package com.sleepbot.app.api

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.sleepbot.app.MainActivity
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.session.PunchResult
import com.sleepbot.app.session.SleepSession
import com.sleepbot.app.widget.EXTRA_SHOW_WAKE_DIALOG_ENTRY_ID
import kotlinx.coroutines.launch

/**
 * Third-party Intent API (legacy `IntentAPI`, action `android.intent.action.RUN`).
 * Extras: "Sleep" / "Wake up" (booleans), "SleepTime" / "WakeUpTime" (ms), "Note", "DialogFree".
 * Result extras: `header` (int) and `status` (String). Key/secret auth, `isAuto` and `dataAPI` are dropped.
 */
class IntentApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val intent = intent
        val prefs = app.prefs
        if (!prefs.allowIntegration && intent.getStringExtra("dummy") != "dummybugger") {
            return finishWith(Activity.RESULT_CANCELED, 100, getString(R.string.intent_error_reject) + " (user)", toast = true)
        }
        val wake = intent.getBooleanExtra("Wake up", false)
        val sleep = intent.getBooleanExtra("Sleep", false)
        val restrict = when {
            wake && sleep -> SleepSession.Restrict.NONE
            wake -> SleepSession.Restrict.WAKE_ONLY
            sleep -> SleepSession.Restrict.SLEEP_ONLY
            else -> return finishWith(Activity.RESULT_CANCELED, 400, getString(R.string.intent_error_invalid) + " (data)", toast = true)
        }
        val sleepTime = intent.getLongExtra("SleepTime", 0L).takeIf { it != 0L }
        val wakeTime = intent.getLongExtra("WakeUpTime", 0L).takeIf { it != 0L }
        val refSleep = sleepTime ?: app.session.sleepStart
        if (wakeTime != null && wakeTime < refSleep) {
            return finishWith(Activity.RESULT_CANCELED, 400, getString(R.string.intent_error_invalid) + " (data)", toast = true)
        }
        val overrides = SleepSession.Overrides(sleep = sleepTime, awake = wakeTime, note = intent.getStringExtra("Note"))
        val dialogFree = intent.getBooleanExtra("DialogFree", false)
        lifecycleScope.launch {
            when (val r = app.session.toggle(restrict, overrides)) {
                PunchResult.PunchedIn -> finishWith(Activity.RESULT_OK, 200, "Sleep")
                is PunchResult.PunchedOut -> {
                    if (!dialogFree) startActivity(
                        Intent(this@IntentApiActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra(EXTRA_SHOW_WAKE_DIALOG_ENTRY_ID, r.result.entryId),
                    )
                    finishWith(Activity.RESULT_OK, 200, "Wake up")
                }
                PunchResult.OffsetInFuture -> {
                    startActivity(Intent(this@IntentApiActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finishWith(Activity.RESULT_CANCELED, 400, getString(R.string.intent_error_invalid) + " (data)")
                }
                PunchResult.Blocked -> finishWith(Activity.RESULT_CANCELED, 400, getString(R.string.intent_error_invalid) + " (data)")
            }
        }
    }

    private fun finishWith(result: Int, header: Int, status: String, toast: Boolean = false) {
        setResult(result, Intent().putExtra("header", header).putExtra("status", status))
        if (toast) Toast.makeText(applicationContext, status, Toast.LENGTH_LONG).show()
        if (result != Activity.RESULT_OK) Log.e(TAG, status)
        finish()
    }

    private companion object { const val TAG = "sleepbot api" }
}
