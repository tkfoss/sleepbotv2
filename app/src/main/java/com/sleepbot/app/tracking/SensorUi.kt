package com.sleepbot.app.tracking

import android.content.Context
import android.media.MediaPlayer
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sleepbot.app.R
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.ui.graph.GraphSpec
import com.sleepbot.app.ui.graph.GraphType
import com.sleepbot.app.ui.graph.GraphView
import com.sleepbot.app.ui.main.LockOrientation
import com.sleepbot.app.ui.theme.SB
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private fun yLabels(c: Context) = listOf(c.getString(R.string.trk_label_high), c.getString(R.string.trk_label_medium), c.getString(R.string.trk_label_low))

private fun timeFmt(c: Context) = SimpleDateFormat(if (DateFormat.is24HourFormat(c)) "HH:mm" else "hh:mm", Locale.US)

/** GraphView spec for the movement series; labels every [mod]-th point. */
private fun movementSpec(c: Context, pts: List<Pair<Long, Float>>, mod: Int, bg: androidx.compose.ui.graphics.Color, centered: Boolean, titleSize: Dp): GraphSpec {
    val fmt = timeFmt(c)
    val m = mod.coerceAtLeast(1)
    return GraphSpec(
        type = GraphType.SMOOTH,
        title = c.getString(R.string.trk_accel_graph_title),
        xLabels = pts.mapIndexed { i, p -> if (i % m == 0) fmt.format(Date(p.first)) else "" },
        yLabels = yLabels(c),
        values = FloatArray(pts.size) { pts[it].second },
        max = 100f, diff = 100f,
        markers = false,
        titleCentered = centered,
        titleSize = titleSize,
        background = bg,
        yFade = false,
    )
}

private fun soundSpec(c: Context, s: SoundGraphData, mod: Int, bg: androidx.compose.ui.graphics.Color, centered: Boolean, titleSize: Dp, highlight: IntRange?): GraphSpec {
    val fmt = timeFmt(c)
    val m = mod.coerceAtLeast(1)
    return GraphSpec(
        type = GraphType.SOUND,
        title = c.getString(R.string.trk_sound_graph_title),
        xLabels = List(s.times.size) { i -> if (i % m == 0) fmt.format(Date(s.times[i])) else "" },
        yLabels = yLabels(c),
        values = s.values,
        max = s.max, diff = s.max,
        markers = false,
        titleCentered = centered,
        titleSize = titleSize,
        background = bg,
        highlight = highlight,
        yFade = false,
    )
}

/** Movement + Sound 88dp mini graphs for the entry editor; renders nothing when no data. */
@Composable
fun SensorMiniGraphs(entry: SleepEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val data by produceState<SensorData?>(null, entry.id, entry.punchToken, entry.sleep, entry.awake) {
        value = runCatching { SensorRepository(ctx).load(entry) }.getOrNull()
    }
    val d = data ?: return
    if (d.isEmpty) return
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        if (d.movement.isNotEmpty()) {
            val spec = remember(d) { movementSpec(ctx, d.movement, d.movement.size / 8, SB.EditorBg, true, 18.dp) }
            GraphView(spec, Modifier.fillMaxWidth().height(88.dp))
        }
        d.sound?.let { s ->
            val spec = remember(d) { soundSpec(ctx, s, s.times.size / 8, SB.EditorBg, true, 18.dp, null) }
            GraphView(spec, Modifier.fillMaxWidth().height(88.dp))
        }
    }
}

/** Landscape zoomed Movement/Sound graphs with tap-to-play (legacy ZoomedSensorGraphs). */
@Composable
fun ZoomedSensorScreen(entryId: Long, onBack: () -> Unit) {
    LockOrientation()
    val ctx = LocalContext.current
    val loaded by produceState<Pair<SleepEntry, SensorData>?>(null, entryId) {
        value = runCatching { SensorRepository(ctx).load(entryId) }.getOrNull()
    }
    var highlight by remember { mutableStateOf<IntRange?>(null) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    fun stop() {
        player[0]?.let { runCatching { it.stop() }; it.release() }
        player[0] = null
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) { stop(); highlight = null } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); stop() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(SB.MainBg)) {
        val (entry, data) = loaded ?: return@BoxWithConstraints
        val multiplier = ((entry.awake - entry.sleep) / 3_600_000L).coerceAtLeast(1L).toInt()
        val width = maxWidth * multiplier
        val movement = data.movement.takeIf { it.isNotEmpty() }?.let {
            movementSpec(ctx, it, it.size / (12 * multiplier), SB.PlotBg, false, 24.dp)
        }
        val sound = data.sound
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(width).fillMaxHeight()) {
                val soundGraph: @Composable (Modifier) -> Unit = { mod ->
                    if (sound != null) {
                        val spec = soundSpec(ctx, sound, sound.times.size / (12 * multiplier), SB.PlotBg, false, 24.dp, highlight)
                        GraphView(spec, mod.pointerInput(sound) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                stop(); highlight = null
                                val u = density / 2f
                                val left = 30 * u
                                val contentW = size.width - 50 * u
                                val n = maxOf(sound.times.size, sound.values.size, 2)
                                val slot = ((down.position.x - left) / (contentW / (n - 1))).roundToInt().coerceIn(0, n - 1)
                                val ci = sound.clipNear(slot) ?: return@awaitEachGesture
                                val clip = sound.clips[ci]
                                if (clip.graphStart != -1) highlight = clip.graphStart..clip.graphEnd
                                val f = clip.file ?: return@awaitEachGesture
                                try {
                                    val mp = MediaPlayer()
                                    player[0] = mp
                                    mp.setDataSource(f.absolutePath)
                                    mp.setOnCompletionListener { highlight = null }
                                    mp.setOnPreparedListener { it.start() }
                                    mp.prepareAsync()
                                } catch (e: Exception) {
                                    Log.w("ZoomedSensor", "cannot play ${f.path}", e)
                                    stop()
                                }
                            }
                        })
                    }
                }
                if (movement != null) {
                    GraphView(movement, Modifier.fillMaxWidth().weight(0.5f))
                    Box(Modifier.fillMaxWidth().weight(0.5f)) { soundGraph(Modifier.fillMaxSize()) }
                } else {
                    Box(Modifier.fillMaxWidth().weight(0.5f)) { soundGraph(Modifier.fillMaxSize()) }
                    Box(Modifier.fillMaxWidth().weight(0.5f))
                }
            }
        }
    }
}
