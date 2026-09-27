package com.sleepbot.app.ui.overview

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.data.Debt
import com.sleepbot.app.ui.graph.GraphView
import com.sleepbot.app.ui.main.LockOrientation
import com.sleepbot.app.ui.main.PressableImage
import com.sleepbot.app.ui.theme.SB
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Tracking tab: "Current Trend" over 10 days + CURRENT DEBT / AVERAGE SLEEP/DAY. */
@Composable
fun OverviewTab(onOpenGraphs: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val app = context.app
    val prefs = app.prefs
    val prefTick by remember { prefs.changes() }.collectAsState(null)
    val today = Debt.todayStart()
    val from = minOf(today - 9 * Debt.DAY, Debt.windowFrom(prefs))
    val entries by remember(prefTick, today) { app.db.entries().observeRange(from - Debt.DAY, today + Debt.DAY) }.collectAsState(emptyList())
    val spec = remember(entries, prefTick) {
        if (prefs.overviewGraph == 1) Charts.pattern(entries, 10, res.getString(R.string.current_sleep_records), prefs, overview = true)
        else Charts.trend(entries.filter { it.awake > today - 9 * Debt.DAY }, 10, res.getString(R.string.current_trend), prefs, ratings = false, overview = true)
    }
    val summary = remember(entries, prefTick) { Debt.summarize(entries, prefs) }

    Column(Modifier.fillMaxSize().background(SB.TabColor).padding(6.dp)) {
        Box(
            Modifier.weight(7f).fillMaxWidth().padding(top = 4.dp)
                .clip(RoundedCornerShape(10.dp)).background(SB.EditorBg)
                .border(1.dp, Color.White, RoundedCornerShape(10.dp)).padding(5.dp)
                .clickable(onClick = onOpenGraphs),
        ) {
            GraphView(spec, Modifier.fillMaxSize())
        }
        Row(Modifier.weight(3f).fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.current_debt), color = SB.GraphLine, fontSize = 22.sp)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.avg_sleep_day), color = SB.GraphLine, fontSize = 22.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(TimeFormat.numberToHours(summary.debt), color = Color.White, fontSize = 30.sp)
                Spacer(Modifier.height(8.dp))
                Text(TimeFormat.numberToHours(summary.averagePerDay), color = Color.White, fontSize = 30.sp)
            }
        }
    }
}

private enum class ChartKind { TREND, LENGTH, PATTERN, SLEEP, WAKE }

/** Landscape graph screen (legacy GraphActivity). */
@Composable
fun GraphsScreen(onBack: () -> Unit) {
    LockOrientation()
    val context = LocalContext.current
    val res = LocalResources.current
    val app = context.app
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val ranges = stringArrayResource(R.array.range_choices)
    var rangeIdx by rememberSaveable { mutableIntStateOf(1) }
    var kind by rememberSaveable { mutableStateOf(ChartKind.TREND) }
    var ratings by remember { mutableStateOf(prefs.graphRatings) }
    var menu by remember { mutableStateOf(false) }
    var firstAwake by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { firstAwake = app.db.entries().firstAwake() }

    val today = Debt.todayStart()
    val days = when (rangeIdx) {
        0 -> firstAwake?.let { ((today - it) / Debt.DAY + 2).toInt().coerceIn(1, 5990) } ?: 10
        else -> intArrayOf(0, 10, 30, 60, 180, 360)[rangeIdx]
    }
    val entries by remember(days) { app.db.entries().observeRange(today - (days - 1) * Debt.DAY - Debt.DAY, today + Debt.DAY) }.collectAsState(emptyList())
    val inRange = remember(entries, days) { entries.filter { it.awake > today - (days - 1) * Debt.DAY } }
    val spec = remember(inRange, kind, ratings, days) {
        when (kind) {
            ChartKind.TREND -> Charts.trend(inRange, days, res.getString(R.string.title_trend), prefs, ratings, overview = false)
            ChartKind.LENGTH -> Charts.length(inRange, days, res.getString(R.string.title_length))
            ChartKind.PATTERN -> Charts.pattern(entries, days, res.getString(R.string.title_pattern), prefs, overview = false)
            ChartKind.SLEEP -> Charts.hourTally(inRange, false, res.getString(R.string.title_sleep), prefs.is24h())
            ChartKind.WAKE -> Charts.hourTally(inRange, true, res.getString(R.string.title_wake), prefs.is24h())
        }
    }
    val layer = rememberGraphicsLayer()

    fun share() = scope.launch {
        val bmp = layer.toImageBitmap().asAndroidBitmap()
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "exports").apply { mkdirs() }.let { dir ->
                File(dir, "${spec.title.replace(' ', '_')}_${System.currentTimeMillis()}.png").also { f ->
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                }
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, spec.title).putExtra(Intent.EXTRA_TEXT, spec.title + " @SleepBot")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, res.getString(R.string.share)))
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(Modifier.width(110.dp).fillMaxHeight().clickable { menu = true }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ranges[rangeIdx], color = Color.White, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Text("◢", color = Color.Gray, fontSize = 12.sp)
                }
                DropdownMenu(menu, { menu = false }) {
                    ranges.forEachIndexed { i, r -> DropdownMenuItem({ Text(r) }, { rangeIdx = i; menu = false }) }
                }
            }
            ChartKind.entries.forEach { k ->
                val label = stringResource(when (k) {
                    ChartKind.TREND -> R.string.trend; ChartKind.LENGTH -> R.string.length; ChartKind.PATTERN -> R.string.pattern
                    ChartKind.SLEEP -> R.string.sleep_caps; ChartKind.WAKE -> R.string.wake_caps
                })
                Box(
                    Modifier.weight(1f).fillMaxHeight().padding(horizontal = 4.dp)
                        .background(if (kind == k) Color(0xFF5A5A5A) else Color(0xFF3C3C3C), RoundedCornerShape(2.dp))
                        .clickable { kind = k },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = Color.White, fontSize = 14.sp) }
            }
            PressableImage(R.drawable.share_icon, R.drawable.share_icon, stringResource(R.string.share), { share() },
                Modifier.padding(horizontal = 10.dp).size(28.dp))
        }
        Box(Modifier.fillMaxSize()) {
            GraphView(spec, Modifier.fillMaxSize().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            })
            if (kind == ChartKind.TREND) {
                Row(Modifier.align(Alignment.TopEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End) {
                    Checkbox(ratings, { ratings = it; prefs.graphRatings = it },
                        colors = CheckboxDefaults.colors(checkedColor = SB.HoloBlue, uncheckedColor = Color.Gray))
                    Text(stringResource(R.string.ratings), color = Color.White, fontSize = 18.sp)
                }
            }
        }
    }
}
