package com.taskpilot.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
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
    DEVICE("Device & flow", "Wake, wait, notify, lock", Icons.Rounded.Tune, Color(0xFFEC4899)),
}

data class StepInfo(
    val type: StepType,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val category: Category,
)

object StepCatalog {
    val all: List<StepInfo> = listOf(
        StepInfo(StepType.SELECT_FILES, "Select files", "Go to a folder and pick files (all, newest, or the ones made during this run) to send next.", Icons.Rounded.LibraryAddCheck, Category.FILES),
        StepInfo(StepType.EDIT_FILE, "Edit a file", "Change settings in your code before it runs — e.g. set START/END dates to last week automatically.", Icons.Rounded.EditNote, Category.FILES),
        StepInfo(StepType.WAIT_NEW_FILES, "Wait for new files", "Wait until new files show up in a folder (e.g. a script's output) and stop changing.", Icons.Rounded.FolderOpen, Category.FILES),

        StepInfo(StepType.OPEN_FILE, "Open file in app", "Open a file with a chosen app — e.g. your .py script in Pydroid 3.", Icons.Rounded.Description, Category.APPS),
        StepInfo(StepType.LAUNCH_APP, "Open app", "Bring any installed app to the front.", Icons.Rounded.OpenInNew, Category.APPS),

        StepInfo(StepType.TAP_ELEMENT, "Tap a button", "Tap something on screen — Run, Undo, Redo, Send… You pick it from a live capture.", Icons.Rounded.TouchApp, Category.SCREEN),
        StepInfo(StepType.WAIT_ELEMENT, "Wait for something on screen", "Pause until a text or button appears (or disappears).", Icons.Rounded.Visibility, Category.SCREEN),
        StepInfo(StepType.INPUT_TEXT, "Type text", "Put text into a text box.", Icons.Rounded.Keyboard, Category.SCREEN),
        StepInfo(StepType.SYSTEM_KEY, "Press Back / Home / Recents", "Press one of the phone's navigation keys.", Icons.Rounded.Smartphone, Category.SCREEN),

        StepInfo(StepType.SEND_WHATSAPP, "Send to WhatsApp number", "Send the selected files straight to a WhatsApp chat and press Send.", Icons.Rounded.Send, Category.SEND),
        StepInfo(StepType.SHARE_FILES, "Share files to an app", "Hand the selected files to Gmail, Drive, Telegram or any app.", Icons.Rounded.Share, Category.SEND),

        StepInfo(StepType.WAKE_UNLOCK, "Wake & unlock", "Turn the screen on and get past the lock screen.", Icons.Rounded.LockOpen, Category.DEVICE),
        StepInfo(StepType.DELAY, "Wait", "Pause for a few seconds.", Icons.Rounded.Timer, Category.DEVICE),
        StepInfo(StepType.NOTIFY, "Show a notification", "Leave yourself a note when something is done.", Icons.Rounded.Notifications, Category.DEVICE),
        StepInfo(StepType.LOCK_SCREEN, "Lock screen", "Turn the screen off again at the end.", Icons.Rounded.Lock, Category.DEVICE),
    )

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
        else -> Step(type = t)
    }

    private fun name(path: String) = File(path).name

    fun summary(step: Step): String = when (step.type) {
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
            StepType.EDIT_FILE -> {
                if (step.s("path").isBlank()) out += "Choose a file"
                else if (EditParams.edits(step).isEmpty()) out += "Add a change"
            }
            else -> {}
        }
        return out
    }
}
