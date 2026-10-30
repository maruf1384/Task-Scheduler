package com.taskpilot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskpilot.app.engine.Recorder

private val BarBg = Color(0xF21B2150)
private val Rec = Color(0xFFEF4444)

private fun kindIcon(k: Recorder.Kind): ImageVector = when (k) {
    Recorder.Kind.BUTTON -> Icons.Rounded.TouchApp
    Recorder.Kind.FIELD -> Icons.Rounded.TextFields
    Recorder.Kind.ITEM -> Icons.Rounded.ViewList
    Recorder.Kind.TOGGLE -> Icons.Rounded.ToggleOn
    Recorder.Kind.SCROLL -> Icons.Rounded.SwipeDown
}

/** Floating control bar shown over the app being automated. */
@Composable
fun RecorderBar() {
    val session by Recorder.session.collectAsState()
    val steps by Recorder.steps.collectAsState()
    val panelOpen by Recorder.panelOpen.collectAsState()
    val live by Recorder.liveRecord.collectAsState()
    val typing by Recorder.typingInto.collectAsState()
    val msg by Recorder.message.collectAsState()
    val confirmCancel by Recorder.confirmCancel.collectAsState()
    val s = session ?: return
    Surface(
        color = BarBg,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 10.dp,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Rec))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Building · ${s.appName} · ${steps.size} step${if (steps.size == 1) "" else "s"}",
                    color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { Recorder.moveBar() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.SwapVert, "Move this bar", tint = Color.White)
                }
            }
            if (msg.isNotBlank()) {
                Text(msg, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            if (typing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Recorder.saveTyped() }) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save text")
                    }
                    TextButton(onClick = { Recorder.cancelTyping() }) { Text("Cancel", color = Color.White) }
                }
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { Recorder.togglePanel() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (panelOpen) Color.White else Color(0xFF3D4ED8), contentColor = if (panelOpen) BarBg else Color.White),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Rounded.List, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text(if (panelOpen) "Hide options" else "Options")
                    }
                    FilterChip(
                        selected = live,
                        onClick = { Recorder.setLive(!live) },
                        label = { Text("Record my taps", color = Color.White) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Rec else Color.White.copy(alpha = 0.5f))) },
                    )
                    IconButton(onClick = { Recorder.undo() }, enabled = steps.isNotEmpty()) {
                        Icon(Icons.Rounded.Undo, "Undo last step", tint = Color.White)
                    }
                    Button(
                        onClick = { Recorder.finish() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("Done") }
                    TextButton(onClick = {
                        if (confirmCancel || steps.isEmpty()) Recorder.cancel() else Recorder.confirmCancel.value = true
                    }) {
                        Text(if (confirmCancel) "Tap again to discard" else "Discard", color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

/** "On this screen" — every option of the real app, grouped, each expanding into actions. */
@Composable
fun RecorderPanel() {
    val options by Recorder.options.collectAsState()
    val selected by Recorder.selected.collectAsState()
    val busy by Recorder.busy.collectAsState()
    var filter by remember { mutableStateOf<Recorder.Kind?>(null) }
    val shown = options.filter { filter == null || it.kind == filter }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        shadowElevation = 16.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant))
            }
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("On this screen", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (busy) "Reading the screen…" else "${options.size} options · pick one, then what to do",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { Recorder.refresh() }) { Icon(Icons.Rounded.Refresh, "Read the screen again") }
                IconButton(onClick = { Recorder.togglePanel() }) { Icon(Icons.Rounded.KeyboardArrowDown, "Hide") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${options.size}") })
                Recorder.Kind.entries.forEach { k ->
                    val n = options.count { it.kind == k }
                    if (n > 0) FilterChip(selected = filter == k, onClick = { filter = if (filter == k) null else k }, label = { Text("${k.title} $n") })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(onClick = { Recorder.phoneKey("BACK") }, label = { Text("Back") }, leadingIcon = { Icon(Icons.Rounded.ArrowBack, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { Recorder.phoneKey("HOME") }, label = { Text("Home") }, leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { Recorder.phoneKey("WAIT") }, label = { Text("Wait 2 s") }, leadingIcon = { Icon(Icons.Rounded.Timer, null, Modifier.size(16.dp)) })
            }
            HorizontalDivider()
            if (!busy && shown.isEmpty()) {
                Text(
                    "Nothing found. Some apps draw their screen as one picture — try ↻, scroll a bit, or use “Record my taps”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.key }) { o ->
                    val isSel = selected?.key == o.key
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { Recorder.select(if (isSel) null else o) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(kindIcon(o.kind), MaterialTheme.colorScheme.primary, 34.dp, 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    o.kind.title.removeSuffix("s") + if (o.detail.isNotBlank()) " · ${o.detail}" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(if (isSel) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSel) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val actions = when (o.kind) {
                                    Recorder.Kind.FIELD -> listOf("type" to "Type text", "tap" to "Tap", "wait" to "Wait for it")
                                    Recorder.Kind.SCROLL -> listOf("down" to "Scroll down", "up" to "Scroll up")
                                    Recorder.Kind.TOGGLE -> listOf("tap" to "Switch it", "wait" to "Wait for it")
                                    else -> listOf("tap" to "Tap", "long" to "Long press", "wait" to "Wait for it")
                                }
                                actions.forEachIndexed { i, (a, label) ->
                                    if (i == 0) Button(onClick = { Recorder.choose(o, a) }) { Text(label) }
                                    else AssistChip(onClick = { Recorder.choose(o, a) }, label = { Text(label) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Outlines the option you picked in the list, on top of the real app. */
@Composable
fun RecorderHighlight() {
    val sel by Recorder.selected.collectAsState()
    val view = LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        Modifier.fillMaxSize().onGloballyPositioned {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            origin = Offset(loc[0].toFloat(), loc[1].toFloat())
        },
    ) {
        val o = sel ?: return@Canvas
        val tl = Offset(o.bounds.left - origin.x, o.bounds.top - origin.y)
        val sz = Size(o.bounds.width().toFloat(), o.bounds.height().toFloat())
        drawRect(Color(0x55FFB020), tl, sz)
        drawRect(Color(0xFFFFB020), tl, sz, style = Stroke(width = 6f))
    }
}
