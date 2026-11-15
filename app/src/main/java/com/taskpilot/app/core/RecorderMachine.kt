package com.taskpilot.app.core

import com.taskpilot.app.data.Selector
import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType

/**
 * "Build it in the real app" — the recorder — as ONE state and ONE function.
 *
 * The recorder is always in exactly one [RecMode]:
 *
 *     Opening ──app is in front──▶ Choosing ◀──────────────┐
 *                                   │  ▲                    │
 *                 Hide options      │  │ Options            │ action finished /
 *                                   ▼  │                    │ text saved / cancelled
 *                                 Peeking ◀──▶ Watching     │
 *                                   (Record my taps on/off) │
 *     Choosing ──Tap / Long press / Scroll / Back / Home──▶ Acting ──┘
 *     Choosing ──Type text──▶ Typing ──Save text / Cancel──▶ Choosing
 *     any ──Done──▶ (Commit)        any ──Discard ×2──▶ (Abandon)
 *
 * [reduce] takes the current state and an event and returns the next state plus the things the
 * Android side must do ([RecEffect]). It touches no Android class, so every transition is tested
 * on a plain JVM (RecorderMachineTest).
 */

enum class OptKind(val title: String, val one: String) {
    BUTTON("Buttons", "Button"),
    FIELD("Text boxes", "Text box"),
    ITEM("Items", "Item"),
    TOGGLE("Switches", "Switch"),
    SCROLL("Scroll areas", "Scroll area");

    /** What can be done with this kind of thing — the first one is the main button. */
    fun actions(): List<OptAction> = when (this) {
        FIELD -> listOf(OptAction.TYPE, OptAction.TAP, OptAction.WAIT_FOR)
        SCROLL -> listOf(OptAction.SCROLL_DOWN, OptAction.SCROLL_UP)
        TOGGLE -> listOf(OptAction.TAP, OptAction.WAIT_FOR)
        BUTTON, ITEM -> listOf(OptAction.TAP, OptAction.LONG_PRESS, OptAction.WAIT_FOR)
    }
}

enum class OptAction(val label: String) {
    TAP("Tap"),
    LONG_PRESS("Long press"),
    TYPE("Type text"),
    WAIT_FOR("Wait for it"),
    SCROLL_DOWN("Scroll down"),
    SCROLL_UP("Scroll up");

    fun labelFor(kind: OptKind): String = if (this == TAP && kind == OptKind.TOGGLE) "Switch it" else label
}

/** One thing on the other app's screen that can be automated. */
data class ScreenOption(
    /** Stable identity across re-reads of the same screen: kind + id + label (+ a counter for twins). */
    val sig: String,
    val kind: OptKind,
    val label: String,
    val detail: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** How a step finds it again (Selector.* keys, plus "pkg" and "appName"). */
    val params: Map<String, String>,
    val password: Boolean = false,
    /** Appeared since the previous read of the same screen (a menu opened, a row loaded…). */
    val isNew: Boolean = false,
)

data class RecSession(val wfId: String, val insertAt: Int, val pkg: String, val appName: String)

sealed interface RecMode {
    /** Waiting for the chosen app to come to the front. */
    data object Opening : RecMode

    /** The options panel is open. [expanded] = the option whose actions are showing. */
    data class Choosing(val expanded: String? = null) : RecMode

    /** Panel hidden; the user is looking at / using the app. Nothing is recorded. */
    data object Peeking : RecMode

    /** "Record my taps": the user's own taps and typing become steps. */
    data object Watching : RecMode

    /** The user is typing into [option] with the app's own keyboard; "Save text" ends it. */
    data class Typing(val option: ScreenOption) : RecMode

    /** TaskPilot is doing something in the app; nothing else is accepted until it has finished. */
    data object Acting : RecMode

    val tag: String
        get() = when (this) {
            Opening -> "Opening"
            is Choosing -> "Choosing"
            Peeking -> "Peeking"
            Watching -> "Watching"
            is Typing -> "Typing"
            Acting -> "Acting"
        }
}

/** Text typed while "Record my taps" is on; saved as one step when the user moves on. */
data class PendingText(val params: Map<String, String>, val label: String, val text: String)

