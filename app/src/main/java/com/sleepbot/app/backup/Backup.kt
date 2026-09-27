package com.sleepbot.app.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.sleepbot.app.app
import com.sleepbot.app.data.AccelRecord
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.data.SoundRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Local backup / restore (legacy LocalBackup + DataManager, spec §5). Writes plain JSON with the
 * legacy field layout; restore also accepts legacy obfuscated `backup.bak` files and CSV exports
 * (callers detect those with [looksLikeCsv]).
 */
object Backup {
    const val DB_VERSION = 18

    sealed interface RestoreResult {
        data class Ok(val restored: Int, val total: Int) : RestoreResult
        data class Error(val message: String) : RestoreResult
    }

    fun looksLikeCsv(bytes: ByteArray) = String(bytes, 0, minOf(5, bytes.size), Charsets.UTF_8).contains("Date")

    private fun readAllRows(context: Context): List<SleepEntry> {
        val db = context.app.db.openHelper.readableDatabase
        val out = ArrayList<SleepEntry>()
        db.query("SELECT _id, sleep, awake, note, rating, utc_offset, punch_token, createdDate, modifiedDate, movement_id, voice_id, deleted FROM hours ORDER BY awake ASC").use { c ->
            while (c.moveToNext()) {
                out += SleepEntry(
                    id = c.getLong(0), sleep = c.getLong(1), awake = c.getLong(2), note = c.getString(3) ?: "",
                    rating = c.getInt(4), utcOffsetSec = c.getLong(5), punchToken = c.getLong(6),
                    created = c.getLong(7), modified = c.getLong(8), hasMovement = c.getInt(9) != 0,
                    hasSound = c.getInt(10) != 0, deleted = c.getInt(11) != 0,
                )
            }
        }
        return out
    }

    private fun floats(a: FloatArray) = JSONArray().apply { a.forEach { put(it.toDouble()) } }

    /** Builds the backup JSON (all rows, including soft-deleted ones). */
    suspend fun build(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = context.app.prefs
        val sensors = context.app.db.sensors()
        val entries = JSONArray()
        for (e in readAllRows(context)) {
            val o = JSONObject()
                .put("_id", e.id.toString()).put("sleep", e.sleep.toString()).put("awake", e.awake.toString())
                .put("note", e.note).put("user", "").put("createdDate", e.created.toString())
                .put("modifiedDate", e.modified.toString()).put("calendar_id", "")
                .put("movement_id", if (e.hasMovement) "1" else "").put("voice_id", if (e.hasSound) "1" else "")
                .put("rating", e.rating.toString()).put("aid", "").put("calendar_name", "")
                .put("utc_offset", e.utcOffsetSec.toString()).put("deleted", if (e.deleted) "1" else "0")
                .put("punch_token", e.punchToken.toString())
            val extras = JSONArray()
            sensors.accel(e.punchToken)?.let { r ->
                val v = JSONObject().put("start", r.startTime / 1000).put("end", r.endTime / 1000)
                    .put("interval", r.intervalMs / 1000).put("intervalMs", r.intervalMs).put("data", floats(r.values))
                extras.put(JSONObject().put("category", "movement").put("value", v.toString()))
            }
            for (r in sensors.sound(e.punchToken)) {
                val files = JSONArray()
                r.filePath?.let { files.put(JSONObject().put("start", r.startTime / 1000).put("end", r.endTime / 1000).put("path", "").put("localPath", it)) }
                val v = JSONObject().put("start", r.startTime / 1000).put("end", r.endTime / 1000)
                    .put("interval", r.intervalMs / 1000).put("intervalMs", r.intervalMs).put("data", floats(r.values)).put("files", files)
                extras.put(JSONObject().put("category", "voice").put("value", v.toString()))
            }
            if (extras.length() > 0) o.put("extras", extras)
            entries.put(o)
        }
        val order = prefs.dateFormat()
        val settings = JSONObject()
            .put("dateFormat", when { order.startsWith("dd") -> "d-m-Y"; order.startsWith("yy") -> "Y-m-d"; else -> "m-d-Y" })
            .put("timeFormat", if (prefs.is24h()) "24" else "12")
            .put("locale", Locale.getDefault().toString())
            .put("punchDelay", prefs.punchInDelayMin * 60)
            .put("optimalAmount", prefs.optimalHours.toDouble())
            .put("usageCount", 0)
            .put("debtRange", prefs.debtRangeDays)
            .put("lastReset", prefs.debtResetTime / 1000)
            .put("timezoneId", TimeZone.getDefault().id)
        JSONObject()
            .put("platform", "android")
            .put("softwareBuildTime", System.currentTimeMillis())
            .put("softwareVersion", "4.0.0")
            .put("dbVersion", DB_VERSION)
            .put("systemCharset", "UTF-8")
            .put("createTime", System.currentTimeMillis())
            .put("softwareSettings", settings)
            .put("hourEntries", entries)
            .toString(1)
    }

