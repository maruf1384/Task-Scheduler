package com.taskpilot.app.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Saves an enum as a short word in the JSON files. The words are the ones older versions of the
 * app already wrote ("success", "ok", "Scheduled"…), so old files load unchanged — and a word
 * this version doesn't know (written by a newer version) becomes [fallback] instead of an error.
 */
open class WireEnumSerializer<E : Enum<E>>(
    name: String,
    private val all: List<E>,
    private val wire: (E) -> String,
    private val fallback: E,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(wire(value))
    override fun deserialize(decoder: Decoder): E {
        val s = decoder.decodeString()
        return all.firstOrNull { wire(it) == s } ?: fallback
    }
}

/** How a whole run ended. [NEVER] = this automation has not run yet. */
@Serializable(with = RunStatus.Ser::class)
enum class RunStatus(val wire: String) {
    NEVER(""),
    SUCCESS("success"),
    FAILED("failed"),
    STOPPED("stopped"),

    /** A scheduled run that could not start (e.g. another automation was still running). */
    SKIPPED("skipped");

    object Ser : WireEnumSerializer<RunStatus>("RunStatus", RunStatus.entries, { it.wire }, NEVER)
}

/** How one step of a run ended. */
@Serializable(with = StepStatus.Ser::class)
enum class StepStatus(val wire: String) {
    OK("ok"),
    FAILED("failed"),
    SKIPPED("skipped");

    object Ser : WireEnumSerializer<StepStatus>("StepStatus", StepStatus.entries, { it.wire }, SKIPPED)
}

/** What started a run. */
@Serializable(with = Trigger.Ser::class)
enum class Trigger(val wire: String) {
    SCHEDULED("Scheduled"),
    MANUAL("Manual");

    /** Shown in History. */
    val label: String get() = wire

    object Ser : WireEnumSerializer<Trigger>("Trigger", Trigger.entries, { it.wire }, MANUAL)
}

/** The accessibility service has three states, not two: "switched on but not running" is the trap. */
enum class A11yState {
    /** Not switched on in Android's Accessibility settings. */
    OFF,

    /** Switched on in settings, but Android is not running it (stopped by the phone, or crashed). */
    ON_NOT_RUNNING,

    /** Switched on and connected — automations can run. */
    READY;

    companion object {
        fun of(enabledInSettings: Boolean, connected: Boolean): A11yState = when {
            connected -> READY
            enabledInSettings -> ON_NOT_RUNNING
            else -> OFF
        }
    }
}
