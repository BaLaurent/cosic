package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Control-map composition (K1) and the scheduling hooks of a hap's context (K8). */
class SchedulingAndValuesTest {
    // ---- jsMerge, unionWithObj, composeOp ----------------------------------------------

    @Test
    fun jsMergeKeepsThePositionOfAKeyThatBothMapsHave() {
        val merged = jsMerge(linkedMapOf("b" to 1.0, "a" to 2.0), linkedMapOf("a" to 3.0, "c" to 4.0))
        assertEquals(listOf("b", "a", "c"), merged.keys.toList())
        assertEquals(3.0, merged["a"])
    }

    @Test
    fun jsMergePutsWholeNumberKeysFirst() {
        val merged = jsMerge(linkedMapOf("x" to 1.0, "10" to 1.0), linkedMapOf("2" to 1.0))
        assertEquals(listOf("2", "10", "x"), merged.keys.toList())
    }

    @Test
    fun unionWithObjCombinesSharedKeysAndKeepsTheOrderOfTheLeftMap() {
        val union = unionWithObj(linkedMapOf("s" to "bd", "n" to 1.0), linkedMapOf("n" to 2.0, "gain" to 0.5)) { x, y -> (x as Double) + (y as Double) }
        assertEquals(listOf("s", "n", "gain"), union.keys.toList())
        assertEquals(3.0, union["n"])
        assertEquals("bd", union["s"])
    }

    @Test
    fun aPlainNumberNextToAControlMapOnlyWarns() {
        val warnings = ArrayList<String>()
        val outer = logger
        logger = { message, _, _ -> warnings.add(message) }
        try {
            val map = linkedMapOf<String, Any?>("s" to "bd")
            assertSame(map, composeOp(map, 2.0) { x, _ -> x })
            assertEquals(listOf("[warn]: Can't do arithmetic on control pattern."), warnings)
        } finally {
            logger = outer
        }
    }

    @Test
    fun composeOpOfPlainValuesCallsTheFunction() {
        assertEquals(3.0, composeOp(1.0, 2.0) { x, y -> (x as Double) + (y as Double) })
        assertEquals("a", composeOp(listOf(1.0), "a") { _, y -> y }, "lists are plain values")
    }

    @Test
    fun composeOpOfMapsIsTheUnion() {
        val result = composeOp(linkedMapOf("n" to 1.0), linkedMapOf("n" to 2.0, "s" to "x")) { x, y -> (x as Double) * 10 + (y as Double) }
        assertEquals(linkedMapOf("n" to 12.0, "s" to "x"), result)
    }

    @Test
    fun aMapAndAnUndefinedValueGetAValueKey() {
        val result = composeOp(linkedMapOf("s" to "bd"), Undefined) { x, _ -> x } as Map<*, *>
        assertEquals(listOf("s", "value"), result.keys.toList())
        assertSame(Undefined, result["value"])
    }

    // ---- hooks ---------------------------------------------------------------------------

    private fun scheduled(hap: Hap) = ScheduledHap(hap, 0L, 0.0, 0.5, 0.0, 1.0)

    @Test
    fun onScheduleAddsHooksInOrderAndOrsDominance() {
        val seen = ArrayList<String>()
        val hap = pure("a").onSchedule({ seen.add("one") }, false).onSchedule({ seen.add("two") }).query(cycleSpan(0)).single()
        assertEquals(2, hap.context.hooks!!.size)
        assertTrue(hap.context.dominant)
        hap.context.hooks!!.forEach { it.run(scheduled(hap)) }
        assertEquals(listOf("one", "two"), seen)
        assertFalse(pure("a").onSchedule({}, false).query(cycleSpan(0)).single().context.dominant)
    }

    @Test
    fun combiningContextsLetsTheSecondOnesHooksAndTagsWin() {
        val one = ScheduleHook { }
        val two = ScheduleHook { }
        val a = HapContext(hooks = listOf(one), dominant = true, tags = listOf("a"))
        assertEquals(listOf(two), a.combine(HapContext(hooks = listOf(two), dominant = false)).hooks)
        assertFalse(a.combine(HapContext(hooks = listOf(two), dominant = false)).dominant)
        assertEquals(listOf(one), a.combine(HapContext()).hooks)
        assertTrue(a.combine(HapContext()).dominant)
        assertEquals(listOf("a"), a.combine(HapContext()).tags)
    }

    @Test
    fun logWritesTheHapAsItIsScheduledWithoutTakingItOverFromTheAudio() {
        val messages = ArrayList<String>()
        val outer = logger
        logger = { message, _, _ -> messages.add(message) }
        try {
            val hap = pure("a").log().query(cycleSpan(0)).single()
            assertFalse(hap.context.dominant)
            hap.context.hooks!!.forEach { it.run(scheduled(hap)) }
            assertEquals(listOf("[hap] 0/1 → 1/1: a"), messages)
            messages.clear()
            val valued = pure("a").fmap { linkedMapOf("value" to it, "note" to "c#") }.logValues().query(cycleSpan(0)).single()
            valued.context.hooks!!.forEach { it.run(scheduled(valued)) }
            assertEquals(listOf("[hap] value:a note:c#"), messages)
        } finally {
            logger = outer
        }
    }

    @Test
    fun queryContextIsOnlyTrueInsideTheSchedulersQuery() {
        assertFalse(QueryContext.fromScheduler)
        QueryContext.asScheduler { assertTrue(QueryContext.fromScheduler) }
        assertFalse(QueryContext.fromScheduler)
    }
}
