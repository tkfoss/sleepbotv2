package com.sleepbot.app.tracking

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Legacy MovementEntryIndex: sensitivity index 0..4 → S. */
internal val MOVEMENT_ENTRY_INDEX = floatArrayOf(3.0f, 2.0f, 1.0f, 0.5f, 0.2f)

/** S for a motion-sensitivity pref index. */
internal fun movementS(index: Int): Float = MOVEMENT_ENTRY_INDEX[index.coerceIn(0, 4)]

/** Legacy MOVEMENT_MAX = (int)(S * 50). */
fun movementMax(index: Int): Int = (movementS(index) * 50).toInt()

/** Max-per-bucket series, relative to [base] (legacy AccelRecord.bindValuesToData buckets). */
internal class BucketSeries(val base: Long, val intervalMs: Int = 30_000) {
    var values = FloatArray(0)
        private set

    @Synchronized
    fun add(t: Long, v: Float) {
        if (t < base) return
        val k = ((t - base) / intervalMs).toInt()
        if (k >= values.size) values = values.copyOf(maxOf(k + 1, values.size * 2, 64))
        if (k + 1 > size) size = k + 1
        if (v > values[k]) values[k] = v
    }

    var size = 0
        private set

    @Synchronized
    fun snapshot(): FloatArray = values.copyOf(size)
}

/** Live 100-point graph buffer for the night screen, already in draw order. */
data class LiveMovement(val values: FloatArray, val labels: List<String>) {
    companion object { val EMPTY = LiveMovement(FloatArray(0), emptyList()) }
}

/**
 * Exact port of the 3.2.8 RecordMovementService per-event algorithm (spec tracking.md §4.2).
 * Not thread-safe: feed it from a single sensor thread.
 */
internal class MotionTracker(
    sensitivityIndex: Int,
    private val use24h: Boolean,
    /** Session bucket store; values stored are `value * S/2`. */
    val series: BucketSeries,
    /** Called with each completed window's (unscaled) max, after the ring buffer has been updated. */
    private val onWindow: (newValue: Int, initialized: Boolean, now: Long) -> Unit,
    private val onLive: (LiveMovement) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : SensorEventListener {
    val s = movementS(sensitivityIndex)
    private val sensitivityFactor = s / 2.0f
    val movementMax = (s * 50).toInt()
    val threshold = movementMax * 0.5f

    private var lastTime = 0L
    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
    private val temp = IntArray(TEMP_SIZE)
    private var tempI = 0
    private val graphY = IntArray(GRAPH_SIZE)
    private val graphX = Array(GRAPH_SIZE) { "" }
    private var graphI = 0
    private var hasOverflow = false
    private var lastLabelT = 0L
    var hasInitialized = false
        private set
    private val startedAt = clock()
    private val fmt = SimpleDateFormat(if (use24h) "HH:mm" else "hh:mm", Locale.US)

    override fun onSensorChanged(e: SensorEvent) {
        if (e.values.size < 3) return
        onSample(e.values[0], e.values[1], e.values[2], clock())
    }

    fun onSample(x: Float, y: Float, z: Float, now: Long) {
        if (lastTime == 0L) { lastTime = now; return }
        val dt = now - lastTime
        if (dt < MIN_DIFF) return
        lastTime = now
        val value = (abs((x + y + z) - (lastX + lastY + lastZ)) / dt * 10000f).toInt()
        lastX = x; lastY = y; lastZ = z
        if (hasInitialized) series.add(now, (value * sensitivityFactor).toInt().toFloat())
        if (tempI >= TEMP_SIZE - 1) {
            tempI = 0
            var newValue = Int.MIN_VALUE
            for (i in 0 until TEMP_SIZE) {
                if (temp[i] >= movementMax) { newValue = temp[i]; break }
                else if (temp[i] > newValue) newValue = temp[i]
            }
            if (graphI >= GRAPH_SIZE - 1) { hasOverflow = true; graphI = 0 } else graphI++
            graphY[graphI] = newValue
            if (now - lastLabelT > 120_000) {
                graphX[graphI] = fmt.format(Date(now)); lastLabelT = now
            } else graphX[graphI] = ""
            onLive(live())
            onWindow(newValue, hasInitialized, now)
            if (!hasInitialized && startedAt < now) hasInitialized = true
        } else {
            temp[tempI++] = value
        }
    }

    /** Ring buffer from start to end (legacy updateValues(start, graph_y, graph_x, end)). */
    private fun live(): LiveMovement {
        val start = if (hasOverflow) (graphI + 1) % GRAPH_SIZE else 0
        val len = if (hasOverflow) GRAPH_SIZE else graphI + 1
        val v = FloatArray(len) { graphY[(start + it) % GRAPH_SIZE].toFloat() }
        val l = List(len) { graphX[(start + it) % GRAPH_SIZE] }
        return LiveMovement(v, l)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val MIN_DIFF = 175L
        const val TEMP_SIZE = 20
        const val GRAPH_SIZE = 100
        const val AUTO_SAVE_INTERVAL = 600_000L
    }
}
