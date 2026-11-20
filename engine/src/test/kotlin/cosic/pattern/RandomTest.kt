package cosic.pattern

import cosic.script.catalogue.Catalogue
import cosic.script.evaluateProgram
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The random number generator, the random signals and the functions made from them. The numbers are what the pinned
 * `@strudel/core` produces (a Node script that imports `signal.mjs` printed them), at full precision: the generators are
 * integer arithmetic, so the same numbers must come out. Hap lists are the snapshots of Strudel's own test suite.
 */
class RandomTest {
    init {
        Catalogue.installAll()
    }

    @AfterTest
    fun back() {
        Rng.reset()
    }

    private fun hh(n: Double = 8.0): Pattern = pure(linkedMapOf<String, Any?>("s" to "hh"))._fast(n)

    /** The values of the haps of cycle [cycle], in the order of their parts. */
    private fun Pattern.values(cycle: Long = 0): List<Any?> =
        queryArc(Rational(cycle), Rational(cycle + 1)).sortedBy { it.part.begin }.map { it.value }

    /** The value of the signal at the time [t]. */
    private fun valueAt(signal: Pattern, t: Double): Any? =
        signal.queryArc(Rational.of(t), Rational.of(t) + Rational(1, 1000)).single().value

    private fun Pattern.withSpeed(): Pattern = fmap { v -> jsMerge(v as Map<String, Any?>, mapOf("speed" to 2.0)) }

    private val faster = jsFn(1) { args -> (args[0] as Pattern).withSpeed() }

    private fun Pattern.speeded(begin: Long, end: Long): Int =
        queryArc(Rational(begin), Rational(end)).count { (it.value as Map<*, *>).containsKey("speed") }

    /** The haps of two cycles as they print, in query order (a continuous pattern cannot be sorted by part). */
    private fun shown(p: Pattern): List<String> = p.queryArc(Rational.ZERO, Rational(2)).map { it.show(true) }

    // ---- the generators ----------------------------------------------------------------------------------------------

    @Test
    fun legacyNumbersAtATime() {
        val expected = listOf(
            0.0 to 0.0, 0.125 to 0.6852155700325966, 0.25 to 0.36975969187915325, 0.375 to 0.40139251574873924,
            0.5 to 0.2604806162416935, 1.0 to 0.5195421651005745, 2.0 to 0.9595271199941635, 3.0 to 0.21728911064565182,
            0.1 to 0.15612453781068325, 0.3 to 0.8844355642795563, 7.5 to 0.3756653554737568, 100.0 to 0.9990921225398779,
            299.99 to 0.026058901101350784, 300.0 to 0.0, 301.25 to 0.7287282031029463, -0.5 to 0.3014927301555872,
            -3.0 to 0.21725867129862309, 12345.678 to 0.6342629622668028,
        )
        for ((t, value) in expected) assertEquals(value, getRandAtTime(t), "legacy at $t")
    }

    @Test
    fun legacySeedIsAddedToTheTime() {
        val t3 = 1.0 / 3.0
        val expected = listOf(
            Triple(0.0, 1.0, 0.5195421651005745), Triple(0.0, 2.0, 0.9595271199941635), Triple(0.0, 0.5, 0.2604806162416935),
            Triple(0.0, 7.0, 0.180575393140316), Triple(0.5, 1.0, 0.6084080748260021), Triple(0.5, 2.0, 0.45861607417464256),
            Triple(0.5, 0.5, 0.5195421651005745), Triple(0.5, 7.0, 0.3756653554737568), Triple(t3, 1.0, 0.34520373493433),
            Triple(t3, 2.0, 0.42431837879121304), Triple(t3, 0.5, 0.021360723301768303), Triple(t3, 7.0, 0.972003385424614),
        )
        for ((t, seed, value) in expected) assertEquals(value, getRandAtTime(t, seed), "legacy at $t with seed $seed")
        // the seed is just a time offset: seed 1 at time 0 is time 1 without a seed
        assertEquals(getRandAtTime(1.0), getRandAtTime(0.0, 1.0))
    }

    @Test
    fun aMissingOrNullSeedIsZero() {
        assertEquals(getRandAtTime(0.25), getRandAtTime(0.25, null))
        assertEquals(getRandAtTime(0.25), getRandAtTime(0.25, Undefined))
        // a seed that is not a number is added as text (`0.25 + "a"`): not a number, so the generator has nothing to read
        assertEquals(0.0, getRandAtTime(0.25, "a"))
        Rng.mode = RngMode.PRECISE
        assertEquals(getRandAtTime(0.25), getRandAtTime(0.25, Undefined))
    }

    @Test
    fun legacyListsAreSigned() {
        assertEquals(
            listOf(
                -0.8426077850162983, 0.0886186733841896, 0.9342893119901419, -0.06848422810435295,
                0.6248505394905806, -0.5372848063707352, -0.8283301629126072, -0.7969411127269268,
            ),
            getRandsAtTime(1.0 / 16.0, 8).toList(),
        )
        assertEquals(
            listOf(0.2604806162416935, -0.3654713351279497, -0.9900747090578079, -0.22572625242173672),
            getRandsAtTime(0.5, 4).toList(),
        )
        // a single number is the absolute value of the first of a list
        assertEquals(getRandAtTime(1.0 / 16.0), Math.abs(getRandsAtTime(1.0 / 16.0, 2)[0]))
        assertEquals(emptyList(), getRandsAtTime(0.5, 0).toList())
        assertEquals(emptyList(), getRandsAtTime(0.5, -3).toList())
    }

