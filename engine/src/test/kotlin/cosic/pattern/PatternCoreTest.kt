package cosic.pattern

import cosic.pattern.legacy.*
import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PatternCoreTest {
    // ---- TimeSpan and Hap --------------------------------------------------------------

    @Test
    fun spanCyclesSplitsAtCycleBoundaries() {
        val pieces = span(r(1, 2), r(5, 2)).spanCycles()
        assertEquals(listOf(span(r(1, 2), r(1)), span(r(1), r(2)), span(r(2), r(5, 2))), pieces)
    }

    @Test
    fun spanCyclesKeepsWholeCyclesIntact() {
        assertEquals(listOf(span(r(0), r(1))), span(r(0), r(1)).spanCycles())
        assertEquals(listOf(span(r(3), r(4))), span(r(3), r(4)).spanCycles())
        assertEquals(listOf(span(r(0), r(1)), span(r(1), r(2))), span(r(0), r(2)).spanCycles())
    }

    @Test
    fun spanCyclesKeepsZeroWidthSpans() {
        assertEquals(listOf(span(r(1, 2), r(1, 2))), span(r(1, 2), r(1, 2)).spanCycles())
        assertEquals(listOf(span(r(1), r(1))), span(r(1), r(1)).spanCycles())
    }

    @Test
    fun anInvertedSpanHasNoCycles() {
        assertEquals(emptyList(), span(r(0), r(-2)).spanCycles())
        assertEquals(emptyList(), span(r(1), r(1, 2)).spanCycles())
    }

    @Test
    fun intersectionOfOverlappingSpans() {
        assertEquals(span(r(1, 2), r(1)), span(r(0), r(1)).intersection(span(r(1, 2), r(2))))
    }

    @Test
    fun intersectionOfDisjointOrTouchingSpansIsNull() {
        assertNull(span(r(0), r(1)).intersection(span(r(1), r(2))))
        assertNull(span(r(1), r(2)).intersection(span(r(0), r(1))))
        assertNull(span(r(0), r(1)).intersection(span(r(2), r(3))))
    }

    @Test
    fun intersectionKeepsZeroWidthSpanInsideTheOther() {
        assertEquals(span(r(1, 2), r(1, 2)), span(r(1, 2), r(1, 2)).intersection(span(r(0), r(1))))
        assertEquals(span(r(0), r(0)), span(r(0), r(0)).intersection(span(r(0), r(1))))
        assertEquals(span(r(1, 2), r(1, 2)), span(r(0), r(1)).intersection(span(r(1, 2), r(1, 2))))
    }

    @Test
    fun intersectionDropsZeroWidthSpanAtTheEndOfTheOther() {
        assertNull(span(r(1), r(1)).intersection(span(r(0), r(1))))
        assertNull(span(r(0), r(1)).intersection(span(r(1), r(1))))
    }

    @Test
    fun intersectionEThrowsWhenTheSpansDoNotMeet() {
        assertEquals(span(r(1), r(2)), span(r(0), r(2)).intersectionE(span(r(1), r(3))))
        assertEquals("TimeSpans do not intersect", assertFailsWith<CosicError> { span(r(0), r(1)).intersectionE(span(r(2), r(3))) }.message)
    }

    @Test
    fun withTimeMapsBothEnds() {
        assertEquals(span(r(2), r(4)), span(r(1), r(2)).withTime { it * r(2) })
    }

    @Test
    fun withCycleWorksRelativeToTheCycleOfTheBegin() {
        assertEquals(span(r(3), r(7, 2)), span(r(5, 2), r(11, 4)).withCycle { it * r(2) })
        assertEquals(span(r(2), r(3)), span(r(2), r(5, 2)).withCycle { it * r(2) })
        assertEquals(span(r(0), r(1, 2)), span(r(3), r(7, 2)).cycleArc())
        assertEquals(span(r(1, 2), r(3, 2)), span(r(5, 2), r(7, 2)).cycleArc())
    }

    @Test
    fun hapOnsetAndWhole() {
        val whole = span(r(0), r(1))
        assertTrue(Hap(whole, span(r(0), r(1, 2)), "a").hasOnset())
        assertFalse(Hap(whole, span(r(1, 2), r(1)), "a").hasOnset())
        val continuous = Hap(null, span(r(0), r(1)), "a")
        assertFalse(continuous.hasOnset())
        assertEquals(span(r(0), r(1)), continuous.wholeOrPart())
        assertEquals(whole, Hap(whole, span(r(0), r(1, 2)), "a").wholeOrPart())
    }

    @Test
    fun hapDurationIsTheWholeUnlessTheValueSaysOtherwise() {
        val whole = span(r(0), r(1, 2))
        assertEquals(r(1, 2), Hap(whole, whole, "a").duration)
        assertEquals(r(1, 4), Hap(whole, whole, mapOf("clip" to 0.5)).duration)
        assertEquals(r(3, 4), Hap(whole, whole, mapOf("duration" to 0.75)).duration)
        assertEquals(r(3, 8), Hap(whole, whole, mapOf("duration" to 0.75, "clip" to 0.5)).duration)
        assertEquals(r(1, 4), Hap(whole, whole, mapOf("clip" to 0.5)).endClipped)
    }

    @Test
    fun spanEqualsComparesWholesOnly() {
        val a = Hap(span(r(0), r(1, 2)), span(r(0), r(1, 2)), "a")
        val b = Hap(span(r(0), r(1, 2)), span(r(0), r(1, 4)), "b")
        val c = Hap(span(r(0), r(1, 4)), span(r(0), r(1, 4)), "c")
        assertTrue(a.spanEquals(b))
        assertFalse(a.spanEquals(c))
        val d = Hap(null, span(r(0), r(1, 2)), "d")
        assertTrue(d.spanEquals(Hap(null, span(r(0), r(1)), "e")))
        assertFailsWith<CosicError> { d.spanEquals(a) }
    }

    @Test
    fun combiningContextsAddsLocationsAndLetsTheSecondOneOverrideTheRest() {
        val a = HapContext(listOf(SourceRange(0, 1)), mapOf("x" to 1, "y" to 1), tags = listOf("a"))
        val b = HapContext(listOf(SourceRange(5, 6)), mapOf("y" to 2), tags = null)
        val c = a.combine(b)
        assertEquals(listOf(SourceRange(0, 1), SourceRange(5, 6)), c.locations)
        assertEquals(mapOf("x" to 1, "y" to 2), c.extra)
        assertEquals(listOf("a"), c.tags)
        assertEquals(listOf("b"), a.combine(HapContext(tags = listOf("b"))).tags)
    }

    @Test
    fun stateAndHapTransformationsDropTheStatefulFlag() {
        val whole = span(r(0), r(1))
        val hap = Hap(whole, whole, jsFn(1) { arrayOf("state", "value") }, stateful = true)
        assertFalse(hap.withValue { it }.stateful)
        assertFalse(hap.withSpan { it }.stateful)
        assertFalse(hap.setContext(HapContext.EMPTY).stateful)
    }

    @Test
    fun resolveStateRunsAStatefulFunction() {
        val whole = span(r(0), r(1))
        val counter = jsFn(1) { args -> listOf((args[0] as Double) + 1, args[0]) }
        val hap = Hap(whole, whole, counter, stateful = true)
        val (state2, hap2) = hap.resolveState(10.0)
        val (state3, hap3) = hap.resolveState(state2)
        assertEquals(11.0, state2)
        assertEquals(12.0, state3)
        assertEquals(10.0, hap2.value)
        assertEquals(11.0, hap3.value)
        assertFalse(hap2.stateful)
    }

    // ---- constructors ------------------------------------------------------------------

    @Test
    fun pureRepeatsEveryCycleAndSplitsQueriesAtCycleBoundaries() {
        val haps = pure("a").query(span(r(1, 2), r(3, 2)))
        assertEquals(
            listOf(
                Hap(span(r(0), r(1)), span(r(1, 2), r(1)), "a"),
                Hap(span(r(1), r(2)), span(r(1), r(3, 2)), "a"),
            ),
            haps,
        )
    }

    @Test
    fun pureSupportsZeroWidthQueries() {
        assertEquals(1, pure("a").queryArc(Rational.ZERO, Rational.ZERO).size)
    }

    @Test
    fun silenceHasNoHaps() {
        assertEquals(emptyList(), silence.query(span(r(0), r(4))))
    }

    @Test
    fun fastcatDividesTheCycle() {
        val p = fastcat(pure("a"), pure("b"))
        assertEquals(listOf("a@0-1/2", "b@1/2-1"), p.shownCycle(0))
        assertEquals(listOf("a@1-3/2", "b@3/2-2"), p.shownCycle(1))
    }

    @Test
    fun fastcatOfNothingIsSilence() {
        assertEquals(emptyList(), fastcat(emptyList()).shownCycle())
        assertSame(silence, fastcat(emptyList()))
    }

    @Test
    fun fastcatTakesListsAsSubsequences() {
        assertEquals(fastcat(pure("a"), fastcat(pure("b"), pure("c"))).shownCycle(), fastcat("a", listOf("b", "c")).shownCycle())
    }

    @Test
    fun slowcatAlternatesOnePatternPerCycle() {
        val p = slowcat(pure("a"), pure("b"))
        assertEquals(listOf("a@0-1"), p.shownCycle(0))
        assertEquals(listOf("b@1-2"), p.shownCycle(1))
        assertEquals(listOf("a@2-3"), p.shownCycle(2))
        assertEquals(listOf("b@-1-0"), p.shownCycle(-1))
    }

    @Test
    fun slowcatGivesEachPatternItsOwnCycleCount() {
        val p = slowcat(slowcat(pure("a"), pure("b")), pure("c"))
        val values = (0L..5L).map { p.cycleValues(it).single() }
        assertEquals(listOf("a", "c", "b", "c", "a", "c"), values)
        assertEquals(listOf("b@2-3"), p.shownCycle(2))
    }

    @Test
    fun slowcatSplitsQueriesAcrossCycles() {
        val p = slowcat(pure("a"), pure("b"))
        assertEquals(listOf("a@0-1|1/2-1", "b@1-2|1-3/2"), p.shown(span(r(1, 2), r(3, 2))))
    }

    @Test
    fun slowcatOfOnePatternIsThatPattern() {
        assertEquals(listOf("a@7-8"), slowcat(pure("a")).shownCycle(7))
        val only = pure("a")
        assertSame(only, slowcat(only))
    }

    @Test
    fun slowcatCanBeEmptyAndGoIntoNegativeTime() {
        assertEquals(emptyList(), slowcat(emptyList()).shownCycle())
        val p = fastcat("a", "b", "c").late(r(1000000))
        assertEquals(fastcat("a", "b", "c").shownCycle(), p.shownCycle())
    }

    @Test
    fun slowcatPrimeQueriesWithTheUnshiftedTime() {
        val p = slowcatPrime(listOf(fastcat(0.0, 1.0, 2.0, 3.0).slow(r(2)), fastcat(4.0, 5.0, 6.0, 7.0).slow(r(2)))).fast(r(4))
        assertEquals(listOf(0.0, 1.0, 6.0, 7.0, 0.0, 1.0, 6.0, 7.0), p.cycleValues())
        assertEquals(emptyList(), slowcatPrime(emptyList()).shownCycle())
    }

    @Test
    fun stackPlaysAllPatternsTogether() {
        val p = stack(pure("a"), fastcat(pure("b"), pure("c")))
        assertEquals(listOf("b@0-1/2", "a@0-1", "c@1/2-1"), p.shownCycle())
    }

    @Test
    fun stackTakesListsAsSequences() {
        assertEquals(stack("a", fastcat("b", "c")).shownCycle(), stack("a", listOf("b", "c")).shownCycle())
    }

    @Test
    fun stackKeepsGoingWhenOneOfItsPatternsFails() {
        val seen = ArrayList<Throwable>()
        val outer = errorLogger
        errorLogger = { seen.add(it) }
        try {
            val broken = Pattern { throw CosicError("boom") }
            assertEquals(listOf("a@0-1"), stack(pure("a"), broken).shownCycle())
            assertEquals(listOf("boom"), seen.map { it.message })
        } finally {
            errorLogger = outer
        }
    }

    @Test
    fun stackDoesNotSwallowAQueryThatRanOutOfBudget() {
        val dense = Pattern { throw CosicError(TOO_DENSE) }
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { stack(pure("a"), dense).query(cycleSpan(0)) }.message)
        val deep = Pattern { throw CosicError(TOO_DEEP) }
        assertEquals(TOO_DEEP, assertFailsWith<CosicError> { stack(deep).query(cycleSpan(0)) }.message)
    }

    // ---- mapping and filtering --------------------------------------------------------

    @Test
    fun fmapKeepsStructureAndLocations() {
        val located = pure("a").withLoc(SourceRange(2, 3))
        val haps = located.fmap { (it as String).uppercase() }.query(cycleSpan(0))
        assertEquals(listOf(Hap(span(r(0), r(1)), span(r(0), r(1)), "A", HapContext(listOf(SourceRange(2, 3))))), haps)
    }

    @Test
    fun withQueryTimeAndWithHapTimeScaleTime() {
        val p = pure("a").withQueryTime { it * r(2) }.withHapTime { it / r(2) }
        assertEquals(listOf("a@0-1/2", "a@1/2-1"), p.shownCycle())
    }

    @Test
    fun splitQueriesSplitsAtCycleBoundaries() {
        val whole = Pattern { s -> listOf(Hap(s.span, s.span, 1)) }
        assertEquals(1, whole.query(span(r(0), r(5, 2))).size)
        val split = whole.splitQueries().query(span(r(0), r(5, 2)))
        assertEquals(listOf(span(r(0), r(1)), span(r(1), r(2)), span(r(2), r(5, 2))), split.map { it.part })
    }

    @Test
    fun withQuerySpanMaybeAnswersNothingWhenTheFunctionDoes() {
        val p = pure("a").withQuerySpanMaybe { if (it.begin < r(1)) it else null }
        assertEquals(listOf("a@0-1"), p.shownCycle(0))
        assertEquals(emptyList(), p.shownCycle(1))
    }

    @Test
    fun filterValuesKeepsMatchingHaps() {
        val p = fastcat(pure(1), pure(2), pure(3)).filterValues { (it as Int) % 2 == 1 }
        assertEquals(listOf(1, 3), p.cycleValues())
    }

    @Test
    fun filterHapsCanKeepOnsetsOnly() {
        val onsets = pure("a").onsetsOnly()
        assertEquals(listOf("a@1-2|1-3/2"), onsets.shown(span(r(1, 2), r(3, 2))))
    }

    @Test
    fun removeUndefinedsDropsNullAndUndefined() {
        val p = fastcat(pure(1.0), pure(Undefined), pure(null), pure(2.0)).removeUndefineds()
        assertEquals(listOf(1.0, 2.0), p.cycleValues())
    }

    @Test
    fun discreteOnlyDropsHapsWithoutAWhole() {
        assertEquals(emptyList(), saw.discreteOnly().query(cycleSpan(0)))
        assertEquals(1, stack(saw, pure("a")).discreteOnly().query(cycleSpan(0)).size)
    }

    // ---- context ----------------------------------------------------------------------

    @Test
    fun withLocAddsALocationToEveryHap() {
        val p = fastcat(pure("a"), pure("b")).withLoc(SourceRange(1, 2)).withLoc(SourceRange(5, 6))
        assertEquals(listOf(listOf(SourceRange(1, 2), SourceRange(5, 6))), p.query(cycleSpan(0)).map { it.context.locations }.distinct())
    }

    @Test
    fun aConstantPatternRemembersWhereItWasWritten() {
        val p = pure(2.0).withLoc(SourceRange(4, 5))
        assertEquals(2.0, p.pureValue)
        assertEquals(SourceRange(4, 5), p.pureLoc)
        assertNull(p.fmap { it }.pureValue, "fmap makes a new pattern, which is no longer a constant")
    }

    @Test
    fun zeroIsNotRememberedAsWhereItWasWritten() {
        val p = pure(0.0).withLoc(SourceRange(4, 5))
        assertNull(p.pureLoc)
        assertEquals(0.0, p.pureValue)
        assertNull(pure(Undefined).pureValue)
        assertNull(pure(null).pureValue)
    }

    @Test
    fun withContextKeepsAConstantConstant() {
        val p = pure("a").withContext { it.copy(tags = listOf("x")) }
        assertEquals("a", p.pureValue)
        assertEquals(listOf("x"), p.query(cycleSpan(0)).single().context.tags)
    }

    @Test
    fun setContextAndStripContext() {
        val p = pure("a").withLoc(SourceRange(0, 1))
        assertEquals(HapContext.EMPTY, p.stripContext().query(cycleSpan(0)).single().context)
        assertEquals(listOf(SourceRange(9, 9)), p.setContext(HapContext(listOf(SourceRange(9, 9)))).query(cycleSpan(0)).single().context.locations)
        assertEquals(HapContext.EMPTY, p.firstCycle().single().context)
        assertEquals(listOf(SourceRange(0, 1)), p.firstCycle(withContext = true).single().context.locations)
    }

    // ---- applicative and join ---------------------------------------------------------

    @Test
    fun zipLeftTakesStructureFromTheLeft() {
        val p = fastcat(pure("a"), pure("b")).zipLeft(pure(1)) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1/2", "b1@1/2-1"), p.shownCycle())
    }

    @Test
    fun zipLeftSplitsPartsButKeepsTheLeftWhole() {
        val p = pure("a").zipLeft(fastcat(pure(1), pure(2))) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1|0-1/2", "a2@0-1|1/2-1"), p.shownCycle())
        val q = fastcat(pure("a"), pure("b")).zipLeft(fastcat(pure(1), pure(2), pure(3), pure(4))) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1/2|0-1/4", "a2@0-1/2|1/4-1/2", "b3@1/2-1|1/2-3/4", "b4@1/2-1|3/4-1"), q.shownCycle())
    }

    @Test
    fun zipLeftOnlyCombinesRightHapsInsideTheLeftPart() {
        val p = fastcat(pure("a"), pure("b")).zipLeft(fastcat(pure(1), pure(2), pure(3), pure(4))) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1/2|0-1/4"), p.shown(span(r(0), r(1, 4))))
    }

    @Test
    fun appLeftPutsTheLocationsOfTheValueSideFirst() {
        val a = pure("a").withLoc(SourceRange(0, 1))
        val b = pure("b").withLoc(SourceRange(5, 6))
        val haps = a.zipLeft(b) { x, y -> "$x$y" }.query(cycleSpan(0))
        assertEquals(listOf(SourceRange(5, 6), SourceRange(0, 1)), haps.single().context.locations)
    }

    @Test
    fun appLeftOnAValueThatIsNotAFunctionIsAnError() {
        assertFailsWith<CosicError> { pure("a").appLeft(pure(1)).query(cycleSpan(0)) }
    }

    @Test
    fun innerJoinKeepsInnerWholes() {
        val outer = fastcat(pure(pure("p")), pure(fastcat(pure("q"), pure("r"))))
        assertEquals(listOf("p@0-1|0-1/2", "r@1/2-1"), outer.innerJoin().shownCycle())
    }

    @Test
    fun innerJoinConcatenatesOuterThenInnerLocations() {
        val inner = pure("a").withLoc(SourceRange(0, 1))
        val outer = pure(inner).withLoc(SourceRange(7, 8))
        val haps = outer.innerJoin().query(cycleSpan(0))
        assertEquals(listOf(SourceRange(7, 8), SourceRange(0, 1)), haps.single().context.locations)
    }

    @Test
    fun innerJoinClipsPartsToTheQuery() {
        val outer = pure(fastcat(pure("q"), pure("r")))
        assertEquals(listOf("q@0-1/2|1/4-1/2", "r@1/2-1|1/2-3/4"), outer.innerJoin().shown(span(r(1, 4), r(3, 4))))
    }

    @Test
    fun aValueThatIsNotAPatternCannotBeJoined() {
        assertFailsWith<CosicError> { pure("a").innerJoin().query(cycleSpan(0)) }
        assertFailsWith<CosicError> { pure(3.0).join().query(cycleSpan(0)) }
    }

    // ---- steps ------------------------------------------------------------------------

    @Test
    fun withStepsIsANoOpWhenStepsAreNotTracked() {
        val p = fastcat("a", "b")
        Steps.enabled = false
        try {
            assertSame(p, p.withSteps { it * r(2) })
            assertNull(stack(p, p).steps)
            assertNull(slowcat(p, p).steps)
        } finally {
            Steps.enabled = true
        }
        assertEquals(r(4), p.withSteps { it * r(2) }.steps)
    }

    @Test
    fun setStepsMakesACopyAndNeverTouchesSharedPatterns() {
        val copy = silence.setSteps(r(5))
        assertEquals(r(5), copy.steps)
        assertEquals(r(1), silence.steps)
        assertEquals(r(0), nothing.steps)
    }
}
