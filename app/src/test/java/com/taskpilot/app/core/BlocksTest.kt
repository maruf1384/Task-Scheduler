package com.taskpilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocksTest {
    private fun b(vararg r: Role) = Blocks.ofRoles(r.toList())
    private val S = Role.STEP
    private val IF = Role.IF
    private val ELSE = Role.ELSE
    private val END = Role.END
    private val REP = Role.REPEAT

    @Test fun plainListHasNoBlocks() {
        val x = b(S, S, Role.STOP)
        assertTrue(x.ok)
        assertEquals(listOf(0, 0, 0), (0..2).map { x.depth(it) })
        assertNull(x.firstProblem())
    }

    @Test fun ifOtherwiseEnd() {
        val x = b(IF, S, ELSE, S, END, S)
        assertTrue(x.ok)
        assertEquals(4, x.end(0))
        assertEquals(2, x.otherwise(0))
        assertEquals(4, x.end(2))
        assertEquals(0, x.opener(2))
        assertEquals(0, x.opener(4))
        assertEquals(listOf(0, 1, 0, 1, 0, 0), (0..5).map { x.depth(it) })
        assertEquals("End of If", x.endLabel(4))
        assertEquals(0..4, x.blockRange(0))
    }

    @Test fun nestedRepeatInsideIf() {
        val x = b(IF, REP, S, END, END)
        assertTrue(x.ok)
        assertEquals(4, x.end(0))
        assertEquals(3, x.end(1))
        assertEquals(listOf(0, 1, 2, 1, 0), (0..4).map { x.depth(it) })
        assertEquals("End of Repeat", x.endLabel(3))
        assertEquals("End of If", x.endLabel(4))
    }

    @Test fun strayEnd() {
        val x = b(S, END)
        assertFalse(x.ok)
        assertEquals("Step 2: This End has no If or Repeat above it", x.firstProblem())
        assertEquals("End", x.endLabel(1))
    }

    @Test fun missingEnd() {
        val x = b(IF, S, REP, S)
        assertEquals(setOf(0, 2), x.problems.keys)
        assertEquals("This If has no End", x.problems[0])
        assertEquals("This Repeat has no End", x.problems[2])
        assertEquals("Step 1: This If has no End", x.firstProblem())
    }

    @Test fun otherwiseOutsideIf() {
        assertEquals("“Otherwise” must be inside an If", b(ELSE).problems[0])
        assertEquals("“Otherwise” must be inside an If", b(REP, ELSE, END).problems[1])
    }

    @Test fun twoOtherwise() {
        val x = b(IF, ELSE, ELSE, END)
        assertEquals("This If already has an “Otherwise”", x.problems[2])
        assertEquals(1, x.otherwise(0))
    }

    @Test fun outOfRangeIsSafe() {
        val x = b(S)
        assertEquals(-1, x.end(5))
        assertEquals(0, x.depth(-1))
        assertNull(x.blockRange(0))
    }
}
