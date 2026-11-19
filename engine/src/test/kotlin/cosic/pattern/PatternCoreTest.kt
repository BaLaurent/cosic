package cosic.pattern

import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    }

    @Test
    fun spanCyclesKeepsZeroWidthSpans() {
        assertEquals(listOf(span(r(1, 2), r(1, 2))), span(r(1, 2), r(1, 2)).spanCycles())
        assertEquals(listOf(span(r(1), r(1))), span(r(1), r(1)).spanCycles())
    }

    @Test
    fun intersectOfOverlappingSpans() {
        assertEquals(span(r(1, 2), r(1)), span(r(0), r(1)).intersect(span(r(1, 2), r(2))))
    }

    @Test
    fun intersectOfDisjointOrTouchingSpansIsNull() {
        assertNull(span(r(0), r(1)).intersect(span(r(1), r(2))))
        assertNull(span(r(1), r(2)).intersect(span(r(0), r(1))))
        assertNull(span(r(0), r(1)).intersect(span(r(2), r(3))))
    }

    @Test
    fun intersectKeepsZeroWidthSpanInsideTheOther() {
        assertEquals(span(r(1, 2), r(1, 2)), span(r(1, 2), r(1, 2)).intersect(span(r(0), r(1))))
        assertEquals(span(r(0), r(0)), span(r(0), r(0)).intersect(span(r(0), r(1))))
        assertEquals(span(r(1, 2), r(1, 2)), span(r(0), r(1)).intersect(span(r(1, 2), r(1, 2))))
    }

    @Test
    fun intersectDropsZeroWidthSpanAtTheEndOfTheOther() {
        assertNull(span(r(1), r(1)).intersect(span(r(0), r(1))))
        assertNull(span(r(0), r(1)).intersect(span(r(1), r(1))))
    }

    @Test
    fun withTimeMapsBothEnds() {
        assertEquals(span(r(2), r(4)), span(r(1), r(2)).withTime { it * r(2) })
    }

    @Test
    fun hapOnsetAndWhole() {
        val whole = span(r(0), r(1))
        assertTrue(Hap(whole, span(r(0), r(1, 2)), "a", emptyList()).hasOnset())
        assertFalse(Hap(whole, span(r(1, 2), r(1)), "a", emptyList()).hasOnset())
        val continuous = Hap(null, span(r(0), r(1)), "a", emptyList())
        assertFalse(continuous.hasOnset())
        assertEquals(span(r(0), r(1)), continuous.wholeOrPart())
        assertEquals(whole, Hap(whole, span(r(0), r(1, 2)), "a", emptyList()).wholeOrPart())
    }

    // ---- constructors ------------------------------------------------------------------

    @Test
    fun pureRepeatsEveryCycleAndSplitsQueriesAtCycleBoundaries() {
        val haps = pure("a").query(span(r(1, 2), r(3, 2)))
        assertEquals(
            listOf(
                Hap(span(r(0), r(1)), span(r(1, 2), r(1)), "a", emptyList()),
                Hap(span(r(1), r(2)), span(r(1), r(3, 2)), "a", emptyList()),
            ),
            haps,
        )
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
        assertEquals(emptyList(), fastcat(emptyList<Pattern<String>>()).shownCycle())
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
    }

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

    @Test
    fun stackPlaysAllPatternsTogether() {
        val p = stack(pure("a"), fastcat(pure("b"), pure("c")))
        assertEquals(listOf("b@0-1/2", "a@0-1", "c@1/2-1"), p.shownCycle())
    }

    // ---- mapping and filtering --------------------------------------------------------

    @Test
    fun fmapKeepsStructureAndLocations() {
        val located = Pattern<String> { s ->
            pure("a").query(s).map { it.copy(locations = listOf(SourceRange(2, 3))) }
        }
        val haps = located.fmap { it.uppercase() }.query(cycleSpan(0))
        assertEquals(listOf(Hap(span(r(0), r(1)), span(r(0), r(1)), "A", listOf(SourceRange(2, 3)))), haps)
    }

    @Test
    fun withQueryTimeAndWithHapTimeScaleTime() {
        val p = pure("a").withQueryTime { it * r(2) }.withHapTime { it / r(2) }
        assertEquals(listOf("a@0-1/2", "a@1/2-1"), p.shownCycle())
    }

    @Test
    fun splitQueriesSplitsAtCycleBoundaries() {
        val whole = Pattern<Int> { s -> listOf(Hap(s, s, 1, emptyList())) }
        assertEquals(1, whole.query(span(r(0), r(5, 2))).size)
        val split = whole.splitQueries().query(span(r(0), r(5, 2)))
        assertEquals(listOf(span(r(0), r(1)), span(r(1), r(2)), span(r(2), r(5, 2))), split.map { it.part })
    }

    @Test
    fun filterValuesKeepsMatchingHaps() {
        val p = fastcat(pure(1), pure(2), pure(3)).filterValues { it % 2 == 1 }
        assertEquals(listOf(1, 3), p.cycleValues())
    }

    @Test
    fun filterHapsCanKeepOnsetsOnly() {
        val onsets = pure("a").filterHaps { it.hasOnset() }
        assertEquals(listOf("a@1-2|1-3/2"), onsets.shown(span(r(1, 2), r(3, 2))))
    }

    // ---- applicative and join ---------------------------------------------------------

    @Test
    fun appLeftTakesStructureFromTheLeft() {
        val p = fastcat(pure("a"), pure("b")).appLeft(pure(1)) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1/2", "b1@1/2-1"), p.shownCycle())
    }

    @Test
    fun appLeftSplitsPartsButKeepsTheLeftWhole() {
        val p = pure("a").appLeft(fastcat(pure(1), pure(2))) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1|0-1/2", "a2@0-1|1/2-1"), p.shownCycle())
        val q = fastcat(pure("a"), pure("b")).appLeft(fastcat(pure(1), pure(2), pure(3), pure(4))) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1/2|0-1/4", "a2@0-1/2|1/4-1/2", "b3@1/2-1|1/2-3/4", "b4@1/2-1|3/4-1"), q.shownCycle())
    }

    @Test
    fun appLeftOnlyCombinesRightHapsInsideTheLeftPart() {
        val p = fastcat(pure("a"), pure("b")).appLeft(fastcat(pure(1), pure(2), pure(3), pure(4))) { x, y -> "$x$y" }
        assertEquals(listOf("a1@0-1/2|0-1/4"), p.shown(span(r(0), r(1, 4))))
    }

    @Test
    fun appLeftConcatenatesLocationsLeftThenRight() {
        fun located(value: String, at: SourceRange) =
            Pattern<String> { s -> pure(value).query(s).map { it.copy(locations = listOf(at)) } }
        val haps = located("a", SourceRange(0, 1)).appLeft(located("b", SourceRange(5, 6))) { x, y -> x + y }.query(cycleSpan(0))
        assertEquals(listOf(SourceRange(0, 1), SourceRange(5, 6)), haps.single().locations)
    }

    @Test
    fun innerJoinKeepsInnerWholes() {
        val outer = fastcat(pure(pure("p")), pure(fastcat(pure("q"), pure("r"))))
        assertEquals(listOf("p@0-1|0-1/2", "r@1/2-1"), outer.innerJoin().shownCycle())
    }

    @Test
    fun innerJoinConcatenatesInnerThenOuterLocations() {
        val inner = Pattern<String> { s -> pure("a").query(s).map { it.copy(locations = listOf(SourceRange(0, 1))) } }
        val outer = Pattern<Pattern<String>> { s -> pure(inner).query(s).map { it.copy(locations = listOf(SourceRange(7, 8))) } }
        val haps = outer.innerJoin().query(cycleSpan(0))
        assertEquals(listOf(SourceRange(0, 1), SourceRange(7, 8)), haps.single().locations)
    }

    @Test
    fun innerJoinClipsPartsToTheQuery() {
        val outer = pure(fastcat(pure("q"), pure("r")))
        assertEquals(listOf("q@0-1/2|1/4-1/2", "r@1/2-1|1/2-3/4"), outer.innerJoin().shown(span(r(1, 4), r(3, 4))))
    }

    // ---- notes and scales -------------------------------------------------------------

    @Test
    fun noteNamesToMidi() {
        assertEquals(48, noteToMidi("c"))
        assertEquals(60, noteToMidi("c4"))
        assertEquals(51, noteToMidi("eb3"))
        assertEquals(69, noteToMidi("a4"))
        assertEquals(69, noteToMidi("A4"))
        assertEquals(61, noteToMidi("c#4"))
        assertEquals(61, noteToMidi("db4"))
        assertEquals(58, noteToMidi("bb"))
        assertEquals(59, noteToMidi("b"))
        assertEquals(59, noteToMidi("cb4"))
        assertEquals(0, noteToMidi("c-1"))
        assertEquals(127, noteToMidi("g9"))
    }

    @Test
    fun invalidNoteNamesAreNull() {
        for (bad in listOf("", "h3", "bd", "c##3", "4", "c 4", "c4x", "cb#")) {
            assertNull(noteToMidi(bad), "'$bad' must not be a note")
        }
    }

    @Test
    fun scaleDegreesToNotes() {
        assertEquals(60.0, scaleNote(7, "C:major"))
        assertEquals(48.0, scaleNote(0, "C:major"))
        assertEquals(52.0, scaleNote(2, "C:major"))
        assertEquals(47.0, scaleNote(-1, "C:major"))
        assertEquals(51.0, scaleNote(2, "C:minor"))
        assertEquals(69.0, scaleNote(0, "A4:minor"))
        assertEquals(57.0, scaleNote(4, "C:majorpentatonic"))
        assertEquals(60.0, scaleNote(5, "C:majorpentatonic"))
    }

    @Test
    fun scaleNamesAreNormalised() {
        assertEquals(59.0, scaleNote(6, "C:harmonic minor"))
        assertEquals(59.0, scaleNote(6, "C:Harmonic_Minor"))
        assertEquals(53.0, scaleNote(1, "D:minor:pentatonic"))
        assertEquals(scaleNote(3, "C:major"), scaleNote(3, "C:ionian"))
        assertEquals(scaleNote(3, "C:minor"), scaleNote(3, "C:aeolian"))
    }

    @Test
    fun everyScaleKnownByThePlanExists() {
        val names = listOf(
            "major", "ionian", "minor", "aeolian", "dorian", "phrygian", "lydian", "mixolydian", "locrian",
            "harmonicminor", "melodicminor", "majorpentatonic", "minorpentatonic", "blues", "chromatic", "wholetone",
        )
        for (name in names) scaleNote(1, "C:$name")
        assertEquals(49.0, scaleNote(1, "C:chromatic"))
        assertEquals(50.0, scaleNote(1, "C:wholetone"))
        assertEquals(51.0, scaleNote(1, "C:blues"))
    }

    @Test
    fun unknownScaleThrows() {
        val e = assertFailsWith<CosicError> { scaleNote(0, "C:foo") }
        assertEquals("Unknown scale 'C:foo'", e.message)
        assertFailsWith<CosicError> { scaleNote(0, "H:major") }
        assertFailsWith<CosicError> { scaleNote(0, "C") }
    }
}
