package com.sleepbot.app.ui.entries

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.data.Entries
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.tracking.SensorMiniGraphs
import com.sleepbot.app.ui.common.BarAction
import com.sleepbot.app.ui.common.BottomActionBar
import com.sleepbot.app.ui.common.DateButton
import com.sleepbot.app.ui.common.DatePickDialog
import com.sleepbot.app.ui.common.StarRating
import com.sleepbot.app.ui.common.drawableBackground
import com.sleepbot.app.ui.common.TimePickDialog
import com.sleepbot.app.ui.main.PressableImage
import com.sleepbot.app.ui.theme.SB
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private enum class Pick { SLEEP_TIME, SLEEP_DATE, WAKE_TIME, WAKE_DATE }

/** Entry editor (legacy EntryEditActivity). [entryId] −1 creates a new entry on SAVE. */
@Composable
fun EntryEditScreen(entryId: Long, onBack: () -> Unit, onOpenSensors: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val prefs = app.prefs
    val dao = app.db.entries()
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()

    var original by remember { mutableStateOf<SleepEntry?>(null) }
    var loaded by remember { mutableStateOf(entryId < 0) }
    val nowMin = remember { LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES) }
    var sleep by remember { mutableStateOf(nowMin) }
    var wake by remember { mutableStateOf(nowMin) }
    var rating by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        if (entryId >= 0) dao.get(entryId)?.let { e ->
            original = e
            sleep = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.sleep), zone)
            wake = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.awake), zone)
            rating = e.rating.coerceAtLeast(0)
            note = e.note
        }
        loaded = true
    }
    if (!loaded) return

    val sleepMs = sleep.atZone(zone).toInstant().toEpochMilli()
    val wakeMs = wake.atZone(zone).toInstant().toEpochMilli()
    val durationH = (wakeMs - sleepMs) / 3_600_000.0
    val valid = wakeMs - sleepMs >= 0
    val is24 = prefs.is24h()
    val timeFmt = remember(is24) { DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "hh:mm a", Locale.getDefault()) }
    val dateFmt = remember { DateTimeFormatter.ofPattern(prefs.dateFormat(), Locale.getDefault()) }
    val titleFmt = remember { DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()) }

    fun save() = scope.launch {
        val now = System.currentTimeMillis()
        val o = original
        if (o == null) {
            dao.insert(SleepEntry(sleep = sleepMs, awake = wakeMs, note = note, rating = if (rating == 0) -1 else rating,
                utcOffsetSec = java.util.TimeZone.getDefault().getOffset(wakeMs) / 1000L))
        } else {
            dao.update(o.copy(sleep = sleepMs, awake = wakeMs, note = note, rating = if (rating == 0) o.rating.coerceAtMost(0) else rating, modified = now))
        }
        onBack()
    }

    val layer = rememberGraphicsLayer()
    LaunchedEffect(sharing) {
        if (!sharing) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        val bmp = layer.toImageBitmap().asAndroidBitmap()
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, "exports").apply { mkdirs() }.let { dir ->
                File(dir, "entry_${System.currentTimeMillis()}.png").also { f ->
                    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
                }
            }
        }
        sharing = false
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_entry)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_entry)))
    }

    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(SB.EditorBg).systemBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp)) {
            Text(wake.format(titleFmt), color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
            PressableImage(R.drawable.share_icon, R.drawable.share_icon, stringResource(R.string.share_entry), { sharing = true },
                Modifier.align(Alignment.CenterEnd).size(24.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            Column(Modifier.background(SB.EditorBg).drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }) {
                original?.let { e -> SensorMiniGraphs(e, onClick = { onOpenSensors(e.id) }) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.duration), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (valid) TimeFormat.durationText(context, durationH) else "", color = Color.White, fontSize = 16.sp)
                }
                TimeRow(stringResource(R.string.sleep_time), sleep.format(timeFmt), sleep.format(dateFmt), { pick = Pick.SLEEP_TIME }, { pick = Pick.SLEEP_DATE })
                TimeRow(stringResource(R.string.wake_time), wake.format(timeFmt), wake.format(dateFmt), { pick = Pick.WAKE_TIME }, { pick = Pick.WAKE_DATE })
                if (sharing) {
                    Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(48.dp).background(Color(0xFF0B1932))) {
                        Box(Modifier.fillMaxSize().drawableBackground(R.drawable.namebar))
                        Image(painterResource(R.drawable.sleepbot_logo), null, Modifier.align(Alignment.Center).height(48.dp))
                    }
                }
                StarRating(rating, { rating = it }, Modifier.fillMaxWidth().padding(vertical = 18.dp))
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    Text(stringResource(R.string.note), color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
                    BasicTextField(
                        note, { note = it }, Modifier.weight(1f),
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(SB.HoloBlue), maxLines = 20,
                        decorationBox = { inner ->
                            Column {
                                inner()
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(SB.SpinnerLine))
                            }
                        },
                    )
                }
            }
            if (!valid) Text(stringResource(R.string.invalid_time), color = SB.Error, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(16.dp))
        }
        BottomActionBar(listOf(
            BarAction(R.drawable.editentry_cancelicon, stringResource(R.string.cancel_caps)) { onBack() },
            BarAction(R.drawable.editentry_deleteicon, stringResource(R.string.delete_caps)) { if (original == null) onBack() else confirmDelete = true },
            BarAction(R.drawable.editentry_saveicon, stringResource(R.string.save_caps), enabled = valid) { save() },
        ), iconGap = 8.dp)
    }

    when (pick) {
        Pick.SLEEP_TIME -> TimePickDialog(sleep.toLocalTime(), is24, { sleep = sleep.with(it) }, { pick = null })
        Pick.WAKE_TIME -> TimePickDialog(wake.toLocalTime(), is24, { wake = wake.with(it) }, { pick = null })
        // Legacy: choosing the sleep date moves the wake date to the following day.
        Pick.SLEEP_DATE -> DatePickDialog(sleep.toLocalDate(), { sleep = sleep.with(it); wake = wake.with(it.plusDays(1)) }, { pick = null })
        Pick.WAKE_DATE -> DatePickDialog(wake.toLocalDate(), { wake = wake.with(it) }, { pick = null })
        null -> {}
    }
    if (confirmDelete) DeleteDialog(onConfirm = { scope.launch { Entries.delete(context, entryId); onBack() } }, onDismiss = { confirmDelete = false })
}

@Composable
private fun TimeRow(label: String, time: String, date: String, onTime: () -> Unit, onDate: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        DateButton(time, onTime, fontSize = 16.sp, width = 100.dp)
        Spacer(Modifier.width(10.dp))
        DateButton(date, onDate, fontSize = 16.sp, width = 100.dp)
    }
}
