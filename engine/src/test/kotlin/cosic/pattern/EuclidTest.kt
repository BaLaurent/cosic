package cosic.pattern

import cosic.script.catalogue.registerEuclid
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Euclidean rhythms (`euclid.mjs`): Bjorklund's algorithm with Toussaint's examples, the way `euclid` stretches, inverts,
 * rotates and holds a rhythm, `euclidish`, and the catalogue entries. What each function does to a pattern is also compared
 * with Strudel's output by the extra snippets of parity/extra-snippets/2.7.json (ParityTest) and its documented examples.
 */
class EuclidTest {
    init {
        registerEuclid()
    }

    private fun bits(ons: Double, steps: Double): String = bjorklund(ons, steps).joinToString("")

    /** Toussaint's way of writing a rhythm (`x ~ ~ x`, or `x _ _ x` for a held hit) as bits: `1001`. */
    private fun rhythm(spec: String): String = spec.split(" ").joinToString("") { if (it == "x") "1" else "0" }

    /** The wholes of the haps of the first cycle in steps of 1/[steps], as `begin-end`: `3-8` is a hit from the fourth step on that lasts five steps. */
    private fun Pattern.wholes(steps: Long): List<String> = firstCycle().map { it.whole!! }.sortedBy { it.begin }.map { whole ->
        "${(whole.begin * Rational(steps)).compact()}-${(whole.end * Rational(steps)).compact()}"
    }

    private val x = pure("x")

    // ---- Bjorklund ---------------------------------------------------------------------------------------------------

    @Test
    fun bjorklundOfTheEuclidianExamples() {
        assertEquals("10010010", bits(3.0, 8.0))
        assertEquals("10110110", bits(5.0, 8.0))
        assertEquals("1110", bits(3.0, 4.0))
        assertEquals("11111111", bits(8.0, 8.0))
        assertEquals("10000000", bits(1.0, 8.0))
        assertEquals("11111110", bits(7.0, 8.0))
    }

    @Test
    fun noHitsAreAllRests() {
        assertEquals("0000", bits(0.0, 4.0))
        assertEquals("", bits(0.0, 0.0))
    }

    @Test
    fun aNegativeNumberOfHitsInvertsTheRhythm() {
        assertEquals("01101101", bits(-3.0, 8.0))
        assertEquals("00000000", bits(-8.0, 8.0))
        assertEquals("01111111", bits(-1.0, 8.0))
    }

    /** The rhythms of the test `reproduces Toussaint's example euclidean algorithms` of mini.test.mjs, where Toussaint's own text is corrected as it corrects it. */
    private val toussaint = mapOf(
        (1 to 2) to "x ~", (1 to 3) to "x ~ ~", (1 to 4) to "x ~ ~ ~", (4 to 12) to "x ~ ~ x ~ ~ x ~ ~ x ~ ~",
        (2 to 5) to "x ~ x ~ ~", (3 to 4) to "x x x ~", (3 to 5) to "x ~ x ~ x", (3 to 7) to "x ~ x ~ x ~ ~",
        (3 to 8) to "x ~ ~ x ~ ~ x ~", (4 to 7) to "x ~ x ~ x ~ x", (4 to 9) to "x ~ x ~ x ~ x ~ ~",
        (4 to 11) to "x ~ ~ x ~ ~ x ~ ~ x ~", (5 to 6) to "x x x x x ~", (5 to 7) to "x ~ x x ~ x x",
        (5 to 8) to "x ~ x x ~ x x ~", (5 to 9) to "x ~ x ~ x ~ x ~ x", (5 to 11) to "x ~ x ~ x ~ x ~ x ~ ~",
        (5 to 12) to "x ~ ~ x ~ x ~ ~ x ~ x ~", (5 to 16) to "x ~ ~ x ~ ~ x ~ ~ x ~ ~ x ~ ~ ~",
        (7 to 8) to "x x x x x x x ~", (7 to 12) to "x ~ x x ~ x ~ x x ~ x ~", (7 to 16) to "x ~ ~ x ~ x ~ x ~ ~ x ~ x ~ x ~",
        (9 to 16) to "x ~ x x ~ x ~ x ~ x x ~ x ~ x ~", (11 to 24) to "x ~ ~ x ~ x ~ x ~ x ~ x ~ ~ x ~ x ~ x ~ x ~ x ~",
        (13 to 24) to "x ~ x x ~ x ~ x ~ x ~ x ~ x x ~ x ~ x ~ x ~ x ~",
    )

