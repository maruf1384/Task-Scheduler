package com.taskpilot.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taskpilot.app.core.Cond
import com.taskpilot.app.core.CondGroup
import com.taskpilot.app.core.CondKind
import com.taskpilot.app.core.Conditions
import com.taskpilot.app.core.OnFail
import com.taskpilot.app.core.RepeatMode
import com.taskpilot.app.data.Step
import com.taskpilot.app.engine.FileOps
import com.taskpilot.app.engine.PhoneConditions
import com.taskpilot.app.engine.RunContext
import com.taskpilot.app.ui.SectionLabel
import com.taskpilot.app.ui.SwitchRow
import com.taskpilot.app.ui.IconBadge
import com.taskpilot.app.ui.theme.Success
import com.taskpilot.app.ui.theme.Warn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

// ============================================================ If / Repeat / Stop / Otherwise / End

@Composable
internal fun IfSection(step: Step, onChange: (Step) -> Unit) {
    SectionLabel("Run the steps inside only when")
    ConditionsEditor(step, onChange)
    SectionLabel("Give it time")
    Field(
        step, "waitUpTo", "Wait up to (seconds) for it to become true", onChange, keyboard = KeyboardType.Number,
        support = "0 = check once. e.g. 120 to give Pydroid time to show “[Program finished]”",
    )
    Hint(
        "The steps between this If and its End run only when the answer is yes. For steps that should " +
            "run when it's no, tap ⋮ on the If in the list → “Add Otherwise”. Each check and its answer " +
            "is written to History.",
    )
}

@Composable
internal fun RepeatSection(step: Step, onChange: (Step) -> Unit) {
    val mode = RepeatMode.of(step)
    SectionLabel("Repeat")
    Choice(
        listOf(
            RepeatMode.TIMES.wire to ("A number of times" to "e.g. tap “Next” 5 times"),
            RepeatMode.EACH_FILE.wire to ("Once for each selected file" to "The steps inside get one file at a time — e.g. send each file with its own message"),
            RepeatMode.UNTIL.wire to ("Until something is true" to "Runs the steps, then checks — e.g. until “Sent” is on screen"),
            RepeatMode.WHILE.wire to ("As long as something is true" to "Checks first — e.g. while “Loading” is on screen"),
        ),
        mode.wire,
    ) { onChange(step.put("mode", it)) }
    when (mode) {
        RepeatMode.TIMES -> Field(step, "count", "How many times", onChange, keyboard = KeyboardType.Number)
        RepeatMode.EACH_FILE -> Hint(
            "Goes through the files picked by an earlier “Select files” or “Wait for new files” step. " +
                "Inside, “Send” and “Share” steps get only the current file; {loop.file} is its name and " +
                "{loop.index} / {loop.count} say which one it is. After the End, all the files are selected again.",
        )
        RepeatMode.UNTIL, RepeatMode.WHILE -> {
            SectionLabel(if (mode == RepeatMode.UNTIL) "Stop repeating when" else "Keep repeating while")
            ConditionsEditor(step, onChange)
            Field(
                step, "max", "Never more than (rounds)", onChange, keyboard = KeyboardType.Number,
                support = "A safety limit: after this many rounds the run carries on after the End, with a note in History",
            )
        }
    }
    if (mode != RepeatMode.EACH_FILE) Hint("In the steps inside you can use {loop.index} — the round number (1, 2, 3 …).")
}

@Composable
internal fun StopSection(step: Step, onChange: (Step) -> Unit) {
    SectionLabel("Finish the automation here")
    Choice(
        listOf(
            "ok" to ("As done ✓" to "Nothing went wrong — there's just nothing more to do"),
            "fail" to ("As failed ✗" to "You get a failure notification, and History shows it in red"),
        ),
        step.s("result", "ok"),
    ) { onChange(step.put("result", it)) }
    Field(step, "message", "Message (optional)", onChange, support = "Shown in the notification and History — e.g. “No new files today”")
    Hint("Usually placed inside an If — e.g. If “No files were found” → Stop as done.")
}