    @Test
    fun theLegacyListGivesTheOrderOfShuffle() {
        // `randrun(4)` of cycle 0 sorts these: the permutation of the snapshot of `shuffle`, 2 1 3 0
        val rands = getRandsAtTime(0.5, 4)
        assertEquals(listOf(2, 1, 3, 0), rands.indices.sortedBy { rands[it] })
    }

    @Test
    fun preciseNumbersAtATime() {
        Rng.mode = RngMode.PRECISE
        val expected = listOf(
            0.0 to 0.3880893050227314, 0.125 to 0.6546637422870845, 0.25 to 0.12939242972061038, 0.375 to 0.8542453737463802,
            0.5 to 0.3268951131030917, 1.0 to 0.1182357866782695, 2.0 to 0.5812791362404823, 3.0 to 0.6148205685894936,
            0.1 to 0.2083400257397443, 0.3 to 0.38979181414470077, 7.5 to 0.22418501740321517, 100.0 to 0.5646722377277911,
            299.99 to 0.1903235032223165, 300.0 to 0.751419703476131, 301.25 to 0.9876253323163837, -0.5 to 0.20198934338986874,
            -3.0 to 0.3910470313858241, 12345.678 to 0.9965943533461541,
        )
        for ((t, value) in expected) assertEquals(value, getRandAtTime(t), "precise at $t")
    }

    @Test
    fun preciseSeedGoesThroughTheHash() {
        Rng.mode = RngMode.PRECISE
        val t3 = 1.0 / 3.0
        val expected = listOf(
            Triple(0.0, 1.0, 0.025488380575552583), Triple(0.0, 2.0, 0.7764297795947641), Triple(0.0, 0.5, 0.3880893050227314),
            Triple(0.0, 7.0, 0.6800784703809768), Triple(0.0, -3.0, 0.7106156600639224), Triple(0.5, 1.0, 0.5648446304257959),
            Triple(0.5, 2.0, 0.4299591153394431), Triple(0.5, 0.5, 0.3268951131030917), Triple(0.5, 7.0, 0.2579206919763237),
            Triple(0.5, -3.0, 0.4761659703217447), Triple(t3, 1.0, 0.4405094843823463), Triple(t3, 2.0, 0.08531494322232902),
            Triple(t3, 0.5, 0.11797208851203322), Triple(t3, 7.0, 0.07700254139490426), Triple(t3, -3.0, 0.8065114966593683),
            Triple(5.25, 1.0, 0.3165054591372609), Triple(5.25, 2.0, 0.320373454131186), Triple(5.25, 0.5, 0.1565582058392465),
            Triple(5.25, 7.0, 0.27285924716852605), Triple(5.25, -3.0, 0.07474274560809135),
        )
        for ((t, seed, value) in expected) assertEquals(value, getRandAtTime(t, seed), "precise at $t with seed $seed")
        // a seed of a fraction of one is no seed at all: it is cut to a whole number
        assertEquals(getRandAtTime(0.0), getRandAtTime(0.0, 0.5))
    }

    @Test
    fun preciseListsAreNotSigned() {
        Rng.mode = RngMode.PRECISE
        assertEquals(
            listOf(
                0.738785972353071, 0.20828274125233293, 0.4327744028996676, 0.6270653042010963,
                0.5797256273217499, 0.7755217386875302, 0.17339632054790854, 0.594271685462445,
            ),
            getRandsAtTime(1.0 / 16.0, 8).toList(),
        )
        assertEquals(
            listOf(0.8783906053286046, 0.9883859332185239, 0.44724237523041666, 0.49326980602927506),
            getRandsAtTime(3.5, 4, 2.0).toList(),
        )
        // the first number of a list is the single number of the same time
        assertEquals(getRandAtTime(3.5, 2.0), getRandsAtTime(3.5, 4, 2.0)[0])
    }

    @Test
    fun useRNGSelectsTheGeneratorAndSaysWhich() {
        assertEquals(RngMode.LEGACY, Rng.mode)
        assertEquals("precise", useRNG("precise"))
        assertEquals(RngMode.PRECISE, Rng.mode)
        assertEquals("legacy", useRNG("legacy"))
        assertEquals(RngMode.LEGACY, Rng.mode)
        // any other text is the precise one, and so is anything else but a missing value
        useRNG("whatever")
        assertEquals(RngMode.PRECISE, Rng.mode)
        assertEquals("legacy", useRNG())
        assertEquals(RngMode.LEGACY, Rng.mode)
        assertEquals("legacy", useRNG(Undefined))
        assertNull(useRNG(null))
        assertEquals(RngMode.PRECISE, Rng.mode)
        Rng.reset()
        assertEquals(RngMode.LEGACY, Rng.mode)
    }

