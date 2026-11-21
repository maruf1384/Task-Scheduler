package com.taskpilot.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SwipeDown
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.taskpilot.app.core.Conditions
import com.taskpilot.app.core.FlowMachine
import com.taskpilot.app.core.OnFail
import com.taskpilot.app.core.RepeatMode
import com.taskpilot.app.data.EditParams
import com.taskpilot.app.data.Phone
import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import com.taskpilot.app.engine.FileOps
import java.io.File

enum class Category(val title: String, val blurb: String, val icon: ImageVector, val tint: Color) {
    FILES("Files & folders", "Find, wait for and pick files", Icons.Rounded.Folder, Color(0xFFF59E0B)),
    APPS("Apps", "Open apps or open a file inside an app", Icons.Rounded.Apps, Color(0xFF6366F1)),
    SCREEN("On-screen actions", "Tap buttons, wait for things, type, press keys", Icons.Rounded.TouchApp, Color(0xFF0EA5E9)),
    SEND("Send & share", "WhatsApp or any app that accepts files", Icons.Rounded.Forum, Color(0xFF22C55E)),
    DEVICE("Device", "Wake, wait, notify, lock", Icons.Rounded.Tune, Color(0xFFEC4899)),
    LOGIC("If, repeat & stop", "Decide what happens next from the screen, files or time", Icons.Rounded.AltRoute, Color(0xFF8B5CF6)),
}

data class StepInfo(
    val type: StepType,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val category: Category,
    /** False for “Otherwise” and “End”: they are added from the If / Repeat they belong to. */
    val inSheet: Boolean = true,
)

object StepCatalog {
    val all: List<StepInfo> = listOf(
        StepInfo(StepType.SELECT_FILES, "Select files", "Go to a folder and pick files (all, newest, or the ones made during this run) to send next.", Icons.Rounded.LibraryAddCheck, Category.FILES),
        StepInfo(StepType.EDIT_FILE, "Edit a file", "Change settings in your code before it runs — e.g. set START/END dates to last week automatically.", Icons.Rounded.EditNote, Category.FILES),
        StepInfo(StepType.WAIT_NEW_FILES, "Wait for new files", "Wait until new files show up in a folder (e.g. a script's output) and stop changing.", Icons.Rounded.FolderOpen, Category.FILES),

        StepInfo(StepType.OPEN_FILE, "Open file in app", "Open a file with a chosen app — e.g. your .py script in Pydroid 3.", Icons.Rounded.Description, Category.APPS),
        StepInfo(StepType.OPEN_URL, "Open a web page", "Open a link in Chrome or any browser — e.g. an ERP or D-Nothi page.", Icons.Rounded.Language, Category.APPS),
        StepInfo(StepType.LAUNCH_APP, "Open app", "Bring any installed app to the front.", Icons.Rounded.OpenInNew, Category.APPS),

        StepInfo(StepType.TAP_ELEMENT, "Tap a button", "Tap something on screen — Run, Undo, Redo, Send… You pick it from a live capture.", Icons.Rounded.TouchApp, Category.SCREEN),
        StepInfo(StepType.WAIT_ELEMENT, "Wait for something on screen", "Pause until a text or button appears (or disappears).", Icons.Rounded.Visibility, Category.SCREEN),
        StepInfo(StepType.SCROLL, "Scroll", "Scroll a list or page up or down (e.g. to reach an item further down).", Icons.Rounded.SwipeDown, Category.SCREEN),
        StepInfo(StepType.INPUT_TEXT, "Type text", "Put text into a text box.", Icons.Rounded.Keyboard, Category.SCREEN),
        StepInfo(StepType.SYSTEM_KEY, "Press Back / Home / Recents", "Press one of the phone's navigation keys.", Icons.Rounded.Smartphone, Category.SCREEN),

        StepInfo(StepType.SEND_WHATSAPP, "Send to WhatsApp number", "Send the selected files straight to a WhatsApp chat and press Send.", Icons.Rounded.Send, Category.SEND),
        StepInfo(StepType.SEND_EMAIL, "Send an email", "Gmail (or another mail app): to, subject, message and the selected files — sent automatically.", Icons.Rounded.Email, Category.SEND),
        StepInfo(StepType.SHARE_FILES, "Share files to an app", "Hand the selected files to Gmail, Drive, Telegram or any app.", Icons.Rounded.Share, Category.SEND),

        StepInfo(StepType.WAKE_UNLOCK, "Wake & unlock", "Turn the screen on and get past the lock screen.", Icons.Rounded.LockOpen, Category.DEVICE),
        StepInfo(StepType.DELAY, "Wait", "Pause for a few seconds.", Icons.Rounded.Timer, Category.DEVICE),
        StepInfo(StepType.NOTIFY, "Show a notification", "Leave yourself a note when something is done.", Icons.Rounded.Notifications, Category.DEVICE),
        StepInfo(StepType.LOCK_SCREEN, "Lock screen", "Turn the screen off again at the end.", Icons.Rounded.Lock, Category.DEVICE),

        StepInfo(StepType.IF, "If", "Run some steps only when something is true — text on screen, a file exists, a day or time, the battery… With “Otherwise” for when it isn't.", Icons.Rounded.AltRoute, Category.LOGIC),
        StepInfo(StepType.REPEAT, "Repeat", "Run some steps again: a number of times, once for each file, or until something is true.", Icons.Rounded.Repeat, Category.LOGIC),
        StepInfo(StepType.STOP, "Stop the automation", "Finish here — as done or as failed, with your own message.", Icons.Rounded.StopCircle, Category.LOGIC),
        StepInfo(StepType.ELSE, "Otherwise", "The steps for when the If isn't true.", Icons.Rounded.AltRoute, Category.LOGIC, inSheet = false),
        StepInfo(StepType.END, "End", "Closes an If or a Repeat.", Icons.Rounded.UnfoldLess, Category.LOGIC, inSheet = false),
    )

