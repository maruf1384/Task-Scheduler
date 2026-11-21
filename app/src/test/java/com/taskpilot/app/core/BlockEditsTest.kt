package com.taskpilot.app.core

import com.taskpilot.app.data.Step
import com.taskpilot.app.data.StepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockEditsTest {
    /** "a If b Else c End d" → steps; ids are the words, normal steps are notifications. */
    private fun list(s: String): List<Step> = s.split(' ').filter { it.isNotBlank() }.map { w ->
        when (w) {
            "If" -> Step(id = w + Math.random(), type = StepType.IF, label = "If")
            "Rep" -> Step(id = w + Math.random(), type = StepType.REPEAT, label = "Rep")
            "Else" -> Step(id = w + Math.random(), type = StepType.ELSE, label = "Else")
            "End" -> Step(id = w + Math.random(), type = StepType.END, label = "End")
            else -> Step(id = w, type = StepType.NOTIFY, label = w)
        }
    }
    private fun words(steps: List<Step>) = steps.joinToString(" ") { st ->
        st.label.ifBlank { mapOf(StepType.ELSE to "Else", StepType.END to "End").getValue(st.type) }
    }
    private fun fits(steps: List<Step>) = assertTrue(words(steps), Blocks.of(steps).ok)

    @Test fun markersCantBeMovedDeletedOrTurnedOff() {
        val s = list("If a Else b End")
        for (i in listOf(2, 4)) {
            val a = BlockEdits.allowed(s, i)
            assertFalse(a.moveUp || a.moveDown || a.delete || a.toggle || a.duplicate || a.run)
        }
        assertEquals(s, BlockEdits.move(s, 4, -1))
        assertEquals(s, BlockEdits.delete(s, 2))
    }

    @Test fun aStepMovesIntoAndOutOfABlock() {
        val s = list("a If b End")
        val inIt = BlockEdits.move(s, 0, 1)        // a jumps below the If → inside it
        assertEquals("If a b End", words(inIt))
        fits(inIt)
        val out = BlockEdits.move(list("If b End c"), 1, 1)
        assertEquals("If End b c", words(out))
        fits(out)
    }

    @Test fun aBlockMovesAsOne() {
        val s = list("a If b Else c End d")
        val down = BlockEdits.move(s, 1, 1)
        assertEquals("a d If b Else c End", words(down))
        fits(down)
        val up = BlockEdits.move(s, 1, -1)
        assertEquals("If b Else c End a d", words(up))
        fits(up)
        assertFalse(BlockEdits.allowed(down, 2).moveDown)   // its End is the last row
    }

    @Test fun aBlockCanMoveIntoAnotherBlock() {
        val s = list("Rep x End If y End")
        val r = BlockEdits.move(s, 3, -1)
        assertEquals("Rep x If y End End", words(r))
        fits(r)
    }

    @Test fun everyMoveKeepsTheListFitting() {
        val s = list("a If b Rep c End Else d End e")
        for (i in s.indices) for (dir in listOf(-1, 1)) fits(BlockEdits.move(s, i, dir))
    }

    @Test fun duplicateCopiesTheWholeBlockWithNewIds() {
        val s = list("If a End b")
        val d = BlockEdits.duplicate(s, 0)
        assertEquals("If a End If a End b", words(d))
        fits(d)
        assertEquals(d.size, d.map { it.id }.toSet().size)
        assertNotEquals(s[1].id, d[4].id)
    }

    @Test fun unwrapKeepsTheStepsInside() {
        val s = list("If a Else b End c")
        val u = BlockEdits.unwrap(s, 0)
        assertEquals("a b c", words(u))
        assertTrue(BlockEdits.allowed(s, 0).unwrap)
        assertFalse(BlockEdits.allowed(s, 0).delete)     // the menu offers unwrap / delete-all instead
    }

    @Test fun deleteBlockTakesEverything() {
        val s = list("x If a Rep b End End c")
        assertEquals("x c", words(BlockEdits.deleteBlock(s, 1)))
        assertEquals("x If a End c", words(BlockEdits.deleteBlock(s, 3)))
        assertEquals(3, BlockEdits.stepsInside(s, 1))    // a, the Repeat, b
    }

    @Test fun otherwiseIsAddedAboveTheEnd() {
        val s = list("If a End")
        assertTrue(BlockEdits.allowed(s, 0).addOtherwise)
        val o = BlockEdits.addOtherwise(s, 0)
        assertEquals("If a Else End", words(o))
        fits(o)
        assertFalse(BlockEdits.allowed(o, 0).addOtherwise)
        assertTrue(BlockEdits.allowed(o, 0).removeOtherwise)
        val rep = list("Rep a End")
        assertEquals(rep, BlockEdits.addOtherwise(rep, 0))
    }

    @Test fun removeOtherwiseTakesItsPart() {
        val s = list("If a Else b c End d")
        assertEquals("If a End d", words(BlockEdits.removeOtherwise(s, 0)))
    }

    @Test fun aBrokenIfCanStillBeDeletedAlone() {
        val s = list("a If b")
        assertTrue(BlockEdits.allowed(s, 1).delete)
        assertEquals("a b", words(BlockEdits.delete(s, 1)))
    }
}
