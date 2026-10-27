package com.taskpilot.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taskpilot.app.data.Phone
import com.taskpilot.app.data.Repo
import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import com.taskpilot.app.data.Templates
import com.taskpilot.app.engine.AutomationService
import com.taskpilot.app.engine.CaptureStore
import com.taskpilot.app.engine.FileOps
import com.taskpilot.app.engine.Runner
import com.taskpilot.app.ui.AppIcon
import com.taskpilot.app.ui.AppIcons
import com.taskpilot.app.ui.IconBadge
import com.taskpilot.app.ui.Nav
import com.taskpilot.app.ui.Screen
import com.taskpilot.app.ui.SectionLabel
import com.taskpilot.app.ui.StepCatalog
import com.taskpilot.app.ui.SwitchRow
import com.taskpilot.app.ui.rememberToast
import com.taskpilot.app.ui.theme.Mono
import com.taskpilot.app.ui.theme.Success
import com.taskpilot.app.ui.theme.Warn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val StepSaver = Saver<Step, String>(
    save = { Repo.json.encodeToString(it) },
    restore = { Repo.json.decodeFromString<Step>(it) },
)

private enum class Picker { FOLDER, FILE, APP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepEditorScreen(s: Screen.StepEdit, nav: Nav) {
    val ctx = LocalContext.current
    val toast = rememberToast()
    val wf = remember(s.wfId) { Repo.get(s.wfId) }
    if (wf == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val original = remember(s) {
        s.stepId?.let { id -> wf.steps.firstOrNull { it.id == id } }
            ?: StepCatalog.defaults(s.newType ?: StepType.DELAY)
    }
    val isNew = s.stepId == null
    val index = if (isNew) s.insertAt.coerceIn(0, wf.steps.size) else wf.steps.indexOfFirst { it.id == original.id }.coerceAtLeast(0)
    var draft by rememberSaveable(stateSaver = StepSaver) { mutableStateOf(original) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var pickerKey by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var captureAsk by remember { mutableStateOf(false) }
    var expectingCapture by rememberSaveable { mutableStateOf(false) }
    val capture by CaptureStore.result.collectAsState()
    val info = StepCatalog.info(draft.type)
    val dirty = draft != original || isNew

    fun commit() {
        val latest = Repo.get(wf.id) ?: return
        val steps = latest.steps.toMutableList()
        val i = steps.indexOfFirst { it.id == draft.id }
        if (i >= 0) steps[i] = draft else steps.add(index.coerceIn(0, steps.size), draft)
        Repo.upsert(latest.copy(steps = steps))
        nav.pop()
    }

    fun leave() { if (dirty && !isNew && draft != original) confirmLeave = true else nav.pop() }

    BackHandler { leave() }

    fun openPicker(p: Picker, key: String) { picker = p; pickerKey = key }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New step" else "Step ${index + 1}") },
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                actions = { TextButton(onClick = { commit() }) { Text(if (isNew) "Add" else "Save", fontWeight = FontWeight.Bold) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)) {
                Button(onClick = { commit() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (isNew) "Add step" else "Save changes")
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Header
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(info.icon, info.category.tint, 48.dp, 26.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info.category.title.uppercase(), style = MaterialTheme.typography.labelSmall, color = info.category.tint, fontWeight = FontWeight.Bold)
                        Text(info.title, style = MaterialTheme.typography.titleLarge)
                        Text(info.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            val issues = StepCatalog.issues(draft)
            if (issues.isEmpty()) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = Success)
                    Spacer(Modifier.width(6.dp))
                    Text("Ready", style = MaterialTheme.typography.labelMedium, color = Success)
                }
            } else {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = Warn)
                    Spacer(Modifier.width(6.dp))
                    Text(issues.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Warn)
                }
            }

