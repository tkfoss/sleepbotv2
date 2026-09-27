package com.sleepbot.app.api

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.sleepbot.app.MainActivity
import com.sleepbot.app.app
import com.sleepbot.app.session.PunchResult
import com.sleepbot.app.session.SleepSession
import com.sleepbot.app.widget.EXTRA_SHOW_WAKE_DIALOG_ENTRY_ID
import com.sleepbot.app.widget.modeMessage
import kotlinx.coroutines.launch

/**
 * URI API (legacy `UriAPI`): `sb://sleep`, `sb://wake`, `sb://any`, `sb://isSleeping`
 * (result code 0 = AWAKE, 1 = SLEEPING), `sb://debug_code`. Requires "Enable Integration".
 */
class UriApiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val data = intent.data
        if (intent.action != Intent.ACTION_VIEW || data?.scheme != "sb") return done("Invalid Scheme")
        if (!app.prefs.allowIntegration) return done("Punch in from 3rd party integration is restricted.")
        val restrict = when (val host = data.host.orEmpty()) {
            "isSleeping" -> {
                setResult(if (app.prefs.isAwake) 0 else 1)
                return done(null)
            }
            "debug_code" -> {
                Toast.makeText(applicationContext, "Debug key: " + (packageManager.getPackageInfo(packageName, 0).firstInstallTime % 1000000), Toast.LENGTH_SHORT).show()
                return done(null)
            }
            "sleep" -> SleepSession.Restrict.SLEEP_ONLY
            "wake" -> SleepSession.Restrict.WAKE_ONLY
            "any" -> SleepSession.Restrict.NONE
            else -> return done("Invalid mode: $host")
        }
        val dialogFree = intent.getBooleanExtra("DialogFree", false)
        lifecycleScope.launch {
            when (val r = app.session.toggle(restrict)) {
                PunchResult.PunchedIn -> Toast.makeText(applicationContext, modeMessage(this@UriApiActivity), Toast.LENGTH_LONG).show()
                is PunchResult.PunchedOut -> if (!dialogFree) startActivity(
                    Intent(this@UriApiActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_SHOW_WAKE_DIALOG_ENTRY_ID, r.result.entryId),
                )
                PunchResult.OffsetInFuture ->
                    startActivity(Intent(this@UriApiActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                PunchResult.Blocked -> Log.i(TAG, "Request blocked ($restrict)")
            }
            finish()
        }
    }

    private fun done(log: String?) {
        if (log != null) Log.i(TAG, log)
        finish()
    }

    private companion object { const val TAG = "sleepbot api" }
}