data class RecState(
    val session: RecSession,
    val mode: RecMode,
    val steps: List<Step>,
    val options: List<ScreenOption> = emptyList(),
    /** A screen read is in flight. */
    val reading: Boolean = false,
    /** How many times the set of options has changed — the panel scrolls to the top on a new screen. */
    val screenNo: Int = 0,
    val message: String = "",
    /** "Discard" was tapped once; a second tap really discards. Any other action disarms it. */
    val discardArmed: Boolean = false,
    val barOnTop: Boolean = true,
    val pendingText: PendingText? = null,
) {
    val panelOpen: Boolean get() = mode is RecMode.Choosing
    val expanded: ScreenOption? get() = (mode as? RecMode.Choosing)?.expanded?.let { sig -> options.firstOrNull { it.sig == sig } }
    val typing: ScreenOption? get() = (mode as? RecMode.Typing)?.option
    val watching: Boolean get() = mode == RecMode.Watching

    /** Steps so far, counting text that is typed but not yet saved as a step. */
    val stepCount: Int get() = steps.size + if (pendingText != null) 1 else 0
}

sealed interface RecEvent {
    /** The chosen app came to the front ([found]) or the wait gave up. */
    data class AppReady(val found: Boolean) : RecEvent
    data object TogglePanel : RecEvent
    data class Watch(val on: Boolean) : RecEvent
    data class Expand(val sig: String?) : RecEvent
    data class Choose(val sig: String, val action: OptAction) : RecEvent

    /** "BACK" or "HOME" from the panel. */
    data class Key(val key: String) : RecEvent
    data object AddWait : RecEvent

    /** The action TaskPilot performed is finished and the screen has settled. */
    data object ActionDone : RecEvent
    data class SaveTyped(val text: String, val password: Boolean) : RecEvent
    data object CancelTyping : RecEvent

    /** Seen while Watching: the user tapped something in the app. */
    data class UserTapped(val params: Map<String, String>, val label: String, val long: Boolean) : RecEvent

    /** Seen while Watching: the user typed into a box. */
    data class UserTyped(val params: Map<String, String>, val label: String, val text: String, val password: Boolean) : RecEvent
    data object ReadStarted : RecEvent
    data class ScreenRead(val options: List<ScreenOption>) : RecEvent
    data object Undo : RecEvent
    data object Discard : RecEvent

    /** The "tap again to discard" window ran out. */
    data object DiscardTimeout : RecEvent
    data object Done : RecEvent
    data object MoveBar : RecEvent

    val tag: String get() = this::class.simpleName ?: "?"
}

/** What the Android side must do after a transition. */
sealed interface RecEffect {
    /** Tap / long-press / scroll [sig] in the real app, then send [RecEvent.ActionDone]. */
    data class Perform(val sig: String, val action: OptAction) : RecEffect

    /** Put the cursor in the text box [sig] so the app's keyboard opens. */
    data class Focus(val sig: String) : RecEffect

    /** Press Back / Home, then send [RecEvent.ActionDone]. */
    data class PressKey(val key: String) : RecEffect

    /** Read the screen now (don't wait for it to change). */
    data object ReadScreen : RecEffect

    /** Done: add [steps] to the automation and close the recorder. */
    data class Commit(val steps: List<Step>) : RecEffect

    /** Discard: close the recorder, keep nothing. */
    data object Abandon : RecEffect
}

object RecorderMachine {
    private const val DISCARD_HINT = "Tap Discard again"

    fun start(session: RecSession, addOpenStep: Boolean): RecState = RecState(
        session = session,
        mode = RecMode.Opening,
        steps = if (addOpenStep) {
            listOf(Step(type = StepType.LAUNCH_APP, p = mapOf("pkg" to session.pkg, "appName" to session.appName)))
        } else emptyList(),
        message = "Opening ${session.appName}…",
    )

    fun reduce(s: RecState, e: RecEvent): Pair<RecState, List<RecEffect>> {
        // "Tap again to discard" only survives things the user didn't do.
        val passive = e is RecEvent.Discard || e is RecEvent.ReadStarted || e is RecEvent.ScreenRead ||
            e is RecEvent.ActionDone || e is RecEvent.AppReady
        val st = if (passive) s else s.copy(discardArmed = false)
        return step(st, e)
    }

    private fun stay(s: RecState) = s to emptyList<RecEffect>()

