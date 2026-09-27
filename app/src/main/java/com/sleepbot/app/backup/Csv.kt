package com.sleepbot.app.backup

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.sleepbot.app.app
import com.sleepbot.app.data.SleepEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Legacy CsvExporter / export-file restore (spec §4). */
object Csv {
    const val HEADER = "Date, Sleep Time, Wake Time, Hours,Note"
    const val EMPTY = "No entry found during selected period."

    /** Date patterns offered by "Restore from an Export file". */
    val IMPORT_PATTERNS = listOf("MM/dd/yy", "dd/MM/yy", "yy/MM/dd")

    /** Legacy `String.valueOf(hours)` truncated to at most 4 chars; −1.0 for invalid rows. */
    fun hoursCell(e: SleepEntry): String {
        val h = e.durationHours
        val s = h.toString()
        return if (s.length > 4) s.substring(0, 4) else s
    }

    /** CSV body in the exact legacy layout (no quoting, `\n` line endings). */
    fun build(entries: List<SleepEntry>, datePattern: String): String {
        if (entries.isEmpty()) return EMPTY
        val date = SimpleDateFormat(datePattern, Locale.getDefault())
        val hm = SimpleDateFormat("HH:mm", Locale.US)
        val sb = StringBuilder(HEADER).append('\n')
        for (e in entries) {
            if (e.deleted) continue
            sb.append(date.format(Date(e.awake))).append(',')
                .append(hm.format(Date(e.sleep))).append(',')
                .append(hm.format(Date(e.awake))).append(',')
                .append(hoursCell(e)).append(',')
                .append(e.note).append('\n')
        }
        return sb.toString()
    }

    /**
     * Writes the legacy CSV format for [entries] (already sorted awake DESC) to the cache dir and
     * returns an ACTION_SEND chooser intent (FileProvider URI attached, subject/summary text set).
     */
    fun exportShareIntent(context: Context, entries: List<SleepEntry>, summary: String): Intent {
        val pattern = context.app.prefs.dateFormat()
        val now = System.currentTimeMillis()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val name = SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)).replace('/', '_') + "_" + now + ".csv"
        val file = File(dir, name)
        file.writeText(build(entries, pattern))
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val stamp = SimpleDateFormat(pattern + " HH:mm", Locale.getDefault()).format(Date(now))
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "SleepBot: Sleep data exported at$stamp")
            .putExtra(Intent.EXTRA_TEXT, "\n" + summary)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        return Intent.createChooser(send, "Export to").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    data class ImportResult(val imported: Int, val failed: Boolean)

    /**
     * Parses an export file (legacy restore): skip header, `awake = parse(date + wake,
     * "<pattern>HH:mm")`, `sleep = awake − hours`, note = everything after the 4th comma. The
     * sleep-time column is ignored. A parse error aborts the rest of the file.
     */
    fun parse(text: String, datePattern: String): Pair<List<SleepEntry>, Boolean> {
        val fmt = SimpleDateFormat(datePattern + "HH:mm", Locale.US).apply { isLenient = false }
        val out = ArrayList<SleepEntry>()
        val lines = text.replace("\r\n", "\n").split('\n')
        for ((i, raw) in lines.withIndex()) {
            if (i == 0 || raw.isBlank()) continue
            try {
                val parts = raw.split(',', limit = 5)
                val date = parts[0].trim()
                val wake = parts[2].trim()
                val hours = parts[3].trim().toDouble()
                val note = if (parts.size > 4) parts[4] else ""
                val awake = fmt.parse(date + wake)!!.time
                val sleep = awake - (hours * 3_600_000).toLong()
                out += SleepEntry(sleep = sleep, awake = awake, note = note, punchToken = awake)
            } catch (_: Exception) {
                return out to true
            }
        }
        return out to false
    }

    suspend fun import(context: Context, text: String, datePattern: String): ImportResult {
        val (entries, failed) = parse(text, datePattern)
        if (entries.isNotEmpty()) context.app.db.entries().insertAll(entries)
        return ImportResult(entries.size, failed)
    }
}
