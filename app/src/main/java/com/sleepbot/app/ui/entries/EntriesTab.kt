package com.sleepbot.app.ui.entries

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepbot.app.R
import com.sleepbot.app.app
import com.sleepbot.app.backup.Csv
import com.sleepbot.app.data.Entries
import com.sleepbot.app.data.SleepEntry
import com.sleepbot.app.ui.common.BarAction
import com.sleepbot.app.ui.common.BottomActionBar
import com.sleepbot.app.ui.common.CellText
import com.sleepbot.app.ui.common.DatePickDialog
import com.sleepbot.app.ui.common.DateButton
import com.sleepbot.app.ui.common.drawableBackground
import com.sleepbot.app.ui.theme.SB
import com.sleepbot.app.util.TimeFormat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A list row with the legacy RecordWrapper display fields. */
data class EntryRow(val entry: SleepEntry, val date: String, val hours: String, val debt: String)

/** Port of RecordWrapper.make_map: date on the latest row of a wake-day, debt on its earliest row. */
fun buildRows(entries: List<SleepEntry>, optimal: Float, decimal: Boolean, shortDate: DateTimeFormatter, zone: ZoneId = ZoneId.systemDefault()): List<EntryRow> {
    fun day(e: SleepEntry) = Instant.ofEpochMilli(e.awake).atZone(zone).toLocalDate()
    val out = ArrayList<EntryRow>(entries.size)
    // Entries are awake-DESC, so each wake-day's rows are contiguous.
    var i = 0
    while (i < entries.size) {
        val d = day(entries[i])
        var j = i
        while (j < entries.size && day(entries[j]) == d) j++
        val debt = optimal - entries.subList(i, j).sumOf { it.durationHours }
        for (k in i until j) {
            out += EntryRow(
                entry = entries[k],
                date = if (k == i) shortDate.format(d) else "",
                hours = TimeFormat.hourCell(entries[k].durationHours, decimal),
                debt = if (k == j - 1) TimeFormat.debtCell(debt, decimal) else "",
            )
        }
        i = j
    }
    return out
}

data class PeriodStats(val records: Int, val days: Int, val total: Double) {
    val avgDaily get() = if (days == 0) 0.0 else total / days
    val avgRecord get() = if (records == 0) 0.0 else total / records
    fun summary(context: Context) = buildString {
        fun s(id: Int) = context.getString(id)
        fun t(d: Double) = String.format(Locale.US, "%.1f", d) + " " + s(R.string.hours_ori)
        append(s(R.string.summary_num_records)).append(" $records\n")
        append(s(R.string.summary_num_days)).append(" $days\n")
        append(s(R.string.summary_total_sleep)).append(" ${t(total)}\n")
        append(s(R.string.summary_avg_daily)).append(" ${t(avgDaily)}\n")
        append(s(R.string.summary_avg_record)).append(" ${t(avgRecord)}\n")
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntriesTab(onOpenEntry: (Long) -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    val app = context.app
    val prefs = app.prefs
    val dao = app.db.entries()
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    var from by rememberSaveable { mutableStateOf(LocalDate.now().minusMonths(1).toEpochDay()) }
    var to by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toEpochDay()) }
    val fromMs = LocalDate.ofEpochDay(from).atStartOfDay(zone).toInstant().toEpochMilli()
    val toMs = LocalDate.ofEpochDay(to).atStartOfDay(zone).toInstant().toEpochMilli()
    val entries by remember(fromMs, toMs) { dao.observeRange(fromMs + 1, toMs) }.collectAsState(null)
    val hasAny by remember { dao.observeAll().map { it.isNotEmpty() } }.collectAsState(true)
    val fullFmt = remember { DateTimeFormatter.ofPattern(prefs.dateFormat(), Locale.getDefault()) }
    val shortFmt = remember { DateTimeFormatter.ofPattern(prefs.shortDateFormat(), Locale.getDefault()) }
    val rows = remember(entries) { buildRows(entries.orEmpty(), prefs.optimalHours, prefs.decimalHours, shortFmt, zone) }

    var pick by remember { mutableStateOf<Int?>(null) } // 0 = from, 1 = to
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var firstHint by remember { mutableStateOf(false) }
    LaunchedEffect(entries?.size) {
        if (entries?.size == 1 && !prefs.firstEntryHintSeen) { prefs.firstEntryHintSeen = true; firstHint = true }
    }