    /** If / Otherwise / End / Repeat / Stop: they steer the run rather than do something. */
    fun isLogic(t: StepType): Boolean = info(t).category == Category.LOGIC

    /** “Otherwise” and “End”: markers that only exist as part of an If / Repeat. */
    fun isMarker(t: StepType): Boolean = t == StepType.ELSE || t == StepType.END

    /** Steps that open a block and get an End added under them. */
    fun opensBlock(t: StepType): Boolean = t == StepType.IF || t == StepType.REPEAT

    fun info(t: StepType): StepInfo = all.first { it.type == t }

    fun titleOf(step: Step): String = step.label.ifBlank { info(step.type).title }

    fun defaults(t: StepType): Step = when (t) {
        StepType.DELAY -> Step(type = t, p = mapOf("seconds" to "3"))
        StepType.TAP_ELEMENT -> Step(type = t, p = mapOf("timeout" to "20", "coords" to "true"))
        StepType.WAIT_ELEMENT -> Step(type = t, p = mapOf("timeout" to "120", "mode" to "appear"))
        StepType.WAIT_NEW_FILES -> Step(type = t, p = mapOf("timeout" to "600", "settle" to "6", "recursive" to "true"))
        StepType.SELECT_FILES -> Step(type = t, p = mapOf("mode" to "run", "recursive" to "true"))
        StepType.SEND_WHATSAPP -> Step(type = t, p = mapOf("app" to "com.whatsapp", "autoSend" to "true"))
        StepType.OPEN_FILE -> Step(type = t, p = mapOf("uriMode" to "file"))
        StepType.SYSTEM_KEY -> Step(type = t, p = mapOf("key" to "HOME"))
        StepType.NOTIFY -> Step(type = t, p = mapOf("title" to "TaskPilot", "text" to "Done at {time}"))
        StepType.WAKE_UNLOCK -> Step(type = t, p = mapOf("strict" to "true"))
        StepType.EDIT_FILE -> Step(type = t, p = mapOf("save" to "copy", "weekStart" to "7", "edits" to "[]"))
        StepType.SCROLL -> Step(type = t, p = mapOf("dir" to "down", "times" to "1", "timeout" to "15"))
        StepType.OPEN_URL -> Step(type = t, p = mapOf("url" to "https://"))
        StepType.SEND_EMAIL -> Step(type = t, p = mapOf("app" to "com.google.android.gm", "appName" to "Gmail", "attach" to "true", "autoSend" to "true"))
        StepType.IF -> Step(type = t, p = mapOf("conds" to "[]", "match" to "all", "waitUpTo" to "0"))
        StepType.REPEAT -> Step(type = t, p = mapOf("mode" to "times", "count" to "3", "max" to "20", "conds" to "[]", "match" to "all"))
        StepType.STOP -> Step(type = t, p = mapOf("result" to "ok"))
        else -> Step(type = t)
    }

