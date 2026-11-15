package com.taskpilot.app.core

/**
 * Pressing Send in WhatsApp — the decision logic, separated from the screen-reading code.
 *
 *     Looking ──a "Send to …?" dialog / preview Send is showing──▶ press it (max [maxPresses])
 *     Looking ──the files are in the chat, nothing to press──▶ (twice in a row) ──▶ Done ✓
 *     Looking ──files in chat, a caption is waiting, composer shows "Send"──▶ press it ONCE
 *     Looking ──timed out, or 10 s since the last press──▶ Done (the caller checks the chat once more)
 *
 * The rules that must never break (each has a test in SendFlowTest):
 *  - the chat's own round button is pressed at most once, and only when a caption was typed —
 *    without a caption it is the MICROPHONE and pressing it sends a voice note;
 *  - nothing is pressed after the files are in the chat (except that one caption send);
 *  - at most [maxPresses] presses in total.
 */
data class SendFlow(
    val startedAt: Long,
    val captionTyped: Boolean,
    val timeoutMs: Long = 45_000,
    val maxPresses: Int = 4,
    val presses: Int = 0,
    val lastPressAt: Long = 0,
    val composerPressed: Boolean = false,
    /** Looks in a row with the files in the chat and nothing left to press. */
    val quietLooks: Int = 0,
) {
    enum class Target { CONFIRM, COMPOSER }

    sealed interface Decision {
        /** Press [target]; then call [pressed]. */
        data class Press(val target: Target) : Decision

        /** Look again after [ms]. */
        data class Wait(val ms: Long) : Decision

        /** Stop. [confirmed] = the files were seen in the chat. */
        data class Done(val confirmed: Boolean) : Decision
    }

    /** What is on WhatsApp's screen right now. Read lazily — each one is a walk over the screen. */
    interface Look {
        /** A "Send to …?" dialog button or a preview-screen Send button is showing. */
        fun confirmVisible(): Boolean

        /** One of the files' names is showing in the chat. */
        fun filesInChat(): Boolean

        /** The chat's round button currently says "Send" (not the microphone). */
        fun composerSendVisible(): Boolean
    }

    /** Decide what to do after looking at the screen. */
    fun decide(now: Long, look: Look): Pair<SendFlow, Decision> {
        if (now >= startedAt + timeoutMs || presses >= maxPresses) return this to Decision.Done(confirmed = false)
        if (look.confirmVisible()) return this to Decision.Press(Target.CONFIRM)
        if (look.filesInChat()) {
            if (captionTyped && !composerPressed && look.composerSendVisible()) {
                return copy(composerPressed = true) to Decision.Press(Target.COMPOSER)
            }
            val quiet = quietLooks + 1
            return if (quiet >= 2) copy(quietLooks = quiet) to Decision.Done(confirmed = true)
            else copy(quietLooks = quiet).idle(now)
        }
        return copy(quietLooks = 0).idle(now)
    }

    /** Call after carrying out a [Decision.Press]. [ok] = the press was delivered. */
    fun pressed(now: Long, target: Target, ok: Boolean): Pair<SendFlow, Decision> {
        if (ok) return copy(presses = presses + 1, lastPressAt = now, quietLooks = 0) to Decision.Wait(AFTER_PRESS_MS)
        // The press didn't land. For the composer that still counts as "nothing left to press".
        val next = if (target == Target.COMPOSER) copy(quietLooks = quietLooks + 1) else this
        if (target == Target.COMPOSER && next.quietLooks >= 2) return next to Decision.Done(confirmed = true)
        return next.idle(now)
    }

    private fun idle(now: Long): Pair<SendFlow, Decision> =
        if (presses > 0 && now - lastPressAt > GIVE_UP_AFTER_PRESS_MS) this to Decision.Done(confirmed = false)
        else this to Decision.Wait(LOOK_EVERY_MS)

    companion object {
        const val AFTER_PRESS_MS = 2000L
        const val LOOK_EVERY_MS = 500L
        const val GIVE_UP_AFTER_PRESS_MS = 10_000L
    }
}
