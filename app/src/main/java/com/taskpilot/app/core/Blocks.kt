package com.taskpilot.app.core

import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType

/** What a step does to the flow of a run. */
enum class Role { STEP, IF, ELSE, END, REPEAT, STOP }

fun roleOf(t: StepType): Role = when (t) {
    StepType.IF -> Role.IF
    StepType.ELSE -> Role.ELSE
    StepType.END -> Role.END
    StepType.REPEAT -> Role.REPEAT
    StepType.STOP -> Role.STOP
    else -> Role.STEP
}

/**
 * How the If / Otherwise / End and Repeat / End steps of an automation fit together.
 *
 * Automations stay a flat list (so saved files, "Run from here", the recorder and the editor all
 * keep working by position); the blocks are worked out from the markers every time:
 *
 *     0 If …            depth 0   end=3  otherwise=2
 *     1   Tap “Run”     depth 1
 *     2 Otherwise       depth 0   opener=0  end=3
 *     3 End             depth 0   opener=0
 *
 * Anything that doesn't fit (an End with nothing to close, an If without End…) is listed in
 * [problems] by position, and a run refuses to start until it's fixed.
 */
class Blocks private constructor(
    val roles: List<Role>,
    /** For If / Otherwise / Repeat: position of the End that closes the block, else -1. */
    private val endAt: IntArray,
    /** For If: position of its Otherwise, else -1. */
    private val elseAt: IntArray,
    /** For Otherwise / End: position of the If or Repeat it belongs to, else -1. */
    private val openerAt: IntArray,
    /** How many blocks each step is inside (Otherwise and End line up with their If). */
    private val depthAt: IntArray,
    /** Position → what's wrong with it. */
    val problems: Map<Int, String>,
) {
    val ok: Boolean get() = problems.isEmpty()
    val size: Int get() = roles.size

    fun end(i: Int): Int = endAt.getOrElse(i) { -1 }
    fun otherwise(i: Int): Int = elseAt.getOrElse(i) { -1 }
    fun opener(i: Int): Int = openerAt.getOrElse(i) { -1 }
    fun depth(i: Int): Int = depthAt.getOrElse(i) { 0 }

    /** "End of If" / "End of Repeat" — or plain "End" when it closes nothing. */
    fun endLabel(i: Int): String = when (roles.getOrNull(opener(i))) {
        Role.IF -> "End of If"
        Role.REPEAT -> "End of Repeat"
        else -> "End"
    }

    /** The positions a whole block covers: from its If/Repeat to its End (inclusive). */
    fun blockRange(i: Int): IntRange? {
        val e = end(i)
        return if (roles.getOrNull(i) in setOf(Role.IF, Role.REPEAT) && e > i) i..e else null
    }

    /** A short sentence for a run that can't start, or null when everything fits. */
    fun firstProblem(): String? = problems.entries.minByOrNull { it.key }?.let { "Step ${it.key + 1}: ${it.value}" }

    companion object {
        fun of(steps: List<Step>): Blocks = ofRoles(steps.map { roleOf(it.type) })

        fun ofRoles(roles: List<Role>): Blocks {
            val n = roles.size
            val end = IntArray(n) { -1 }
            val otherwise = IntArray(n) { -1 }
            val opener = IntArray(n) { -1 }
            val depth = IntArray(n)
            val problems = LinkedHashMap<Int, String>()
            val open = ArrayList<Int>()
            for (i in 0 until n) {
                when (roles[i]) {
                    Role.IF, Role.REPEAT -> {
                        depth[i] = open.size
                        open += i
                    }
                    Role.ELSE -> {
                        val top = open.lastOrNull()
                        depth[i] = (open.size - 1).coerceAtLeast(0)
                        when {
                            top == null || roles[top] != Role.IF -> problems[i] = "“Otherwise” must be inside an If"
                            otherwise[top] != -1 -> problems[i] = "This If already has an “Otherwise”"
                            else -> { otherwise[top] = i; opener[i] = top }
                        }
                    }
                    Role.END -> {
                        val top = open.removeLastOrNull()
                        depth[i] = open.size
                        if (top == null) {
                            problems[i] = "This End has no If or Repeat above it"
                        } else {
                            end[top] = i
                            opener[i] = top
                            if (otherwise[top] != -1) end[otherwise[top]] = i
                        }
                    }
                    Role.STEP, Role.STOP -> depth[i] = open.size
                }
            }
            for (i in open) problems[i] = if (roles[i] == Role.IF) "This If has no End" else "This Repeat has no End"
            return Blocks(roles, end, otherwise, opener, depth, problems.toSortedMap())
        }
    }
}
