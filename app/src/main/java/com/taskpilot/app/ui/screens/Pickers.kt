package com.taskpilot.app.ui.screens

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Image as ImageIcon
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.taskpilot.app.engine.CaptureResult
import com.taskpilot.app.engine.FileOps
import com.taskpilot.app.engine.Perms
import com.taskpilot.app.engine.UiElement
import com.taskpilot.app.ui.IconBadge
import com.taskpilot.app.ui.theme.Mono
import com.taskpilot.app.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

// ============================================================ shared full-screen shell

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreen(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
    onBack: () -> Unit = onClose,
    backIsUp: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = if (backIsUp) onBack else onClose) {
                            Icon(if (backIsUp) Icons.Rounded.ArrowBack else Icons.Rounded.Close, "Back")
                        }
                    },
                    actions = { if (backIsUp) IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                content()
            }
        }
    }
}

// ============================================================ folder / file browser (branch → sub-branch)

@Composable
fun FileBrowserDialog(start: String, pickFile: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val root = FileOps.root
    val startDir = remember(start) {
        var f: File? = File(start)
        if (f != null && f.isFile) f = f.parentFile
        while (f != null && !f.isDirectory) f = f.parentFile
        if (f == null || !f.absolutePath.startsWith(root.absolutePath)) root else f
    }
    var cur by remember { mutableStateOf(startDir) }
    var query by remember(cur) { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val hasAccess = remember(refresh) { Perms.allFiles(ctx) }
    val entries = remember(cur, refresh) {
        (cur.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && (pickFile || it.isDirectory) }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val shown = if (query.isBlank()) entries else entries.filter { it.name.contains(query, ignoreCase = true) }
    val crumbs = remember(cur) {
        val list = ArrayList<File>()
        var f: File? = cur
        while (f != null) {
            list.add(0, f)
            if (f.absolutePath == root.absolutePath) break
            f = f.parentFile
        }
        list
    }
    val crumbState = rememberLazyListState()
    LaunchedEffect(crumbs.size) { if (crumbs.isNotEmpty()) crumbState.scrollToItem(crumbs.lastIndex) }
    val atRoot = cur.absolutePath == root.absolutePath

    FullScreen(
        title = if (pickFile) "Choose a file" else "Choose a folder",
        subtitle = if (pickFile) "Open folders, then tap the file" else "Open the folder, then press Select",
        onClose = onDismiss,
        onBack = { if (atRoot) onDismiss() else cur.parentFile?.let { cur = it } },
        backIsUp = !atRoot,
    ) {
        // breadcrumb trail
        LazyRow(
            state = crumbState,
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            itemsIndexed(crumbs) { i, f ->
                val last = i == crumbs.lastIndex
                Text(
                    if (f.absolutePath == root.absolutePath) "Device storage" else f.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (last) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { cur = f }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                if (!last) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Filter in ${if (atRoot) "Device storage" else cur.name}") },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Box(Modifier.weight(1f)) {
            if (!hasAccess) {
                NoAccess(ctx) { refresh++ }
            } else if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("📂", style = MaterialTheme.typography.displaySmall)
                    Text(if (query.isBlank()) "This folder is empty" else "Nothing matches “$query”", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(shown, key = { it.absolutePath }) { f ->
                        FileRow(ctx, f) { if (f.isDirectory) cur = f else onPick(f.absolutePath) }
                    }
                }
            }
        }
        if (!pickFile) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder, null, tint = Warn)
                    Spacer(Modifier.width(10.dp))
                    Text(if (atRoot) "Device storage" else cur.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Button(onClick = { onPick(cur.absolutePath) }) { Text("Select") }
                }
            }
        }
    }
}

@Composable
private fun FileRow(ctx: Context, f: File, onClick: () -> Unit) {
    val isDir = f.isDirectory
    val sub = remember(f) {
        if (isDir) {
            val n = f.list()?.count { !it.startsWith(".") } ?: 0
            "$n item${if (n == 1) "" else "s"}"
        } else {
            Formatter.formatShortFileSize(ctx, f.length()) + " · " +
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified()))
        }
    }
    val ext = f.extension.lowercase()
    val (icon, tint) = when {
        isDir -> Icons.Rounded.Folder to Warn
        ext == "py" -> Icons.Rounded.Code to Color(0xFF3B82F6)
        ext in setOf("xlsx", "xls", "csv") -> Icons.Rounded.TableChart to Color(0xFF16A34A)
        ext in setOf("png", "jpg", "jpeg", "webp") -> Icons.Rounded.ImageIcon to Color(0xFFEC4899)
        else -> Icons.Rounded.Description to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isDir) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoAccess(ctx: Context, onRecheck: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        IconBadge(Icons.Rounded.SdStorage, Warn, 64.dp, 34.dp)
        Spacer(Modifier.height(16.dp))
        Text("Allow access to your files", style = MaterialTheme.typography.titleMedium)
        Text(
            "TaskPilot needs “All files access” to show folders like Download and to find your script's output.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        Button(onClick = { try { ctx.startActivity(Perms.allFilesIntent(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }) { Text("Allow access") }
        TextButton(onClick = onRecheck) { Text("I've allowed it — refresh") }
    }
}

// ============================================================ app picker

data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)

private object AppList {
    @Volatile var cache: List<AppEntry>? = null
    fun load(ctx: Context): List<AppEntry> {
        cache?.let { return it }
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it }
            .distinctBy { it.first }
            .filter { it.first != ctx.packageName }
            .map { (pkg, ri) ->
                val icon = try { ri.loadIcon(pm).toBitmap(96, 96).asImageBitmap() } catch (_: Exception) { null }
                AppEntry(pkg, ri.loadLabel(pm).toString(), icon)
            }
            .sortedBy { it.label.lowercase() }
        cache = list
        return list
    }
}

