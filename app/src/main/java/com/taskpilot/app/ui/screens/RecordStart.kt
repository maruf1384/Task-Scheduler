package com.taskpilot.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.StepType
import com.taskpilot.app.engine.AutomationService
import com.taskpilot.app.engine.Recorder
import com.taskpilot.app.engine.Runner
import com.taskpilot.app.ui.rememberToast

/**
 * Starts "Build it in the real app" for [wfId], inserting the recorded steps at [insertAt].
 * An "Open app" step is added first unless an earlier step already works in that app.
 * Returns null on success, else a reason to show.
 */
fun startRecording(wfId: String, insertAt: Int, pkg: String, appName: String): String? {
    val svc = AutomationService.instance ?: return "Turn on the TaskPilot accessibility service in Setup first."
    if (Runner.isRunning) return "An automation is running — wait for it to finish."
    val before = Repo.get(wfId)?.steps?.take(insertAt).orEmpty()
    val alreadyThere = before.any { s ->
        s.s("pkg") == pkg || s.s(Selector.PKG) == pkg ||
            (s.type == StepType.SEND_WHATSAPP && s.s("app") == pkg)
    }
    return Recorder.start(svc, Recorder.Session(wfId, insertAt, pkg, appName), addOpenStep = !alreadyThere)
}

/** The highlighted "Build it in the real app" card used in the add-step and new-automation sheets. */
@Composable
fun BuildInAppCard(subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FiberManualRecord, null, tint = Color(0xFFEF4444))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Build it in the real app", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Shows "Added 6 steps from Gmail" when you come back from building in an app. */
@Composable
fun RecorderResultToast() {
    val toast = rememberToast()
    val msg by Recorder.finished.collectAsState()
    LaunchedEffect(msg) {
        val m = msg ?: return@LaunchedEffect
        toast(m)
        Recorder.finished.value = null
    }
}