    private fun step(s: RecState, e: RecEvent): Pair<RecState, List<RecEffect>> = when (e) {
        is RecEvent.AppReady ->
            if (s.mode != RecMode.Opening) stay(s)
            else s.copy(
                mode = RecMode.Choosing(),
                message = if (e.found) "Pick what to do on this screen"
                else "Couldn't see ${s.session.appName} open — open it yourself; the options follow the screen",
            ) to listOf(RecEffect.ReadScreen)

        RecEvent.TogglePanel -> when (s.mode) {
            is RecMode.Choosing -> stay(s.copy(mode = RecMode.Peeking, message = "Use the app — the options keep following the screen"))
            RecMode.Peeking, RecMode.Watching ->
                flush(s).copy(mode = RecMode.Choosing(), message = "Pick what to do on this screen") to listOf(RecEffect.ReadScreen)
            RecMode.Opening, RecMode.Acting, is RecMode.Typing -> stay(s)
        }

        is RecEvent.Watch -> when {
            e.on && (s.mode is RecMode.Choosing || s.mode == RecMode.Peeking) ->
                stay(s.copy(mode = RecMode.Watching, message = "Use ${s.session.appName} normally — your taps and typing are saved"))
            !e.on && s.mode == RecMode.Watching ->
                stay(flush(s).copy(mode = RecMode.Peeking, message = "Record my taps: off"))
            else -> stay(s)
        }

        is RecEvent.Expand ->
            if (s.mode !is RecMode.Choosing) stay(s)
            else stay(s.copy(mode = RecMode.Choosing(e.sig?.takeIf { sig -> s.options.any { it.sig == sig } })))

        is RecEvent.Choose -> choose(s, e)

        is RecEvent.Key ->
            if (s.mode !is RecMode.Choosing) stay(s)
            else s.copy(
                mode = RecMode.Acting,
                steps = s.steps + Step(type = StepType.SYSTEM_KEY, p = mapOf("key" to e.key)),
                message = "Saved: ${e.key.lowercase()}",
            ) to listOf(RecEffect.PressKey(e.key))

        RecEvent.AddWait ->
            if (s.mode !is RecMode.Choosing) stay(s)
            else stay(s.copy(steps = s.steps + Step(type = StepType.DELAY, p = mapOf("seconds" to "2")), message = "Saved: wait 2 s"))

        RecEvent.ActionDone ->
            if (s.mode != RecMode.Acting) stay(s)
            else s.copy(mode = RecMode.Choosing()) to listOf(RecEffect.ReadScreen)

        is RecEvent.SaveTyped -> {
            val o = s.typing
            when {
                o == null -> stay(s)
                e.password || o.password ->
                    s.copy(mode = RecMode.Choosing(), message = "Password boxes are never recorded.") to listOf(RecEffect.ReadScreen)
                else -> s.copy(
                    mode = RecMode.Choosing(),
                    steps = s.steps + Step(
                        type = StepType.INPUT_TEXT,
                        label = "Type in “${o.label}”",
                        p = base(s, o.params) + mapOf("text" to e.text, "timeout" to "15"),
                    ),
                    message = "Saved: type “${e.text.take(30)}”",
                ) to listOf(RecEffect.ReadScreen)
            }
        }

        RecEvent.CancelTyping ->
            if (s.mode !is RecMode.Typing) stay(s)
            else s.copy(mode = RecMode.Choosing(), message = "Pick what to do on this screen") to listOf(RecEffect.ReadScreen)

        is RecEvent.UserTapped ->
            if (s.mode != RecMode.Watching) stay(s)
            else {
                val f = flush(s)
                val verb = if (e.long) "Long-press" else "Tap"
                stay(
                    f.copy(
                        steps = f.steps + Step(
                            type = StepType.TAP_ELEMENT,
                            label = "$verb “${e.label}”",
                            p = base(s, e.params) + mapOf("timeout" to "20", "coords" to "true", "long" to e.long.toString()),
                        ),
                        message = "Saved: ${verb.lowercase()} “${e.label}”",
                    ),
                )
            }

        is RecEvent.UserTyped -> when {
            s.mode != RecMode.Watching -> stay(s)
            e.password -> stay(s.copy(pendingText = null, message = "Password boxes are never recorded."))
            else -> stay(s.copy(pendingText = PendingText(e.params, e.label, e.text)))
        }

        RecEvent.ReadStarted -> stay(s.copy(reading = true))

        is RecEvent.ScreenRead -> stay(screenRead(s, e.options))

        RecEvent.Undo -> when {
            s.mode == RecMode.Acting || s.mode is RecMode.Typing -> stay(s)
            s.pendingText != null -> stay(s.copy(pendingText = null, message = "Removed the text you typed (the app itself isn't changed back)"))
            s.steps.isEmpty() -> stay(s)
            else -> stay(s.copy(steps = s.steps.dropLast(1), message = "Removed the last step (the app itself isn't changed back)"))
        }

        RecEvent.Discard ->
            if (s.discardArmed || s.stepCount == 0) s to listOf(RecEffect.Abandon)
            else stay(s.copy(discardArmed = true, message = "$DISCARD_HINT to throw away ${s.stepCount} step${if (s.stepCount == 1) "" else "s"}"))

        // reduce() has already disarmed it; drop the "tap again" hint too.
        RecEvent.DiscardTimeout -> stay(if (s.message.startsWith(DISCARD_HINT)) s.copy(message = "") else s)

        RecEvent.Done -> {
            val f = flush(s)
            f to listOf(RecEffect.Commit(f.steps))
        }

        RecEvent.MoveBar -> stay(s.copy(barOnTop = !s.barOnTop))
    }

