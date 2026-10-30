package com.taskpilot.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskpilot.app.data.EditParams
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Templates
import com.taskpilot.app.engine.CodeEdit
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import com.taskpilot.app.data.Workflow
import com.taskpilot.app.engine.Runner
import com.taskpilot.app.engine.Scheduler
import com.taskpilot.app.ui.Category
import com.taskpilot.app.ui.IconBadge
import com.taskpilot.app.ui.LocalSnack
import com.taskpilot.app.ui.Nav
import com.taskpilot.app.ui.Screen
import com.taskpilot.app.ui.SectionLabel
import com.taskpilot.app.ui.StepCatalog
import com.taskpilot.app.ui.fmtIn
import com.taskpilot.app.ui.fmtTime
import com.taskpilot.app.ui.fmtWhen
import com.taskpilot.app.ui.rememberToast
import com.taskpilot.app.ui.theme.Danger
import com.taskpilot.app.ui.theme.Warn
import kotlinx.coroutines.launch
import java.util.UUID

private val EMOJIS = listOf("⚡", "📊", "📤", "🐍", "📁", "💬", "🔔", "🌙", "📈", "🧾", "⚙️", "🚀")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(wfId: String, nav: Nav) {
    val ctx = LocalContext.current
    val all by Repo.workflows.collectAsState()
    val wf = all.firstOrNull { it.id == wfId }
    val running by Runner.state.collectAsState()
    val toast = rememberToast()
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()

    if (wf == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }

    fun save(w: Workflow) {
        Repo.upsert(w)
        Scheduler.schedule(ctx, w)
    }

    var showAdd by remember { mutableStateOf<Int?>(null) } // insert position
    var recordAt by remember { mutableStateOf<Int?>(null) } // "Build it in the real app" insert position
    var showRename by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val isMine = running?.wfId == wf.id

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(Modifier.clickable { showRename = true }, verticalAlignment = Alignment.CenterVertically) {
                        Text(wf.emoji + "  ", style = MaterialTheme.typography.titleLarge)
                        Text(wf.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Rounded.Edit, "Rename", Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename & icon") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; showRename = true })
                            DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
                                menu = false
                                val copy = wf.copy(
                                    id = UUID.randomUUID().toString(), name = wf.name + " (copy)",
                                    schedule = wf.schedule.copy(enabled = false),
                                    steps = wf.steps.map { it.copy(id = UUID.randomUUID().toString()) },
                                    lastRunAt = 0, lastStatus = "",
                                )
                                Repo.upsert(copy)
                                nav.replace(Screen.Editor(copy.id))
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Delete automation", color = Danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) }, onClick = {
                                menu = false
                                Scheduler.cancel(ctx, wf.id)
                                Repo.delete(wf.id)
                            })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { showAdd = wf.steps.size }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add step")
                    }
                    if (isMine) {
                        Button(
                            onClick = { Runner.stop() },
                            modifier = Modifier.weight(1f).height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Danger),
                        ) {
                            Icon(Icons.Rounded.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    } else {
                        Button(
                            onClick = { Runner.launch(ctx, wf.id, "Manual")?.let(toast) },
                            modifier = Modifier.weight(1f).height(52.dp),
                            enabled = wf.steps.isNotEmpty() && running == null,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Test run")
                        }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        ) {
            item(key = "schedule") {
                ScheduleCard(wf, onChange = { save(it) }, onPickTime = { showTime = true })
            }
            suggestDatesStep(wf)?.let { (openIndex, script) ->
                item(key = "suggestEdit") {
                    SuggestEditCard(script) {
                        val st = Templates.weeklyDatesStep(script.absolutePath).let { s ->
                            val names = try {
                                CodeEdit.assignments(script.readText()).map { it.name }
                            } catch (_: Exception) { emptyList() }
                            if ("START_DATETIME" in names && "END_DATETIME" in names) s
                            else EditParams.withEdits(s.copy(label = ""), emptyList())
                        }
                        val list = wf.steps.toMutableList().apply { add(openIndex, st) }
                        save(wf.copy(steps = list))
                        nav.push(Screen.StepEdit(wf.id, st.id, null, openIndex))
                    }
                }
            }
            item(key = "stepsLabel") {
                SectionLabel("Steps · ${wf.steps.size}") {
                    if (wf.steps.isNotEmpty()) Text("Runs top to bottom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (wf.steps.isEmpty()) {
                item(key = "empty") {
                    Card(
                        onClick = { showAdd = 0 },
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.PlaylistAdd, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text("Add your first step", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Pick from Files, Apps, On-screen actions, Send & share and more.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            itemsIndexed(wf.steps, key = { _, s -> s.id }) { i, step ->
                StepRow(
                    index = i,
                    step = step,
                    isFirst = i == 0,
                    isLast = i == wf.steps.lastIndex,
                    active = isMine && running?.index == i,
                    modifier = Modifier.animateItem(),
                    onOpen = { nav.push(Screen.StepEdit(wf.id, step.id, null, i)) },
                    onMove = { dir ->
                        val j = i + dir
                        if (j in wf.steps.indices) {
                            val list = wf.steps.toMutableList()
                            val t = list[i]; list[i] = list[j]; list[j] = t
                            save(wf.copy(steps = list))
                        }
                    },
                    onDuplicate = {
                        val list = wf.steps.toMutableList()
                        list.add(i + 1, step.copy(id = UUID.randomUUID().toString()))
                        save(wf.copy(steps = list))
                    },
                    onToggle = {
                        save(wf.copy(steps = wf.steps.map { if (it.id == step.id) it.copy(enabled = !it.enabled) else it }))
                    },
                    onInsertBelow = { showAdd = i + 1 },
                    onRunFrom = { Runner.launch(ctx, wf.id, "Manual", startAt = i)?.let(toast) },
                    onRunOnly = { Runner.launch(ctx, wf.id, "Manual", startAt = i, endExclusive = i + 1)?.let(toast) },
                    onDelete = {
                        val before = wf
                        save(wf.copy(steps = wf.steps.filterNot { it.id == step.id }))
                        scope.launch {
                            snack.currentSnackbarData?.dismiss()
                            val r = snack.showSnackbar("Step deleted", actionLabel = "Undo", withDismissAction = true)
                            if (r == SnackbarResult.ActionPerformed) save(Repo.get(before.id)?.copy(steps = before.steps) ?: before)
                        }
                    },
                )
            }
            if (wf.steps.isNotEmpty()) {
                item(key = "addEnd") {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .clickable { showAdd = wf.steps.size },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
                        }
                        TextButton(onClick = { showAdd = wf.steps.size }) { Text("Add another step") }
                    }
                }
            }
        }
    }

    showAdd?.let { pos ->
        AddStepSheet(
            onDismiss = { showAdd = null },
            onRecord = { showAdd = null; recordAt = pos },
        ) { type ->
            showAdd = null
            nav.push(Screen.StepEdit(wf.id, null, type, pos))
        }
    }
    recordAt?.let { pos ->
        AppPickerDialog(onDismiss = { recordAt = null }) { pkg, label ->
            recordAt = null
            startRecording(wf.id, pos, pkg, label)?.let(toast)
        }
    }
    RecorderResultToast()
    if (showRename) RenameDialog(wf, onDismiss = { showRename = false }) { name, emoji ->
        showRename = false
        save(wf.copy(name = name, emoji = emoji))
    }
    if (showTime) TimeDialog(wf.schedule.hour, wf.schedule.minute, onDismiss = { showTime = false }) { h, m ->
        showTime = false
        val updated = wf.copy(schedule = wf.schedule.copy(hour = h, minute = m, enabled = true))
        save(updated)
        Scheduler.nextTrigger(updated.schedule)?.let { toast("Next run ${fmtWhen(it)}") }
    }
}

@Composable
private fun ScheduleCard(wf: Workflow, onChange: (Workflow) -> Unit, onPickTime: () -> Unit) {
    val s = wf.schedule
    val next = Scheduler.nextTrigger(s)
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Schedule, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Schedule", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !s.enabled -> "Off — runs only when you tap Test run"
                            next == null -> "Pick at least one day"
                            else -> "Next: ${fmtWhen(next)} · ${fmtIn(next)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.enabled, onCheckedChange = { onChange(wf.copy(schedule = s.copy(enabled = it))) })
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable(onClick = onPickTime)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fmtTime(s.hour, s.minute),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (s.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            val labels = listOf("M", "T", "W", "T", "F", "S", "S")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (d in 1..7) {
                    val on = d in s.days
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                            .clickable {
                                val days = if (on) s.days - d else s.days + d
                                onChange(wf.copy(schedule = s.copy(days = days)))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            labels[d - 1],
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: Step,
    isFirst: Boolean,
    isLast: Boolean,
    active: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onToggle: () -> Unit,
    onInsertBelow: () -> Unit,
    onRunFrom: () -> Unit,
    onRunOnly: () -> Unit,
    onDelete: () -> Unit,
) {
    val info = StepCatalog.info(step.type)
    val issues = StepCatalog.issues(step)
    val line = MaterialTheme.colorScheme.outlineVariant
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(40.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(18.dp).background(if (isFirst) Color.Transparent else line))
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> MaterialTheme.colorScheme.secondary
                            !step.enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
                            issues.isNotEmpty() -> Warn
                            else -> MaterialTheme.colorScheme.primary
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (!step.enabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                )
            }
            Box(Modifier.width(2.dp).weight(1f).background(if (isLast) Color.Transparent else line))
        }
        Card(
            onClick = onOpen,
            modifier = Modifier.weight(1f).padding(vertical = 5.dp).alpha(if (step.enabled) 1f else 0.55f),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (active) 3.dp else 1.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(info.icon, info.category.tint, 38.dp, 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(StepCatalog.titleOf(step), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        StepCatalog.summary(step),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (issues.isNotEmpty() && step.enabled) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, Modifier.size(14.dp), tint = Warn)
                            Spacer(Modifier.width(4.dp))
                            Text(issues.first(), style = MaterialTheme.typography.labelSmall, color = Warn)
                        }
                    }
                    if (!step.enabled) Text("Turned off — skipped when running", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Step options") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onOpen() })
                        if (!isFirst) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, onClick = { menu = false; onMove(-1) })
                        if (!isLast) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, onClick = { menu = false; onMove(1) })
                        DropdownMenuItem(text = { Text("Add step below") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { menu = false; onInsertBelow() })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(
                            text = { Text(if (step.enabled) "Turn off" else "Turn on") },
                            leadingIcon = { Icon(if (step.enabled) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) },
                            onClick = { menu = false; onToggle() },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Run only this step") }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) }, onClick = { menu = false; onRunOnly() })
                        DropdownMenuItem(text = { Text("Run from here") }, leadingIcon = { Icon(Icons.Rounded.FastForward, null) }, onClick = { menu = false; onRunFrom() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Delete", color = Danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

/** Branch → sub-branch picker: categories expand into their actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStepSheet(onDismiss: () -> Unit, onRecord: (() -> Unit)? = null, onPick: (StepType) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var open by remember { mutableStateOf<Category?>(Category.FILES) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item {
                Text("Add a step", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                Text(
                    "Choose a group, then the action.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                )
                if (onRecord != null) {
                    BuildInAppCard(
                        "Open Gmail, D-Nothi, Chrome or any app and pick from the options on its own screens — each choice becomes a step.",
                        onRecord,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            items(Category.entries.toList(), key = { it.name }) { cat ->
                val expanded = open == cat
                val actions = StepCatalog.all.filter { it.category == cat }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable { open = if (expanded) null else cat }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconBadge(cat.icon, cat.tint, 42.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(cat.title, style = MaterialTheme.typography.titleMedium)
                            Text(cat.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${actions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                    if (expanded) {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                            actions.forEach { a ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                        .clickable { onPick(a.type) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(2.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(cat.tint))
                                    Spacer(Modifier.width(12.dp))
                                    Icon(a.icon, null, tint = cat.tint, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Text(a.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(wf: Workflow, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(wf.name) }
    var emoji by remember { mutableStateOf(wf.emoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name & icon") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions.Default,
                )
                Spacer(Modifier.height(14.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(EMOJIS) { e ->
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (e == emoji) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { emoji = e },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifBlank { "Untitled" }, emoji) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(h: Int, m: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val st = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run at") },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = st) } },
        confirmButton = { TextButton(onClick = { onPick(st.hour, st.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** An "Open file" of a .py with no "Edit a file" for it yet → (where to insert, the script). */
private fun suggestDatesStep(wf: Workflow): Pair<Int, java.io.File>? {
    val i = wf.steps.indexOfFirst { it.type == StepType.OPEN_FILE && it.s("path").endsWith(".py", true) }
    if (i < 0) return null
    val path = wf.steps[i].s("path")
    if (wf.steps.any { it.type == StepType.EDIT_FILE && it.s("path") == path }) return null
    val f = java.io.File(path)
    return if (f.isFile) i to f else null
}

@Composable
private fun SuggestEditCard(script: java.io.File, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.padding(top = 12.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("New: update the code before it runs", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Add an “Edit a file” step for ${script.name} — e.g. set START_DATETIME / END_DATETIME to last week automatically each time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Button(onClick = onAdd) { Text("Add “Edit a file” step") }
        }
    }
}
