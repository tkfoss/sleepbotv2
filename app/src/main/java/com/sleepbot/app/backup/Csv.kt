package com.sleepbot.app.backup

import android.content.Context
import android.content.Intent
import com.sleepbot.app.data.SleepEntry

/** STUB — owned by the alarm/settings agent. */
object Csv {
    /**
     * Writes the legacy CSV format for [entries] (already sorted awake DESC) to the cache dir and
     * returns an ACTION_SEND chooser intent (FileProvider URI attached, subject/summary text set).
     */
    fun exportShareIntent(context: Context, entries: List<SleepEntry>, summary: String): Intent = Intent()
}