    private fun choose(s: RecState, e: RecEvent.Choose): Pair<RecState, List<RecEffect>> {
        if (s.mode !is RecMode.Choosing) return stay(s)
        val o = s.options.firstOrNull { it.sig == e.sig } ?: return stay(s.copy(message = "That option is no longer on the screen"))
        if (e.action !in o.kind.actions()) return stay(s)
        val p = base(s, o.params)
        return when (e.action) {
            OptAction.WAIT_FOR -> stay(
                s.copy(
                    steps = s.steps + Step(
                        type = StepType.WAIT_ELEMENT, label = "Wait for “${o.label}”",
                        p = p + mapOf("mode" to "appear", "timeout" to "60"),
                    ),
                    message = "Saved: wait for “${o.label}”",
                ),
            )
            OptAction.TYPE ->
                if (o.password) stay(s.copy(message = "Password boxes are never recorded — TaskPilot doesn't store passwords."))
                else s.copy(
                    mode = RecMode.Typing(o),
                    message = "Type in “${o.label}” using the app's keyboard, then tap Save text",
                ) to listOf(RecEffect.Focus(o.sig))
            OptAction.TAP, OptAction.LONG_PRESS -> {
                val long = e.action == OptAction.LONG_PRESS
                s.copy(
                    mode = RecMode.Acting,
                    steps = s.steps + Step(
                        type = StepType.TAP_ELEMENT,
                        label = (if (long) "Long-press" else "Tap") + " “${o.label}”",
                        p = p + mapOf("timeout" to "20", "coords" to "true", "long" to long.toString()),
                    ),
                    message = "Saved: ${if (long) "long-press" else "tap"} “${o.label}” — finding the next options…",
                ) to listOf(RecEffect.Perform(o.sig, e.action))
            }
            OptAction.SCROLL_DOWN, OptAction.SCROLL_UP -> {
                val dir = if (e.action == OptAction.SCROLL_DOWN) "down" else "up"
                s.copy(
                    mode = RecMode.Acting,
                    steps = s.steps + Step(
                        type = StepType.SCROLL, label = "Scroll ${o.label} $dir",
                        p = p + mapOf("dir" to dir, "times" to "1", "timeout" to "15"),
                    ),
                    message = "Saved: scroll $dir — finding the next options…",
                ) to listOf(RecEffect.Perform(o.sig, e.action))
            }
        }
    }

    /** Selector params + the app they belong to (falls back to the app being recorded). */
    private fun base(s: RecState, params: Map<String, String>): Map<String, String> {
        val pkg = params["pkg"]?.takeIf { it.isNotBlank() } ?: params[Selector.PKG]?.takeIf { it.isNotBlank() } ?: s.session.pkg
        val appName = params["appName"]?.takeIf { it.isNotBlank() } ?: if (pkg == s.session.pkg) s.session.appName else pkg
        return params + mapOf("pkg" to pkg, "appName" to appName)
    }

    /** Turns text typed while Watching into a step. */
    private fun flush(s: RecState): RecState {
        val t = s.pendingText ?: return s
        return s.copy(
            pendingText = null,
            steps = s.steps + Step(
                type = StepType.INPUT_TEXT,
                label = "Type in “${t.label.ifBlank { "text box" }}”",
                p = base(s, t.params) + mapOf("text" to t.text, "timeout" to "15"),
            ),
            message = "Saved: type “${t.text.take(30)}”",
        )
    }

    /**
     * A fresh read of the screen arrived. Same options as before → nothing visibly changes (so the
     * list doesn't jump while the user is reading it). Some options are new on the same screen →
     * they're marked [ScreenOption.isNew]. A completely different screen → a clean list.
     */
    private fun screenRead(s: RecState, read: List<ScreenOption>): RecState {
        val before = s.options.associateBy { it.sig }
        val sameSet = read.size == s.options.size && read.indices.all { read[it].sig == s.options[it].sig }
        val overlap = read.any { it.sig in before }
        val options = read.map { o ->
            val old = before[o.sig]
            o.copy(isNew = if (sameSet) old?.isNew == true else overlap && old == null)
        }
        val mode = when (val m = s.mode) {
            is RecMode.Choosing -> if (m.expanded != null && options.none { it.sig == m.expanded }) RecMode.Choosing() else m
            else -> m
        }
        return s.copy(options = options, reading = false, mode = mode, screenNo = if (sameSet) s.screenNo else s.screenNo + 1)
    }
}
