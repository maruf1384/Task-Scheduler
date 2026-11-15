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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
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
import com.taskpilot.app.core.OptKind
import com.taskpilot.app.core.RecEvent
import com.taskpilot.app.core.RecMode
import com.taskpilot.app.engine.Recorder

private val BarBg = Color(0xF21B2150)
private val Rec = Color(0xFFEF4444)
private val NewTag = Color(0xFF16A34A)

private fun kindIcon(k: OptKind): ImageVector = when (k) {
    OptKind.BUTTON -> Icons.Rounded.TouchApp
    OptKind.FIELD -> Icons.Rounded.TextFields
    OptKind.ITEM -> Icons.Rounded.ViewList
    OptKind.TOGGLE -> Icons.Rounded.ToggleOn
    OptKind.SCROLL -> Icons.Rounded.SwipeDown
}

/**
 * Floating control bar shown over the app being automated.
 * It draws `Recorder.state` and sends `RecEvent`s — it keeps no state of its own.
 */
@Composable
fun RecorderBar() {
    val state by Recorder.state.collectAsState()
    val st = state ?: return
    val send = Recorder::send
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
                    "Building · ${st.session.appName} · ${st.stepCount} step${if (st.stepCount == 1) "" else "s"}",
                    color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { send(RecEvent.MoveBar) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.SwapVert, "Move this bar", tint = Color.White)
                }
            }
            if (st.message.isNotBlank()) {
                Text(st.message, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            when (st.mode) {
                RecMode.Opening, RecMode.Acting -> LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))

                is RecMode.Typing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Recorder.saveTyped() }) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Save text")
                    }
                    TextButton(onClick = { send(RecEvent.CancelTyping) }) { Text("Cancel", color = Color.White) }
                }

                is RecMode.Choosing, RecMode.Peeking, RecMode.Watching -> Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { send(RecEvent.TogglePanel) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (st.panelOpen) Color.White else Color(0xFF3D4ED8),
                            contentColor = if (st.panelOpen) BarBg else Color.White,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Rounded.List, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        // The count is live: the options follow the app's screen even while hidden.
                        Text(if (st.panelOpen) "Hide options" else "Options · ${st.options.size}")
                    }
                    FilterChip(
                        selected = st.watching,
                        onClick = { send(RecEvent.Watch(!st.watching)) },
                        label = { Text("Record my taps", color = Color.White) },
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(if (st.watching) Rec else Color.White.copy(alpha = 0.5f))) },
                    )
                    IconButton(onClick = { send(RecEvent.Undo) }, enabled = st.stepCount > 0) {
                        Icon(Icons.Rounded.Undo, "Undo last step", tint = Color.White)
                    }
                    Button(
                        onClick = { send(RecEvent.Done) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("Done") }
                    TextButton(onClick = { send(RecEvent.Discard) }) {
                        Text(if (st.discardArmed) "Tap again to discard" else "Discard", color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

/** "On this screen" — every option of the real app's current screen, each expanding into actions. */
@Composable
fun RecorderPanel() {
    val state by Recorder.state.collectAsState()
    val st = state ?: return
    val send = Recorder::send
    val options = st.options
    val expanded = st.expanded?.sig
    var filter by remember { mutableStateOf<OptKind?>(null) }
    val shown = options.filter { filter == null || it.kind == filter }
    val list = rememberLazyListState()
    // A different screen → start from the top. (More options on the same screen → stay put.)
    LaunchedEffect(st.screenNo) {
        if (options.none { it.isNew }) { filter = null; list.scrollToItem(0) }
    }
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
                        if (options.isEmpty() && st.reading) "Reading the screen…"
                        else "${options.size} options · updates by itself as the app changes",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { send(RecEvent.TogglePanel) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Hide") }
            }
            if (st.reading && options.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All ${options.size}") })
                OptKind.entries.forEach { k ->
                    val n = options.count { it.kind == k }
                    if (n > 0) FilterChip(selected = filter == k, onClick = { filter = if (filter == k) null else k }, label = { Text("${k.title} $n") })
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(onClick = { send(RecEvent.Key("BACK")) }, label = { Text("Back") }, leadingIcon = { Icon(Icons.Rounded.ArrowBack, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { send(RecEvent.Key("HOME")) }, label = { Text("Home") }, leadingIcon = { Icon(Icons.Rounded.Home, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { send(RecEvent.AddWait) }, label = { Text("Wait 2 s") }, leadingIcon = { Icon(Icons.Rounded.Timer, null, Modifier.size(16.dp)) })
            }
            HorizontalDivider()
            if (!st.reading && shown.isEmpty()) {
                Text(
                    "Nothing found here yet. Some apps draw their screen as one picture — scroll a little, or use “Record my taps”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                items(shown, key = { it.sig }) { o ->
                    val isSel = expanded == o.sig
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { send(RecEvent.Expand(if (isSel) null else o.sig)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(kindIcon(o.kind), MaterialTheme.colorScheme.primary, 34.dp, 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(o.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (o.isNew) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "NEW", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clip(RoundedCornerShape(50)).background(NewTag).padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(
                                    o.kind.one + if (o.detail.isNotBlank()) " · ${o.detail}" else "",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(if (isSel) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSel) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                o.kind.actions().forEachIndexed { i, a ->
                                    val label = a.labelFor(o.kind)
                                    if (i == 0) Button(onClick = { send(RecEvent.Choose(o.sig, a)) }) { Text(label) }
                                    else AssistChip(onClick = { send(RecEvent.Choose(o.sig, a)) }, label = { Text(label) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Outlines the option whose actions are showing, on top of the real app. */
@Composable
fun RecorderHighlight() {
    val state by Recorder.state.collectAsState()
    val o = state?.expanded
    val view = LocalView.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(
        Modifier.fillMaxSize().onGloballyPositioned {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            origin = Offset(loc[0].toFloat(), loc[1].toFloat())
        },
    ) {
        if (o == null) return@Canvas
        val tl = Offset(o.left - origin.x, o.top - origin.y)
        val sz = Size((o.right - o.left).toFloat(), (o.bottom - o.top).toFloat())
        drawRect(Color(0x55FFB020), tl, sz)
        drawRect(Color(0xFFFFB020), tl, sz, style = Stroke(width = 6f))
    }
}