    @Test
    fun toussaintsExamples() {
        assertEquals(25, toussaint.size)
        for ((spec, target) in toussaint) {
            assertEquals(rhythm(target), bits(spec.first.toDouble(), spec.second.toDouble()), "x(${spec.first},${spec.second})")
        }
    }

    @Test
    fun toussaintsExamplesHeldUntilTheNextHit() {
        // `x(k,-n)` is the same rhythm with every hit lasting until the next one: `x _ _ x _ _ x _`
        for ((spec, target) in toussaint) {
            val (k, n) = spec
            val steps = target.split(" ")
            val expected = steps.indices.filter { steps[it] == "x" }.map { start ->
                val end = (start + 1 until n).firstOrNull { steps[it] == "x" } ?: n
                "$start-$end"
            }
            assertEquals(expected, x.euclidLegato(k.toDouble(), n.toDouble()).wholes(n.toLong()), "x($k,-$n)")
        }
    }

    @Test
    fun bjorklundRefusesWhatIsNoLength() {
        for ((ons, steps) in listOf(5.0 to 4.0, 2.5 to 8.0, 3.0 to 8.5, Double.NaN to 8.0, 3.0 to Double.POSITIVE_INFINITY)) {
            assertEquals("Invalid array length", assertFailsWith<CosicError> { bjorklund(ons, steps) }.message, "bjorklund($ons, $steps)")
        }
    }

    @Test
    fun aRhythmOfMoreThanMaxCountStepsIsRefused() {
        assertEquals(MAX_COUNT, bjorklund(3.0, MAX_COUNT.toDouble()).size)
        val e = assertFailsWith<CosicError> { bjorklund(3.0, MAX_COUNT + 1.0) }
        assertTrue(e.message.startsWith("Too many steps"), e.message)
        assertFailsWith<CosicError> { x.euclid(3.0, MAX_COUNT * 3.0) }
        assertFailsWith<CosicError> { x.euclid(MAX_COUNT * 3.0, 8.0) }
    }

    // ---- euclid ------------------------------------------------------------------------------------------------------

    @Test
    fun euclidPutsTheEventsOnTheHits() {
        assertEquals(listOf("x@0-1/8", "x@3/8-1/2", "x@3/4-7/8"), x.euclid(3.0, 8.0).shownCycle())
        assertEquals(listOf("x@0-1/8", "x@1/4-3/8", "x@3/8-1/2", "x@5/8-3/4", "x@3/4-7/8"), x.euclid(5.0, 8.0).shownCycle())
        assertEquals(emptyList(), x.euclid(0.0, 8.0).shownCycle())
        assertEquals(emptyList(), x.euclid(3.0, 0.0).shownCycle())
    }

    @Test
    fun moreHitsThanStepsStretchTheRhythmOverCycles() {
        // euclid(5, 4) is eight steps over two cycles: the five hits of euclid(5, 8) at half the speed
        assertEquals(x.euclid(5.0, 8.0).shownCycle(), x.euclid(5.0, 4.0)._fast(2.0).shownCycle())
        assertEquals(5, x.euclid(5.0, 4.0).lines(0, 2).size)
        // the hits are at 0, 2, 3, 5 and 6 of eight steps of a quarter cycle
        assertEquals(
            listOf("x@0-1/4", "x@1/2-3/4", "x@3/4-1", "x@5/4-3/2", "x@3/2-7/4"),
            x.euclid(5.0, 4.0).shown(span(r(0), r(2))),
        )
    }

    @Test
    fun aNegativeNumberOfHitsInvertsWhatIsPlayed() {
        assertEquals(listOf("x@1/8-1/4", "x@1/4-3/8", "x@1/2-5/8", "x@5/8-3/4", "x@7/8-1"), x.euclid(-3.0, 8.0).shownCycle())
    }

    @Test
    fun aNegativeNumberOfStepsHoldsEachHit() {
        assertEquals(x.euclidLegato(3.0, 8.0).shownCycle(), x.euclid(3.0, -8.0).shownCycle())
        assertEquals(listOf("x@0-3/8", "x@3/8-3/4", "x@3/4-1"), x.euclidLegato(3.0, 8.0).shownCycle())
        assertEquals(listOf("x@0-1/4", "x@1/4-3/8", "x@3/8-5/8", "x@5/8-3/4", "x@3/4-1"), x.euclidLegato(5.0, 8.0).shownCycle())
        // and a negative number of steps of the legato version is euclid
        assertEquals(x.euclid(3.0, 8.0).shownCycle(), x.euclidLegato(3.0, -8.0).shownCycle())
    }

