package com.taskpilot.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Templates
import com.taskpilot.app.data.Workflow
import com.taskpilot.app.engine.Perms
import com.taskpilot.app.engine.Runner
import com.taskpilot.app.engine.Scheduler
import com.taskpilot.app.ui.EmojiBadge
import com.taskpilot.app.ui.Nav
import com.taskpilot.app.ui.Pill
import com.taskpilot.app.ui.Screen
import com.taskpilot.app.ui.SectionLabel
import com.taskpilot.app.ui.StepCatalog
import com.taskpilot.app.ui.fmtAgo
import com.taskpilot.app.ui.fmtIn
import com.taskpilot.app.ui.fmtSchedule
import com.taskpilot.app.ui.fmtWhen
import com.taskpilot.app.ui.rememberToast
import com.taskpilot.app.ui.theme.Danger
import com.taskpilot.app.ui.theme.Success
import com.taskpilot.app.ui.theme.Warn
import java.util.UUID

@Composable
fun HomeScreen(nav: Nav) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Rounded.Bolt, null) }, label = { Text("Automations") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Rounded.History, null) }, label = { Text("History") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Rounded.Shield, null) }, label = { Text("Setup") })
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showNew = true },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("New automation") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> AutomationsTab(nav) { tab = 2 }
                1 -> HistoryTab()
                else -> SetupTab()
            }
        }
    }
    if (showNew) NewAutomationSheet(onDismiss = { showNew = false }) { wf ->
        showNew = false
        Repo.upsert(wf)
        nav.push(Screen.Editor(wf.id))
    }
}

@Composable
private fun AutomationsTab(nav: Nav, openSetup: () -> Unit) {
    val ctx = LocalContext.current
    val wfs by Repo.workflows.collectAsState()
    val running by Runner.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val ready = remember(tick, wfs) { Perms.readiness(ctx) }
    val next = remember(wfs, tick) {
        wfs.mapNotNull { w -> Scheduler.nextTrigger(w.schedule)?.let { w to it } }.minByOrNull { it.second }
    }
    val toast = rememberToast()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 4.dp)) {
                Text("TaskPilot", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Runs only when scheduled · everything stays on this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        running?.let { r ->
            item(key = "running") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Running · ${r.wfName}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Text("Step ${r.index + 1} of ${r.total} · ${r.stepTitle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            FilledTonalButton(onClick = { Runner.stop() }) {
                                Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Stop")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (r.index + 1f) / r.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                        )
                    }
                }
            }
        }

        if (ready.first < ready.second) {
            item(key = "setup") {
                Card(
                    onClick = openSetup,
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                progress = { ready.first.toFloat() / ready.second },
                                modifier = Modifier.size(44.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                            )
                            Text("${ready.first}/${ready.second}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Finish setup", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text("A few permissions are needed before automations can run on their own.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }

        item(key = "next") { NextRunCard(next) }

        item(key = "label") { SectionLabel("Your automations · ${wfs.size}") }

        if (wfs.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🧭", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("No automations yet", style = MaterialTheme.typography.titleMedium)
                    Text("Tap “New automation” to build one step by step.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        items(wfs, key = { it.id }) { wf ->
            WorkflowCard(
                wf = wf,
                isRunning = running?.wfId == wf.id,
                onOpen = { nav.push(Screen.Editor(wf.id)) },
                onToggle = { on ->
                    val s = wf.schedule.copy(enabled = on, days = if (on && wf.schedule.days.isEmpty()) setOf(1, 2, 3, 4, 5, 6, 7) else wf.schedule.days)
                    val updated = wf.copy(schedule = s)
                    Repo.upsert(updated)
                    Scheduler.schedule(ctx, updated)
                    if (on) Scheduler.nextTrigger(s)?.let { toast("Next run ${fmtWhen(it)}") }
                },
                onRun = { Runner.launch(ctx, wf.id, "Manual")?.let(toast) },
                onDuplicate = {
                    val copy = wf.copy(
                        id = UUID.randomUUID().toString(),
                        name = wf.name + " (copy)",
                        schedule = wf.schedule.copy(enabled = false),
                        steps = wf.steps.map { it.copy(id = UUID.randomUUID().toString()) },
                        lastRunAt = 0, lastStatus = "",
                    )
                    Repo.upsert(copy)
                    toast("Duplicated")
                },
                onDelete = {
                    Scheduler.cancel(ctx, wf.id)
                    Repo.delete(wf.id)
                    toast("Deleted “${wf.name}”")
                },
            )
        }
    }
}

@Composable
private fun NextRunCard(next: Pair<Workflow, java.time.ZonedDateTime>?) {
    val brush = Brush.linearGradient(listOf(Color(0xFF3D4ED8), Color(0xFF1E2A78)))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(brush)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.AlarmOn, null, tint = Color.White) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("NEXT RUN", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold)
                if (next == null) {
                    Text("Nothing scheduled", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("Turn on a schedule to let an automation run by itself.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                } else {
                    Text(fmtWhen(next.second), style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("${next.first.emoji} ${next.first.name} · ${fmtIn(next.second)}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    wf: Workflow,
    isRunning: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val issues = wf.steps.filter { it.enabled }.sumOf { StepCatalog.issues(it).size }
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(wf.emoji)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(wf.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${wf.steps.size} steps · ${if (wf.schedule.enabled) fmtSchedule(wf.schedule) else "Not scheduled"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = wf.schedule.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Run now") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) }, onClick = { menu = false; onRun() })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Delete", color = Danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Danger) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    isRunning -> Pill("Running now", MaterialTheme.colorScheme.secondary)
                    issues > 0 -> Pill("$issues thing${if (issues > 1) "s" else ""} to set up", Warn)
                    wf.lastStatus == "success" -> Pill("Last run OK · ${fmtAgo(wf.lastRunAt)}", Success)
                    wf.lastStatus == "failed" -> Pill("Last run failed · ${fmtAgo(wf.lastRunAt)}", Danger)
                    wf.lastStatus == "stopped" -> Pill("Stopped · ${fmtAgo(wf.lastRunAt)}", MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Pill("Never run", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRun) {
                    Icon(Icons.Rounded.PlayArrow, "Run now", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${wf.name}”?") },
            text = { Text("Its steps and schedule will be removed. Run history is kept.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = Danger) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAutomationSheet(onDismiss: () -> Unit, onCreate: (Workflow) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("New automation", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("Start empty or from a ready-made recipe.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            BlankOrTemplateButtons(onCreate)
        }
    }
}

@Composable
private fun BlankOrTemplateButtons(onCreate: (Workflow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            onClick = { onCreate(Workflow(name = "New automation", emoji = "⚡")) },
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge("⚡", 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Start blank", style = MaterialTheme.typography.titleMedium)
                    Text("Build it step by step", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null)
            }
        }
        Card(
            onClick = { onCreate(Templates.weeklyDemand()) },
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge("📊", 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Python script → WhatsApp", style = MaterialTheme.typography.titleMedium)
                    Text("Run a .py in Pydroid 3, wait for its output, send the files to a number", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
