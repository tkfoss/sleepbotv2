package com.sleepbot.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.sleepbot.app.app
import com.sleepbot.app.data.SoundRecord
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Legacy SoundEntryIndex: 0.12 * {5, 2, 1, 0.5, 0.2}[i] of full scale (32767).
 * Index 0 = highest threshold = least sensitive ("Very Low (record only very loud sounds)"),
 * index 4 = lowest threshold = most sensitive ("Very High (record nearly all sounds)").
 */
private val SOUND_FACTORS = floatArrayOf(5f, 2f, 1f, 0.5f, 0.2f)

fun soundThreshold(index: Int): Int = (0.12f * SOUND_FACTORS[index.coerceIn(0, 4)] * 32767).toInt()

/**
 * Port of RecordVoiceService/RecordingThread using AudioRecord: ~1 Hz peak amplitude while idle;
 * when a peak exceeds the threshold a WAV clip is recorded in 5 s chunks until a chunk (after the
 * first) peaks below the threshold. Runs on its own thread.
 */
internal class SoundTracker(
    private val context: Context,
    private val token: Long,
    sensitivityIndex: Int,
    private val onAmplitude: (Int) -> Unit,
) {
    private val threshold = soundThreshold(sensitivityIndex)
    private val dir = File(context.filesDir, "sounds").apply { mkdirs() }
    @Volatile private var running = false
    private var thread: Thread? = null

    private class Active(val start: Long, val path: String) {
        val series = BucketSeries(start)
        var id = 0L
    }

    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        running = true
        thread = Thread({ runLoop() }, "SleepBotSound").apply { start() }
        return true
    }

    /** Stops recording and saves the last record (blocking, call off the main thread if possible). */
    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(minBuf, CHUNK * 2 * 4)
        for (src in intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT)) {
            try {
                val ar = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
                if (ar.state == AudioRecord.STATE_INITIALIZED) {
                    ar.startRecording()
                    if (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) return ar
                }
                ar.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source $src failed", e)
            }
            if (!running) return null
        }
        return null
    }

    private fun runLoop() {
        var ar = open()
        if (ar == null) {
            // Legacy: mic busy → wait 5 s and retry once with the default source.
            Thread.sleep(5000)
            if (!running) return
            ar = open() ?: return
        }
        val buf = ShortArray(CHUNK)
        val pre = ArrayDeque<ShortArray>()
        var record: Active? = null
        var writer: WavWriter? = null
        var idlePeak = 0; var idleCount = 0
        var clipPeak = 0; var clipCount = 0; var counter = 0
        try {
            while (running) {
                val n = ar.read(buf, 0, CHUNK)
                if (n <= 0) { if (n < 0) Thread.sleep(100); continue }
                var peak = 0
                for (i in 0 until n) { val a = abs(buf[i].toInt()); if (a > peak) peak = a }
                peak = peak.coerceAtMost(32767)
                onAmplitude(peak)
                val now = System.currentTimeMillis()
                val w = writer
                if (w == null) {
                    pre.addLast(buf.copyOf(n)); if (pre.size > CHUNKS_PER_SEC) pre.removeFirst()
                    if (peak > idlePeak) idlePeak = peak
                    if (++idleCount < CHUNKS_PER_SEC) continue
                    val amp = idlePeak
                    idlePeak = 0; idleCount = 0
                    record?.series?.add(now, amp.toFloat())
                    if (amp > threshold) {
                        record?.let { save(it, now) }
                        val r = Active(now, File(dir, "$now.wav").absolutePath)
                        r.series.add(now, amp.toFloat())
                        record = r
                        writer = try { WavWriter(File(r.path)).also { ww -> pre.forEach { ww.write(it, it.size) } } } catch (e: Exception) { Log.w(TAG, "clip open failed", e); null }
                        pre.clear()
                        clipPeak = 0; clipCount = 0; counter = 0
                    }
                } else {
                    try { w.write(buf, n) } catch (e: Exception) { Log.w(TAG, "clip write failed", e) }
                    if (peak > clipPeak) clipPeak = peak
                    if (++clipCount < CHUNKS_PER_CLIP_STEP) continue
                    val sec = clipPeak
                    clipPeak = 0; clipCount = 0
                    val r = record!!
                    r.series.add(now, sec.toFloat())
                    val tooLong = now - r.start > MAX_CLIP_MS
                    if ((sec < threshold && counter > 0) || tooLong) {
                        w.close(); writer = null
                        save(r, now)
                    }
                    counter++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sound loop failed", e)
        } finally {
            try { ar.stop() } catch (_: Exception) {}
            ar.release()
            writer?.close()
            // Legacy bug fix: always persist the last record, clip in progress or not.
            record?.let { save(it, System.currentTimeMillis()) }
            onAmplitude(0)
        }
    }

    private fun save(r: Active, now: Long) {
        val rec = SoundRecord(
            id = r.id, punchToken = token, startTime = r.start, endTime = now,
            intervalMs = r.series.intervalMs, values = r.series.snapshot(), filePath = r.path,
        )
        try {
            runBlocking {
                val dao = context.app.db.sensors()
                if (r.id == 0L) r.id = dao.insertSound(rec) else dao.updateSound(rec)
            }
        } catch (e: Exception) {
            Log.w(TAG, "save sound failed", e)
        }
    }

    companion object {
        private const val TAG = "SoundTracker"
        const val RATE = 16_000
        private const val CHUNK = RATE / 10           // 100 ms
        private const val CHUNKS_PER_SEC = 10
        private const val CHUNKS_PER_CLIP_STEP = 50   // 5 s (legacy 4–5 s)
        private const val MAX_CLIP_MS = 5 * 60_000L
    }
}

/** Minimal 16-bit mono PCM WAV writer. */
internal class WavWriter(file: File, private val rate: Int = SoundTracker.RATE) {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var dataBytes = 0L
    private var bb = ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN)

    fun write(samples: ShortArray, n: Int) {
        if (bb.capacity() < n * 2) bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear()
        for (i in 0 until n) bb.putShort(samples[i])
        raf.write(bb.array(), 0, n * 2)
        dataBytes += n * 2
    }

    fun close() {
        try {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
            raf.seek(0); raf.write(h.array())
        } finally {
            raf.close()
        }
    }
}