    @Test
    fun theRhythmIsRotatedToTheRight() {
        assertEquals(listOf("x@0-1/8", "x@1/4-3/8", "x@5/8-3/4"), x.euclidRot(3.0, 8.0, 2.0).shownCycle())
        assertEquals(
            listOf("x@0-1/8", "x@1/4-3/8", "x@1/2-5/8", "x@5/8-3/4", "x@7/8-1"),
            x.euclidRot(5.0, 8.0, 2.0).shownCycle(),
        )
        assertEquals(listOf("x@3/16-1/4", "x@1/2-9/16", "x@7/8-15/16"), x.euclidRot(3.0, 16.0, 14.0).shownCycle())
    }

    @Test
    fun aRotationPastTheEndOfTheRhythmLeavesItAlone() {
        // `rotate` is made of two `slice`s, which clamp their indices instead of wrapping them
        val plain = x.euclid(3.0, 8.0).shownCycle()
        for (rotation in listOf(8.0, 10.0, -10.0, -8.0, 0.0, 0.5, Double.NaN)) {
            assertEquals(plain, x.euclidRot(3.0, 8.0, rotation).shownCycle(), "rotation $rotation")
        }
        // a negative rotation turns it to the left: 01001010
        assertEquals(listOf("x@1/8-1/4", "x@1/2-5/8", "x@3/4-7/8"), x.euclidRot(3.0, 8.0, -2.0).shownCycle())
    }

    @Test
    fun theLegatoRhythmIsRotatedToo() {
        assertEquals(listOf("x@0-1/4", "x@1/4-5/8", "x@5/8-1"), x.euclidLegatoRot(3.0, 8.0, 2.0).shownCycle())
        assertEquals(
            listOf("x@0-1/4", "x@1/4-1/2", "x@1/2-5/8", "x@5/8-7/8", "x@7/8-1"),
            x.euclidLegatoRot(5.0, 8.0, 2.0).shownCycle(),
        )
    }

    @Test
    fun theStepsOfAnEuclideanPatternAreTheStepsOfTheRhythm() {
        assertEquals(Rational(8), x.euclid(3.0, 8.0).steps)
        assertEquals(Rational(8), x.euclid(5.0, 4.0).steps)
        assertEquals(Rational(8), x.euclidLegato(3.0, 8.0).steps)
        assertEquals(Rational(12), x.euclidish(3.0, 12.0, 0.5).steps)
    }

    @Test
    fun bjorkTakesAListOfPulsesStepsAndRotation() {
        assertEquals(x.euclid(3.0, 8.0).shownCycle(), x.bjork(listOf(3.0, 8.0)).shownCycle())
        assertEquals(x.euclidRot(3.0, 8.0, 2.0).shownCycle(), x.bjork(listOf(3.0, 8.0, 2.0)).shownCycle())
        // the steps default to the pulses, but only when they are left out
        assertEquals(x.euclid(3.0, 3.0).shownCycle(), x.bjork(3.0).shownCycle())
        assertEquals(x.euclid(3.0, 3.0).shownCycle(), x.bjork(listOf(3.0, Undefined)).shownCycle())
        assertEquals(x.euclid(3.0, 8.0).shownCycle(), x.bjork(listOf(3.0, 8.0, Undefined)).shownCycle())
    }

    // ---- euclidish ---------------------------------------------------------------------------------------------------

    @Test
    fun euclidishWithNoMorphingIsEuclid() {
        assertEquals(x.euclid(3.0, 8.0).shownCycle(), x.euclidish(3.0, 8.0, 0.0).shownCycle())
        assertEquals(x.euclid(5.0, 12.0).lines(0, 2), x.euclidish(5.0, 12.0, 0.0).lines(0, 2))
    }

