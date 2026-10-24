package com.retweakeverything.osmoremote.core.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class StepTreeTest {
    private val tree = listOf(
        Step.Wait(1),
        Step.Repeat(3, listOf(Step.TakePhoto(), Step.Wait(5))),
        Step.Highlight,
    )

    @Test fun getsNested() {
        assertEquals(Step.Wait(5), StepTree.get(tree, listOf(1, 1)))
    }

    @Test fun replacesNested() {
        val out = StepTree.replace(tree, listOf(1, 1), Step.Wait(9))
        assertEquals(Step.Wait(9), StepTree.get(out, listOf(1, 1)))
        assertEquals(Step.Wait(5), StepTree.get(tree, listOf(1, 1))) // original untouched
    }

    @Test fun insertsIntoRepeatAndTopLevel() {
        val a = StepTree.insert(tree, listOf(1), Step.Highlight)
        assertEquals(3, (a[1] as Step.Repeat).steps.size)
        val b = StepTree.insert(tree, emptyList(), Step.Sleep, index = 0)
        assertEquals(Step.Sleep, b[0])
    }

    @Test fun removesNested() {
        val out = StepTree.remove(tree, listOf(1, 0))
        assertEquals(listOf<Step>(Step.Wait(5)), (out[1] as Step.Repeat).steps)
    }

    @Test fun movesWithinListAndIgnoresEdges() {
        assertEquals(listOf(Step.Wait(5), Step.TakePhoto()), (StepTree.move(tree, listOf(1, 1), -1)[1] as Step.Repeat).steps)
        assertEquals(tree, StepTree.move(tree, listOf(0), -1))
        assertEquals(tree, StepTree.move(tree, listOf(2), +1))
    }

    @Test fun countsBlocks() {
        assertEquals(5, StepTree.countBlocks(tree))
    }
}