    Column(Modifier.fillMaxSize().drawableBackground(R.drawable.background)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(3.4f), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.from), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 6.dp))
                DateButton(LocalDate.ofEpochDay(from).format(fullFmt), { pick = 0 })
            }
            Row(Modifier.weight(2.8f), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.to), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp))
                DateButton(LocalDate.ofEpochDay(to).format(fullFmt), { pick = 1 })
            }
            Box(Modifier.weight(0.8f).clickable { showStats = true }, contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.stats_unselected), stringResource(R.string.period_stats), Modifier.width(47.dp).height(48.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Box(Modifier.weight(1f))
            CellText(stringResource(R.string.col_date), Modifier.weight(3f).padding(start = 5.dp), SB.TableHeader, bold = true, align = TextAlign.Start)
            CellText(stringResource(R.string.col_sleep), Modifier.weight(3f), SB.TableHeader, bold = true)
            CellText(stringResource(R.string.col_wake), Modifier.weight(3f), SB.TableHeader, bold = true)
            CellText(stringResource(R.string.col_hours), Modifier.weight(3f), SB.TableHeader, bold = true)
            CellText(stringResource(R.string.col_debt), Modifier.weight(3f), SB.TableHeader, bold = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SB.Divider))
        Box(Modifier.weight(1f).fillMaxWidth().background(SB.ListBg)) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, r -> r.entry.id }) { i, r ->
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { onOpenEntry(r.entry.id) }, onLongClick = { menuFor = r.entry.id })
                                .padding(3.dp),
                        ) {
                            CellText(if (r.entry.note.isNotEmpty()) "*" else " ", Modifier.weight(1f))
                            CellText(r.date, Modifier.weight(3f).padding(horizontal = 4.dp), align = TextAlign.Start)
                            CellText(TimeFormat.table(r.entry.sleep), Modifier.weight(3f))
                            CellText(TimeFormat.table(r.entry.awake), Modifier.weight(3f))
                            CellText(r.hours, Modifier.weight(3f))
                            CellText(r.debt, Modifier.weight(3f))
                        }
                        DropdownMenu(menuFor == r.entry.id, { menuFor = null }) {
                            DropdownMenuItem({ Text(stringResource(R.string.edit)) }, { menuFor = null; onOpenEntry(r.entry.id) })
                            DropdownMenuItem({ Text(stringResource(R.string.delete)) }, { menuFor = null; deleteId = r.entry.id })
                        }
                    }
                    if (i < rows.size - 1) HorizontalDivider(color = Color(0xFF232C3D), thickness = 1.dp)
                }
            }
            if (entries != null && rows.isEmpty()) {
                Text(
                    stringResource(if (hasAny) R.string.no_entry_found_hint else R.string.no_entry_hint),
                    color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.fillMaxWidth().background(SB.Overlay).padding(20.dp),
                )
            }
        }
        BottomActionBar(listOf(
            BarAction(R.drawable.new_icon, stringResource(R.string.add_new_entry)) { onOpenEntry(-1) },
            BarAction(R.drawable.share_icon, stringResource(R.string.share_caps)) {
                val list = entries.orEmpty()
                context.startActivity(Csv.exportShareIntent(context, list, periodStats(list, zone).summary(context)))
            },
        ))
    }

    pick?.let { which ->
        DatePickDialog(LocalDate.ofEpochDay(if (which == 0) from else to), { d -> if (which == 0) from = d.toEpochDay() else to = d.toEpochDay() }, { pick = null })
    }
    deleteId?.let { id ->
        DeleteDialog(onConfirm = { scope.launch { Entries.delete(context, id) } }, onDismiss = { deleteId = null })
    }
    if (showStats) {
        val s = periodStats(entries.orEmpty(), zone)
        AlertDialog(
            onDismissRequest = { showStats = false },
            title = { Text(stringResource(R.string.period_stats)) },
            text = {
                Text(s.summary(context) + stringResource(R.string.summary_cum) + " " + String.format(Locale.US, "%.1f", (prefs.optimalHours - s.avgDaily) * s.days) +
                    "\n\n" + stringResource(R.string.period_stats_note))
            },
            confirmButton = { TextButton({ showStats = false }) { Text(stringResource(R.string.confirm)) } },
            dismissButton = {
                TextButton({
                    showStats = false
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, res.getString(R.string.period_stats))
                        .putExtra(android.content.Intent.EXTRA_TEXT, s.summary(context) + "\n via SleepBot")
                    context.startActivity(android.content.Intent.createChooser(send, res.getString(R.string.period_stats)))
                }) { Text(stringResource(R.string.share)) }
            },
        )
    }
    if (firstHint) {
        AlertDialog(
            onDismissRequest = { firstHint = false },
            title = { Text(stringResource(R.string.first_entry_title)) },
            text = { Text(stringResource(R.string.first_entry_msg)) },
            confirmButton = { TextButton({ firstHint = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

fun periodStats(entries: List<SleepEntry>, zone: ZoneId): PeriodStats {
    val days = entries.map { Instant.ofEpochMilli(it.awake).atZone(zone).toLocalDate() }.distinct().size
    return PeriodStats(entries.size, days, entries.sumOf { it.durationHours })
}

@Composable
fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm_title)) },
        text = { Text(stringResource(R.string.delete_confirm_msg)) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text(stringResource(R.string.yes)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.no)) } },
    )
}