    @Test
    fun euclidishMovesTheHitsTowardsAnEvenRhythm() {
        // the hits of euclid(3, 8) are at 0, 3/8 and 3/4; of three even hits at 0, 1/3 and 2/3; each lasts a step of eight
        val even = x.euclidish(3.0, 8.0, 1.0).firstCycle().sortedBy { it.whole!!.begin }
        assertEquals(3, even.size)
        // the distance the hits move is a difference of doubles, turned into a fraction again, so the times are only as exact as that
        assertEquals(0.0, even[0].whole!!.begin.toDouble(), 1e-6)
        assertEquals(1.0 / 3, even[1].whole!!.begin.toDouble(), 1e-6)
        assertEquals(2.0 / 3, even[2].whole!!.begin.toDouble(), 1e-6)
        assertTrue(even.all { it.whole!!.duration == r(1, 8) })
        // with a morph of one half the hits stand between the two rhythms
        val half = x.euclidish(3.0, 8.0, 0.5).firstCycle().sortedBy { it.whole!!.begin }
        assertTrue(half[1].whole!!.begin > r(1, 3) && half[1].whole!!.begin < r(3, 8))
        assertTrue(half[2].whole!!.begin > r(2, 3) && half[2].whole!!.begin < r(3, 4))
    }

    @Test
    fun euclidishNeedsAWholeNumberOfHitsThatFitTheSteps() {
        assertEquals("Invalid array length", assertFailsWith<CosicError> { x.euclidish(-3.0, 8.0, 0.5) }.message)
        assertEquals("Invalid array length", assertFailsWith<CosicError> { x.euclidish(5.0, 4.0, 0.5) }.message)
        assertEquals(emptyList(), x.euclidish(0.0, 8.0, 0.5).shownCycle())
    }

    // ---- the catalogue -----------------------------------------------------------------------------------------------

    @Test
    fun everyNameIsRegisteredWithItsArity() {
        for ((name, arity) in listOf(
            "euclid" to 3, "euclidRot" to 4, "euclidrot" to 4, "euclidLegato" to 3, "euclidLegatoRot" to 4,
            "euclidish" to 4, "eish" to 4, "bjork" to 2,
        )) {
            val registered = checkNotNull(Registry.lookup(name)) { name }
            assertEquals(arity, registered.arity, name)
            assertEquals(RegisteredKind.REGISTER, registered.kind, name)
            assertTrue(registered.hasPlain, name)
        }
    }

    @Test
    fun theMethodsPatternifyTheirArguments() {
        assertEquals(x.euclid(3.0, 8.0).lines(0, 1), x.callMethod("euclid", 3.0, 8.0).lines(0, 1))
        // 3 for the first half of the cycle and 5 for the second: the hits of each rhythm that start in its half
        assertEquals(
            listOf("[ 0/1 → 1/8 | x ]", "[ 3/8 → 1/2 | x ]", "[ 5/8 → 3/4 | x ]", "[ 3/4 → 7/8 | x ]"),
            x.callMethod("euclid", fastcat(3.0, 5.0), 8.0).lines(0, 1),
        )
    }

    @Test
    fun programsCallThemInAnyForm() {
        fun lines(code: String) = evaluateProgram(code).pattern.lines(0, 1)
        val expected = lines("""s("bd").euclid(3, 8)""")
        assertEquals(3, expected.size)
        assertEquals(expected, lines("""s("bd")._euclid(3, 8)"""))
        assertEquals(expected, lines("""euclid(3, 8, s("bd"))"""))
        assertEquals(expected, lines("""euclid(3, 8)(s("bd"))"""))
        assertEquals(expected, lines("""s("bd").bjork([3, 8])"""))
        assertEquals(expected, lines("""s("bd").euclidRot(3, 8, 0)"""))
        assertEquals(lines("""s("bd").euclidRot(3, 8, 2)"""), lines("""s("bd").euclidrot(3, 8, 2)"""))
        assertEquals(lines("""s("hh").euclidish(3, 8, 0.3)"""), lines("""s("hh").eish(3, 8, 0.3)"""))
    }

    @Test
    fun bjorklundIsAGlobalFunctionThatGivesTheList() {
        assertEquals(listOf("[ 0/1 → 1/1 | 1 0 0 1 0 0 1 0 ]"), evaluateProgram("""pure(bjorklund(3, 8))""").pattern.lines(0, 1))
        assertEquals(listOf("[ 0/1 → 1/1 | 0 1 1 0 1 1 0 1 ]"), evaluateProgram("""pure(bjorklund(-3, 8))""").pattern.lines(0, 1))
    }

    @Test
    fun anInvalidRhythmIsAnErrorOfTheProgram() {
        assertFailsWith<CosicError> { evaluateProgram("""s("bd").euclid(3, 8.5)""") }
        assertFailsWith<CosicError> { evaluateProgram("""s("bd").euclidish(5, 4, 0.5)""") }
        assertFailsWith<CosicError> { evaluateProgram("""bjorklund(5, 4)""") }
    }
}
