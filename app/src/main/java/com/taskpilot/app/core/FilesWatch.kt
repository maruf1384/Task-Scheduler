package com.taskpilot.app.core

/**
 * "Wait for new files" — deciding when a script's output is complete, or that it never will be.
 *
 *     Waiting ──new files appear──▶ Growing ──unchanged for [settleMs]──▶ Ready ✓
 *        │                            │ a file changes → keep Growing
 *        │ the app shows the script ended (Traceback / [Program finished]) and still no files
 *        ▼
 *     Ended ──[graceMs] with no files──▶ ScriptEnded ✗        (files appear in time → Growing)
 *     any ──[timeoutMs] passed──▶ TimedOut ✗
 *
 * Immutable; the caller feeds it what it sees ([files], [screen]) and acts on the [Verdict].
 */
data class FilesWatch(
    val startedAt: Long,
    val timeoutMs: Long,
    val settleMs: Long,
    val graceMs: Long = 12_000,
    /** Names + sizes + times of the new files at the last look ("" = none yet). */
    val lastSig: String = "",
    val stableSince: Long = 0,
    /** When the app first showed that the script had ended (0 = not seen). */
    val endedAt: Long = 0,
    val end: ScriptEnd? = null,
) {
    sealed interface Verdict {
        data object Waiting : Verdict

        /** The files are complete. */
        data object Ready : Verdict

        /** The script crashed / finished and made no files. */
        data class ScriptEnded(val end: ScriptEnd) : Verdict
        data object TimedOut : Verdict
    }

    val hasFiles: Boolean get() = lastSig.isNotEmpty()

    fun timedOut(now: Long): Boolean = now >= startedAt + timeoutMs
    fun msLeft(now: Long): Long = (startedAt + timeoutMs - now).coerceAtLeast(0)

    /** New files were found; [sig] changes whenever any of them changes. */
    fun files(now: Long, sig: String): Pair<FilesWatch, Verdict> = when {
        sig.isEmpty() -> copy(lastSig = "") to Verdict.Waiting
        sig != lastSig -> copy(lastSig = sig, stableSince = now) to Verdict.Waiting
        now - stableSince >= settleMs -> this to Verdict.Ready
        else -> this to Verdict.Waiting
    }

    /** No files yet; [lines] is the text the watched app is showing. */
    fun screen(now: Long, lines: List<String>): Pair<FilesWatch, Verdict> {
        val seen = if (endedAt == 0L) ScriptEnd.detect(lines) else null
        val w = if (seen != null) copy(endedAt = now, end = seen) else this
        val e = w.end
        return if (e != null && now - w.endedAt > graceMs) w to Verdict.ScriptEnded(e) else w to Verdict.Waiting
    }
}

/** What a Python runner (Pydroid 3) shows when a script is over. */
data class ScriptEnd(val crashed: Boolean, val errorLine: String) {
    val how: String get() = if (crashed) "crashed" else "finished"

    companion object {
        private val ERROR_LINE = Regex("""^\s*[A-Za-z_.]*(Error|Exception|Interrupt|Exit)\b.*:.*""")

        /** null = the script still seems to be running. */
        fun detect(screenLines: List<String>): ScriptEnd? {
            val lines = screenLines.flatMap { it.split('\n') }.map { it.trim() }
            val crashed = lines.any { it.startsWith("Traceback (most recent call last)") }
            val finished = lines.any { it.contains("[Program finished]") }
            if (!crashed && !finished) return null
            return ScriptEnd(crashed, lines.lastOrNull { ERROR_LINE.matches(it) }?.take(220) ?: "")
        }
    }
}
