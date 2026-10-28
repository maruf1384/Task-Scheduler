package com.taskpilot.app.engine

import android.app.KeyguardManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.taskpilot.app.data.Phone
import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import com.taskpilot.app.ui.WakeActivity
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What each kind of step actually does. */
object StepExec {

    private fun ok(msg: String = "") = Outcome(true, msg)
    private fun fail(msg: String) = Outcome(false, msg)

    fun expand(s: String, rc: RunContext): String {
        val now = LocalDateTime.now()
        return s.replace("{date}", now.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
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
        StepType.LAUNCH_APP -> launchApp(ctx, svc, step)
        StepType.OPEN_FILE -> openFile(ctx, svc, step)
        StepType.TAP_ELEMENT -> tapElement(svc, step)
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
        StepType.WAIT_NEW_FILES -> waitNewFiles(step, rc)
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
        val pin = step.s("pin").filter { it.isDigit() }
        if (pin.isNotEmpty()) {
            if (!svc.enterPin(pin)) return fail("Couldn't find the PIN keypad on the lock screen")
            for (k in 0 until 10) {
                delay(400)
                if (!km.isKeyguardLocked) return ok("Unlocked with your PIN")
            }
            return fail("The saved PIN didn't unlock the phone")
        }
        return if (step.b("strict", true)) {
            fail("Phone is locked with a PIN or pattern. Save your PIN in this step, or switch the screen lock to Swipe/None.")
        } else ok("Screen on (still locked)")
    }

    private suspend fun launchApp(ctx: Context, svc: AutomationService, step: Step): Outcome {
        val pkg = step.s("pkg")
        if (pkg.isBlank()) return fail("Choose an app")
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return fail("${step.s("appName", pkg)} isn't installed")
        svc.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return if (svc.waitForPackage(pkg, 15_000)) { delay(800); ok("Opened ${step.s("appName", pkg)}") }
        else fail("${step.s("appName", pkg)} didn't come to the front")
    }

    private suspend fun openFile(ctx: Context, svc: AutomationService, step: Step): Outcome {
        val f = File(step.s("path"))
        if (step.s("path").isBlank()) return fail("Choose a file")
        if (!f.exists()) return fail("File not found: ${FileOps.pretty(f.absolutePath)}")
        val pkg = step.s("pkg")
        val app = step.s("appName", pkg.ifBlank { "the app" })
        if (pkg.isNotBlank() && !installed(ctx, pkg)) return fail("$app isn't installed")
        // Try the chosen link type first, then automatically fall back to the other one:
        // some apps (e.g. Pydroid 3 builds) accept only a real file path, others only a content link.
        val chosen = step.s("uriMode", "file")
        val modes = if (chosen == "file") listOf("file", "content") else listOf("content", "file")
        val mimes = listOf(FileOps.mime(f), "text/plain", "*/*").distinct()
        var opened = false
        outer@ for (mode in modes) {
            val uri: Uri = try {
                if (mode == "file") Uri.fromFile(f) else FileOps.uriFor(ctx, f)
            } catch (_: Exception) {
                continue@outer
            }
            for (m in mimes) {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, m).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                if (pkg.isNotBlank()) i.setPackage(pkg)
                try {
                    svc.startActivity(i)
                    opened = true
                    break@outer
                } catch (_: Exception) {
                    // ActivityNotFoundException, SecurityException, FileUriExposedException… try the next mime/mode.
                }
            }
        }
        if (!opened) return fail(
            "$app wouldn't open .${f.extension} from another app. Open $app yourself and load the file there, " +
                "or use an “Open app” step followed by on-screen taps.",
        )
        if (pkg.isNotBlank() && !svc.waitForPackage(pkg, 15_000)) return fail("$app didn't open")
        delay(1000)
        return ok("Opened ${f.name} in $app")
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
                delay(600)
                return if (done) ok("Tapped “$name”") else fail("Found “$name” but couldn't tap it")
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

    private suspend fun waitNewFiles(step: Step, rc: RunContext): Outcome {
        val folder = File(step.s("folder"))
        if (!folder.isDirectory) return fail("Folder not found: ${FileOps.pretty(folder.absolutePath)}")
        val exts = FileOps.parseExts(step.s("ext"))
        val timeout = step.i("timeout", 600).coerceAtLeast(5) * 1000L
        val settle = step.i("settle", 6).coerceAtLeast(1) * 1000L
        val since = rc.startMs - 2000
        val end = System.currentTimeMillis() + timeout
        var lastSig = ""
        var stableSince = 0L
        while (System.currentTimeMillis() < end) {
            val found = FileOps.scanNew(folder, since, exts, step.b("recursive", true))
            if (found.isNotEmpty()) {
                val sig = found.joinToString("|") { "${it.path}:${it.length()}:${it.lastModified()}" }
                val now = System.currentTimeMillis()
                if (sig != lastSig) {
                    lastSig = sig
                    stableSince = now
                } else if (now - stableSince >= settle) {
                    rc.files = found
                    return ok("${found.size} new file(s): ${found.joinToString(", ") { it.name }}")
                }
            }
            delay(1500)
        }
        return fail("No new files appeared within ${timeout / 1000}s")
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
        val r = svc.sendAndConfirm(pkg, names)
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
}
