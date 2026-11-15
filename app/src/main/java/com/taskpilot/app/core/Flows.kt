package com.taskpilot.app.core

/**
 * How a "Pick on screen" request ended. [stepId] says which step asked, so the answer can only be
 * picked up by the screen that asked for it (it used to be a loose `expectingCapture` flag that
 * stayed set when the capture was cancelled or its preparation failed).
 */
sealed interface CaptureOutcome<out T> {
    val stepId: String

    data class Ready<T>(override val stepId: String, val result: T) : CaptureOutcome<T>

    /** The earlier steps couldn't be run, or the screen couldn't be read. [reason] is shown to the user. */
    data class Failed(override val stepId: String, val reason: String) : CaptureOutcome<Nothing>
    data class Cancelled(override val stepId: String) : CaptureOutcome<Nothing>
}

/**
 * Installing an update from inside the app.
 *
 *     Idle ──Install──▶ NeedsPermission ──(user allows, taps Install again)──▶ Preparing
 *     Idle ──Install──▶ Preparing ──copied──▶ Confirming ──Android installs──▶ (app restarts)
 *     Preparing / Confirming ──error or cancelled──▶ Failed ──Install──▶ Preparing
 *
 * The answer from Android arrives in a broadcast receiver; before this state existed the Setup
 * card never heard about it and kept saying "Android will now ask you…" after a failure.
 */
sealed interface UpdateState {
    data object Idle : UpdateState

    /** "Allow from this source" is off for TaskPilot. */
    data object NeedsPermission : UpdateState
    data class Preparing(val fileName: String) : UpdateState

    /** Handed to Android; its confirmation is showing or about to. */
    data object Confirming : UpdateState
    data class Failed(val reason: String) : UpdateState

    /** A file that can't be used was picked (not TaskPilot, or older than the installed version). */
    data class Rejected(val reason: String) : UpdateState

    val busy: Boolean get() = this is Preparing

    /** The line shown on the Setup card, or null for nothing. */
    val message: String?
        get() = when (this) {
            Idle -> null
            NeedsPermission -> "Turn on “Allow from this source” for TaskPilot, come back, and tap Install again. (Only needed once.)"
            is Preparing -> "Preparing $fileName…"
            Confirming -> "Android will now ask you to confirm the update. TaskPilot closes and reopens with everything kept."
            is Failed -> "The update didn't install: $reason"
            is Rejected -> reason
        }

    val isError: Boolean get() = this is Failed || this is Rejected
}
