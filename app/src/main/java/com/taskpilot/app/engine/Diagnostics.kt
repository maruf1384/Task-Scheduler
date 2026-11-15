package com.taskpilot.app.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.taskpilot.app.core.Trace
import com.taskpilot.app.data.RunRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A plain-text report of one run for whoever is helping fix a problem — a person or an AI.
 * It is built only when the user taps "Copy report", and goes only to the clipboard.
 *
 * In it: app + phone version, which permissions are on, the run's steps and messages, and the
 * recent state changes from [Trace]. Never in it: the PIN, screen contents, typed text.
 */
object Diagnostics {
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun report(ctx: Context, run: RunRecord?): String = buildString {
        val (version, code) = try { Updater.currentVersion(ctx) } catch (_: Exception) { "?" to 0L }
        appendLine("TaskPilot report")
        appendLine("app: $version ($code) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine(
            "setup: accessibility=${Perms.a11yState(ctx)} files=${Perms.allFiles(ctx)} exactAlarms=${Perms.exactAlarms(ctx)} " +
                "battery=${Perms.battery(ctx)} notifications=${Perms.notifications(ctx)} secureLock=${Perms.secureLock(ctx)}",
        )
        appendLine("engine now: ${Engine.mode.value.tag}")
        if (run != null) {
            appendLine()
            appendLine("run: ${run.workflowName} · ${run.trigger.label} · ${run.status}")
            appendLine("started ${time.format(Date(run.startedAt))} · took ${(run.endedAt - run.startedAt) / 1000}s")
            if (run.summary.isNotBlank()) appendLine("summary: ${run.summary}")
            for (l in run.logs) {
                appendLine("  ${l.index + 1}. [${l.status}] ${l.title} (${l.durationMs / 1000}s)" + if (l.message.isNotBlank()) " — ${l.message}" else "")
            }
        }
        val trace = Trace.dump()
        appendLine()
        appendLine("recent state changes (${trace.size}, since the app last started):")
        trace.takeLast(120).forEach { appendLine("  $it") }
    }

    fun copy(ctx: Context, run: RunRecord?) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("TaskPilot report", report(ctx, run)))
    }
}
