package com.sleepbot.app.tracking

import android.content.Context
import com.sleepbot.app.app
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.data.SoundRecord
import java.io.File

/** Sound graph data: 30 s slots from the first record start to the last record end. */
class SoundGraphData(
    val start: Long,
    val times: LongArray,
    val values: FloatArray,
    val max: Float,
    val clips: List<SoundClip>,
    /** Slot index → index into [clips] (legacy soundRecordMap). */
    val slotClip: Map<Int, Int>,
) {
    /** Legacy tap lookup: nearest mapped slot within 30 slots of [slot]. */
    fun clipNear(slot: Int): Int? {
        var best: Int? = null
        var bestD = Int.MAX_VALUE
        for (k in slotClip.keys) {
            val d = kotlin.math.abs(k - slot)
            if (d < bestD) { bestD = d; best = k }
        }
        return if (best != null && bestD < 30) slotClip[best] else null
    }
}

data class SoundClip(val record: SoundRecord, val graphStart: Int, val graphEnd: Int) {
    val file: File? get() = record.filePath?.let(::File)?.takeIf { it.exists() }
}

class SensorData(
    /** Movement series (time ms, value) after legacy bindValuesFromData downsampling. */
    val movement: List<Pair<Long, Float>>,
    val sound: SoundGraphData?,
) {
    val isEmpty: Boolean get() = movement.isEmpty() && sound == null
}

/** Loads an entry's sensor records (legacy DatabaseHelper.getAccelRecord / getSoundRecords). */
class SensorRepository(context: Context) {
    private val app = context.app
    private val dao get() = app.db.sensors()

    suspend fun load(entry: SleepEntry): SensorData {
        val accel = dao.accel(entry.punchToken)
        val movement = accel?.let { bindValues(it.startTime, it.endTime, it.intervalMs, it.values) } ?: emptyList()
        val sounds = dao.sound(entry.punchToken)
        return SensorData(movement, if (sounds.isEmpty()) null else buildSound(sounds))
    }

    suspend fun load(entryId: Long): Pair<SleepEntry, SensorData>? {
        val e = app.db.entries().get(entryId) ?: return null
        return e to load(e)
    }

    companion object {
        /**
         * Legacy AccelRecord.bindValuesFromData on the dense bucket array: below 2 h every bucket
         * is a point; from 2 h on, buckets are grouped in chunks of `div = hours` and each chunk
         * contributes its max at the time of that max.
         */
        fun bindValues(startTime: Long, endTime: Long, intervalMs: Int, values: FloatArray): List<Pair<Long, Float>> {
            if (values.isEmpty()) return emptyList()
            val interval = if (intervalMs <= 0) 30_000 else intervalMs
            val div = ((endTime - startTime) / 3_600_000L).toInt()
            if (div < 2) return values.mapIndexed { t, v -> (startTime + interval.toLong() * t) to v }
            val out = ArrayList<Pair<Long, Float>>(values.size / div + 1)
            var i = 0
            while (i < values.size) {
                val end = minOf(values.size, i + div)
                var maxV = Float.NEGATIVE_INFINITY
                var maxT = i
                for (j in i until end) if (values[j] > maxV) { maxV = values[j]; maxT = j }
                out += (startTime + interval.toLong() * maxT) to maxV
                i = end
            }
            return out
        }

        /** Legacy ZoomedSensorGraphs/EntryEditActivity slot assignment. */
        fun buildSound(records: List<SoundRecord>): SoundGraphData {
            val interval = 30_000
            val start = records.first().startTime
            val end = records.last().endTime
            val total = ((end - start) / interval).toInt().coerceAtLeast(0) + 1
            val times = LongArray(total) { start + it.toLong() * interval }
            val values = FloatArray(total)
            val bound = records.map { bindValues(it.startTime, it.endTime, it.intervalMs, it.values) }
            var max = Float.NEGATIVE_INFINITY
            bound.forEach { pts -> pts.forEach { if (it.second > max) max = it.second } }
            if (max <= 0f) max = 1f
            val slotClip = HashMap<Int, Int>()
            val clips = ArrayList<SoundClip>()
            var index = 0
            records.forEachIndexed { ri, r ->
                var gStart = -1
                var gEnd: Int
                for ((time, value) in bound[ri]) {
                    while (index < total && times[index] < time) index++
                    if (index >= total) break
                    var found = index
                    if (index == 0) {
                        if (gStart == -1) gStart = index
                        values[index] = value; index++
                    } else if (values[index - 1] != 0f) {
                        values[index] = value
                        if (gStart == -1) gStart = index
                        index++
                    } else if (kotlin.math.abs(time - times[index - 1]) < kotlin.math.abs(time - times[index])) {
                        found = index - 1
                        values[index - 1] = value
                        if (gStart == -1) gStart = index - 1
                    } else {
                        values[index] = value
                        if (gStart == -1) gStart = index
                        index++
                    }
                    slotClip[found] = ri
                }
                gEnd = index
                clips += SoundClip(r, gStart, gEnd)
            }
            return SoundGraphData(start, times, values, max, clips, slotClip)
        }
    }
}