            when (draft.type) {
                StepType.WAKE_UNLOCK -> {
                    SectionLabel("Lock screen")
                    Hint("Turns the screen on and opens the lock screen. Swipe locks open by themselves. For a PIN lock, save the PIN below — it is kept only inside this app on this phone. Pattern locks can't be opened automatically.")
                    Field(draft, "pin", "PIN (optional)", { draft = it }, keyboard = KeyboardType.NumberPassword, password = true,
                        support = "Digits only. Leave empty if you use Swipe/None.")
                    SwitchRow("Stop if still locked", "If off, the run continues even when the phone stays locked", draft.b("strict", true)) {
                        draft = draft.put("strict", it.toString())
                    }
                }

                StepType.LAUNCH_APP -> {
                    SectionLabel("App")
                    AppCard(draft.s("pkg"), draft.s("appName")) { openPicker(Picker.APP, "pkg") }
                }

                StepType.OPEN_FILE -> {
                    SectionLabel("File")
                    PathCard(draft.s("path"), isFile = true) { openPicker(Picker.FILE, "path") }
                    SectionLabel("Open with")
                    AppCard(draft.s("pkg"), draft.s("appName"), emptyText = "Let Android ask") { openPicker(Picker.APP, "pkg") }
                    SectionLabel("Link type")
                    Choice(
                        listOf(
                            "file" to ("Plain file path" to "Best for Pydroid 3 and editors — the app opens the real file in place"),
                            "content" to ("Secure link" to "Modern Android way; some apps open a temporary copy"),
                        ),
                        draft.s("uriMode", "file"),
                    ) { draft = draft.put("uriMode", it) }
                }

                StepType.TAP_ELEMENT, StepType.WAIT_ELEMENT, StepType.INPUT_TEXT -> {
                    SectionLabel("Which app")
                    AppCard(draft.s("pkg"), draft.s("appName"), emptyText = "Any app") { openPicker(Picker.APP, "pkg") }
                    SectionLabel(if (draft.type == StepType.INPUT_TEXT) "Text box (optional)" else "Element on screen")
                    ElementCard(Selector.from(draft), onPick = { captureAsk = true }, onClear = {
                        draft = draft.copy(p = draft.p - Selector.KEYS.toSet())
                    })
                    if (draft.type == StepType.INPUT_TEXT) {
                        Hint("Leave the text box empty to type into whichever box is focused.")
                        SectionLabel("Text")
                        Field(draft, "text", "Text to type", { draft = it }, support = "You can use {date}, {time}, {count}, {files}")
                    }
                    ManualSelector(draft) { draft = it }
                    SectionLabel("Options")
                    when (draft.type) {
                        StepType.TAP_ELEMENT -> {
                            Field(draft, "timeout", "Wait up to (seconds) for it to appear", { draft = it }, keyboard = KeyboardType.Number)
                            SwitchRow("Long press", null, draft.b("long", false)) { draft = draft.put("long", it.toString()) }
                            SwitchRow("Fall back to the saved position", "If the button can't be recognised, tap where it was when you picked it", draft.b("coords", true)) {
                                draft = draft.put("coords", it.toString())
                            }
                        }
                        StepType.WAIT_ELEMENT -> {
                            Choice(
                                listOf(
                                    "appear" to ("Until it appears" to "e.g. wait for “[Program finished]”"),
                                    "disappear" to ("Until it disappears" to "e.g. wait for a loading spinner to go away"),
                                ),
                                draft.s("mode", "appear"),
                            ) { draft = draft.put("mode", it) }
                            Field(draft, "timeout", "Give up after (seconds)", { draft = it }, keyboard = KeyboardType.Number)
                        }
                        else -> Field(draft, "timeout", "Wait up to (seconds) for the box", { draft = it }, keyboard = KeyboardType.Number)
                    }
                }

                StepType.SYSTEM_KEY -> {
                    SectionLabel("Key")
                    Choice(
                        listOf(
                            "BACK" to ("Back" to "Go back one screen"),
                            "HOME" to ("Home" to "Go to the home screen"),
                            "RECENTS" to ("Recent apps" to "Open the app switcher"),
                            "NOTIFICATIONS" to ("Notifications" to "Pull down the notification shade"),
                        ),
                        draft.s("key", "HOME"),
                    ) { draft = draft.put("key", it) }
                }

                StepType.DELAY -> {
                    SectionLabel("How long")
                    Field(draft, "seconds", "Seconds", { draft = it }, keyboard = KeyboardType.Number)
                    QuickPicks(listOf(2, 5, 10, 30, 60)) { draft = draft.put("seconds", it.toString()) }
                }

                StepType.WAIT_NEW_FILES -> {
                    SectionLabel("Folder to watch")
                    PathCard(draft.s("folder"), isFile = false) { openPicker(Picker.FOLDER, "folder") }
                    SectionLabel("Options")
                    Field(draft, "ext", "Only these types (optional)", { draft = it }, support = "e.g. xlsx  or  xlsx, pdf — empty = any file")
                    SwitchRow("Include sub-folders", "Scripts often save into a new dated folder", draft.b("recursive", true)) { draft = draft.put("recursive", it.toString()) }
                    Field(draft, "timeout", "Give up after (seconds)", { draft = it }, keyboard = KeyboardType.Number,
                        support = "Now: ${StepCatalog.fmtSecs(draft.i("timeout", 600))}")
                    Field(draft, "settle", "Files must stay unchanged for (seconds)", { draft = it }, keyboard = KeyboardType.Number,
                        support = "Makes sure every file is completely written before moving on")
                    Hint("The files found here are also remembered for a following “Send” step.")
                }

                StepType.SELECT_FILES -> {
                    SectionLabel("Go to folder")
                    PathCard(draft.s("folder"), isFile = false) { openPicker(Picker.FOLDER, "folder") }
                    SectionLabel("Select")
                    Choice(
                        listOf(
                            "run" to ("Files made during this run" to "Only the new output from this run (recommended)"),
                            "newestDir" to ("Everything in the newest sub-folder" to "For scripts that create a new folder each time"),
                            "all" to ("All files in the folder" to "Every matching file"),
                            "latest" to ("Just the newest file" to "The single most recent file"),
                        ),
                        draft.s("mode", "run"),
                    ) { draft = draft.put("mode", it) }
                    SectionLabel("Filter")
                    Field(draft, "ext", "Only these types (optional)", { draft = it }, support = "e.g. xlsx — empty = all files")
                    if (draft.s("mode", "run") != "newestDir") {
                        SwitchRow("Include sub-folders", null, draft.b("recursive", true)) { draft = draft.put("recursive", it.toString()) }
                    }
                    PreviewFiles(draft)
                }

                StepType.SEND_WHATSAPP -> {
                    SectionLabel("Send to")
                    Field(draft, "phone", "Phone number", { draft = it }, keyboard = KeyboardType.Phone,
                        support = Phone.toInternational(draft.s("phone"))?.let { "Will send to +$it" } ?: "e.g. 01521216482 — local numbers get +88 automatically")
                    SectionLabel("Using")
                    Choice(
                        listOf(
                            Templates.WHATSAPP to ("WhatsApp" to "com.whatsapp"),
                            Templates.WHATSAPP_BUSINESS to ("WhatsApp Business" to "com.whatsapp.w4b"),
                        ),
                        draft.s("app", Templates.WHATSAPP),
                    ) { draft = draft.put("app", it) }
                    SectionLabel("Message")
                    Field(draft, "caption", "Caption (optional)", { draft = it }, support = "You can use {date}, {time}, {count}, {files}")
                    SwitchRow("Press Send automatically", "Off = TaskPilot opens the chat and you press Send", draft.b("autoSend", true)) {
                        draft = draft.put("autoSend", it.toString())
                    }
                    Hint("Sends the files picked by the previous “Select files” or “Wait for new files” step. The number must have WhatsApp; saving it in your contacts makes this most reliable.")
                }

                StepType.SHARE_FILES -> {
                    SectionLabel("Share to")
                    AppCard(draft.s("pkg"), draft.s("appName"), emptyText = "Ask each time (share sheet)") { openPicker(Picker.APP, "pkg") }
                    SectionLabel("Message")
                    Field(draft, "caption", "Text (optional)", { draft = it }, support = "You can use {date}, {time}, {count}, {files}")
                }

                StepType.NOTIFY -> {
                    SectionLabel("Notification")
                    Field(draft, "title", "Title", { draft = it })
                    Field(draft, "text", "Text", { draft = it }, support = "You can use {date}, {time}, {count}, {files}")
                }

                StepType.LOCK_SCREEN -> {
                    SectionLabel("About")
                    Hint("Turns the screen off and locks the phone — handy as the last step of a night-time run.")
                }
            }