private val SUGGESTED = listOf("ru.iiec.pydroid3", "com.whatsapp", "com.whatsapp.w4b", "com.google.android.gm", "org.telegram.messenger")

@Composable
fun AppPickerDialog(onDismiss: () -> Unit, onPick: (pkg: String, label: String) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(AppList.cache) { value = withContext(Dispatchers.IO) { AppList.load(ctx) } }
    var query by remember { mutableStateOf("") }
    FullScreen(title = "Choose an app", onClose = onDismiss) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text("Search apps") },
            singleLine = true,
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val filtered = if (query.isBlank()) list else list.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
            val suggested = if (query.isBlank()) SUGGESTED.mapNotNull { p -> list.firstOrNull { it.pkg == p } } else emptyList()
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                if (suggested.isNotEmpty()) {
                    item { ListHeader("Suggested") }
                    items(suggested, key = { "s-" + it.pkg }) { a -> AppRow(a) { onPick(a.pkg, a.label) } }
                    item { ListHeader("All apps") }
                }
                items(filtered, key = { it.pkg }) { a -> AppRow(a) { onPick(a.pkg, a.label) } }
            }
        }
    }
}

@Composable
private fun ListHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun AppRow(a: AppEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ic = a.icon
        if (ic != null) Image(ic, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.bodyLarge)
            Text(a.pkg, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ============================================================ element picker (from a live capture)

@Composable
fun ElementPickerDialog(res: CaptureResult, onDismiss: () -> Unit, onPick: (UiElement) -> Unit) {
    val hasShot = res.shot != null
    var tab by remember { mutableIntStateOf(if (hasShot) 0 else 1) }
    var selected by remember { mutableStateOf<UiElement?>(null) }
    var onlyTappable by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    val pool = remember(res, onlyTappable) {
        res.elements.filter { !onlyTappable || it.clickable || it.editable }
    }

    FullScreen(title = "Tap the button you want", subtitle = "${res.elements.size} things found on screen", onClose = onDismiss) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, enabled = hasShot, text = { Text("Screen") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("List") })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = onlyTappable, onClick = { onlyTappable = !onlyTappable }, label = { Text("Tappable only") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0 && res.shot != null) {
                ScreenPane(res, pool, selected) { selected = it }
            } else {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        placeholder = { Text("Search: Run, Undo, Send…") },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    val shown = pool.filter {
                        query.isBlank() || it.label.contains(query, true) || it.id.contains(query, true) || it.desc.contains(query, true)
                    }
                    LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(shown) { e ->
                            val isSel = selected == e
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { selected = e }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconBadge(
                                    if (e.editable) Icons.Rounded.TextFields else if (e.clickable) Icons.Rounded.TouchApp else Icons.Rounded.Description,
                                    if (e.clickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    36.dp, 18.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(e.shortId, e.shortCls).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val s = selected
                Column(Modifier.weight(1f)) {
                    if (s == null) {
                        Text("Nothing selected", style = MaterialTheme.typography.titleSmall)
                        Text(if (tab == 0) "Tap a highlighted box on the picture" else "Tap an item in the list", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("“${s.label}”", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(s.shortId, s.shortCls).filter { it.isNotBlank() }.joinToString(" · "),
                            style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = { selected?.let(onPick) }, enabled = selected != null) { Text("Use this") }
            }
        }
    }
}

@Composable
private fun ScreenPane(res: CaptureResult, pool: List<UiElement>, selected: UiElement?, onSelect: (UiElement) -> Unit) {
    val shot = res.shot ?: return
    val bmp = remember(shot) { shot.asImageBitmap() }
    val primary = MaterialTheme.colorScheme.primary
    val highlight = Color(0xFFFFB020)
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF0B0D12)).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val maxWpx = constraints.maxWidth.toFloat()
        val maxHpx = constraints.maxHeight.toFloat()
        val sw = res.screenW.coerceAtLeast(1).toFloat()
        val sh = res.screenH.coerceAtLeast(1).toFloat()
        val scale = minOf(maxWpx / sw, maxHpx / sh)
        val wDp = with(density) { (sw * scale).toDp() }
        val hDp = with(density) { (sh * scale).toDp() }
        Box(Modifier.size(wDp, hDp).clip(RoundedCornerShape(12.dp))) {
            Image(bmp, contentDescription = "Captured screen", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(pool, scale) {
                        detectTapGestures { off ->
                            val x = off.x / scale
                            val y = off.y / scale
                            val hits = pool.filter { it.bounds.contains(x.toInt(), y.toInt()) }
                            val best = hits.sortedWith(
                                compareBy<UiElement>({ !it.clickable }, { it.bounds.width().toLong() * it.bounds.height() }),
                            ).firstOrNull()
                            if (best != null) onSelect(best)
                        }
                    },
            ) {
                for (e in pool) {
                    val tl = Offset(e.bounds.left * scale, e.bounds.top * scale)
                    val sz = Size(e.bounds.width() * scale, e.bounds.height() * scale)
                    drawRect(primary.copy(alpha = 0.10f), tl, sz)
                    drawRect(primary.copy(alpha = 0.55f), tl, sz, style = Stroke(width = 1.5f))
                }
                selected?.let { e ->
                    val tl = Offset(e.bounds.left * scale, e.bounds.top * scale)
                    val sz = Size(e.bounds.width() * scale, e.bounds.height() * scale)
                    drawRect(highlight.copy(alpha = 0.30f), tl, sz)
                    drawRect(highlight, tl, sz, style = Stroke(width = 4f))
                }
            }
        }
    }
}