    suspend fun write(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val json = build(context)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        JSONObject(json).getJSONArray("hourEntries").length()
    }

    /** Legacy obfuscation: base64(URL_SAFE) of bytes shifted by the last (signed) byte. */
    fun decodeLegacy(raw: ByteArray): String {
        val b = Base64.decode(raw, Base64.URL_SAFE)
        if (b.isEmpty()) return ""
        val k = b.last()
        for (i in 0 until b.size - 1) b[i] = (b[i] - k).toByte()
        return String(b, Charsets.UTF_8)
    }

    private fun startOfToday() = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun JSONObject.l(k: String, def: Long = 0) = optString(k, "").toLongOrNull() ?: def

    private fun floatsOf(a: JSONArray?) = if (a == null) FloatArray(0) else FloatArray(a.length()) { a.optDouble(it, 0.0).toFloat() }

    suspend fun restore(context: Context, bytes: ByteArray): RestoreResult = withContext(Dispatchers.IO) {
        val invalid = RestoreResult.Error("Restore file does not have a valid format or it is corrupted, restore canceled.")
        val root = try {
            val text = String(bytes, Charsets.UTF_8).trim()
            JSONObject(if (text.startsWith("{")) text else decodeLegacy(bytes))
        } catch (_: Exception) {
            return@withContext invalid
        }
        if (root.optInt("dbVersion", DB_VERSION) > DB_VERSION) {
            return@withContext RestoreResult.Error("The given database is too new to be restored.")
        }
        val arr = root.optJSONArray("hourEntries") ?: return@withContext invalid
        val db = context.app.db
        val known = HashSet<Long>()
        db.openHelper.readableDatabase.query("SELECT modifiedDate FROM hours").use { c -> while (c.moveToNext()) known += c.getLong(0) }
        var restored = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val modified = o.l("modifiedDate")
            if (modified != 0L && modified in known) continue
            val token = o.l("punch_token").takeIf { it != 0L } ?: o.l("sleep")
            val extras = o.optJSONArray("extras")
            var hasMovement = o.optString("movement_id").isNotEmpty()
            var hasSound = o.optString("voice_id").isNotEmpty()
            try {
                if (extras != null) for (j in 0 until extras.length()) {
                    val x = extras.getJSONObject(j)
                    val v = JSONObject(x.getString("value"))
                    val start = v.optLong("start") * 1000
                    val data = floatsOf(v.optJSONArray("data"))
                    val interval = v.optInt("intervalMs", v.optInt("interval", 1) * 1000).coerceAtLeast(1)
                    val end = if (v.has("end")) v.optLong("end") * 1000 else start + data.size.toLong() * interval
                    when (x.optString("category")) {
                        "movement" -> {
                            db.sensors().insertAccel(AccelRecord(punchToken = token, startTime = start, endTime = end, intervalMs = interval, values = data))
                            hasMovement = true
                        }
                        "voice" -> {
                            val file = v.optJSONArray("files")?.optJSONObject(0)?.optString("localPath")?.takeIf { it.isNotEmpty() }
                            db.sensors().insertSound(SoundRecord(punchToken = token, startTime = start, endTime = end, intervalMs = interval, values = data, filePath = file))
                            hasSound = true
                        }
                    }
                }
                db.entries().insert(
                    SleepEntry(
                        sleep = o.l("sleep"), awake = o.l("awake"), note = o.optString("note", ""),
                        rating = o.optString("rating").toIntOrNull() ?: -1,
                        utcOffsetSec = o.l("utc_offset"), punchToken = token,
                        created = o.l("createdDate", System.currentTimeMillis()),
                        modified = if (modified != 0L) modified else System.currentTimeMillis(),
                        hasMovement = hasMovement, hasSound = hasSound,
                        deleted = o.optString("deleted") == "1",
                    ),
                )
                if (modified != 0L) known += modified
                restored++
            } catch (_: Exception) {
                // Skip malformed rows, keep going.
            }
        }
        context.app.prefs.debtResetTime = startOfToday()
        RestoreResult.Ok(restored, arr.length())
    }
}
