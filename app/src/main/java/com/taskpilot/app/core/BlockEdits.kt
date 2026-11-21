package com.taskpilot.app.core

import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import java.util.UUID

/**
 * Changes to the step list that keep If / Otherwise / End and Repeat / End fitting together.
 * The editor uses only these, so a list that fits stays fitting (each rule is tested).
 *
 * The idea: an If or Repeat moves, copies and deletes as one block; Otherwise and End never move
 * on their own. A normal step may move into or out of a block — that's how steps are put inside.
 */
object BlockEdits {
    /** What the row's ⋮ menu may offer. */
    data class Allowed(
        val moveUp: Boolean,
        val moveDown: Boolean,
        val duplicate: Boolean,
        val toggle: Boolean,
        val delete: Boolean,
        /** If / Repeat: "Remove (keep the steps inside)" + "Delete with everything inside". */
        val unwrap: Boolean,
        /** If without an Otherwise yet. */
        val addOtherwise: Boolean,
        /** If that has one. */
        val removeOtherwise: Boolean,
        val run: Boolean,
    )

    fun allowed(steps: List<Step>, i: Int, blocks: Blocks = Blocks.of(steps)): Allowed {
        val role = blocks.roles.getOrNull(i) ?: return Allowed(false, false, false, false, false, false, false, false, false)
        val opener = role == Role.IF || role == Role.REPEAT
        val marker = role == Role.ELSE || role == Role.END
        val range = blocks.blockRange(i)
        return Allowed(
            moveUp = !marker && i > 0,
            moveDown = !marker && (range?.last ?: i) < steps.lastIndex,
            duplicate = !marker && (!opener || range != null),
            toggle = !marker,
            // An If/Repeat without its End (a broken list) can still be deleted on its own.
            delete = !marker && (!opener || range == null),
            unwrap = opener && range != null,
            addOtherwise = role == Role.IF && range != null && blocks.otherwise(i) < 0,
            removeOtherwise = role == Role.IF && blocks.otherwise(i) >= 0,
            run = !marker,
        )
    }

    /** Positions that move together with row [i]: the whole block for an If/Repeat. */
    private fun span(blocks: Blocks, i: Int): IntRange = blocks.blockRange(i) ?: i..i

    /** Moves row [i] (with its block) one place up (dir -1) or down (+1). */
    fun move(steps: List<Step>, i: Int, dir: Int): List<Step> {
        val b = Blocks.of(steps)
        if (!allowed(steps, i, b).let { if (dir < 0) it.moveUp else it.moveDown }) return steps
        val r = span(b, i)
        val out = steps.toMutableList()
        if (dir < 0) {
            val above = out.removeAt(r.first - 1)
            out.add(r.last, above)
        } else {
            val below = out.removeAt(r.last + 1)
            out.add(r.first, below)
        }
        return out
    }

    /** A copy of row [i] (with its whole block, all with new ids) placed right after it. */
    fun duplicate(steps: List<Step>, i: Int): List<Step> {
        val b = Blocks.of(steps)
        if (!allowed(steps, i, b).duplicate) return steps
        val r = span(b, i)
        val copy = steps.subList(r.first, r.last + 1).map { it.copy(id = UUID.randomUUID().toString()) }
        return steps.toMutableList().apply { addAll(r.last + 1, copy) }
    }

    /** Deletes a normal step, a Stop, or an If/Repeat that has no End. */
    fun delete(steps: List<Step>, i: Int): List<Step> {
        if (!allowed(steps, i).delete) return steps
        return steps.filterIndexed { j, _ -> j != i }
    }

    /** Deletes an If/Repeat with everything inside it, its Otherwise and its End. */
    fun deleteBlock(steps: List<Step>, i: Int): List<Step> {
        val r = Blocks.of(steps).blockRange(i) ?: return steps
        return steps.filterIndexed { j, _ -> j !in r }
    }

    /** Removes an If/Repeat with its Otherwise and End, keeping the steps that were inside. */
    fun unwrap(steps: List<Step>, i: Int): List<Step> {
        val b = Blocks.of(steps)
        if (!allowed(steps, i, b).unwrap) return steps
        val gone = setOfNotNull(i, b.end(i), b.otherwise(i).takeIf { it >= 0 })
        return steps.filterIndexed { j, _ -> j !in gone }
    }

    /** Adds an empty Otherwise just above the If's End. */
    fun addOtherwise(steps: List<Step>, i: Int): List<Step> {
        val b = Blocks.of(steps)
        if (!allowed(steps, i, b).addOtherwise) return steps
        return steps.toMutableList().apply { add(b.end(i), Step(type = StepType.ELSE)) }
    }

    /** Deletes the Otherwise of the If at [i] together with the steps in the Otherwise part. */
    fun removeOtherwise(steps: List<Step>, i: Int): List<Step> {
        val b = Blocks.of(steps)
        val o = b.otherwise(i)
        if (o < 0) return steps
        val e = b.end(i).takeIf { it > o } ?: (o + 1)
        return steps.filterIndexed { j, _ -> j !in o until e }
    }

    /** How many steps [deleteBlock] / [removeOtherwise] would take away — for the menu wording. */
    fun stepsInside(steps: List<Step>, i: Int): Int {
        val b = Blocks.of(steps)
        val r = b.blockRange(i) ?: return 0
        return r.count { b.roles[it] != Role.ELSE && b.roles[it] != Role.END } - 1
    }
}