    @Test
    fun aProgramGetsTheGeneratorItChose() {
        val legacy = evaluateProgram("""s("hh*4").pan(rand)""").pattern.lines(0, 1)
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | s:hh pan:0 ]", "[ 1/4 → 1/2 | s:hh pan:0.36975969187915325 ]",
                "[ 1/2 → 3/4 | s:hh pan:0.2604806162416935 ]", "[ 3/4 → 1/1 | s:hh pan:0.19582648016512394 ]",
            ),
            legacy,
        )
        val precise = evaluateProgram("""useRNG('precise'); s("hh*4").pan(rand)""").pattern.lines(0, 1)
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | s:hh pan:0.3880893050227314 ]", "[ 1/4 → 1/2 | s:hh pan:0.12939242972061038 ]",
                "[ 1/2 → 3/4 | s:hh pan:0.3268951131030917 ]", "[ 3/4 → 1/1 | s:hh pan:0.28293057484552264 ]",
            ),
            precise,
        )
        // the next program starts with the default again, whatever the last one did
        assertEquals(legacy, evaluateProgram("""s("hh*4").pan(rand)""").pattern.lines(0, 1))
    }

    @Test
    fun theProgramFunctionTakesAnyCountAndFailsForTooMany() {
        assertEquals(0.5195421651005745, jsGetRandsAtTime(1.0, Undefined, Undefined))
        assertEquals(0.5195421651005745, jsGetRandsAtTime(0.0, 1.0, 1.0))
        assertEquals(listOf(0.2604806162416935, -0.3654713351279497), jsGetRandsAtTime(0.5, 2.0, Undefined))
        // only the number 1 gives a bare number: the text "1" is a count like any other
        assertTrue(jsGetRandsAtTime(0.5, "1", Undefined) is List<*>)
        assertEquals(emptyList<Double>(), jsGetRandsAtTime(0.5, 0.0, Undefined))
        assertEquals(3, (jsGetRandsAtTime(0.5, 2.5, Undefined) as List<*>).size)
        assertFailsWith<CosicError> { jsGetRandsAtTime(0.0, 1.0e9, Undefined) }
        assertEquals(
            "[ 0/1 → 1/1 | s:bd n:0.5195421651005745 ]",
            evaluateProgram("""s("bd").n(getRandsAtTime(1))""").pattern.lines(0, 1).single(),
        )
    }

    // ---- the random signals ------------------------------------------------------------------------------------------

    @Test
    fun randIsAContinuousSignalSampledAtTheStartOfTheSpan() {
        val haps = rand.queryArc(Rational(1, 8), Rational(1, 4))
        assertEquals(1, haps.size)
        assertNull(haps[0].whole)
        assertEquals(TimeSpan(Rational(1, 8), Rational(1, 4)), haps[0].part)
        assertEquals(0.6852155700325966, haps[0].value)
        // a span that crosses a cycle is not cut
        assertEquals(listOf(0.2604806162416935), rand.queryArc(Rational(1, 2), Rational(3, 2)).map { it.value })
    }

    @Test
    fun theSeedComesFromTheControlsOfTheQuery() {
        assertEquals(0.5195421651005745, rand.queryArc(Rational.ZERO, Rational.ONE, mapOf(RAND_SEED_KEY to 1.0))[0].value)
        assertEquals(0.45861607417464256, valueAt(withSeed({ 2.5 }, rand), 0.0))
        assertEquals(0.21728911064565182, valueAt(withSeed({ 2.5 }, rand), 0.5))
        assertEquals(0.40994881466031075, valueAt(withSeed({ 2.5 }, rand), 1.0))
        // the function gets the seed that is there, or undefined
        var seen: Any? = null
        withSeed({ s -> seen = s; s }, rand).queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(Undefined, seen)
        withSeed({ s -> seen = s; s }, rand).queryArc(Rational.ZERO, Rational.ONE, mapOf(RAND_SEED_KEY to 7.0))
        assertEquals(7.0, seen)
        // seeds nest: the inner one sees what the outer one set
        assertEquals(
            rand.queryArc(Rational.ZERO, Rational.ONE, mapOf(RAND_SEED_KEY to 3.0))[0].value,
            withSeed({ 2.0 }, withSeed({ s -> (s as Double) + 1.0 }, rand)).queryArc(Rational.ZERO, Rational.ONE)[0].value,
        )
        // withSeed keeps the steps of the pattern, and the other controls
        assertEquals(Rational(4), withSeed({ 1.0 }, fastcat("a", "b", "c", "d")).steps)
        assertEquals(
            "x",
            withSeed({ 1.0 }, Pattern { state -> listOf(Hap(null, state.span, state.controls["id"])) })
                .queryArc(Rational.ZERO, Rational.ONE, mapOf("id" to "x"))[0].value,
        )
    }

    @Test
    fun perlinAndBerlinAtATime() {
        val perlinAt = listOf(
            0.0 to 0.0, 0.1 to 0.004447280933260919, 0.25 to 0.05378073193423916, 0.5 to 0.25977108255028725,
            0.9 to 0.5150948841673144, 1.0 to 0.5195421651005745, 1.5 to 0.739534642547369, 2.75 to 0.29412234208211885,
            10.3 to 0.21559249657146662, -0.5 to 0.260244095697999, -1.25 to 0.5659260944812559, 123.456 to 0.32005983600124877,
        )
        for ((t, value) in perlinAt) assertEquals(value, valueAt(perlin, t), "perlin at $t")
        val berlinAt = listOf(
            0.0 to 0.0, 0.1 to 0.025977108255028726, 0.25 to 0.06494277063757181, 0.5 to 0.12988554127514362,
            0.9 to 0.23379397429525853, 1.0 to 0.25977108255028725, 1.5 to 0.4996528625488281, 2.75 to 0.5612469764892012,
            10.3 to 0.1532737546600403, -0.5 to 0.260244095697999, -1.25 to 0.6749008125625551, 123.456 to 0.21723988775909014,
        )
        for ((t, value) in berlinAt) assertEquals(value, valueAt(berlin, t), "berlin at $t")
        assertEquals(0.20317544349018135, valueAt(withSeed({ 3.0 }, perlin), 0.25))
        assertEquals(0.5155037306722079, valueAt(withSeed({ 3.0 }, perlin), 2.75))
        assertEquals(0.11876277532428503, valueAt(withSeed({ 3.0 }, berlin), 0.25))
        assertEquals(0.2536306942347437, valueAt(withSeed({ 3.0 }, berlin), 2.75))
    }

    @Test
    fun perlinAndBerlinWithThePreciseGenerator() {
        Rng.mode = RngMode.PRECISE
        assertEquals(0.3880893050227314, valueAt(perlin, 0.0))
        assertEquals(0.3601552494128555, valueAt(perlin, 0.25))
        assertEquals(0.6113485062564905, valueAt(perlin, 2.75))
        assertEquals(0.5900477153030663, valueAt(perlin, 123.456))
        assertEquals(0.1940446525113657, valueAt(berlin, 0.0))
        assertEquals(0.2088241258461494, valueAt(berlin, 0.25))
        assertEquals(0.5211972813413013, valueAt(berlin, 2.75))
        assertEquals(0.14668818982318044, valueAt(withSeed({ 3.0 }, rand), 0.0))
        assertEquals(0.8358188336715102, valueAt(withSeed({ 3.0 }, rand), 0.5))
    }

    @Test
    fun rand2IsBipolarAndBrandIsABoolean() {
        assertEquals(0.6852155700325966 * 2 - 1, valueAt(rand2, 0.125))
        // `rand < p`
        assertEquals(false, valueAt(brand, 0.125))
        assertEquals(true, valueAt(brand, 0.5))
        assertEquals(true, valueAt(_brandBy(0.7), 0.125))
        assertEquals(false, valueAt(_brandBy(0.6), 0.125))
        val trues = (0 until 1000).count { valueAt(_brandBy(0.25), it / 7.0) == true }
        assertTrue(trues in 200..300, "$trues of 1000")
        // the probability can be a pattern, and splits the signal like the cycles of it
        val haps = brandBy(slowcat(0.3, 0.9)).queryArc(Rational.ZERO, Rational(2))
        assertEquals(listOf("0/1 → 1/1", "1/1 → 2/1"), haps.map { it.part.show() })
        assertTrue(haps.all { it.value is Boolean })
        assertTrue(haps.all { it.whole == null })
    }

    @Test
    fun irandIsAWholeNumberBelowItsArgument() {
        assertEquals(5.0, valueAt(_irand(8), 0.125))
        assertEquals(0.0, valueAt(_irand(8), 0.0))
        for (i in 0 until 200) {
            val v = valueAt(_irand(8), i * 0.37) as Double
            assertTrue(v == Math.floor(v) && v >= 0.0 && v < 8.0, "$v")
        }
        // the argument is a pattern: it splits the signal at the cycles of the argument, and each cycle gets its own
        val haps = irand(8.0).queryArc(Rational.ZERO, Rational(2))
        assertEquals(listOf("0/1 → 1/1" to 0.0, "1/1 → 2/1" to 4.0), haps.map { it.part.show() to it.value })
        assertTrue(haps.all { it.whole == null })
        assertEquals(listOf(0.0, 4.0), irand(slowcat(2.0, 8.0)).queryArc(Rational.ZERO, Rational(2)).map { it.value })
    }

    @Test
    fun randLIsAListOfAbsoluteValues() {
        assertEquals(
            listOf(0.36975969187915325, 0.18273563869297504, 0.012781793251633644),
            randL(3.0).queryArc(Rational(1, 4), Rational(1, 2))[0].value,
        )
        assertEquals(
            listOf(0.6084080748260021, 0.4424846563488245, 0.755185678601265),
            randL(3.0).queryArc(Rational(3, 2), Rational(2))[0].value,
        )
        // exactly one number is not a list, and Strudel fails to make it into one
        assertEquals(
            "getRandsAtTime(...).map is not a function",
            assertFailsWith<CosicError> { randL(1.0).queryArc(Rational.ZERO, Rational.ONE) }.message,
        )
        assertEquals(emptyList<Double>(), randL(0.0).queryArc(Rational.ZERO, Rational.ONE)[0].value)
        assertFailsWith<CosicError> { randL(1.0e9).queryArc(Rational.ZERO, Rational.ONE) }
    }

    @Test
    fun randrunIsAPermutationEachCycle() {
        val cycles = listOf(
            listOf(2.0, 1.0, 3.0, 0.0), listOf(2.0, 0.0, 3.0, 1.0), listOf(1.0, 0.0, 2.0, 3.0), listOf(0.0, 2.0, 3.0, 1.0),
            listOf(2.0, 1.0, 0.0, 3.0), listOf(3.0, 1.0, 0.0, 2.0), listOf(0.0, 3.0, 2.0, 1.0), listOf(2.0, 1.0, 0.0, 3.0),
        )
        val run4 = randrun(4.0)
        for ((cycle, order) in cycles.withIndex()) assertEquals(order, run4.values(cycle.toLong()), "cycle $cycle")
        val run5 = randrun(5.0)
        assertEquals(listOf(2.0, 1.0, 3.0, 0.0, 4.0), run5.values(0))
        assertEquals(listOf(2.0, 0.0, 3.0, 4.0, 1.0), run5.values(1))
        assertEquals(listOf(1.0, 0.0, 4.0, 2.0, 3.0), run5.values(2))
        assertEquals(listOf(0.0, 2.0, 4.0, 3.0, 1.0), run5.values(3))
        // seed 2 reads the numbers of two cycles later
        assertEquals(cycles[2], withSeed({ 2.0 }, run4).values(0))
        assertEquals(cycles[3], withSeed({ 2.0 }, run4).values(1))
        // every cycle has every number once
        for (cycle in 0L until 40L) assertEquals(listOf(0.0, 1.0, 2.0, 3.0), run4.values(cycle).map { it as Double }.sorted())
        assertEquals(listOf(0.0), randrun(1.0).values(3))
    }

    @Test
    fun randrunIsASegmentedSignal() {
        val haps = randrun(4.0).queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(listOf("0/1 → 1/4", "1/4 → 1/2", "1/2 → 3/4", "3/4 → 1/1"), haps.map { it.part.show() })
        assertEquals(listOf("0/1 → 1/4", "1/4 → 1/2", "1/2 → 3/4", "3/4 → 1/1"), haps.map { it.whole!!.show() })
    }

    @Test
    fun randrunWithAnAwkwardCount() {
        // no numbers to put in order: nothing plays
        assertEquals(emptyList(), randrun(0.0).queryArc(Rational.ZERO, Rational.ONE))
        assertEquals(emptyList(), randrun(-2.0).queryArc(Rational.ZERO, Rational.ONE))
        // asking for more than a cycle can hold is refused, by the budget of the query or by the generator
        assertFailsWith<CosicError> { withQueryBudget { randrun(1.0e9).queryArc(Rational.ZERO, Rational.ONE) } }
    }

    @Test
    fun miniRandIsRandShiftedBy0003PerSeed() {
        assertEquals(valueAt(rand, 0.125), valueAt(miniRand(0), 0.125))
        assertEquals(valueAt(rand, 0.125 + 0.0003 * 2), valueAt(miniRand(2), 0.125))
        assertEquals(valueAt(rand, 0.5 + 0.0003 * 5), valueAt(miniRand(5), 0.5))
        assertEquals(0.0003, MINI_RAND_OFFSET)
    }

    // ---- degrading and applying at random ----------------------------------------------------------------------------

    @Test
    fun degradeKeepsTheEventsOfStrudelsSnapshot() {
        assertEquals(
            listOf(
                "[ 1/8 → 1/4 | s:hh ]", "[ 1/1 → 9/8 | s:hh ]", "[ 9/8 → 5/4 | s:hh ]", "[ 5/4 → 11/8 | s:hh ]", "[ 3/2 → 13/8 | s:hh ]",
                "[ 15/8 → 2/1 | s:hh ]", "[ 2/1 → 17/8 | s:hh ]", "[ 17/8 → 9/4 | s:hh ]", "[ 19/8 → 5/2 | s:hh ]", "[ 11/4 → 23/8 | s:hh ]",
                "[ 13/4 → 27/8 | s:hh ]", "[ 29/8 → 15/4 | s:hh ]", "[ 15/4 → 31/8 | s:hh ]", "[ 31/8 → 4/1 | s:hh ]",
            ),
            hh()._degrade().lines(0, 4),
        )
        assertEquals(hh()._degrade().lines(0, 4), hh()._degradeBy(0.5).lines(0, 4))
        assertEquals(emptyList(), hh()._degradeBy(1.0).lines(0, 4))
        // an event is kept when the random number is greater than the amount, so even 0 loses the one at time 0, where the number is 0
        // (the number at time 0 is 0)
        assertTrue("[ 0/1 → 1/8 | s:hh ]" !in hh()._degradeBy(0.0).lines(0, 1))
        assertEquals(31, hh()._degradeBy(0.0).lines(0, 4).size)
    }

    @Test
    fun degradeByAndUndegradeByAreComplements() {
        for (x in listOf(0.2, 0.5, 0.8)) {
            val kept = hh(10.0)._degradeBy(x).queryArc(Rational.ZERO, Rational(4)).map { it.whole!!.begin }.toSet()
            val others = hh(10.0)._undegradeBy(1.0 - x).queryArc(Rational.ZERO, Rational(4)).map { it.whole!!.begin }.toSet()
            val all = hh(10.0).queryArc(Rational.ZERO, Rational(4)).map { it.whole!!.begin }.toSet()
            assertEquals(all, kept + others, "x = $x")
            assertTrue((kept intersect others).isEmpty(), "x = $x")
        }
    }

    @Test
    fun undegradeByKeepsTheEventsOfStrudelsSnapshot() {
        // `s("hh*10").layer(x => x.degradeBy(0.2).pan(0), x => x.undegradeBy(0.8).pan(1))`: the first two events of the first cycle have pan 1
        val kept = hh(10.0)._undegradeBy(0.8).queryArc(Rational.ZERO, Rational.ONE).map { it.whole!!.begin.show() }
        assertEquals(listOf("0/1", "1/10"), kept)
        assertEquals(
            listOf("19/10"),
            hh(10.0)._undegradeBy(0.8).queryArc(Rational.ONE, Rational(2)).map { it.whole!!.begin.show() },
        )
        assertEquals(hh(8.0)._undegrade().lines(0, 4), hh(8.0)._undegradeBy(0.5).lines(0, 4))
    }

    @Test
    fun degradeByWithATestPattern() {
        // the test is read at the start of each event: keep it when it is greater than the amount
        val ramp = fastcat(0.1, 0.6)
        val events = fastcat("a", "b")
        assertEquals(listOf("b"), events._degradeByWith(ramp, 0.5).values(0))
        assertEquals(listOf("a", "b"), events._degradeByWith(ramp, 0.05).values(0))
        // a plain number is made a pattern, and the comparison is strict
        assertEquals(listOf("a", "b"), events._degradeByWith(0.6, 0.5).values(0))
        assertEquals(emptyList(), events._degradeByWith(0.5, 0.5).values(0))
        assertEquals(emptyList(), events._degradeByWith(0.5, Undefined).values(0))
        // a signal is read at the start of the whole of each event
        assertEquals(listOf("c", "d"), fastcat("a", "b", "c", "d")._degradeByWith(saw, 0.3).values(0))
        // steps stay the steps of the pattern
        assertEquals(Rational(2), events._degradeByWith(ramp, 0.5).steps)
        assertEquals(Rational(4), fastcat("a", "b", "c", "d")._degradeBy(0.5).steps)
    }

    @Test
    fun seedChangesWhatIsDegraded() {
        val bd = pure(linkedMapOf<String, Any?>("s" to "bd"))._fast(4.0)
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | s:bd ]", "[ 1/4 → 1/2 | s:bd ]", "[ 1/2 → 3/4 | s:bd ]", "[ 1/1 → 5/4 | s:bd ]", "[ 7/4 → 2/1 | s:bd ]",
                "[ 9/4 → 5/2 | s:bd ]", "[ 11/4 → 3/1 | s:bd ]",
            ),
            withSeed({ 1.0 }, bd._degrade()).lines(0, 4),
        )
        assertEquals(bd._degrade().lines(0, 4), withSeed({ 0.0 }, bd._degrade()).lines(0, 4))
    }

    @Test
    fun sometimesByPlaysEachEventOnceAndTheFunctionOnSomeOfThem() {
        val haps = hh()._sometimesBy(0.4, faster).queryArc(Rational.ZERO, Rational(4))
        // every event is there once, with or without the speed
        assertEquals(32, haps.size)
        assertEquals(32, haps.map { it.whole!!.begin }.toSet().size)
        val speeded = haps.count { (it.value as Map<*, *>).containsKey("speed") }
        assertTrue(speeded in 5..20, "$speeded of 32")
        // always and never are the ends
        assertEquals(32, hh()._sometimesBy(1.0, faster).speeded(0, 4))
        assertEquals(0, hh()._sometimesBy(0.0, faster).speeded(0, 4))
        // the probability is a pattern: 0 for the first cycle and 1 for the second
        val patterned = hh()._sometimesBy(slowcat(0.0, 1.0), faster)
        assertEquals(0, patterned.speeded(0, 1))
        assertEquals(8, patterned.speeded(1, 2))
        assertEquals(hh()._sometimes(faster).lines(0, 4), hh()._sometimesBy(0.5, faster).lines(0, 4))
    }

    @Test
    fun someCyclesByPlaysWholeCycles() {
        val result = hh()._someCyclesBy(0.5, faster)
        var withSpeed = 0
        var without = 0
        for (cycle in 0L until 40L) {
            val haps = result.queryArc(Rational(cycle), Rational(cycle + 1))
            val speeded = haps.count { (it.value as Map<*, *>).containsKey("speed") }
            // a cycle is all one or all the other
            assertTrue(haps.size == 8 && (speeded == 0 || speeded == 8), "cycle $cycle has $speeded of ${haps.size}")
            if (speeded == 0) without++ else withSpeed++
        }
        assertTrue(withSpeed > 5 && without > 5, "$withSpeed cycles with the function, $without without")
        assertEquals(hh()._someCycles(faster).lines(0, 4), hh()._someCyclesBy(0.5, faster).lines(0, 4))
    }

    // ---- choosing ----------------------------------------------------------------------------------------------------

    @Test
    fun chooseCyclesPicksOnePerCycle() {
        val picked = chooseCycles(listOf("bd", "hh", "sd"))
        val seen = (0L until 30L).map { picked.values(it) }
        assertTrue(seen.all { it.size == 1 && it[0] in listOf("bd", "hh", "sd") })
        assertEquals(3, seen.map { it[0] }.toSet().size)
        // a pattern that is chosen plays from its own start, whatever cycle it is chosen in
        val patterns = chooseCycles(listOf(fastcat("a", "b"), "c"))
        for (cycle in 0L until 10L) {
            val values = patterns.values(cycle)
            assertTrue(values == listOf("a", "b") || values == listOf("c"), "cycle $cycle: $values")
        }
    }

    @Test
    fun chooseWithPicksByTheNumbersOfAPattern() {
        // 0..1 is cut into as many equal parts as there are values
        val numbers = fastcat(0.0, 0.2, 0.5, 0.7, 0.99)
        assertEquals(listOf("a", "a", "b", "c", "c"), chooseWith(numbers, listOf("a", "b", "c")).values(0))
        assertEquals(listOf("a", "a", "b", "c", "c"), chooseInWith(numbers, listOf("a", "b", "c")).values(0))
        // numbers outside of it stay at the ends
        assertEquals(listOf("a", "c"), chooseWith(fastcat(-5.0, 7.0), listOf("a", "b", "c")).values(0))
        // nothing to choose from is silence
        assertEquals(emptyList(), chooseWith(numbers, emptyList()).values(0))
        assertEquals(emptyList(), choose(emptyList()).values(0))
        assertEquals(silence.steps, choose(emptyList()).steps)
    }

    @Test
    fun theStructureOfChooseWithComesFromTheChoosingPattern() {
        // `chooseWith` keeps the wholes of the choosing pattern, `chooseInWith` those of the chosen values
        val numbers = fastcat(0.0, 0.9)
        val values = listOf(fastcat("a", "b", "c", "d"), "z")
        assertEquals(
            listOf("[ (0/1 → 1/4) ⇝ 1/2 | a ]", "[ 0/1 ⇜ (1/4 → 1/2) | b ]", "[ 1/2 → 1/1 | z ]"),
            chooseWith(numbers, values).lines(0, 1),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 0/1 ⇜ (1/2 → 1/1) | z ]"),
            chooseInWith(numbers, values).lines(0, 1),
        )
    }

    @Test
    fun chooseHasARandomNumberForAPicker() {
        val seen = (0L until 40L).map { choose(listOf("a", "b", "c")).queryArc(Rational(it, 7), Rational(it + 1, 7))[0].value }
        assertEquals(setOf("a", "b", "c"), seen.toSet())
        // `choose` is `chooseWith(rand, ...)`
        assertEquals(shown(chooseWith(rand, listOf("a", "b", "c"))), shown(choose(listOf("a", "b", "c"))))
        assertEquals(shown(chooseInWith(rand, listOf("a", "b", "c"))), shown(chooseIn(listOf("a", "b", "c"))))
    }

    @Test
    fun weightedChoiceFollowsTheWeights() {
        val first = wchooseCycles(listOf(listOf("a", 1.0), listOf("b", 0.0)))
        for (cycle in 0L until 20L) assertEquals(listOf("a"), first.values(cycle))
        val second = wchooseCycles(listOf(listOf("a", 0.0), listOf("b", 3.0)))
        for (cycle in 0L until 20L) assertEquals(listOf("b"), second.values(cycle))
        val both = wchooseCycles(listOf(listOf("a", 1.0), listOf("b", 3.0)))
        val counts = (0L until 600L).map { both.values(it)[0] }.groupingBy { it }.eachCount()
        assertTrue(counts.getValue("b") > counts.getValue("a") * 2, "$counts")
        assertTrue(counts.getValue("a") in 100..220, "$counts")
    }

    @Test
    fun weightedChoiceWithPatternsAndLists() {
        // the weights are numbers or patterns of numbers, and so are the values
        val patterned = wchooseCycles(listOf(listOf("a", slowcat(1.0, 0.0)), listOf("b", slowcat(0.0, 1.0))))
        for (cycle in 0L until 12L) assertEquals(listOf(if (cycle % 2 == 0L) "a" else "b"), patterned.values(cycle), "cycle $cycle")
        assertEquals(listOf("x", "y"), wchooseCycles(listOf(listOf(fastcat("x", "y"), 1.0))).values(0))
        // continuous: no wholes, since the numbers it chooses with have none
        val continuous = wchoose(listOf(listOf("a", 1.0), listOf("b", 1.0)))
        assertTrue(continuous.queryArc(Rational.ZERO, Rational.ONE).all { it.whole == null })
        assertFailsWith<CosicError> { wchoose(listOf("a", "b")) }
    }

    // ---- shuffling ---------------------------------------------------------------------------------------------------

    private fun notes(vararg names: String): Pattern = fastcat(names.map { linkedMapOf<String, Any?>("note" to it, "s" to "piano") })

    @Test
    fun shuffleIsTheSnapshotOfStrudel() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | note:e s:piano ]", "[ 1/4 → 1/2 | note:d s:piano ]", "[ 1/2 → 3/4 | note:f s:piano ]", "[ 3/4 → 1/1 | note:c s:piano ]",
                "[ 1/1 → 5/4 | note:e s:piano ]", "[ 5/4 → 3/2 | note:c s:piano ]", "[ 3/2 → 7/4 | note:f s:piano ]", "[ 7/4 → 2/1 | note:d s:piano ]",
                "[ 2/1 → 9/4 | note:d s:piano ]", "[ 9/4 → 5/2 | note:c s:piano ]", "[ 5/2 → 11/4 | note:e s:piano ]", "[ 11/4 → 3/1 | note:f s:piano ]",
                "[ 3/1 → 13/4 | note:c s:piano ]", "[ 13/4 → 7/2 | note:e s:piano ]", "[ 7/2 → 15/4 | note:f s:piano ]", "[ 15/4 → 4/1 | note:d s:piano ]",
            ),
            notes("c", "d", "e", "f")._shuffle(4.0).lines(0, 4),
        )
    }

    @Test
    fun scrambleIsTheSnapshotOfStrudel() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | note:c s:piano ]", "[ 1/4 → 1/2 | note:d s:piano ]", "[ 1/2 → 3/4 | note:d s:piano ]", "[ 3/4 → 1/1 | note:c s:piano ]",
                "[ 1/1 → 5/4 | note:e s:piano ]", "[ 5/4 → 3/2 | note:e s:piano ]", "[ 3/2 → 7/4 | note:e s:piano ]", "[ 7/4 → 2/1 | note:c s:piano ]",
                "[ 2/1 → 9/4 | note:f s:piano ]", "[ 9/4 → 5/2 | note:d s:piano ]", "[ 5/2 → 11/4 | note:d s:piano ]", "[ 11/4 → 3/1 | note:e s:piano ]",
                "[ 3/1 → 13/4 | note:c s:piano ]", "[ 13/4 → 7/2 | note:f s:piano ]", "[ 7/2 → 15/4 | note:d s:piano ]", "[ 15/4 → 4/1 | note:f s:piano ]",
            ),
            notes("c", "d", "e", "f")._scramble(4.0).lines(0, 4),
        )
    }

    @Test
    fun shuffleOfOnePartIsThePatternAndEachPartPlaysOncePerCycle() {
        val abcd = fastcat("a", "b", "c", "d")
        assertEquals(abcd.lines(0, 1), abcd._shuffle(1.0).lines(0, 1))
        for (cycle in 0L until 20L) {
            assertEquals(listOf("a", "b", "c", "d"), abcd._shuffle(4.0).values(cycle).map { it as String }.sorted(), "cycle $cycle")
        }
        // two parts: the halves in one order or the other
        for (cycle in 0L until 20L) {
            val v = abcd._shuffle(2.0).values(cycle)
            assertTrue(v == listOf("a", "b", "c", "d") || v == listOf("c", "d", "a", "b"), "cycle $cycle: $v")
        }
        // a count that is not a whole number cannot be turned into slices
        assertEquals("Invalid array length", assertFailsWith<CosicError> { abcd._shuffle(2.5) }.message)
        assertEquals("Invalid array length", assertFailsWith<CosicError> { abcd._scramble(-1.0) }.message)
        assertFailsWith<CosicError> { abcd._shuffle(1.0e9) }
        assertEquals(emptyList(), abcd._shuffle(0.0).values(0))
    }

    @Test
    fun scrambleMayRepeatOrOmitParts() {
        val abcd = fastcat("a", "b", "c", "d")
        val cycles = (0L until 30L).map { abcd._scramble(4.0).values(it) }
        assertTrue(cycles.any { it.toSet().size < 4 }, "some cycle should repeat a part")
        assertTrue(cycles.all { it.size == 4 && it.all { v -> v in listOf("a", "b", "c", "d") } })
    }

    // ---- the catalogue -----------------------------------------------------------------------------------------------

    @Test
    fun everyNameOfTheCategoryIsRegistered() {
        for (name in listOf("rand", "rand2", "brand", "perlin", "berlin")) assertNotNull(Registry.constants()[name], name)
        val functions = listOf(
            "_brandBy", "brandBy", "_irand", "irand", "randL", "randrun", "useRNG", "getRandsAtTime", "withSeed", "seed", "__chooseWith",
            "chooseWith", "chooseInWith", "choose", "chooseIn", "chooseCycles", "randcat", "wchoose", "wchooseCycles", "wrandcat", "shuffle",
            "scramble", "degradeByWith", "degradeBy", "degrade", "undegradeBy", "undegrade", "sometimesBy", "sometimes", "someCyclesBy",
            "someCycles", "often", "rarely", "almostNever", "almostAlways", "never", "always",
        )
        for (name in functions) assertNotNull(Registry.lookup(name), name)
        // the functions of patterns are methods too, the ones that make a pattern are not
        for (name in listOf("seed", "shuffle", "scramble", "degradeByWith", "degradeBy", "degrade", "sometimes", "always", "choose")) {
            assertTrue(Registry.lookup(name)!!.hasMethod, name)
        }
        assertEquals(false, Registry.lookup("irand")!!.hasMethod)
    }

    @Test
    fun aRegisteredFunctionCanBeCalledAsAMethodWithPatternedArguments() {
        val abcd = fastcat("a", "b", "c", "d")
        assertEquals(abcd._shuffle(4.0).lines(0, 2), abcd.callMethod("shuffle", 4.0).lines(0, 2))
        assertEquals(abcd._degradeBy(0.5).lines(0, 4), abcd.callMethod("degradeBy", 0.5).lines(0, 4))
        assertEquals(abcd._degradeBy(0.5).lines(0, 4), abcd.callMethod("degrade").lines(0, 4))
        assertEquals(Rational(4), abcd.callMethod("degradeBy", 0.5).steps)
        // with a pattern of amounts the first cycle plays as with -1 (all of the events), the second with 1 (none)
        val patterned = abcd.callMethod("degradeBy", slowcat(-1.0, 1.0))
        assertEquals(listOf("a", "b", "c", "d"), patterned.values(0))
        assertEquals(emptyList(), patterned.values(1))
        // never leaves the pattern as it is and always applies the function
        val twice = jsFn(1) { args -> (args[0] as Pattern)._fast(2.0) }
        assertEquals(abcd.lines(0, 1), abcd.callMethod("never", twice).lines(0, 1))
        assertEquals(abcd._fast(2.0).lines(0, 1), abcd.callMethod("always", twice).lines(0, 1))
        assertEquals(abcd.callMethod("sometimesBy", 0.5, twice).lines(0, 2), abcd.callMethod("sometimes", twice).lines(0, 2))
    }

    @Test
    fun programsCanCallThem() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | s:bd n:2 ]", "[ 1/4 → 1/2 | s:bd n:1 ]", "[ 1/2 → 3/4 | s:bd n:3 ]", "[ 3/4 → 1/1 | s:bd n:0 ]"),
            evaluateProgram("""s("bd*4").n(randrun(4))""").pattern.lines(0, 1),
        )
        assertEquals(
            evaluateProgram("""s("bd*8").pan(rand)""").pattern.lines(0, 2),
            evaluateProgram("""s("bd*8").pan(rand.seed(0))""").pattern.lines(0, 2),
        )
        // a function of the program in `always` and `never`
        assertEquals(
            evaluateProgram("""s("hh*4").speed(2)""").pattern.lines(0, 2),
            evaluateProgram("""s("hh*4").always(x => x.speed(2))""").pattern.lines(0, 2),
        )
        assertEquals(
            evaluateProgram("""s("hh*4")""").pattern.lines(0, 2),
            evaluateProgram("""s("hh*4").never(x => x.speed(2))""").pattern.lines(0, 2),
        )
    }
}
