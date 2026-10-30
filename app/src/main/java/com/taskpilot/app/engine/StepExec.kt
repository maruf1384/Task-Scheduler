package com.taskpilot.app.engine

import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.taskpilot.app.data.EditParams
import com.taskpilot.app.data.Phone
import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import com.taskpilot.app.ui.WakeActivity
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime
import java.util.Arrays
import java.time.format.DateTimeFormatter

/** What each kind of step actually does. */
object StepExec {
    private const val PYDROID = "ru.iiec.pydroid3"

    private fun ok(msg: String = "") = Outcome(true, msg)
    private fun fail(msg: String) = Outcome(false, msg)

    fun expand(s: String, rc: RunContext): String {
        val now = LocalDateTime.now()
        return CodeEdit.expandDates(s, now, java.time.DayOfWeek.SUNDAY).replace("{date}", now.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
            .replace("{time}", now.format(DateTimeFormatter.ofPattern("h:mm a")))
            .replace("{count}", rc.files.size.toString())
            .replace("{files}", rc.files.joinToString(", ") { it.name })
    }

    private fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    suspend fun run(ctx: Context, svc: AutomationService, step: Step, rc: RunContext): Outcome = when (step.type) {
        StepType.WAKE_UNLOCK -> wakeUnlock(ctx, svc, step)
        StepType.LAUNCH_APP -> launchApp(ctx, svc, step, rc)
        StepType.OPEN_FILE -> openFile(ctx, svc, step, rc)
        StepType.TAP_ELEMENT -> tapElement(svc, step)
        StepType.EDIT_FILE -> editFile(ctx, step, rc)
        StepType.SCROLL -> scroll(svc, step)
        StepType.OPEN_URL -> openUrl(ctx, svc, step, rc)
        StepType.SEND_EMAIL -> sendEmail(ctx, svc, step, rc)
        StepType.WAIT_ELEMENT -> waitElement(svc, step)
        StepType.INPUT_TEXT -> inputText(svc, step, rc)
        StepType.SYSTEM_KEY -> {
            val key = step.s("key", "BACK")
            if (svc.globalKey(key)) { delay(600); ok("Pressed ${key.lowercase()}") } else fail("Couldn't press $key")
        }
        StepType.DELAY -> {
            val s = step.i("seconds", 3).coerceIn(0, 3600)
            delay(s * 1000L)
            ok("Waited ${s}s")
        }
        StepType.WAIT_NEW_FILES -> waitNewFiles(svc, step, rc)
        StepType.SELECT_FILES -> selectFiles(step, rc)
        StepType.SEND_WHATSAPP -> sendWhatsApp(ctx, svc, step, rc)
        StepType.SHARE_FILES -> shareFiles(ctx, svc, step, rc)
        StepType.NOTIFY -> {
            Notifier.post(ctx, Notifier.CH_RUNS, expand(step.s("title", "TaskPilot"), rc), expand(step.s("text"), rc))
            ok("Notification shown")
        }
        StepType.LOCK_SCREEN -> if (svc.lockScreen()) ok("Screen locked") else fail("Locking needs Android 9 or newer")
    }

    // -------------------------------------------------------------------------------------

    private suspend fun wakeUnlock(ctx: Context, svc: AutomationService, step: Step): Outcome {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            svc.startActivity(Intent(ctx, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            delay(700)
            return ok("Screen on (already unlocked)")
        }
        svc.startActivity(Intent(ctx, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        delay(1600)
        if (!km.isKeyguardLocked) return ok("Screen on and unlocked")
        if (step.b("usePin", false)) {
            val pin = SecretStore.readPin(ctx)
                ?: return fail("No PIN is saved on this phone — open this step and tap “Save PIN”.")
            try {
                if (!svc.enterPin(pin)) return fail("Couldn't find the PIN keypad on the lock screen")
            } finally {
                Arrays.fill(pin, '\u0000')
            }
            for (k in 0 until 10) {
                delay(400)
                if (!km.isKeyguardLocked) return ok("Unlocked with your saved PIN")
            }
            return fail("The saved PIN didn't unlock the phone — save it again in this step")
        }
        return if (step.b("strict", true)) {
            fail("Phone is locked with a PIN or pattern. Save your PIN in this step, or switch the screen lock to Swipe/None.")
        } else ok("Screen on (still locked)")
    }

    private suspend fun launchApp(ctx: Context, svc: AutomationService, step: Step, rc: RunContext): Outcome {
        val pkg = step.s("pkg")
        if (pkg.isBlank()) return fail("Choose an app")
        rc.lastPkg = pkg
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return fail("${step.s("appName", pkg)} isn't installed")
        svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return if (svc.waitForPackage(pkg, 15_000)) { delay(800); ok("Opened ${step.s("appName", pkg)}") }
        else fail("${step.s("appName", pkg)} didn't come to the front")
    }

    private fun viewIntent(uri: Uri, mime: String, pkg: String): Intent {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        if (pkg.isNotBlank()) i.setPackage(pkg)
        return i
    }

    private suspend fun openFile(ctx: Context, svc: AutomationService, step: Step, rc: RunContext): Outcome {
        if (step.s("path").isBlank()) return fail("Choose a file")
        val chosenFile = File(step.s("path"))
        // An earlier "Edit a file" step made a fresh copy of this file → open that copy instead.
        val useCopy = rc.editedFrom == chosenFile.absolutePath && rc.editedFile != null
        val f = if (useCopy) rc.editedFile!! else chosenFile
        if (!f.exists()) return fail("File not found: ${FileOps.pretty(f.absolutePath)}")
        val pkg = step.s("pkg")
        val app = step.s("appName", pkg.ifBlank { "the app" })
        if (pkg.isNotBlank() && !installed(ctx, pkg)) return fail("$app isn't installed")
        if (pkg.isNotBlank()) rc.lastPkg = pkg
        // Try the chosen link type first, then automatically fall back to the other one:
        // some apps (e.g. Pydroid 3 builds) accept only a real file path, others only a content link.
        val chosen = step.s("uriMode", "file")
        val modes = if (chosen == "file") listOf("file", "content") else listOf("content", "file")
        val mimes = listOf(FileOps.mime(f), "text/plain", "*/*").distinct()
        var working: Intent? = null
        outer@ for (mode in modes) {
            val uri: Uri = try {
                if (mode == "file") Uri.fromFile(f) else FileOps.uriFor(ctx, f)
            } catch (_: Exception) {
                continue@outer
            }
            for (m in mimes) {
                val i = viewIntent(uri, m, pkg)
                try {
                    svc.startActivity(i)
                    working = i
                    break@outer
                } catch (_: Exception) {
                    // ActivityNotFoundException, SecurityException, FileUriExposedException… try the next mime/mode.
                }
            }
        }
        if (working == null) return fail(
            "$app wouldn't open .${f.extension} from another app. Open $app yourself and load the file there, " +
                "or use an “Open app” step followed by on-screen taps.",
        )
        if (pkg.isNotBlank() && !svc.waitForPackage(pkg, 20_000)) return fail("$app didn't open")
        val label = if (useCopy) "the edited copy of ${chosenFile.name}" else f.name
        if (pkg.isBlank() || !step.b("verify", true)) {
            delay(1200)
            return ok("Opened $label in $app")
        }
        // A cold-starting app (e.g. Pydroid after the phone closed it overnight) can swallow the
        // first request and just show its last screen. Check the file is really there; if not, ask again.
        if (svc.waitForFileName(pkg, f.name, 10_000)) {
            delay(800)
            return ok("Opened $label in $app — checked it's on screen")
        }
        try { svc.startActivity(working) } catch (_: Exception) {}
        svc.waitForPackage(pkg, 10_000)
        if (svc.waitForFileName(pkg, f.name, 12_000)) {
            delay(800)
            return ok("Opened $label in $app on the 2nd try — checked it's on screen")
        }
        delay(800)
        return ok("Opened $label in $app (couldn't see the file name on screen to double-check)")
    }

    private suspend fun tapElement(svc: AutomationService, step: Step): Outcome {
        val sel = Selector.from(step)
        if (sel.isEmpty) return fail("No button chosen yet — open this step and tap “Pick on screen”")
        val timeout = step.i("timeout", 20).coerceAtLeast(1) * 1000L
        val long = step.b("long", false)
        val name = sel.label.ifBlank { "the element" }
        if (sel.pkg.isNotBlank()) svc.waitForPackage(sel.pkg, minOf(timeout, 15_000L))
        if (sel.hasKeys) {
            val n = svc.waitFor(sel, timeout)
            if (n != null) {
                val done = svc.clickNode(n, long) || svc.tapNode(n, long)
                if (!done) return fail("Found “$name” but couldn't tap it")
                if (!step.b("verify", sel.pkg == PYDROID)) {
                    delay(600)
                    return ok("Tapped “$name”")
                }
                // Make sure the tap did something: the button should leave the screen (e.g. Pydroid
                // switches to its terminal after Run). If it's still there, tap exactly once more.
                if (svc.waitGone(sel, 6000)) return ok("Tapped “$name” — the screen changed")
                val again = svc.find(sel)
                if (again != null && (svc.clickNode(again, long) || svc.tapNode(again, long)) && svc.waitGone(sel, 6000)) {
                    return ok("Tapped “$name” (needed a 2nd tap) — the screen changed")
                }
                return fail("Tapped “$name” but nothing happened — it's still on screen")
            }
            if (!step.b("coords", true) || !sel.hasPoint) return fail("Couldn't find “$name” within ${timeout / 1000}s")
        } else {
            delay(minOf(timeout, 2500L))
        }
        val (x, y) = svc.scaledPoint(sel)
        return if (svc.tap(x, y, long)) { delay(600); ok("Tapped the saved position of “$name”") }
        else fail("The tap was blocked")
    }

    private suspend fun waitElement(svc: AutomationService, step: Step): Outcome {
        val sel = Selector.from(step)
        if (!sel.hasKeys) return fail("Pick an element that has a text, description or id")
        val timeout = step.i("timeout", 120).coerceAtLeast(1) * 1000L
        val disappear = step.s("mode", "appear") == "disappear"
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            val present = svc.find(sel) != null
            if (present != disappear) return ok(if (disappear) "“${sel.label}” is gone" else "“${sel.label}” appeared")
            delay(700)
        }
        return fail("Waited ${timeout / 1000}s — “${sel.label}” ${if (disappear) "is still there" else "never appeared"}")
    }

    private suspend fun inputText(svc: AutomationService, step: Step, rc: RunContext): Outcome {
        val sel = Selector.from(step)
        val node = if (sel.hasKeys) svc.waitFor(sel, step.i("timeout", 15) * 1000L) else svc.focusedInput()
        node ?: return fail("No text box found")
        return if (svc.setText(node, expand(step.s("text"), rc))) { delay(300); ok("Typed text") }
        else fail("That box doesn't accept text")
    }

    private val ERROR_LINE = Regex("""^\s*[A-Za-z_.]*(Error|Exception|Interrupt|Exit)\b.*:.*""")

    private fun fmtLeft(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d left".format(s / 60, s % 60)
    }

    private suspend fun waitNewFiles(svc: AutomationService, step: Step, rc: RunContext): Outcome {
        val folder = File(step.s("folder"))
        if (!folder.isDirectory) return fail("Folder not found: ${FileOps.pretty(folder.absolutePath)}")
        val exts = FileOps.parseExts(step.s("ext"))
        val timeout = step.i("timeout", 600).coerceAtLeast(5) * 1000L
        val settle = step.i("settle", 6).coerceAtLeast(1) * 1000L
        val watchPkg = step.s("watchPkg").ifBlank { rc.lastPkg }
        val stopEarly = step.b("stopEarly", true) && watchPkg.isNotBlank()
        val since = rc.startMs - 2000
        val end = System.currentTimeMillis() + timeout
        var lastSig = ""
        var stableSince = 0L
        var lastPill = 0L
        var lastWatch = 0L
        var endedAt = 0L               // when the app showed the script had finished / crashed
        var endedHow = ""
        var errorLine = ""
        while (System.currentTimeMillis() < end) {
            val now = System.currentTimeMillis()
            val found = FileOps.scanNew(folder, since, exts, step.b("recursive", true))
            if (found.isNotEmpty()) {
                val sig = found.joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }
                if (sig != lastSig) {
                    lastSig = sig
                    stableSince = now
                } else if (now - stableSince >= settle) {
                    rc.files = found
                    return ok("${found.size} new file(s): ${found.joinToString(", ") { it.name }}")
                }
            } else if (stopEarly && now - lastWatch >= 3000) {
                lastWatch = now
                // Pydroid prints "[Program finished]" when a script ends, and a Traceback when it crashes.
                val lines = svc.screenTexts(watchPkg).flatMap { it.split('\n') }.map { it.trim() }
                val crashed = lines.any { it.startsWith("Traceback (most recent call last)") }
                val finished = lines.any { it.contains("[Program finished]") }
                if ((crashed || finished) && endedAt == 0L) {
                    endedAt = now
                    endedHow = if (crashed) "crashed" else "finished"
                    errorLine = lines.lastOrNull { ERROR_LINE.matches(it) }?.take(220) ?: ""
                }
                // Give a just-finished script 12 s to flush its files before giving up.
                if (endedAt != 0L && now - endedAt > 12_000) {
                    val why = if (errorLine.isNotBlank()) " Error: $errorLine" else ""
                    return fail("The script $endedHow without making new files in ${folder.name}.$why")
                }
            }
            if (now - lastPill >= 5000 && rc.pillPrefix.isNotBlank()) {
                lastPill = now
                svc.updateRunPill("${rc.pillPrefix} · ${fmtLeft(end - now)}")
            }
            delay(1500)
        }
        return fail("No new files appeared within ${timeout / 60_000} min ${(timeout / 1000) % 60} s")
    }

    // ------------------------------------------------------------------ Edit a file

    private fun editFile(ctx: Context, step: Step, rc: RunContext): Outcome {
        val path = step.s("path")
        if (path.isBlank()) return fail("Choose a file")
        val orig = File(path)
        if (!orig.isFile) return fail("File not found: ${FileOps.pretty(orig.absolutePath)}")
        if (orig.length() > 5_000_000) return fail("${orig.name} is too big to edit (over 5 MB)")
        val edits = EditParams.edits(step)
        if (edits.isEmpty()) return fail("No changes set up in this step")
        val text = try { orig.readText(Charsets.UTF_8) } catch (e: Exception) { return fail("Couldn't read ${orig.name}: ${e.message}") }
        val now = LocalDateTime.now()
        val r = CodeEdit.apply(text, edits, now, EditParams.weekStart(step))
        // Any problem = stop. Running the script with half-changed settings could send a wrong report.
        if (r.problems.isNotEmpty()) return fail(r.problems.joinToString("; "))
        return try {
            if (step.s("save", "copy") == "copy") {
                val copy = CodeEdit.copyFor(orig, now)
                CodeEdit.writeAtomic(copy, r.text)
                CodeEdit.cleanCopies(orig, keep = 3)
                rc.editedFrom = orig.absolutePath
                rc.editedFile = copy
                ok(r.changes.joinToString("; ") + " · saved as a fresh copy (${copy.name}); the original is unchanged")
            } else {
                backup(ctx, orig, text)
                CodeEdit.writeAtomic(orig, r.text)
                rc.editedFrom = null
                rc.editedFile = null
                ok(r.changes.joinToString("; ") + " · saved into ${orig.name}")
            }
        } catch (e: Exception) {
            fail("Couldn't save the change: ${e.message}")
        }
    }

    /** Keeps the last 5 versions of a file that was edited in place, inside the app's own folder. */
    private fun backup(ctx: Context, orig: File, text: String) {
        val dir = File(ctx.filesDir, "edit-backups").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        File(dir, "${orig.name}.$stamp").writeText(text, Charsets.UTF_8)
        dir.listFiles()?.filter { it.name.startsWith(orig.name + ".") }?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
    }

    private fun selectFiles(step: Step, rc: RunContext): Outcome {
        val folder = File(step.s("folder"))
        if (!folder.isDirectory) return fail("Folder not found: ${FileOps.pretty(folder.absolutePath)}")
        val exts = FileOps.parseExts(step.s("ext"))
        val recursive = step.b("recursive", true)
        val files: List<File> = when (step.s("mode", "run")) {
            "newestDir" -> {
                val d = FileOps.newestSubdir(folder) ?: return fail("No sub-folders in ${folder.name}")
                FileOps.listAll(d, exts, recursive = false)
            }
            "all" -> FileOps.listAll(folder, exts, recursive)
            "latest" -> listOfNotNull(FileOps.newestFile(folder, exts, recursive))
            else -> FileOps.scanNew(folder, rc.startMs - 2000, exts, recursive)
        }
        if (files.isEmpty()) return fail("No matching files found")
        rc.files = files
        return ok("Selected ${files.size}: ${files.joinToString(", ") { it.name }}")
    }

    private fun shareIntent(ctx: Context, files: List<File>): Intent {
        val uris = ArrayList(files.map { FileOps.uriFor(ctx, it) })
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        i.type = FileOps.commonMime(files)
        val clip = ClipData.newRawUri("files", uris[0])
        for (k in 1 until uris.size) clip.addItem(ClipData.Item(uris[k]))
        i.clipData = clip
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    private suspend fun sendWhatsApp(ctx: Context, svc: AutomationService, step: Step, rc: RunContext): Outcome {
        if (rc.files.isEmpty()) return fail("No files chosen — add a “Select files” step before this one")
        val number = Phone.toInternational(step.s("phone")) ?: return fail("Enter the phone number")
        val pkg = step.s("app", "com.whatsapp")
        if (!installed(ctx, pkg)) return fail(if (pkg.endsWith("w4b")) "WhatsApp Business isn't installed" else "WhatsApp isn't installed")
        val i = shareIntent(ctx, rc.files).setPackage(pkg).putExtra("jid", "$number@s.whatsapp.net")
        val caption = expand(step.s("caption"), rc)
        if (caption.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, caption)
        try {
            svc.startActivity(i)
        } catch (e: Exception) {
            return fail("Couldn't open WhatsApp: ${e.message}")
        }
        if (!svc.waitForPackage(pkg, 15_000)) return fail("WhatsApp didn't open")
        delay(1500)
        if (!step.b("autoSend", true)) return ok("Chat opened with ${rc.files.size} file(s) — waiting for you to press Send")
        val names = rc.files.flatMap { listOf(it.name, it.nameWithoutExtension) }.distinct()
        val r = svc.sendAndConfirm(pkg, names, captionTyped = caption.isNotBlank())
        return when {
            r.seenInChat && r.clicks > 0 -> ok("Sent ${rc.files.size} file(s) to +$number — they're in the chat")
            r.seenInChat -> ok("Sent ${rc.files.size} file(s) to +$number — WhatsApp sent them, they're in the chat")
            r.clicks > 0 -> ok("Pressed Send ${r.clicks}× for ${rc.files.size} file(s) to +$number (couldn't see them in the chat to double-check)")
            else -> fail("No Send button found and the files didn't show up in the chat with +$number. Please check WhatsApp. On screen: ${r.screen}")
        }
    }

    private suspend fun shareFiles(ctx: Context, svc: AutomationService, step: Step, rc: RunContext): Outcome {
        if (rc.files.isEmpty()) return fail("No files chosen — add a “Select files” step before this one")
        val pkg = step.s("pkg")
        val i = shareIntent(ctx, rc.files)
        val caption = expand(step.s("caption"), rc)
        if (caption.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, caption)
        return try {
            if (pkg.isNotBlank()) {
                i.setPackage(pkg)
                svc.startActivity(i)
            } else {
                svc.startActivity(Intent.createChooser(i, "Share files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            delay(1500)
            ok("Shared ${rc.files.size} file(s)")
        } catch (e: Exception) {
            fail("Couldn't share: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ Scroll / web page / email

    private suspend fun scroll(svc: AutomationService, step: Step): Outcome {
        val sel = Selector.from(step)
        val down = step.s("dir", "down") != "up"
        val times = step.i("times", 1).coerceIn(1, 30)
        if (sel.pkg.isNotBlank()) svc.waitForPackage(sel.pkg, 10_000)
        val node = if (sel.hasKeys) svc.waitFor(sel, step.i("timeout", 15) * 1000L) else null
        if (sel.hasKeys && node == null && !sel.hasPoint) return fail("Couldn't find “${sel.label}” to scroll")
        var done = 0
        repeat(times) {
            if (svc.scrollNode(node, down)) done++
            delay(700)
        }
        return if (done > 0) ok("Scrolled ${if (down) "down" else "up"} ×$done") else fail("Couldn't scroll there")
    }

    private suspend fun openUrl(ctx: Context, svc: AutomationService, step: Step, rc: RunContext): Outcome {
        val url = expand(step.s("url"), rc).trim()
        if (!url.matches(Regex("(?i)^[a-z][a-z0-9+.-]*://.+"))) return fail("Enter a full link starting with https://")
        val pkg = step.s("pkg")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg.isNotBlank()) {
            if (!installed(ctx, pkg)) return fail("${step.s("appName", pkg)} isn't installed")
            i.setPackage(pkg)
            rc.lastPkg = pkg
        }
        return try {
            svc.startActivity(i)
            if (pkg.isNotBlank() && !svc.waitForPackage(pkg, 15_000)) return fail("${step.s("appName", pkg)} didn't open")
            delay(1500)
            ok("Opened $url")
        } catch (e: Exception) {
            fail("No app could open that link (${e.javaClass.simpleName})")
        }
    }

    private suspend fun sendEmail(ctx: Context, svc: AutomationService, step: Step, rc: RunContext): Outcome {
        fun list(key: String) = expand(step.s(key), rc).split(',', ';', ' ').map { it.trim() }.filter { it.contains('@') }.toTypedArray()
        val to = list("to")
        if (to.isEmpty()) return fail("Enter at least one email address")
        val pkg = step.s("app", "com.google.android.gm")
        if (pkg.isNotBlank() && !installed(ctx, pkg)) return fail("${step.s("appName", "Gmail")} isn't installed")
        val attach = step.b("attach", true) && rc.files.isNotEmpty()
        val i = if (attach) shareIntent(ctx, rc.files) else Intent(Intent.ACTION_SEND).setType("message/rfc822").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.putExtra(Intent.EXTRA_EMAIL, to)
        list("cc").takeIf { it.isNotEmpty() }?.let { i.putExtra(Intent.EXTRA_CC, it) }
        i.putExtra(Intent.EXTRA_SUBJECT, expand(step.s("subject"), rc))
        i.putExtra(Intent.EXTRA_TEXT, expand(step.s("body"), rc))
        if (pkg.isNotBlank()) i.setPackage(pkg)
        try {
            svc.startActivity(i)
        } catch (e: Exception) {
            return fail("Couldn't open the mail app: ${e.message}")
        }
        if (pkg.isNotBlank() && !svc.waitForPackage(pkg, 15_000)) return fail("The mail app didn't open")
        delay(2500)
        val files = if (attach) " with ${rc.files.size} file(s)" else ""
        if (!step.b("autoSend", true)) return ok("Email to ${to.joinToString()}$files is ready — waiting for you to press Send")
        val send = Selector(pkg, "", "", "Send", "", -1f, -1f, 0, 0, "Send")
        val byId = Selector(pkg, "$pkg:id/send", "", "", "", -1f, -1f, 0, 0, "Send")
        val node = svc.waitFor(byId, 8000) ?: svc.waitFor(send, 4000)
            ?: return fail("Couldn't find the mail app's Send button. On screen: ${svc.describeScreen(pkg)}")
        if (!(svc.clickNode(node) || svc.tapNode(node))) return fail("Found Send but couldn't press it")
        // Sent = the compose screen closes.
        return if (svc.waitGone(byId, 10_000) && svc.find(send) == null) ok("Emailed ${to.joinToString()}$files")
        else fail("Pressed Send but the email is still open — please check the mail app")
    }
}
