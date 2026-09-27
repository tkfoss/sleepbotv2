package com.sleepbot.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleepbot.app.R
import com.sleepbot.app.alarm.ui.drawableBackground
import com.sleepbot.app.ui.theme.SB

val SummaryColor = Color(0xFFBCC3D2)
private val DisabledColor = Color(0xFF6E7D99)

/** 48dp namebar banner with centered title and optional back arrow / trailing actions. */
@Composable
fun NamebarHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    fontSize: Int = 22,
    actions: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().height(48.dp).drawableBackground(R.drawable.namebar)) {
        Text(title, Modifier.align(Alignment.Center), color = Color.White, fontSize = fontSize.sp)
        if (onBack != null) {
            IconButton(onBack, Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) { actions() }
    }
}

/** Holo-style category header: blue caps text with a thin rule. */
@Composable
fun CategoryHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text(title.uppercase(), color = SB.TableHeader, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(thickness = 1.dp, color = SB.TableHeader.copy(alpha = 0.6f))
    }
}

/** Two-line preference row. */
@Composable
fun PrefRow(
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else DisabledColor, fontSize = 18.sp)
            if (!summary.isNullOrEmpty()) {
                Text(summary, color = if (enabled) SummaryColor else DisabledColor, fontSize = 14.sp)
            }
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun SbCheckbox(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    Checkbox(
        checked, onChange, enabled = enabled,
        colors = CheckboxDefaults.colors(checkedColor = SB.HoloBlue, uncheckedColor = SummaryColor, checkmarkColor = Color.White),
    )
}

/** Single-choice list dialog; selecting an item closes it (legacy ListPreference). */
@Composable
fun SingleChoiceDialog(
    title: String,
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            val state = rememberLazyListState((selected - 2).coerceAtLeast(0))
            LazyColumn(Modifier.heightIn(max = 420.dp), state = state) {
                itemsIndexed(items) { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(i) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(i == selected, { onSelect(i) }, colors = RadioButtonDefaults.colors(selectedColor = SB.HoloBlue))
                        Text(label, color = Color.White, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Multi-choice dialog with OK (legacy "Scheduled days"). */
@Composable
fun MultiChoiceDialog(
    title: String,
    items: List<String>,
    checked: List<Boolean>,
    onToggle: (Int, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(items) { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(i, !checked[i]) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f), color = Color.White, fontSize = 16.sp)
                        SbCheckbox(checked[i], { onToggle(i, it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** Text entry dialog (legacy EditTextPreference / "Alarm label"). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    label: String? = null,
    singleLine: Boolean = true,
    onOk: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(), singleLine = singleLine,
                label = label?.let { { Text(it) } },
            )
        },
        confirmButton = { TextButton({ onOk(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Simple message dialog with optional confirm action. */
@Composable
fun MessageDialog(
    title: String?,
    message: String,
    confirm: String = stringResource(R.string.ok),
    onConfirm: () -> Unit,
    dismiss: String? = null,
    onDismiss: () -> Unit = {},
    extra: Pair<String, () -> Unit>? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Box(Modifier.heightIn(max = 460.dp)) { Column(Modifier.verticalScroll(rememberScrollState())) { Text(message) } } },
        confirmButton = {
            Row {
                if (extra != null) TextButton(extra.second) { Text(extra.first) }
                TextButton(onConfirm) { Text(confirm) }
            }
        },
        dismissButton = dismiss?.let { { TextButton(onDismiss) { Text(it) } } },
    )
}

