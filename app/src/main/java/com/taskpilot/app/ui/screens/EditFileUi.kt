package com.taskpilot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.taskpilot.app.data.EditParams
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.Workflow
import com.taskpilot.app.engine.Assignment
import com.taskpilot.app.engine.CodeEdit
import com.taskpilot.app.engine.FileEdit
import com.taskpilot.app.engine.Scheduler
import com.taskpilot.app.engine.SecretStore
import com.taskpilot.app.ui.IconBadge
import com.taskpilot.app.ui.SectionLabel
import com.taskpilot.app.ui.SwitchRow
import com.taskpilot.app.ui.fmtWhen
import com.taskpilot.app.ui.theme.Danger
import com.taskpilot.app.ui.theme.Mono
import com.taskpilot.app.ui.theme.Success
import com.taskpilot.app.ui.theme.Warn
import java.io.File
import java.time.LocalDateTime
import java.util.Arrays

// ============================================================ Edit a file

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFileSection(draft: Step, wf: Workflow, onChange: (Step) -> Unit, onPickFile: () -> Unit) {
    val path = draft.s("path")
    val edits = EditParams.edits(draft)
    val weekStart = EditParams.weekStart(draft)
    var reload by remember { mutableIntStateOf(0) }
    val text: String? = remember(path, reload) {
        val f = File(path)
        if (path.isBlank() || !f.isFile || f.length() > 5_000_000) null
        else try { f.readText(Charsets.UTF_8) } catch (_: Exception) { null }
    }
    val settings = remember(text) { text?.let { CodeEdit.assignments(it) } ?: emptyList() }
    val nextRun = Scheduler.nextTrigger(wf.schedule)
    var showPicker by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    fun setEdits(list: List<FileEdit>) = onChange(EditParams.withEdits(draft, list))

    SectionLabel("File to edit")
    PathCard(path, isFile = true, onBrowse = onPickFile)
    if (path.isNotBlank() && text == null) {
        Text("Can't read this file right now (missing, too big, or All files access is off).", style = MaterialTheme.typography.labelSmall, color = Warn)
    }

    SectionLabel("Changes · ${edits.size}")
    if (edits.isEmpty()) {
        Text(
            "Pick a setting from the code (like START_DATETIME) or add a find & replace.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    edits.forEachIndexed { i, e ->
        EditCard(
            edit = e,
            current = settings.firstOrNull { it.name == e.name },
            weekStart = weekStart,
            nextRun = nextRun?.toLocalDateTime(),
            onChange = { ne -> setEdits(edits.toMutableList().also { it[i] = ne }) },
            onRemove = { setEdits(edits.toMutableList().also { it.removeAt(i) }) },
        )
        Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        FilledTonalButton(onClick = { showPicker = true }, enabled = text != null) {
            Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Change a setting")
        }
        OutlinedButton(onClick = { setEdits(edits + FileEdit(kind = "replace")) }) {
            Icon(Icons.Rounded.FindReplace, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Find & replace")
        }
    }

    SectionLabel("Week starts on")
    val days = listOf(6 to "Saturday", 7 to "Sunday", 1 to "Monday")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        days.forEachIndexed { i, (iso, label) ->
            SegmentedButton(
                selected = weekStart.value == iso,
                onClick = { onChange(draft.put("weekStart", iso.toString())) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = days.size),
            ) { Text(label) }
        }
    }
    Text(
        "Used by the “week” dates. Sunday → last week = Sun–Sat (e.g. 25–31 Oct 2026).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    SectionLabel("Save the change")
    Choice(
        listOf(
            "copy" to ("Into a fresh copy for this run (recommended)" to "Your file stays as it is. A hidden copy with the new values is made next to it, and the next “Open file” step opens that copy — so Pydroid always runs exactly what was just edited."),
            "inplace" to ("Into the file itself" to "The file is changed for good. The last 5 versions are kept inside TaskPilot just in case."),
        ),
        draft.s("save", "copy"),
    ) { onChange(draft.put("save", it)) }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { reload++; showPreview = true }, enabled = text != null && edits.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Preview the result (nothing is saved)")
    }

    if (showPicker) {
        SettingPickerDialog(settings, onDismiss = { showPicker = false }) { a ->
            showPicker = false
            setEdits(edits + FileEdit(kind = "var", name = a.name, value = a.inner))
        }
    }
    if (showPreview && text != null) {
        PreviewDialog(text, edits, weekStart, nextRun?.toLocalDateTime(), onDismiss = { showPreview = false })
    }
}

@Composable
private fun EditCard(
    edit: FileEdit,
    current: Assignment?,
    weekStart: java.time.DayOfWeek,
    nextRun: LocalDateTime?,
    onChange: (FileEdit) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (edit.kind == "replace") {
                        Text("Find & replace", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(edit.name, style = MaterialTheme.typography.titleSmall.merge(Mono), fontWeight = FontWeight.SemiBold)
                        Text(
                            if (current != null) "Now in the file: ${current.rawValue}" else "⚠ Not found in this file",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (current != null) MaterialTheme.colorScheme.onSurfaceVariant else Warn,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove this change") }
            }
            Column(Modifier.padding(end = 10.dp)) {
                if (edit.kind == "replace") {
                    OutlinedTextField(edit.find, { onChange(edit.copy(find = it)) }, label = { Text("Find this text") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(edit.replace, { onChange(edit.copy(replace = it)) }, label = { Text("Replace with") }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(
                        edit.value, { onChange(edit.copy(value = it)) },
                        label = { Text("New value") },
                        singleLine = true,
                        supportingText = {
                            Text(if (current?.quoted == true) "Quotes are added for you" else "Written exactly as typed (e.g. True, 60)")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Insert a date that updates itself:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(CodeEdit.QUICK_TOKENS) { t ->
                        SuggestionChip(
                            onClick = {
                                if (edit.kind == "replace") onChange(edit.copy(replace = edit.replace + t.token))
                                else onChange(edit.copy(value = edit.value + t.token))
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
                val v = if (edit.kind == "replace") edit.replace else edit.value
                if (CodeEdit.hasTokens(v)) {
                    val bad = CodeEdit.validate(v)
                    if (bad != null) {
                        Text(bad, style = MaterialTheme.typography.labelSmall, color = Danger)
                    } else {
                        val now = LocalDateTime.now()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
                                .padding(10.dp),
                        ) {
                            if (nextRun != null) {
                                Text("At the next run (${fmtWhen(nextRun.atZone(java.time.ZoneId.systemDefault()))}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(CodeEdit.expandDates(v, nextRun, weekStart), style = Mono, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                            }
                            Text("If it ran right now:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(CodeEdit.expandDates(v, now, weekStart), style = Mono)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingPickerDialog(settings: List<Assignment>, onDismiss: () -> Unit, onPick: (Assignment) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = settings.filter { q.isBlank() || it.name.contains(q, true) || it.rawValue.contains(q, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a setting") },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text("Search, e.g. DATE") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Text("No “NAME = value” settings found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.line }) { a ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(a) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(a.name, style = MaterialTheme.typography.bodyMedium.merge(Mono), fontWeight = FontWeight.SemiBold)
                            Text(
                                "line ${a.line} · ${a.rawValue}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PreviewDialog(text: String, edits: List<FileEdit>, weekStart: java.time.DayOfWeek, nextRun: LocalDateTime?, onDismiss: () -> Unit) {
    val at = nextRun ?: LocalDateTime.now()
    val r = remember(text, edits, weekStart, at) { CodeEdit.apply(text, edits, at, weekStart) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (nextRun != null) "At the next run" else "If it ran now") },
        text = {
            Column {
                if (nextRun != null) {
                    Text(fmtWhen(nextRun.atZone(java.time.ZoneId.systemDefault())), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                r.changes.forEach { Text("✓ $it", style = MaterialTheme.typography.bodySmall.merge(Mono), color = Success) }
                r.problems.forEach { Text("✗ $it", style = MaterialTheme.typography.bodySmall, color = Danger) }
                if (r.problems.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("With a ✗ the run stops at this step instead of running the script with wrong values.", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

// ============================================================ Phone PIN (never stored in plain text)

@Composable
fun PinSection(draft: Step, onChange: (Step) -> Unit) {
    val ctx = LocalContext.current
    var has by remember { mutableStateOf(SecretStore.hasPin(ctx)) }
    var ask by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (has) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (has) Success else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (has) "PIN saved securely" else "No PIN saved", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Encrypted with a key locked inside this phone's security chip. It is never shown again, never put in an automation, the History or a backup, and can't leave this phone — sharing the app, its code or an automation never carries it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ask = true }) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (has) "Change PIN" else "Save PIN")
                }
                if (has) TextButton(onClick = { confirmRemove = true }) { Text("Remove", color = Danger) }
            }
        }
    }
    SwitchRow(
        "Unlock with the saved PIN",
        if (has) "Types it on the lock screen when the phone is locked" else "Save a PIN first",
        has && draft.b("usePin", false),
    ) { on -> if (has) onChange(draft.put("usePin", on.toString())) else ask = true }

    if (ask) {
        PinDialog(onDismiss = { ask = false }) { pin ->
            val ok = SecretStore.savePin(ctx, pin)      // wipes [pin]
            has = SecretStore.hasPin(ctx)
            ask = false
            if (ok) onChange(draft.put("usePin", "true"))
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the saved PIN?") },
            text = { Text("It is deleted from this phone, together with its encryption key.") },
            confirmButton = {
                TextButton(onClick = {
                    SecretStore.clear(ctx)
                    has = false
                    confirmRemove = false
                    onChange(draft.put("usePin", "false"))
                }) { Text("Remove", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** Screenshots / screen recording are blocked while this is open (FLAG_SECURE). */
@Composable
private fun PinDialog(onDismiss: () -> Unit, onSave: (CharArray) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun wipe() { pin = ""; again = "" }
    AlertDialog(
        onDismissRequest = { wipe(); onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Phone PIN") },
        text = {
            Column {
                Text("The PIN you type to unlock this phone.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    pin, { v -> pin = v.filter { it.isDigit() }.take(16); error = null },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    again, { v -> again = v.filter { it.isDigit() }.take(16); error = null },
                    label = { Text("Type it again") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> error = "At least 4 digits"
                    pin != again -> error = "The two PINs don't match"
                    else -> {
                        val chars = pin.toCharArray()
                        wipe()
                        onSave(chars)
                        Arrays.fill(chars, '\u0000')
                    }
                }
            }) { Text("Save securely") }
        },
        dismissButton = { TextButton(onClick = { wipe(); onDismiss() }) { Text("Cancel") } },
    )
}