            SectionLabel("Name (optional)")
            OutlinedTextField(
                value = draft.label,
                onValueChange = { draft = draft.copy(label = it) },
                placeholder = { Text(info.title) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------------- pickers
    when (picker) {
        Picker.FOLDER -> FileBrowserDialog(
            start = draft.s(pickerKey).ifBlank { FileOps.root.absolutePath + "/Download" },
            pickFile = false,
            onDismiss = { picker = null },
        ) { path -> draft = draft.put(pickerKey, path); picker = null }

        Picker.FILE -> FileBrowserDialog(
            start = draft.s(pickerKey).ifBlank { FileOps.root.absolutePath + "/Download" },
            pickFile = true,
            onDismiss = { picker = null },
        ) { path -> draft = draft.put(pickerKey, path); picker = null }

        Picker.APP -> AppPickerDialog(onDismiss = { picker = null }) { pkg, label ->
            draft = draft.putAll(mapOf(pickerKey to pkg, "appName" to label))
            if (draft.type == StepType.TAP_ELEMENT || draft.type == StepType.WAIT_ELEMENT || draft.type == StepType.INPUT_TEXT) {
                draft = draft.put(Selector.PKG, pkg)
            }
            picker = null
        }

        null -> {}
    }

    if (captureAsk) {
        val app = draft.s("appName").ifBlank { "the app" }
        val pkg = draft.s("pkg")
        val canRunBefore = index > 0
        AlertDialog(
            onDismissRequest = { captureAsk = false },
            icon = { Icon(Icons.Rounded.TouchApp, null) },
            title = { Text("Pick on screen") },
            text = {
                Text(
                    "TaskPilot will switch to $app and show a small floating bar.\n\n" +
                        "Go to the screen that has the button (for Pydroid, the editor with your script open), then tap “◉ Capture”. " +
                        "You'll come back here and simply tap the button on a picture of that screen." +
                        if (canRunBefore) "\n\nTip: “Run steps 1–$index first” gets you to the right screen automatically." else "",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (canRunBefore) {
                        TextButton(onClick = {
                            captureAsk = false
                            if (AutomationService.instance == null) {
                                toast("Turn on the accessibility service in Setup first")
                            } else {
                                expectingCapture = true
                                val err = Runner.launch(ctx, wf.id, "Capture", endExclusive = index, after = { svc -> svc.beginCapture(pkg, launch = false) })
                                if (err != null) {
                                    expectingCapture = false
                                    toast(err)
                                }
                            }
                        }) { Text("Run steps 1–$index first") }
                    }
                    TextButton(onClick = {
                        captureAsk = false
                        val svc = AutomationService.instance
                        if (svc == null) {
                            toast("Turn on the accessibility service in Setup first")
                        } else {
                            expectingCapture = true
                            svc.beginCapture(pkg, launch = pkg.isNotBlank())
                        }
                    }) { Text(if (pkg.isNotBlank()) "Open $app now" else "Start capture") }
                }
            },
            dismissButton = { TextButton(onClick = { captureAsk = false }) { Text("Cancel") } },
        )
    }

    val cap = capture
    if (expectingCapture && cap != null) {
        ElementPickerDialog(
            res = cap,
            onDismiss = { expectingCapture = false; CaptureStore.result.value = null },
        ) { el ->
            var d = draft.putAll(el.toParams(cap.screenW, cap.screenH))
            if (d.s("pkg").isBlank() && el.pkg.isNotBlank()) {
                d = d.putAll(mapOf("pkg" to el.pkg, "appName" to (AppIcons.label(ctx, el.pkg) ?: el.pkg)))
            }
            draft = d
            expectingCapture = false
            CaptureStore.result.value = null
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this step haven't been saved.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; commit() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; nav.pop() }) { Text("Discard") } },
        )
    }
}

// ============================================================ form pieces

@Composable
private fun Hint(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .padding(12.dp),
    ) {
        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Field(
    step: Step,
    key: String,
    label: String,
    onChange: (Step) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    support: String? = null,
    password: Boolean = false,
) {
    val supportContent: (@Composable () -> Unit)? = support?.let { msg -> @Composable { Text(msg) } }
    OutlinedTextField(
        value = step.s(key),
        onValueChange = { v ->
            val clean = if (keyboard == KeyboardType.Number || keyboard == KeyboardType.NumberPassword) v.filter { it.isDigit() } else v
            onChange(step.put(key, clean))
        },
        label = { Text(label) },
        supportingText = supportContent,
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Choice(options: List<Pair<String, Pair<String, String>>>, selected: String, onSelect: (String) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            options.forEach { (value, texts) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = value == selected, role = Role.RadioButton) { onSelect(value) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = value == selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(texts.first, style = MaterialTheme.typography.bodyLarge)
                        Text(texts.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickPicks(values: List<Int>, onPick: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        values.forEachIndexed { i, v ->
            SegmentedButton(
                selected = false,
                onClick = { onPick(v) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = values.size),
            ) { Text(if (v >= 60) "${v / 60}m" else "${v}s") }
        }
    }
}

@Composable
private fun PathCard(path: String, isFile: Boolean, onBrowse: () -> Unit) {
    val exists = path.isNotBlank() && java.io.File(path).exists()
    Card(
        onClick = onBrowse,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (isFile) Icons.Rounded.Description else Icons.Rounded.Folder, if (isFile) MaterialTheme.colorScheme.primary else Warn)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (path.isBlank()) {
                    Text(if (isFile) "No file chosen" else "No folder chosen", style = MaterialTheme.typography.bodyLarge)
                    Text("Tap to browse Device storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(java.io.File(path).name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(FileOps.pretty(java.io.File(path).parent ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!exists) Text("Not found right now (or All files access is off)", style = MaterialTheme.typography.labelSmall, color = Warn)
                }
            }
            FilledTonalButton(onClick = onBrowse) { Text("Browse") }
        }
    }
}

@Composable
private fun AppCard(pkg: String, name: String, emptyText: String = "Choose an app", onPick: () -> Unit) {
    Card(
        onClick = onPick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pkg.isBlank()) IconBadge(Icons.Rounded.Apps, MaterialTheme.colorScheme.onSurfaceVariant) else AppIcon(pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (pkg.isBlank()) emptyText else name.ifBlank { pkg }, style = MaterialTheme.typography.bodyLarge)
                if (pkg.isNotBlank()) Text(pkg, style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onPick) { Text(if (pkg.isBlank()) "Choose" else "Change") }
        }
    }
}

@Composable
private fun ElementCard(sel: Selector, onPick: () -> Unit, onClear: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.TouchApp, MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (sel.isEmpty) {
                        Text("Nothing picked yet", style = MaterialTheme.typography.bodyLarge)
                        Text("Capture the app's screen and tap the button you want", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("“${sel.label.ifBlank { "Saved position" }}”", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        val how = buildList {
                            if (sel.id.isNotBlank()) add("id ${sel.id.substringAfter(":id/")}")
                            if (sel.text.isNotBlank()) add("text")
                            if (sel.desc.isNotBlank()) add("description")
                            if (!sel.hasKeys && sel.hasPoint) add("screen position only")
                        }
                        Text("Recognised by " + how.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick) {
                    Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (sel.isEmpty) "Pick on screen" else "Pick again")
                }
                if (!sel.isEmpty) TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun ManualSelector(step: Step, onChange: (Step) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable { open = !open }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Advanced: edit how it's recognised", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        Field(step, Selector.TEXT, "Exact text", onChange)
        Field(step, Selector.DESC, "Content description", onChange)
        Field(step, Selector.ID, "View id (e.g. ru.iiec.pydroid3:id/fab)", onChange)
        Field(step, Selector.LABEL, "Display name", onChange)
        Hint("Only filled fields are used. If all three are empty, the saved screen position is tapped.")
    }
}

@Composable
private fun PreviewFiles(step: Step) {
    val folder = step.s("folder")
    if (folder.isBlank()) return
    val mode = step.s("mode", "run")
    val files = remember(step) {
        val f = java.io.File(folder)
        val exts = FileOps.parseExts(step.s("ext"))
        when (mode) {
            "newestDir" -> FileOps.newestSubdir(f)?.let { FileOps.listAll(it, exts, false) } ?: emptyList()
            "latest" -> listOfNotNull(FileOps.newestFile(f, exts, step.b("recursive", true)))
            else -> FileOps.listAll(f, exts, step.b("recursive", true)).sortedByDescending { it.lastModified() }.take(8)
        }
    }
    SectionLabel(if (mode == "run") "Right now this folder has" else "Would select now")
    if (files.isEmpty()) {
        Text("No matching files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        files.take(8).forEach { f ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (mode == "run") Text("During a run, only files created after the run starts are picked.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