    private fun name(path: String) = File(path).name

    /** One line under the step's title in the list. */
    fun summary(step: Step): String = baseSummary(step) + failNote(step)

    /** " · tries 3×" when the step is set to do something other than stop on failure. */
    fun failNote(step: Step): String = if (isLogic(step.type)) "" else when (OnFail.of(step)) {
        OnFail.STOP -> ""
        OnFail.RETRY -> " · up to ${step.i("retries", 2) + 1} tries"
        OnFail.CONTINUE -> " · carries on if it fails"
    }

    private fun baseSummary(step: Step): String = when (step.type) {
        StepType.IF -> Conditions.summary(step) +
            (step.i("waitUpTo", 0).takeIf { it > 0 }?.let { " · waits up to ${fmtSecs(it)}" } ?: "")
        StepType.ELSE -> "When the If above isn't true"
        StepType.END -> ""
        StepType.REPEAT -> when (RepeatMode.of(step)) {
            RepeatMode.TIMES -> "${step.i("count", 3)} times"
            RepeatMode.EACH_FILE -> "Once for each selected file"
            RepeatMode.UNTIL -> "Until ${Conditions.summary(step)} · at most ${step.i("max", 20)} rounds"
            RepeatMode.WHILE -> "While ${Conditions.summary(step)} · at most ${step.i("max", 20)} rounds"
        }
        StepType.STOP -> if (step.s("result") == "fail") "Stop as failed" + (step.s("message").takeIf { it.isNotBlank() }?.let { " · “${it.take(40)}”" } ?: "")
            else "Finish here as done" + (step.s("message").takeIf { it.isNotBlank() }?.let { " · “${it.take(40)}”" } ?: "")
        StepType.WAKE_UNLOCK -> if (step.b("usePin", false)) "Screen on · unlock with your saved PIN" else "Screen on · open lock screen"
        StepType.LAUNCH_APP -> step.s("appName").ifBlank { "Choose an app" }
        StepType.OPEN_FILE -> if (step.s("path").isBlank()) "Choose a file" else "${name(step.s("path"))} → ${step.s("appName").ifBlank { "default app" }}"
        StepType.TAP_ELEMENT -> {
            val sel = Selector.from(step)
            if (sel.isEmpty) "Not picked yet" else "“${sel.label.ifBlank { "position" }}” in ${step.s("appName").ifBlank { sel.pkg.substringAfterLast('.') }}"
        }
        StepType.WAIT_ELEMENT -> {
            val sel = Selector.from(step)
            val how = if (step.s("mode") == "disappear") "disappears" else "appears"
            if (sel.isEmpty) "Not picked yet" else "Until “${sel.label}” $how · max ${step.i("timeout", 120)}s"
        }
        StepType.INPUT_TEXT -> "“${step.s("text").take(30)}”"
        StepType.SYSTEM_KEY -> when (step.s("key")) {
            "BACK" -> "Back"; "HOME" -> "Home"; "RECENTS" -> "Recent apps"; "NOTIFICATIONS" -> "Open notifications"; else -> step.s("key")
        }
        StepType.DELAY -> "${step.i("seconds", 3)} seconds"
        StepType.WAIT_NEW_FILES -> if (step.s("folder").isBlank()) "Choose a folder" else
            "In ${name(step.s("folder"))}${extNote(step)} · max ${fmtSecs(step.i("timeout", 600))}"
        StepType.SELECT_FILES -> if (step.s("folder").isBlank()) "Choose a folder" else {
            val m = when (step.s("mode", "run")) { "newestDir" -> "Newest sub-folder"; "all" -> "All files"; "latest" -> "Newest file"; else -> "Files made in this run" }
            "$m · ${name(step.s("folder"))}${extNote(step)}"
        }
        StepType.SEND_WHATSAPP -> {
            val n = Phone.toInternational(step.s("phone"))
            if (n == null) "Enter a number" else "+$n · ${if (step.b("autoSend", true)) "auto-send" else "you press Send"}"
        }
        StepType.SHARE_FILES -> step.s("appName").ifBlank { "Ask each time" }
        StepType.NOTIFY -> step.s("title").ifBlank { "Notification" }
        StepType.LOCK_SCREEN -> "Screen off"
        StepType.SCROLL -> {
            val sel = Selector.from(step)
            "${if (step.s("dir") == "up") "Up" else "Down"} ×${step.i("times", 1)}" + if (sel.isEmpty) " · middle of the screen" else " · “${sel.label.ifBlank { "list" }}”"
        }
        StepType.OPEN_URL -> step.s("url").removePrefix("https://").removePrefix("http://").ifBlank { "Enter a link" } +
            (step.s("appName").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        StepType.SEND_EMAIL -> step.s("to").ifBlank { "Enter who to send to" } +
            (step.s("subject").takeIf { it.isNotBlank() }?.let { " · “${it.take(30)}”" } ?: "") +
            (if (step.b("attach", true)) " · with files" else "")
        StepType.EDIT_FILE -> {
            val e = EditParams.edits(step)
            when {
                step.s("path").isBlank() -> "Choose a file"
                e.isEmpty() -> "${name(step.s("path"))} · no changes yet"
                else -> "${name(step.s("path"))} · " + e.joinToString(", ") { if (it.kind == "replace") "find & replace" else it.name }
            }
        }
    }

    private fun extNote(step: Step): String {
        val e = FileOps.parseExts(step.s("ext"))
        return if (e.isEmpty()) "" else " · " + e.joinToString(", ") { ".$it" }
    }

    fun fmtSecs(s: Int): String = when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 60 -> "${s / 60} min" + if (s % 60 != 0) " ${s % 60}s" else ""
        else -> "${s}s"
    }

