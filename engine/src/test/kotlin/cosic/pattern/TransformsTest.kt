package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TransformsTest {
    private val ab = fastcat(pure("a"), pure("b"))
    private val abc = fastcat(pure("a"), pure("b"), pure("c"))
    private val abcd = fastcat(pure("a"), pure("b"), pure("c"), pure("d"))
    private val sixteen = fastcat((1..16).map { pure(it) })

    // ---- time -------------------------------------------------------------------------

    @Test
    fun fastSpeedsUp() {
        assertEquals(listOf("a@0-1/4", "b@1/4-1/2", "a@1/2-3/4", "b@3/4-1"), ab.fast(r(2)).shownCycle())
    }

    @Test
    fun fastAcceptsFractions() {
        assertEquals(listOf("a@0-2/3", "a@2/3-4/3", "a@4/3-2"), pure("a").fast(r(3, 2)).shown(span(r(0), r(2))))
    }

    @Test
    fun fastByZeroIsSilent() {
        assertEquals(emptyList(), ab.fast(r(0)).shownCycle())
    }

    @Test
    fun fastByNegativeFactorPlaysBackwards() {
        assertEquals(listOf("b@0-1/4", "a@1/4-1/2", "b@1/2-3/4", "a@3/4-1"), ab.fast(r(-2)).shownCycle())
    }

    @Test
    fun slowStretches() {
        assertEquals(listOf("a@0-1", "b@1-2"), ab.slow(r(2)).shown(span(r(0), r(2))))
        assertEquals(emptyList(), ab.slow(r(0)).shownCycle())
    }

    @Test
    fun earlyAndLateShiftTime() {
        assertEquals(listOf("b@0-1/4", "c@1/4-1/2", "d@1/2-3/4", "a@3/4-1"), abcd.early(r(1, 4)).shownCycle())
        assertEquals(listOf("d@0-1/4", "a@1/4-1/2", "b@1/2-3/4", "c@3/4-1"), abcd.late(r(1, 4)).shownCycle())
    }

    @Test
    fun revReversesEachCycle() {
        assertEquals(listOf("c@0-1/3", "b@1/3-2/3", "a@2/3-1"), abc.rev().shownCycle(0))
        assertEquals(listOf("c@1-4/3", "b@4/3-5/3", "a@5/3-2"), abc.rev().shownCycle(1))
    }

    @Test
    fun revClipsPartsOfPartialQueries() {
        assertEquals(listOf("c@0-1/3", "b@1/3-2/3|1/3-1/2"), abc.rev().shown(span(r(0), r(1, 2))))
    }

    @Test
    fun iterRotatesByOneStepPerCycle() {
        val values = (0L..4L).map { abcd.iter(4).cycleValues(it).joinToString("") }
        assertEquals(listOf("abcd", "bcda", "cdab", "dabc", "abcd"), values)
    }

    @Test
    fun iterBackRotatesTheOtherWay() {
        val values = (0L..4L).map { abcd.iterBack(4).cycleValues(it).joinToString("") }
        assertEquals(listOf("abcd", "dabc", "cdab", "bcda", "abcd"), values)
    }

    @Test
    fun plySplitsEveryHapIntoRepeats() {
        assertEquals(listOf("a@0-1/4", "a@1/4-1/2", "b@1/2-3/4", "b@3/4-1"), ab.ply(2).shownCycle())
        assertEquals(
            listOf("a@0-1/6", "a@1/6-1/3", "a@1/3-1/2", "b@1/2-2/3", "b@2/3-5/6", "b@5/6-1"),
            ab.ply(3).shownCycle(),
        )
    }

    @Test
    fun plyKeepsPartsInsideTheQuery() {
        assertEquals(listOf("a@0-1/4"), ab.ply(2).shown(span(r(0), r(1, 4))))
        assertEquals(listOf("a@1/4-1/2|3/8-1/2", "b@1/2-3/4|1/2-5/8"), ab.ply(2).shown(span(r(3, 8), r(5, 8))))
    }

    @Test
    fun insideAppliesAFunctionWithinSlowedTime() {
        assertEquals(listOf("b", "a", "d", "c"), abcd.inside(r(2)) { it.rev() }.cycleValues())
    }

    @Test
    fun outsideAppliesAFunctionAcrossSeveralCycles() {
        val p = slowcat(fastcat(pure("a"), pure("b")), fastcat(pure("c"), pure("d")))
        val reversed = p.outside(r(2)) { it.rev() }
        assertEquals(listOf("d@0-1/2", "c@1/2-1"), reversed.shownCycle(0))
        assertEquals(listOf("b@1-3/2", "a@3/2-2"), reversed.shownCycle(1))
    }

    @Test
    fun withinAppliesToHapsStartingInsideTheWindow() {
        val replaced = fastcat(pure("w"), pure("x"), pure("y"), pure("z"))
        assertEquals(listOf("a", "b", "y", "z"), abcd.within(r(1, 2), r(1), replaced).cycleValues())
    }

    @Test
    fun swingByDelaysEveryOtherEventInsideEachSlot() {
        val eighths = fastcat(listOf("a", "b", "c", "d", "e", "f", "g", "h").map { pure(it) })
        assertEquals(
            listOf(
                "h@-1/24-1/12|0-1/12", "a@0-1/8", "b@5/24-1/3", "c@1/4-3/8", "d@11/24-7/12",
                "e@1/2-5/8", "f@17/24-5/6", "g@3/4-7/8", "h@23/24-13/12|23/24-1",
            ),
            eighths.swingBy(r(1, 3), r(4)).shownCycle(),
        )
    }

    @Test
    fun swingByLeavesEventsOnTheSlotStartAlone() {
        assertEquals(abcd.shownCycle(), abcd.swingBy(r(1, 3), r(4)).shownCycle())
    }

    @Test
    fun offStacksAShiftedCopy() {
        val p = pure("a").off(r(1, 4)) { it }
        assertEquals(listOf("a@-3/4-1/4|0-1/4", "a@0-1", "a@1/4-5/4|1/4-1"), p.shownCycle())
    }

    // ---- structure --------------------------------------------------------------------

    @Test
    fun structTakesStructureFromTruthyHaps() {
        val structure = fastcat(pure(1.0), pure(0.0), pure(1.0), pure(1.0))
        assertEquals(listOf("a@0-1/4", "a@1/2-3/4", "a@3/4-1"), pure("a").struct(structure).shownCycle())
    }

    @Test
    fun structTakesEveryNonEmptyStringAsTruthy() {
        val structure = fastcat(pure("t"), pure("f"), pure("false"), pure("x"))
        assertEquals(listOf("a@0-1/4", "a@1/4-1/2", "a@1/2-3/4", "a@3/4-1"), pure("a").struct(structure).shownCycle())
        assertEquals(listOf("a@0-1/4", "a@3/4-1"), pure("a").struct(fastcat(pure("t"), pure(0.0), pure(""), pure("x"))).shownCycle())
    }

    @Test
    fun maskSilencesPartsWhereTheMaskIsFalse() {
        val half = fastcat(pure(1.0), pure(0.0))
        assertEquals(listOf("a@0-1/4", "b@1/4-1/2"), abcd.mask(half).shownCycle())
        assertEquals(listOf("a@0-1|0-1/2"), pure("a").mask(half).shownCycle())
    }

    @Test
    fun segmentSamplesAtAFixedRate() {
        assertEquals(listOf("a@0-1/2", "a@1/2-1"), pure("a").segment(r(2)).shownCycle())
        assertEquals(listOf(0.25, 0.75), saw.segment(r(2)).cycleValues())
    }

    @Test
    fun chunkTransformsOneSliceAtATime() {
        val upper = abcd.fmap { (it as String).uppercase() }
        val values = (0L..4L).map { abcd.chunk(4, upper).cycleValues(it).joinToString("") }
        assertEquals(listOf("Abcd", "aBcd", "abCd", "abcD", "Abcd"), values)
    }

    @Test
    fun everyAppliesOnTheFirstCycleOfEachGroup() {
        val p = pure("a").every(3, pure("a").fast(r(2)))
        assertEquals(listOf(2, 1, 1, 2, 1), (0L..4L).map { p.shownCycle(it).size })
    }

    @Test
    fun everyWithoutAPositiveCountChangesNothing() {
        assertEquals(abcd.shownCycle(), abcd.every(0, abcd.rev()).shownCycle())
        assertEquals(abcd.shownCycle(), abcd.every(-2, abcd.rev()).shownCycle())
    }

    @Test
    fun lastOfAppliesOnTheLastCycleOfEachGroup() {
        val p = pure("a").lastOf(3, pure("a").fast(r(2)))
        assertEquals(listOf(1, 1, 2, 1, 1, 2), (0L..5L).map { p.shownCycle(it).size })
    }

    @Test
    fun palindromeStyleLastOfReversesEverySecondCycle() {
        val p = abc.lastOf(2, abc.rev())
        assertEquals("abc", p.cycleValues(0).joinToString(""))
        assertEquals("cba", p.cycleValues(1).joinToString(""))
        assertEquals("abc", p.cycleValues(2).joinToString(""))
    }

    // ---- randomness -------------------------------------------------------------------

    @Test
    fun degradeByDropsAboutTheGivenShare() {
        val kept = sixteen.degradeBy(0.5).cycleValues()
        assertTrue(kept.size in 3..13, "kept ${kept.size}")
        assertEquals(kept, sixteen.degradeBy(0.5).cycleValues())
        assertEquals(16, sixteen.degradeBy(0.0).cycleValues().size)
        assertEquals(0, sixteen.degradeBy(1.0).cycleValues().size)
    }

    @Test
    fun degradeBySeedsDecideIndependently() {
        assertNotEquals(sixteen.degradeBy(0.5, 0).cycleValues(), sixteen.degradeBy(0.5, 1).cycleValues())
    }

    @Test
    fun degradeByUsesTheWholeStartNotTheQuery() {
        val whole = sixteen.degradeBy(0.5).cycleValues().map { it as Int }.sorted()
        val halves = (sixteen.degradeBy(0.5).query(span(r(0), r(1, 2))) + sixteen.degradeBy(0.5).query(span(r(1, 2), r(1))))
            .map { it.value as Int }
            .sorted()
        assertEquals(whole, halves)
    }

    @Test
    fun sometimesByAppliesTheFunctionToTheComplementOfWhatIsKept() {
        val p = sixteen.sometimesBy(0.5) { it.fmap { v -> -(v as Int) } }
        val values = p.cycleValues().map { it as Int }
        assertEquals((1..16).toList(), values.map { Math.abs(it) }.sorted())
        assertTrue(values.any { it < 0 } && values.any { it > 0 })
        val kept = sixteen.degradeBy(0.5).cycleValues().map { it as Int }
        assertEquals(kept.sorted(), values.filter { it > 0 }.sorted())
    }

    @Test
    fun someCyclesByDecidesPerCycle() {
        val p = pure("a").someCyclesBy(0.5, pure("b"))
        val values = (0L..39L).map { p.cycleValues(it).single() }
        val expected = (0L..39L).map { if (timeRand(r(it), 1) < 0.5) "b" else "a" }
        assertEquals(expected, values)
        assertTrue("a" in values && "b" in values)
    }

    @Test
    fun randomChoicePicksOneSequencePerCycle() {
        val p = randomChoice(listOf(pure("a"), pure("b"), pure("c")), 1000)
        val picks = (0L..59L).map { p.cycleValues(it).single() }
        assertEquals(setOf("a", "b", "c"), picks.toSet())
        assertEquals(picks, (0L..59L).map { p.cycleValues(it).single() })
        val c = 5L
        val expected = listOf("a", "b", "c")[Math.floor(timeRand(r(c), 1000) * 3).toInt()]
        assertEquals(expected, picks[c.toInt()])
    }

    // ---- edge cases -------------------------------------------------------------------

    @Test
    fun iterWithoutAPositiveCountIsHandled() {
        assertEquals(abcd.shownCycle(1), abcd.iter(0).shownCycle(1))
        assertEquals(abcd.iterBack(4).shownCycle(1), abcd.iter(-4).shownCycle(1))
        assertEquals(abcd.iter(4).shownCycle(1), abcd.iterBack(-4).shownCycle(1))
    }

    @Test
    fun plyOfNothingIsSilent() {
        assertEquals(emptyList(), ab.ply(0).shownCycle())
        assertEquals(emptyList(), ab.ply(-2).shownCycle())
    }

    @Test
    fun chunkWithoutAPositiveCountChangesNothing() {
        assertEquals(abcd.shownCycle(), abcd.chunk(0, abcd.rev()).shownCycle())
    }

    @Test
    fun chunkOnlyBuildsTheSliceItNeeds() {
        assertEquals(4, abcd.chunk(10_000, abcd.rev()).cycleValues(0).size)
    }

    @Test
    fun randomChoiceOfNothingIsSilent() {
        assertEquals(emptyList(), randomChoice(emptyList(), 1000).shownCycle())
    }

    @Test
    fun timecatWithoutAnyWeightIsSilent() {
        assertEquals(emptyList(), timecat(listOf(r(0) to pure("a"), r(0) to pure("b"))).shownCycle())
        assertEquals(emptyList(), timecat(emptyList()).shownCycle())
    }

    @Test
    fun splitWholesTreatsAContinuousHapAsOneWhole() {
        assertEquals(listOf("0@0-1/2", "1@1/2-1"), saw.splitWholes(2) { _, i -> "$i" }.shownCycle())
    }

    @Test
    fun zeroWidthQueriesFindTheEventStartingThere() {
        assertEquals(listOf("b@1/2-1|1/2-1/2"), ab.shown(span(r(1, 2), r(1, 2))))
        assertEquals(listOf("a@1-3/2|1-1"), ab.shown(span(r(1), r(1))))
        assertEquals(listOf("a@0-1|1/2-1/2"), pure("a").shown(span(r(1, 2), r(1, 2))))
    }

    // ---- timecat and compress (legacy mini-notation and arrange) -----------------------------

    @Test
    fun timecatGivesEachStepASliceByWeight() {
        val p = timecat(listOf(r(3) to pure("a"), r(1) to pure("b")))
        assertEquals(listOf("a@0-3/4", "b@3/4-1"), p.shownCycle(0))
        assertEquals(listOf("a@1-7/4", "b@7/4-2"), p.shownCycle(1))
        val q = timecat(listOf(r(1) to pure("a"), r(2) to pure("b"), r(1) to pure("c")))
        assertEquals(listOf("a@0-1/4", "b@1/4-3/4", "c@3/4-1"), q.shownCycle(0))
    }

    @Test
    fun compressSqueezesACycleIntoASlice() {
        assertEquals(listOf("a@1/4-3/4"), pure("a").compress(r(1, 4), r(3, 4)).shownCycle())
        assertEquals(
            listOf("a@1/4-1/2", "b@1/2-3/4"),
            fastcat(pure("a"), pure("b")).compress(r(1, 4), r(3, 4)).shownCycle(),
        )
    }

    @Test
    fun compressClipsPartsButNotWholesToTheSlice() {
        val slowed = pure("a").slow(r(2)).compress(r(1, 4), r(3, 4))
        assertEquals(listOf("a@1/4-5/4|1/4-3/4"), slowed.shownCycle())
    }

    @Test
    fun compressAnswersPartialQueries() {
        assertEquals(
            listOf("a@1/4-1/2"),
            fastcat(pure("a"), pure("b")).compress(r(1, 4), r(3, 4)).shown(span(r(0), r(1, 2))),
        )
    }

    @Test
    fun compressOutsideTheCycleIsSilent() {
        assertEquals(emptyList(), pure("a").compress(r(1, 2), r(1, 4)).shownCycle())
        assertEquals(emptyList(), pure("a").compress(r(1, 2), r(3, 2)).shownCycle())
        assertEquals(emptyList(), pure("a").compress(r(-1, 2), r(1, 2)).shownCycle())
    }
}
