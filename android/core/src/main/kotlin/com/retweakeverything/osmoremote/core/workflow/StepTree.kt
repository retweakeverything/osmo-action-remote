package com.retweakeverything.osmoremote.core.workflow

/**
 * Immutable edits on a step tree, used by the workflow builder.
 *
 * A path addresses a block: [2] is the third top-level block, [2, 0] is the
 * first block inside the Repeat at index 2. An empty parent path means the
 * top level.
 */
object StepTree {
    fun get(steps: List<Step>, path: List<Int>): Step {
        require(path.isNotEmpty())
        val step = steps[path.first()]
        return if (path.size == 1) step else get((step as Step.Repeat).steps, path.drop(1))
    }

    fun replace(steps: List<Step>, path: List<Int>, newStep: Step): List<Step> =
        edit(steps, path.dropLast(1)) { it.toMutableList().also { l -> l[path.last()] = newStep } }

    fun remove(steps: List<Step>, path: List<Int>): List<Step> =
        edit(steps, path.dropLast(1)) { it.toMutableList().also { l -> l.removeAt(path.last()) } }

    /** Inserts [step] into the list at [parentPath] (empty = top level), at the end by default. */
    fun insert(steps: List<Step>, parentPath: List<Int>, step: Step, index: Int? = null): List<Step> =
        edit(steps, parentPath) { it.toMutableList().also { l -> l.add(index ?: l.size, step) } }

    /** Moves a block up (-1) or down (+1) within its own list. No-op at the edges. */
    fun move(steps: List<Step>, path: List<Int>, delta: Int): List<Step> {
        val from = path.last()
        return edit(steps, path.dropLast(1)) { list ->
            val to = from + delta
            if (to !in list.indices) list
            else list.toMutableList().also { l -> l.add(to, l.removeAt(from)) }
        }
    }

    /** Number of leaf (non-Repeat) blocks, counting each Repeat body once. */
    fun countBlocks(steps: List<Step>): Int =
        steps.sumOf { if (it is Step.Repeat) 1 + countBlocks(it.steps) else 1 }

    private fun edit(steps: List<Step>, parentPath: List<Int>, change: (List<Step>) -> List<Step>): List<Step> {
        if (parentPath.isEmpty()) return change(steps)
        val i = parentPath.first()
        val repeat = steps[i] as? Step.Repeat ?: error("path does not point into a Repeat block")
        return steps.toMutableList().also {
            it[i] = repeat.copy(steps = edit(repeat.steps, parentPath.drop(1), change))
        }
    }
}
