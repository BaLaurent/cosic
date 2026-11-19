package cosic.mini

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.cycleSpan
import cosic.pattern.cycleValues
import cosic.pattern.r
import cosic.pattern.shown
import cosic.pattern.shownCycle
import cosic.pattern.span
import cosic.pattern.timeRand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiniTest {
    private fun mini(src: String, base: Int = 0): Pattern<Any> = parseMini(src, base)

    private fun Pattern<Any>.picks(cycles: LongRange): List<Any> = cycles.map { cycleValues(it).single() }

    private fun error(src: String, base: Int = 0): CosicError = assertFailsWith<CosicError> { mini(src, base) }

    // ---- sequences and groups ---------------------------------------------------------

    @Test
    fun aSequenceDividesTheCycle() {
        assertEquals(listOf("bd@0-1/2", "sd@1/2-1"), mini("bd sd").shownCycle())
        assertEquals(listOf("bd@1-3/2", "sd@3/2-2"), mini("bd sd").shownCycle(1))
    }

    @Test
    fun aSingleStepIsTheWholeCycle() {
        assertEquals(listOf("bd@0-1"), mini("bd").shownCycle())
    }

    @Test
    fun squareBracketsGroupSteps() {
        assertEquals(listOf("a@0-1/2", "b@1/2-3/4", "c@3/4-1"), mini("a [b c]").shownCycle())
    }

    @Test
    fun angleBracketsAlternatePerCycle() {
        val p = mini("<a b>")
        assertEquals(listOf<Any>("a", "b", "a"), p.picks(0L..2L))
        assertEquals(listOf("b@1-2"), p.shownCycle(1))
    }

    @Test
    fun angleBracketsWithCommasStackAlternations() {
        val p = mini("<a b, c d e>")
        assertEquals(listOf<Any>("a", "c"), p.cycleValues(0))
        assertEquals(listOf<Any>("b", "d"), p.cycleValues(1))
        assertEquals(listOf<Any>("a", "e"), p.cycleValues(2))
        assertEquals(listOf<Any>("b", "c"), p.cycleValues(3))
    }

    @Test
    fun angleBracketsExpandRepeatsAndIgnoreWeights() {
        assertEquals(listOf<Any>("a", "a", "b", "a"), mini("<a!2 b>").picks(0L..3L))
        assertEquals(listOf<Any>("a", "b", "a"), mini("<a@3 b>").picks(0L..2L))
    }

    @Test
    fun commasStackSequences() {
        assertEquals(listOf("a@0-1", "b@0-1"), mini("a, b").shownCycle())
        assertEquals(listOf("a@0-1/2", "c@0-1/2", "b@1/2-1", "d@1/2-1"), mini("a b, c d").shownCycle())
    }

    @Test
    fun restsTakeUpTimeWithoutSound() {
        assertEquals(listOf("a@1/2-1"), mini("~ a").shownCycle())
        assertEquals(listOf("a@1/2-1"), mini("- a").shownCycle())
        assertEquals(listOf("a@0-1/3", "b@2/3-1"), mini("a ~ b").shownCycle())
    }

    @Test
    fun emptyPatternsAreSilent() {
        assertEquals(emptyList(), mini("").shownCycle())
        assertEquals(emptyList(), mini("   ").shownCycle())
        assertEquals(emptyList(), mini("~").shownCycle())
        assertEquals(emptyList(), mini("[]").shownCycle())
        assertEquals(listOf("a@0-1/3", "b@2/3-1"), mini("a [] b").shownCycle())
    }

    @Test
    fun anyWhitespaceSeparatesSteps() {
        assertEquals(listOf("bd@0-1/3", "sd@1/3-2/3", "hh@2/3-1"), mini("  bd\n  sd\t hh ").shownCycle())
    }

    // ---- modifiers --------------------------------------------------------------------

    @Test
    fun starSpeedsAStepUp() {
        assertEquals(listOf("a@0-1/4", "a@1/4-1/2", "b@1/2-3/4", "c@3/4-1"), mini("a*2 [b c]").shownCycle())
    }

    @Test
    fun slashSlowsAStepDown() {
        assertEquals(listOf("a@0-2"), mini("a/2").shown(span(r(0), r(2))))
        assertEquals(listOf("a@0-2|1-2"), mini("a/2").shownCycle(1))
    }

    @Test
    fun factorsMayBePatterns() {
        val p = mini("a*<2 3>")
        assertEquals(listOf("a@0-1/2", "a@1/2-1"), p.shownCycle(0))
        assertEquals(listOf("a@1-4/3", "a@4/3-5/3", "a@5/3-2"), p.shownCycle(1))
        assertEquals(mini("a*2").shownCycle(), mini("a*[2 2]").shownCycle())
        assertEquals(listOf("a@0-1/2", "b@1/2-1"), mini("<a b>*2").shownCycle(0))
    }

    @Test
    fun weightsStretchSteps() {
        assertEquals(listOf("a@0-3/4", "b@3/4-1"), mini("a@3 b").shownCycle())
        assertEquals(listOf("a@0-3/5", "b@3/5-1"), mini("a@1.5 b").shownCycle())
    }

    @Test
    fun bangRepeatsSteps() {
        assertEquals(listOf("a@0-1/3", "a@1/3-2/3", "a@2/3-1"), mini("a!3").shownCycle())
        assertEquals(listOf("a@0-1/3", "a@1/3-2/3", "b@2/3-1"), mini("a! b").shownCycle())
        assertEquals(listOf("a@0-1/3", "a@1/3-2/3", "b@2/3-1"), mini("a ! b").shownCycle())
        assertEquals(listOf("a@0-1/4", "b@1/4-1/2", "a@1/2-3/4", "b@3/4-1"), mini("[a b]!2").shownCycle())
    }

    @Test
    fun underscoreElongatesTheStepBefore() {
        assertEquals(listOf("a@0-2/3", "b@2/3-1"), mini("a _ b").shownCycle())
        assertEquals(listOf("a@0-3/4", "b@3/4-1"), mini("a _ _ b").shownCycle())
        assertEquals(listOf("a@0-3/4", "b@3/4-1"), mini("a@2 _ b").shownCycle())
    }

    @Test
    fun underscoresAndDashesInsideAtomsStayAtoms() {
        assertEquals(listOf<Any>("bd_2", -1.0, "a-b"), mini("bd_2 -1 a-b").cycleValues())
    }

    @Test
    fun questionMarkDropsHapsDeterministically() {
        val p = mini("a?")
        val played = (0L..31L).filter { p.cycleValues(it).isNotEmpty() }
        assertEquals(played, (0L..31L).filter { mini("a?").cycleValues(it).isNotEmpty() })
        assertEquals((0L..31L).filter { timeRand(r(it), 0) >= 0.5 }, played)
        assertTrue(played.isNotEmpty() && played.size < 32)
    }

    @Test
    fun questionMarkTakesAProbability() {
        val p = mini("a?0.9")
        assertEquals((0L..31L).filter { timeRand(r(it), 0) >= 0.9 }, (0L..31L).filter { p.cycleValues(it).isNotEmpty() })
    }

    @Test
    fun questionMarksInOnePatternUseSeparateSeeds() {
        // seed 0 keeps cycle 0 (timeRand 0.88), seed 1 drops it (timeRand 0.43)
        assertEquals(listOf("a@0-1/2"), mini("a? a?").shownCycle())
    }

    @Test
    fun questionMarkJudgesEachFastHapOnItsOwn() {
        val kept = mini("a*8?").query(cycleSpan(0)).map { it.whole!!.begin }
        assertEquals((0L..7L).filter { timeRand(r(it, 8), 0) >= 0.5 }.map { r(it, 8) }, kept.sorted())
        assertTrue(kept.isNotEmpty() && kept.size < 8)
    }

    @Test
    fun parenthesesMakeAEuclideanRhythm() {
        assertEquals(listOf("x@0-1/8", "x@3/8-1/2", "x@3/4-7/8"), mini("x(3,8)").shownCycle())
        assertEquals(listOf("x@1/8-1/4", "x@1/2-5/8", "x@3/4-7/8"), mini("x(3,8,2)").shownCycle())
        assertEquals(listOf<Any>("bd", "bd", "bd"), mini("bd(3,8)").cycleValues())
    }

    @Test
    fun euclideanArgumentsMayBePatterns() {
        val p = mini("x(<3 5>,8)")
        assertEquals(3, p.cycleValues(0).size)
        assertEquals(5, p.cycleValues(1).size)
        assertEquals(3, mini("x([3 3],8)").cycleValues(0).size)
    }

    @Test
    fun polymeterFollowsTheFirstSequenceByDefault() {
        assertEquals(listOf("a@0-1/3", "b@1/3-2/3", "c@2/3-1"), mini("{a b c}").shownCycle())
        assertEquals(
            listOf("a@0-1/3", "d@0-1/3", "b@1/3-2/3", "e@1/3-2/3", "c@2/3-1", "d@2/3-1"),
            mini("{a b c, d e}").shownCycle(),
        )
    }

    @Test
    fun percentSetsThePolymeterStepCount() {
        val p = mini("{a b c}%4")
        assertEquals(listOf("a@0-1/4", "b@1/4-1/2", "c@1/2-3/4", "a@3/4-1"), p.shownCycle(0))
        assertEquals(listOf("b@1-5/4", "c@5/4-3/2", "a@3/2-7/4", "b@7/4-2"), p.shownCycle(1))
        val patterned = mini("{a b}%<2 4>")
        assertEquals(2, patterned.cycleValues(0).size)
        assertEquals(4, patterned.cycleValues(1).size)
    }

    @Test
    fun pipeChoosesOneSequencePerCycle() {
        val p = mini("a | b | c")
        val expected = (0L..39L).map { c -> listOf<Any>("a", "b", "c")[Math.floor(timeRand(r(c), 1000) * 3).toInt()] }
        assertEquals(expected, p.picks(0L..39L))
        assertEquals(setOf<Any>("a", "b", "c"), expected.toSet())
    }

    @Test
    fun eachPipeGroupHasItsOwnSeed() {
        val p = mini("[a|b] [c|d]")
        for (c in 0L..19L) {
            val first = listOf<Any>("a", "b")[Math.floor(timeRand(r(c), 1000) * 2).toInt()]
            val second = listOf<Any>("c", "d")[Math.floor(timeRand(r(c), 1001) * 2).toInt()]
            assertEquals(listOf(first, second), p.cycleValues(c), "cycle $c")
        }
    }

    @Test
    fun pipeGroupsAreNumberedByTheirFirstPipe() {
        val p = mini("x | [a | b]")
        for (c in 0L..19L) {
            val outer = Math.floor(timeRand(r(c), 1000) * 2).toInt()
            val inner = listOf<Any>("a", "b")[Math.floor(timeRand(r(c), 1001) * 2).toInt()]
            assertEquals(listOf(if (outer == 0) "x" else inner), p.cycleValues(c), "cycle $c")
        }
    }

    // ---- atoms and locations ----------------------------------------------------------

    @Test
    fun numbersAreDoublesAndEverythingElseIsAString() {
        assertEquals(listOf<Any>(1.0, 2.5, 0.5, -1.0, 3.0), mini("1 2.5 .5 -1 3.").cycleValues())
        assertEquals(listOf<Any>("bd:3", "C:major", "eb3", "e3", "c#4"), mini("bd:3 C:major eb3 e3 c#4").cycleValues())
    }

    @Test
    fun atomLocationsIncludeTheBaseOffset() {
        val haps: List<Hap<Any>> = mini("bd sd", 10).query(cycleSpan(0)).sortedBy { it.part.begin }
        assertEquals(listOf(SourceRange(10, 12)), haps[0].locations)
        assertEquals(listOf(SourceRange(13, 15)), haps[1].locations)
    }

    @Test
    fun nestedAtomsKeepTheirOwnLocations() {
        val haps = mini("a [b c]").query(cycleSpan(0)).sortedBy { it.part.begin }
        assertEquals(listOf(SourceRange(0, 1)), haps[0].locations)
        assertEquals(listOf(SourceRange(3, 4)), haps[1].locations)
        assertEquals(listOf(SourceRange(5, 6)), haps[2].locations)
    }

    @Test
    fun constantModifierNumbersAreNotHighlighted() {
        assertEquals(listOf(listOf(SourceRange(0, 2)), listOf(SourceRange(0, 2))), mini("bd*2").query(cycleSpan(0)).map { it.locations })
        assertEquals(listOf(listOf(SourceRange(0, 2))), mini("bd(1,4)").query(cycleSpan(0)).map { it.locations })
    }

    @Test
    fun patternedModifierNumbersAreHighlightedAfterTheStep() {
        val p = mini("bd*<2 3>")
        assertEquals(listOf(SourceRange(0, 2), SourceRange(4, 5)), p.query(cycleSpan(0)).first().locations)
        assertEquals(listOf(SourceRange(0, 2), SourceRange(6, 7)), p.query(cycleSpan(1)).first().locations)
    }

    // ---- errors -----------------------------------------------------------------------

    @Test
    fun unexpectedCharactersReportTheirRange() {
        val e = error("bd ]")
        assertEquals("Mini-notation: unexpected ']'", e.message)
        assertEquals(SourceRange(3, 4), e.range)
        assertEquals(SourceRange(13, 14), error("bd ]", 10).range)
    }

    @Test
    fun unmatchedOpeningBracketsPointAtTheBracket() {
        val e = error("a [b c")
        assertEquals("Mini-notation: missing ']'", e.message)
        assertEquals(SourceRange(2, 3), e.range)
        assertEquals("Mini-notation: missing '>'", error("<a b").message)
        assertEquals("Mini-notation: missing '}'", error("{a b").message)
        assertEquals("Mini-notation: missing ')'", error("a(3,8").message)
    }

    @Test
    fun strayClosingBracketsAreRejected() {
        assertEquals("Mini-notation: unexpected ')'", error("a b)").message)
        assertEquals("Mini-notation: unexpected '>'", error("[a b>").message)
        assertEquals("Mini-notation: unexpected ']'", error("[a b]]").message)
    }

    @Test
    fun modifiersNeedTheirArguments() {
        assertEquals("Mini-notation: unexpected end of pattern", error("a*").message)
        assertEquals("Mini-notation: unexpected end of pattern", error("a@").message)
        assertEquals("Mini-notation: unexpected end of pattern", error("{a b}%").message)
        val e = error("a*x")
        assertEquals("Mini-notation: unexpected 'x'", e.message)
        assertEquals(SourceRange(2, 3), e.range)
        assertEquals("Mini-notation: unexpected 'x'", error("a@x").message)
    }

    @Test
    fun euclidNeedsTwoOrThreeArguments() {
        assertEquals("Mini-notation: unexpected ')'", error("a(3)").message)
        assertEquals("Mini-notation: unexpected ','", error("a(3,8,1,2)").message)
    }

    @Test
    fun stepsCannotStartWithAModifier() {
        assertEquals("Mini-notation: unexpected '*'", error("*2").message)
        assertEquals("Mini-notation: unexpected '@'", error("@3").message)
        assertEquals("Mini-notation: unexpected '!'", error("!a").message)
        assertEquals("Mini-notation: unexpected '_'", error("_ a").message)
        assertEquals("Mini-notation: unexpected '%'", error("bd %").message)
        assertEquals("Mini-notation: unexpected '*'", error("bd *2").message)
    }

    @Test
    fun commasAndPipesCannotBeMixed() {
        assertEquals("Mini-notation: unexpected '|'", error("a, b | c").message)
        assertEquals("Mini-notation: unexpected ','", error("a | b, c").message)
    }

    // ---- edge cases -------------------------------------------------------------------

    @Test
    fun restsMayBeWeighted() {
        assertEquals(listOf("a@0-1/4", "b@3/4-1"), mini("a ~@2 b").shownCycle())
    }

    @Test
    fun repeatsKeepTheModifiersOfTheirStep() {
        assertEquals(listOf("a@0-1/4", "a@1/4-1/2", "a@1/2-3/4", "a@3/4-1"), mini("a*2!2").shownCycle())
    }

    @Test
    fun repeatedAlternationsPlayInStep() {
        val p = mini("<a b>!2")
        assertEquals(listOf<Any>("a", "a"), p.cycleValues(0))
        assertEquals(listOf<Any>("b", "b"), p.cycleValues(1))
    }

    @Test
    fun emptyBracketsAreSilent() {
        assertEquals(emptyList(), mini("{}").shownCycle())
        assertEquals(emptyList(), mini("<>").shownCycle())
        assertEquals(listOf("a@0-1"), mini("a, ").shownCycle())
    }

    @Test
    fun hugeCountsAreRejected() {
        val copies = error("bd!1000000")
        assertEquals("Mini-notation: number out of range", copies.message)
        assertEquals(SourceRange(3, 10), copies.range)
        assertEquals("Mini-notation: number out of range", error("bd(3,1000000)").message)
        assertEquals("Mini-notation: number out of range", assertFailsWith<CosicError> { mini("bd(<3 1000000>,8)").shownCycle(1) }.message)
    }
}
