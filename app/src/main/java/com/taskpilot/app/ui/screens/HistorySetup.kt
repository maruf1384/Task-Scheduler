package com.taskpilot.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.RunRecord
import com.taskpilot.app.engine.Oem
import com.taskpilot.app.engine.Perms
import com.taskpilot.app.engine.Scheduler
import com.taskpilot.app.ui.EmojiBadge
import com.taskpilot.app.ui.IconBadge
import com.taskpilot.app.ui.Pill
import com.taskpilot.app.ui.SectionLabel
import com.taskpilot.app.ui.fmtDuration
import com.taskpilot.app.ui.theme.Danger
import com.taskpilot.app.ui.theme.Success
import com.taskpilot.app.ui.theme.Warn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================ History

@Composable
fun HistoryTab() {
    val runs by Repo.runs.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("History", style = MaterialTheme.typography.headlineMedium)
                    val ok = runs.count { it.status == "success" }
                    Text(
                        if (runs.isEmpty()) "Every run is logged here, step by step" else "${runs.size} runs · $ok succeeded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (runs.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        if (runs.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🕒", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("No runs yet", style = MaterialTheme.typography.titleMedium)
                    Text("Use “Test run” on an automation to try it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(runs, key = { it.id }) { r -> RunCard(r) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All run logs will be removed. Your automations stay.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; Repo.clearRuns() }) { Text("Clear", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private val whenFmt = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault())

@Composable
private fun RunCard(r: RunRecord) {
    var open by remember { mutableStateOf(false) }
    val color = when (r.status) {
        "success" -> Success
        "failed" -> Danger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        onClick = { open = !open },
        modifier = Modifier.animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiBadge(r.emoji, 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.workflowName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${whenFmt.format(Date(r.startedAt))} · ${r.trigger} · ${fmtDuration(r.endedAt - r.startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill(
                    when (r.status) { "success" -> "Done"; "failed" -> "Failed"; else -> "Stopped" },
                    color,
                )
            }
            if (r.summary.isNotBlank()) {
                Text(
                    r.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.status == "failed") Danger else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (open && r.logs.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                r.logs.forEach { l ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        val (ic, c) = when (l.status) {
                            "ok" -> Icons.Rounded.CheckCircle to Success
                            "failed" -> Icons.Rounded.Cancel to Danger
                            else -> Icons.Rounded.RemoveCircleOutline to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Icon(ic, null, Modifier.size(18.dp), tint = c)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${l.index + 1}. ${l.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (l.message.isNotBlank()) Text(l.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (l.durationMs > 0) Text(fmtDuration(l.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (r.logs.isNotEmpty()) {
                Text("Tap for step details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ============================================================ Setup

private fun open(ctx: Context, i: Intent) {
    try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        try { ctx.startActivity(Perms.appInfoIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

@Composable
fun SetupTab() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        tick++
        Scheduler.scheduleAll(ctx)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val files = remember(tick) { Perms.allFiles(ctx) }
    val a11y = remember(tick) { Perms.accessibility(ctx) }
    val notif = remember(tick) { Perms.notifications(ctx) }
    val alarms = remember(tick) { Perms.exactAlarms(ctx) }
    val battery = remember(tick) { Perms.battery(ctx) }
    val secure = remember(tick) { Perms.secureLock(ctx) }
    val done = listOf(files, a11y, notif, alarms, battery).count { it }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 4.dp)) {
                Text("Setup", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (done == 5) "All set — automations can run on their own." else "$done of 5 done. Each one opens the right settings page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done == 5) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { UpdateCard(ctx, tick) }
        item {
            PermCard(Icons.Rounded.Accessibility, "Accessibility service",
                "Lets TaskPilot tap Run in Pydroid and Send in WhatsApp. It stays idle except during a run.",
                a11y, "Turn on") { open(ctx, Perms.accessibilityIntent(ctx)) }
        }
        if (!a11y && Oem.restrictedSettingsApply) {
            item { RestrictedSettingsCard(ctx) }
        }
        item {
            PermCard(Icons.Rounded.SdStorage, "All files access",
                "To browse Device storage, open your .py script and pick up its output files.",
                files, "Allow") { open(ctx, Perms.allFilesIntent(ctx)) }
        }
        item {
            PermCard(Icons.Rounded.Alarm, "Exact alarms",
                "So a run starts at exactly 12:40 AM, not “sometime around then”.",
                alarms, "Allow") { open(ctx, Perms.exactAlarmIntent(ctx)) }
        }
        item {
            PermCard(Icons.Rounded.BatteryChargingFull, "No battery restriction",
                "Stops the phone from blocking the scheduled wake-up. TaskPilot still uses ~no battery — nothing runs between schedules.",
                battery, "Allow") { open(ctx, Perms.batteryIntent(ctx)) }
        }
        item {
            PermCard(Icons.Rounded.Notifications, "Notifications",
                "A short “done” or “failed” message after each run.",
                notif, "Allow") {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(ctx, Perms.notificationSettingsIntent(ctx))
            }
        }

        item { SectionLabel("Good to know") }
        item {
            InfoCard(
                Icons.Rounded.Lock,
                if (secure) "Your phone has a PIN / pattern lock" else "Screen lock",
                if (secure)
                    "Android doesn't let apps get past a secure lock. For night runs either save your PIN in the “Wake & unlock” step (encrypted with a key locked in the phone's security chip — it never leaves the phone), or set Screen lock to Swipe."
                else
                    "No secure lock detected — “Wake & unlock” can open the phone by itself.",
                if (secure) Warn else Success,
                action = if (secure) ("Lock settings" to { open(ctx, Perms.securitySettingsIntent()) }) else null,
            )
        }
        if (Oem.isHeavySkin) {
            item {
                InfoCard(
                    Icons.Rounded.RocketLaunch,
                    "${Oem.label} background limits",
                    "${Oem.label} phones close background apps aggressively. In App info turn on “Allow auto-launch” (or Autostart / Startup manager) and set Battery to “Don't optimise / Allow background activity”. If the accessibility service ever turns itself off, switch it back on here.",
                    MaterialTheme.colorScheme.primary,
                    action = "App info" to { open(ctx, Perms.appInfoIntent(ctx)) },
                )
            }
        }
        item {
            InfoCard(
                Icons.Rounded.DoNotDisturbOn,
                "How battery is saved",
                "No background service, no polling. The system alarm clock wakes TaskPilot only at the scheduled minute; the run finishes in a minute or two and everything goes idle again.",
                Success,
                action = null,
            )
        }
    }
}

@Composable
private fun RestrictedSettingsCard(ctx: Context) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp), tint = Warn)
                Spacer(Modifier.width(8.dp))
                Text("Switch greyed out, or “Restricted setting”?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Android blocks the accessibility switch for apps installed from a file (not the Play Store). " +
                    "The fix on ${Oem.label} — the first step is the one most people miss:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            Oem.restrictedSteps().forEachIndexed { i, step ->
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(step, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 1.dp))
                }
            }
            if (Oem.isHuawei) {
                Text(
                    "Tip for Honor / Huawei: if you still can't find it, open Settings → Security & privacy → More settings and look for “Install unknown apps” / restricted permissions.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { open(ctx, Perms.accessibilityIntent(ctx)) }, modifier = Modifier.weight(1f)) {
                    Text("Accessibility", maxLines = 1)
                }
                OutlinedButton(onClick = { open(ctx, Perms.appInfoIntent(ctx)) }, modifier = Modifier.weight(1f)) {
                    Text("App info", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PermCard(icon: ImageVector, title: String, why: String, granted: Boolean, action: String, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (granted) Success else MaterialTheme.colorScheme.primary, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    if (granted) Icon(Icons.Rounded.CheckCircle, "Done", Modifier.size(18.dp), tint = Success)
                    else Icon(Icons.Rounded.ErrorOutline, "Needed", Modifier.size(18.dp), tint = Warn)
                }
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                TextButton(onClick = onClick) { Text("Settings", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                FilledTonalButton(onClick = onClick) { Text(action) }
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, text: String, tint: Color, action: Pair<String, () -> Unit>?) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp)) {
            IconBadge(icon, tint, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                if (action != null) {
                    TextButton(onClick = action.second, contentPadding = PaddingValues(0.dp)) {
                        Text(action.first)
                    }
                }
            }
        }
    }
}


// ============================================================ Updates (keep permissions)

@Composable
private fun UpdateCard(ctx: Context, tick: Int) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val (installedName, installedCode) = remember { com.taskpilot.app.engine.Updater.currentVersion(ctx) }
    var found by remember { mutableStateOf<List<com.taskpilot.app.engine.Updater.Candidate>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tick) {
        found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (Perms.allFiles(ctx)) com.taskpilot.app.engine.Updater.findInDownloads(ctx) else emptyList()
        }
    }
    val newer = found.firstOrNull { it.versionCode > installedCode }

    fun install(c: com.taskpilot.app.engine.Updater.Candidate) {
        if (!com.taskpilot.app.engine.Updater.canInstall(ctx)) {
            open(ctx, com.taskpilot.app.engine.Updater.allowInstallIntent(ctx))
            status = "Turn on “Allow from this source” for TaskPilot, come back, and tap Install again. (Only needed once.)"
            return
        }
        busy = true
        status = "Preparing ${c.file.name}…"
        scope.launch {
            val err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.taskpilot.app.engine.Updater.install(ctx, c) }
            busy = false
            status = err ?: "Android will now ask you to confirm the update. TaskPilot closes and reopens with everything kept."
        }
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (newer != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.SystemUpdate, MaterialTheme.colorScheme.primary, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("App updates", style = MaterialTheme.typography.titleMedium)
                    Text("Installed: version $installedName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Always update from here. Then Android treats TaskPilot like a store app and keeps the Accessibility permission — no more “Restricted setting” after an update.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (newer != null) {
                Text("Found ${newer.file.name} · version ${newer.versionName}", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Button(onClick = { install(newer) }, enabled = !busy) {
                    Text("Install version ${newer.versionName}")
                }
            } else {
                Text(
                    "No newer TaskPilot APK in Download. Download the new APK in your browser (don't open it), then come back here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { pick = true }, contentPadding = PaddingValues(0.dp)) { Text("Choose an APK file…") }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (pick) {
        FileBrowserDialog(
            start = java.io.File(com.taskpilot.app.engine.FileOps.root, "Download").absolutePath,
            pickFile = true,
            onDismiss = { pick = false },
        ) { path ->
            pick = false
            val c = com.taskpilot.app.engine.Updater.inspect(ctx, java.io.File(path))
            when {
                c == null -> status = "That file isn't a TaskPilot APK."
                c.versionCode < installedCode -> status = "That's an older version (${c.versionName}) — Android won't install it over $installedName."
                else -> install(c)
            }
        }
    }
}