    /** What still needs filling in before the step can run. */
    fun issues(step: Step): List<String> {
        val out = ArrayList<String>()
        when (step.type) {
            StepType.LAUNCH_APP -> if (step.s("pkg").isBlank()) out += "Choose an app"
            StepType.OPEN_FILE -> if (step.s("path").isBlank()) out += "Choose a file"
            StepType.TAP_ELEMENT -> if (Selector.from(step).isEmpty) out += "Pick the button on screen"
            StepType.WAIT_ELEMENT -> if (!Selector.from(step).hasKeys) out += "Pick what to wait for"
            StepType.INPUT_TEXT -> if (step.s("text").isEmpty()) out += "Enter the text"
            StepType.WAIT_NEW_FILES, StepType.SELECT_FILES -> if (step.s("folder").isBlank()) out += "Choose a folder"
            StepType.SEND_WHATSAPP -> if (Phone.toInternational(step.s("phone")) == null) out += "Enter the phone number"
            StepType.OPEN_URL -> if (!step.s("url").matches(Regex("(?i)^[a-z][a-z0-9+.-]*://.+"))) out += "Enter a full link (https://…)"
            StepType.SEND_EMAIL -> if (step.s("to").isBlank()) out += "Enter who to send to"
            StepType.EDIT_FILE -> {
                if (step.s("path").isBlank()) out += "Choose a file"
                else if (EditParams.edits(step).isEmpty()) out += "Add a change"
            }
            StepType.IF -> out += Conditions.problems(step)
            StepType.REPEAT -> when (RepeatMode.of(step)) {
                RepeatMode.TIMES -> if (step.p["count"]?.trim()?.toIntOrNull()?.takeIf { it in 0..FlowMachine.MAX_ROUNDS } == null) out += "Enter how many times (0–${FlowMachine.MAX_ROUNDS})"
                RepeatMode.EACH_FILE -> {}
                RepeatMode.UNTIL, RepeatMode.WHILE -> out += Conditions.problems(step)
            }
            else -> {}
        }
        return out
    }
}