@Composable
internal fun MarkerSection(isElse: Boolean) {
    SectionLabel("About")
    Hint(
        if (isElse) "The steps between “Otherwise” and “End” run when the If above isn't true. To remove it, use ⋮ on the If in the list."
        else "Closes the If or Repeat above it. It is removed together with its If / Repeat (⋮ on that step).",
    )
}

@Composable
internal fun OnFailSection(step: Step, onChange: (Step) -> Unit) {
    var open by remember { mutableStateOf(OnFail.of(step) != OnFail.STOP) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { open = !open }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Replay, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("If this step fails", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                when (OnFail.of(step)) {
                    OnFail.STOP -> "Stop the run (usual)"
                    OnFail.RETRY -> "Try again, up to ${step.i("retries", 2)} more times"
                    OnFail.CONTINUE -> "Carry on with the next step"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
    }
    if (!open) return
    Choice(
        listOf(
            OnFail.STOP.wire to ("Stop the run" to "History shows which step failed and why"),
            OnFail.RETRY.wire to ("Try again" to "For steps that sometimes miss — a slow app, a button that appears late"),
            OnFail.CONTINUE.wire to ("Carry on" to "The run goes on; an If with “The previous step failed” can react to it"),
        ),
        OnFail.of(step).wire,
    ) { onChange(step.put("onFail", it)) }
    if (OnFail.of(step) == OnFail.RETRY) {
        Field(step, "retries", "Extra tries", onChange, keyboard = KeyboardType.Number, support = "1 to 10 — default 2")
        Field(step, "retryWait", "Wait between tries (seconds)", onChange, keyboard = KeyboardType.Number)
    }
}

// ============================================================ conditions

private val CondGroup.icon: ImageVector
    get() = when (this) {
        CondGroup.SCREEN -> Icons.Rounded.Visibility
        CondGroup.FILES -> Icons.Rounded.Folder
        CondGroup.TIME -> Icons.Rounded.Event
        CondGroup.RUN -> Icons.Rounded.Replay
        CondGroup.PHONE -> Icons.Rounded.BatteryChargingFull
    }

private val CondGroup.tint: Color
    get() = when (this) {
        CondGroup.SCREEN -> Color(0xFF0EA5E9)
        CondGroup.FILES -> Color(0xFFF59E0B)
        CondGroup.TIME -> Color(0xFF8B5CF6)
        CondGroup.RUN -> Color(0xFFEC4899)
        CondGroup.PHONE -> Color(0xFF22C55E)
    }

/** A new condition of [k], filled with sensible starting values. */
private fun fresh(k: CondKind): Cond = Cond(
    k.wire,
    p = when (k) {
        CondKind.FILES_FOUND -> mapOf("min" to "1")
        CondKind.WEEKDAY -> mapOf("days" to LocalDate.now().dayOfWeek.value.toString())
        CondKind.TIME_BETWEEN -> mapOf("from" to "00:00", "to" to "06:00")
        CondKind.DAY_OF_MONTH -> mapOf("days" to "1")
        CondKind.BATTERY -> mapOf("min" to "30")
        else -> emptyMap()
    },
)

/** What the conditions editor has open — at most one thing. */
private sealed interface CondDialog {
    data object Add : CondDialog
    /** [index] = -1 for a condition that isn't in the list yet. */
    data class Edit(val index: Int, val cond: Cond) : CondDialog
    data class PickApp(val index: Int, val cond: Cond) : CondDialog
    data class PickPath(val index: Int, val cond: Cond) : CondDialog
}

@Composable
internal fun ConditionsEditor(step: Step, onChange: (Step) -> Unit) {
    val conds = Conditions.of(step)
    var dialog by remember { mutableStateOf<CondDialog?>(null) }
    fun save(list: List<Cond>) = onChange(Conditions.with(step, list))

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (conds.isEmpty()) {
                Text(
                    "No condition yet — add one: something on screen, a file, the day or time, the battery…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
            conds.forEachIndexed { i, c ->
                if (i > 0) {
                    Text(
                        if (Conditions.matchAll(step)) "AND" else "OR",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 66.dp),
                    )
                }
                val g = c.k?.group
                val problem = Conditions.problem(c)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { dialog = CondDialog.Edit(i, c) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(g?.icon ?: Icons.Rounded.WarningAmber, g?.tint ?: Warn, 40.dp, 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Conditions.describe(c), style = MaterialTheme.typography.bodyLarge)
                        if (problem != null) Text(problem, style = MaterialTheme.typography.labelSmall, color = Warn)
                    }
                    IconButton(onClick = { save(conds.filterIndexed { j, _ -> j != i }) }) {
                        Icon(Icons.Rounded.Close, "Remove")
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            TextButton(onClick = { dialog = CondDialog.Add }, modifier = Modifier.padding(horizontal = 4.dp)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (conds.isEmpty()) "Add a condition" else "Add another condition")
            }
        }
    }
    if (conds.size > 1) {
        Spacer(Modifier.height(8.dp))
        Choice(
            listOf(
                "all" to ("All of them" to "Every condition must be true"),
                "any" to ("Any one of them" to "One true condition is enough"),
            ),
            if (Conditions.matchAll(step)) "all" else "any",
        ) { onChange(step.put("match", it)) }
    }
    RightNow(step)

    when (val d = dialog) {
        CondDialog.Add -> CondKindPicker(onDismiss = { dialog = null }) { k -> dialog = CondDialog.Edit(-1, fresh(k)) }
        is CondDialog.Edit -> CondEditDialog(
            d.cond,
            isNew = d.index < 0,
            onDismiss = { dialog = null },
            onPickApp = { dialog = CondDialog.PickApp(d.index, it) },
            onPickPath = { dialog = CondDialog.PickPath(d.index, it) },
        ) { c ->
            save(if (d.index < 0) conds + c else conds.mapIndexed { j, old -> if (j == d.index) c else old })
            dialog = null
        }
        is CondDialog.PickApp -> AppPickerDialog(onDismiss = { dialog = CondDialog.Edit(d.index, d.cond) }) { pkg, label ->
            dialog = CondDialog.Edit(d.index, d.cond.copy(p = d.cond.p + mapOf("pkg" to pkg, "appName" to label)))
        }
        is CondDialog.PickPath -> FileBrowserDialog(
            start = d.cond.s("path").ifBlank { FileOps.root.absolutePath + "/Download" },
            pickFile = true,
            onDismiss = { dialog = CondDialog.Edit(d.index, d.cond) },
        ) { path -> dialog = CondDialog.Edit(d.index, d.cond.copy(p = d.cond.p + ("path" to path))) }
        null -> {}
    }
}

/** "Right now: Yes" for the conditions that can be answered without a run (date, time, files, battery…). */
@Composable
private fun RightNow(step: Step) {
    val ctx = LocalContext.current
    val conds = Conditions.of(step)
    if (conds.isEmpty() || conds.any { Conditions.problem(it) != null }) return
    val runOnly = conds.filter { it.k?.group == CondGroup.SCREEN || it.k == CondKind.FILES_FOUND || it.k == CondKind.PREV_OK }
    val text = if (runOnly.isNotEmpty()) {
        "Checked during the run (it looks at the screen or at this run)"
    } else {
        val ev = Conditions.eval(conds, Conditions.matchAll(step), PhoneConditions(ctx, null, RunContext(System.currentTimeMillis())), LocalDateTime.now(), null)
        "If checked right now: ${ev.detail}"
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (runOnly.isEmpty()) Success else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Branch → sub-branch: first what kind of thing to check, then the exact check. */
@Composable
private fun CondKindPicker(onDismiss: () -> Unit, onPick: (CondKind) -> Unit) {
    var open by remember { mutableStateOf<CondGroup?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check what?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CondGroup.entries.forEach { g ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { open = if (open == g) null else g }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(g.icon, g.tint, 36.dp, 18.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.title, style = MaterialTheme.typography.titleSmall)
                            Text(g.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (open == g) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                    if (open == g) {
                        CondKind.entries.filter { it.group == g }.forEach { k ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 48.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable { onPick(k) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Text(k.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                                if (k.hint.isNotBlank()) Text(k.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CondEditDialog(
    start: Cond,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onPickApp: (Cond) -> Unit,
    onPickPath: (Cond) -> Unit,
    onSave: (Cond) -> Unit,
) {
    var c by remember(start) { mutableStateOf(start) }
    fun set(key: String, v: String) { c = c.copy(p = c.p + (key to v)) }

    @Composable
    fun text(key: String, label: String, def: String = "", number: Boolean = false, support: String? = null) {
        OutlinedTextField(
            value = c.s(key, def),
            onValueChange = { v -> set(key, if (number) v.filter { it.isDigit() } else v) },
            label = { Text(label) },
            singleLine = true,
            supportingText = support?.let { msg -> @Composable { Text(msg) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }

    @Composable
    fun appRow(emptyText: String) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text(c.s("appName").ifBlank { c.s("pkg").ifBlank { emptyText } }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (c.s("pkg").isNotBlank() && emptyText == "Any app") TextButton(onClick = { c = c.copy(p = c.p - setOf("pkg", "appName")) }) { Text("Any") }
            FilledTonalButton(onClick = { onPickApp(c) }) { Text(if (c.s("pkg").isBlank()) "Choose" else "Change") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.k?.label ?: "Condition") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (c.k) {
                    CondKind.TEXT_ON_SCREEN -> {
                        text("text", "Text to look for", support = "Any part of it, upper or lower case")
                        Text("In", style = MaterialTheme.typography.labelMedium)
                        appRow("Any app")
                    }
                    CondKind.APP_IN_FRONT -> appRow("Choose an app")
                    CondKind.PATH_EXISTS -> {
                        Text(c.s("path").ifBlank { "No file chosen" }.let { FileOps.pretty(it) }, style = MaterialTheme.typography.bodyMedium)
                        FilledTonalButton(onClick = { onPickPath(c) }, modifier = Modifier.padding(vertical = 4.dp)) { Text("Browse") }
                        text("path", "…or type the path", support = "Dates work too: {yesterday|dd-MM-yyyy}, {date}")
                    }
                    CondKind.FILES_FOUND -> text("min", "At least this many files", "1", number = true)
                    CondKind.WEEKDAY -> {
                        val days = Conditions.days(c).map { it.value }.toSet()
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                FilterChip(
                                    selected = d.value in days,
                                    onClick = {
                                        val nd = if (d.value in days) days - d.value else days + d.value
                                        set("days", nd.sorted().joinToString(","))
                                    },
                                    label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)) },
                                )
                            }
                        }
                    }
                    CondKind.TIME_BETWEEN -> {
                        text("from", "From (24-hour, e.g. 22:00)", "00:00")
                        text("to", "To (not included)", "06:00", support = "From 22:00 to 06:00 covers the night")
                    }
                    CondKind.DAY_OF_MONTH -> text("days", "Days", "1", support = "e.g. 1, 15, last")
                    CondKind.BATTERY -> text("min", "At least (%)", "30", number = true)
                    CondKind.PREV_OK -> Text("Set the step before this to “If this step fails → Carry on”, so the run gets here after a failure.", style = MaterialTheme.typography.bodySmall)
                    CondKind.CHARGING, CondKind.ONLINE, null -> {}
                }
                Spacer(Modifier.height(8.dp))
                SwitchRow("Turn it around", "True when this is NOT so", c.not) { c = c.copy(not = it) }
                Spacer(Modifier.height(4.dp))
                Text(Conditions.describe(c), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Conditions.problem(c)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Warn) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(c) }) { Text(if (isNew) "Add" else "Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
