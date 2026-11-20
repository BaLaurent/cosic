package cosic.pattern

import cosic.parity.ParityData
import cosic.script.catalogue.Catalogue
import cosic.script.evaluateProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The continuous patterns that are not random. The waves are checked at the start of each quarter of a cycle (what
 * Strudel's `sig.test` does with `struct`), and with the cycle shifted by one, which must change nothing.
 */
class SignalsTest {
    init {
        Catalogue.installAll()
    }

    @AfterTest
    fun back() {
        PointerState.x = 0.0
        PointerState.y = 0.0
        KeyboardState.provider = null
        Rng.reset()
    }

    /** The value at the start of each quarter of the first cycle. */
    private fun Pattern.quarters(): List<Any?> = (0L until 4L).map { queryArc(Rational(it, 4), Rational(it + 1, 4)).single().value }

    private fun Pattern.at(t: Rational): Any? = queryArc(t, t + Rational(1, 1000)).single().value

    /** The values [signal] has for the events of [structure] (the signal is read over the whole of each event). */
    private fun Pattern.over(signal: Pattern): List<Any?> =
        fmap { _ -> jsFn(1) { a -> a[0] } }.appLeft(signal).sortHapsByPart().queryArc(Rational.ZERO, Rational.ONE).map { it.value }

    // ---- the waves ---------------------------------------------------------------------------------------------------

    private val waves: Map<String, Pair<Pattern, List<Double>>> = mapOf(
        "saw" to (saw to listOf(0.0, 0.25, 0.5, 0.75)),
        "saw2" to (saw2 to listOf(-1.0, -0.5, 0.0, 0.5)),
        "isaw" to (isaw to listOf(1.0, 0.75, 0.5, 0.25)),
        "isaw2" to (isaw2 to listOf(1.0, 0.5, 0.0, -0.5)),
        "tri" to (tri to listOf(0.0, 0.5, 1.0, 0.5)),
        "tri2" to (tri2 to listOf(-1.0, 0.0, 1.0, 0.0)),
        "itri" to (itri to listOf(1.0, 0.5, 0.0, 0.5)),
        "itri2" to (itri2 to listOf(1.0, 0.0, -1.0, 0.0)),
        "square" to (square to listOf(0.0, 0.0, 1.0, 1.0)),
        "square2" to (square2 to listOf(-1.0, -1.0, 1.0, 1.0)),
        "isquare" to (isquare to listOf(1.0, 1.0, 0.0, 0.0)),
        "isquare2" to (isquare2 to listOf(1.0, 1.0, -1.0, -1.0)),
    )

    @Test
    fun wavesAtTheQuartersOfACycle() {
        for ((name, wave) in waves) assertEquals(wave.second, wave.first.quarters(), name)
    }

    @Test
    fun wavesDoNotChangeWhenTheCycleIsShiftedByOne() {
        for ((name, wave) in waves) assertEquals(wave.second, wave.first._late(1.0).quarters(), "$name late 1")
        for ((name, wave) in waves) assertEquals(wave.second, wave.first._early(3.0).quarters(), "$name early 3")
    }

    @Test
    fun theSineIsASineBetweenZeroAndOne() {
        assertEquals(listOf(0.5, 1.0, 0.5000000000000001, 0.0), sine.quarters())
        // (sin + 1) / 2, not 0.5 + 0.5 * sin: the two differ in the last digit
        assertEquals(0.5000000000000001, sine.at(Rational(1, 2)))
        assertEquals(listOf(0.0, 1.0, 1.2246467991473532e-16, -1.0), sine2.quarters())
        assertEquals(0.0, sine2.at(Rational.ZERO))
        assertEquals(1.0, sine2.at(Rational(1, 4)))
        assertEquals(-1.0, sine2.at(Rational(3, 4)))
        assertEquals(1.0, cosine.at(Rational.ZERO))
        assertEquals(1.0, cosine2.at(Rational.ZERO))
        assertEquals(-1.0, cosine2.at(Rational(1, 2)))
    }

    @Test
    fun theSawIsModNotTheCyclePosition() {
        // `_mod(t, 1)` for a third is not exactly a third's position in the cycle, and the snapshots show it
        assertEquals(0.33333333333333326, saw.at(Rational(1, 3)))
        assertEquals(0.75, saw.at(Rational(-1, 4)))
        assertEquals(0.25, saw.at(Rational(-3, 4)))
        assertEquals(0.0, saw.at(Rational(-1)))
    }

    @Test
    fun theSquareChangesAtTheMiddleOfTheCycle() {
        assertEquals(0.0, square.at(Rational(2, 5)))
        assertEquals(1.0, square.at(Rational(1, 2)))
        assertEquals(1.0, square.at(Rational(9, 10)))
        assertEquals(0.0, square.at(Rational(11, 10)))
        assertEquals(1.0, isquare.at(Rational(2, 5)))
        assertEquals(0.0, isquare.at(Rational(1, 2)))
    }

    @Test
    fun theTriangleIsTwoSignalsOneAfterTheOther() {
        val haps = tri.queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(listOf("0/1 → 1/2" to 0.0, "1/2 → 1/1" to 1.0), haps.map { it.part.show() to it.value })
        assertTrue(haps.all { it.whole == null })
        assertEquals(1.0, tri.at(Rational(1, 2)))
        assertEquals(0.5, tri.at(Rational(3, 4)))
        assertEquals(0.5, tri.at(Rational(5, 4)))
    }

    @Test
    fun signalsAreSampledAtTheStartOfTheSpanAndNeverCutAtCycles() {
        val haps = sine.queryArc(Rational(1, 4), Rational(1, 2))
        assertEquals(1, haps.size)
        assertNull(haps[0].whole)
        assertEquals(TimeSpan(Rational(1, 4), Rational(1, 2)), haps[0].part)
        assertEquals(1.0, haps[0].value)
        val long = saw.queryArc(Rational(1, 2), Rational(3, 2))
        assertEquals(1, long.size)
        assertEquals(0.5, long[0].value)
        // a zero-width query still has an answer
        assertEquals(1, saw.queryArc(Rational(1, 3), Rational(1, 3)).size)
    }

    @Test
    fun rangeSplitsAContinuousPatternAtCycles() {
        // `range` is `mul` and `add` with an `in` alignment, which cut the signal at cycle boundaries
        assertEquals(2, sine.callMethod("range", 0.0, 1.0).queryArc(Rational(1, 2), Rational(3, 2)).size)
        assertEquals(1, sine.queryArc(Rational(1, 2), Rational(3, 2)).size)
    }

    // ---- time and the length of events ------------------------------------------------------------------------------------

    @Test
    fun timeIsTheTimeInCycles() {
        assertEquals(1.5, time.queryArc(Rational(3, 2), Rational(2))[0].value)
        assertEquals(0.0, time.at(Rational.ZERO))
        assertEquals(-2.5, time.at(Rational(-5, 2)))
    }

    private val rhythm = fastcat(1.0, 1.0, 1.0, fastcat(1.0, 1.0))

    @Test
    fun cyclesPerIsTheDurationOfTheEvents() {
        assertEquals(listOf(0.25, 0.25, 0.25, 0.125, 0.125), rhythm.over(cyclesPer))
    }

    @Test
    fun perIsHowManyEventsLikeThisFitInACycle() {
        assertEquals(listOf(4.0, 4.0, 4.0, 8.0, 8.0), rhythm.over(per))
        // a third of a cycle is exactly three events: the division is done on fractions
        assertEquals(listOf(3.0, 3.0, 3.0), fastcat(1.0, 1.0, 1.0).over(per))
    }

    @Test
    fun perxGoesUpByOneWhenTheDurationHalves() {
        val nested = fastcat(1.0, 1.0, 1.0, fastcat(1.0, fastcat(1.0, 1.0)))
        assertEquals(listOf(3.0, 3.0, 3.0, 4.0, 5.0, 5.0), nested.over(perx))
    }

    @Test
    fun theLengthOfAnEventNeedsAnEvent() {
        // a zero-width query has no duration to divide by
        assertFailsWith<CosicError> { per.queryArc(Rational(1, 2), Rational(1, 2)) }
        assertEquals(0.0, cyclesPer.queryArc(Rational(1, 2), Rational(1, 2))[0].value)
    }

    @Test
    fun openAndCloseStayAtTheirEnd() {
        assertEquals(0.0, openSignal.at(Rational.ZERO))
        assertEquals(0.5, openSignal.at(Rational(1, 2)))
        assertEquals(1.0, openSignal.at(Rational.ONE))
        assertEquals(1.0, openSignal.at(Rational(7)))
        assertEquals(1.0, closeSignal.at(Rational.ZERO))
        assertEquals(0.75, closeSignal.at(Rational(1, 4)))
        assertEquals(0.0, closeSignal.at(Rational.ONE))
        assertEquals(0.0, closeSignal.at(Rational(3)))
        // before time zero they go on the same way
        assertEquals(-3.0, openSignal.at(Rational(-3)))
        assertEquals(4.0, closeSignal.at(Rational(-3)))
    }

    // ---- making signals ----------------------------------------------------------------------------------------------

    @Test
    fun aSignalIsAFunctionOfTheStartOfTheSpan() {
        val doubled = signal { t, _ -> t.toDouble() * 2 }
        assertEquals(0.5, doubled.queryArc(Rational(1, 4), Rational(1, 2))[0].value)
        val seeded = signal { _, controls -> controls["id"] }
        assertEquals("x", seeded.queryArc(Rational.ZERO, Rational.ONE, mapOf("id" to "x"))[0].value)
        val steadyPattern = steady("a")
        val haps = steadyPattern.queryArc(Rational.ZERO, Rational(2))
        assertEquals(1, haps.size)
        assertNull(haps[0].whole)
        assertEquals("a", haps[0].value)
    }

    // ---- the pointer and the keyboard ---------------------------------------------------------------------------------

    @Test
    fun thePointerIsZeroUntilSomethingMovesIt() {
        assertEquals(0.0, mousex.at(Rational.ZERO))
        assertEquals(0.0, mousey.at(Rational.ZERO))
        PointerState.x = 0.25
        PointerState.y = 0.75
        assertEquals(0.25, mousex.at(Rational.ONE))
        assertEquals(0.75, mousey.at(Rational.ONE))
    }

    @Test
    fun noKeyboardIsAnErrorWhenAKeyIsAskedFor() {
        assertEquals(true, _keyDown(emptyList<Any?>()))
        assertEquals(
            "Cannot read properties of undefined (reading 'Shift')",
            assertFailsWith<CosicError> { _keyDown("shift") }.message,
        )
        assertEquals(
            "Cannot read properties of undefined (reading 'Control')",
            assertFailsWith<CosicError> { _keyDown(listOf("Control", "j")) }.message,
        )
        // as a pattern the error is the one of the query: Strudel's queryArc swallows it and answers nothing, and so does the harness
        assertFailsWith<CosicError> { pure("a").callMethod("keyDown").queryArc(Rational.ZERO, Rational.ONE) }
    }

    @Test
    fun keysAreHeldWhenTheKeyboardSaysSo() {
        KeyboardState.provider = { mapOf("Control" to true, "j" to false, "ArrowUp" to true, "Shift" to false, "a" to true) }
        assertTrue(_keyDown("a"))
        assertFalse(_keyDown("j"))
        assertFalse(_keyDown("b"))
        // the names of Strudel's alias table
        assertTrue(_keyDown("control"))
        assertTrue(_keyDown("ctrl"))
        assertTrue(_keyDown("up"))
        assertFalse(_keyDown("shift"))
        assertFalse(_keyDown("down"))
        // a list asks for all of them
        assertTrue(_keyDown(listOf("Control", "a")))
        assertFalse(_keyDown(listOf("Control", "j")))
        assertTrue(_keyDown(emptyList<Any?>()))
        assertEquals(listOf(true, false), fastcat("a", "j").callMethod("keyDown").values())
    }

    private fun Pattern.values(): List<Any?> = queryArc(Rational.ZERO, Rational.ONE).sortedBy { it.part.begin }.map { it.value }

    // ---- the catalogue -----------------------------------------------------------------------------------------------

    @Test
    fun everyNameOfTheCategoryIsRegistered() {
        val constants = listOf(
            "saw", "saw2", "isaw", "isaw2", "sine", "sine2", "cosine", "cosine2", "square", "square2", "isquare", "isquare2", "tri", "tri2",
            "itri", "itri2", "time", "cyclesPer", "per", "perCycle", "perx", "open", "close", "mousex", "mouseX", "mousey", "mouseY",
        )
        for (name in constants) assertNotNull(Registry.constants()[name], name)
        assertEquals(Registry.constants()["per"], Registry.constants()["perCycle"])
        assertEquals(Registry.constants()["mousex"], Registry.constants()["mouseX"])
        for (name in listOf("signal", "steady", "_keyDown", "keyDown", "whenKey")) assertNotNull(Registry.lookup(name), name)
        assertTrue(Registry.lookup("keyDown")!!.hasMethod)
        assertTrue(Registry.lookup("whenKey")!!.hasMethod)
    }

    @Test
    fun programsSeeTheSignals() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | 0 ]", "[ 1/4 → 1/2 | 0.25 ]", "[ 1/2 → 3/4 | 0.5 ]", "[ 3/4 → 1/1 | 0.75 ]"),
            evaluateProgram("signal(t => t % 1).segment(4)").pattern.lines(0, 1),
        )
        // the function gets the time as a fraction, which works like a number
        assertEquals(
            listOf("[ 0/1 → 1/2 | 0 ]", "[ 1/2 → 1/1 | 0.25 ]"),
            evaluateProgram("signal(t => Math.pow(t, 2) % 1).segment(2)").pattern.lines(0, 1),
        )
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | s:bd pan:0 ]", "[ 1/4 → 1/2 | s:bd pan:0.25 ]", "[ 1/2 → 3/4 | s:bd pan:0.5 ]", "[ 3/4 → 1/1 | s:bd pan:0.75 ]",
            ),
            evaluateProgram("""s("bd*4").pan(saw)""").pattern.lines(0, 1),
        )
        assertEquals(
            evaluateProgram("""s("bd*4").pan(mousex)""").pattern.lines(0, 1),
            evaluateProgram("""s("bd*4").pan(mouseX)""").pattern.lines(0, 1),
        )
        assertEquals(
            listOf("[ 0/1 → 1/2 | 7 ]", "[ 1/2 → 1/1 | 7 ]"),
            evaluateProgram("steady(7).segment(2)").pattern.lines(0, 1),
        )
    }

    // ---- Strudel's own values ----------------------------------------------------------------------------------------

    private val vectorPatterns: Map<String, Pattern> by lazy {
        mapOf(
            "saw" to saw, "isaw" to isaw, "sine" to sine, "sine2" to sine2, "cosine" to cosine, "square" to square, "tri" to tri,
            "itri" to itri, "rand" to rand, "perlin" to perlin, "berlin" to berlin, "brand" to brand, "irand8" to irand(8.0),
            "rand seed 3" to withSeed({ 3.0 }, rand), "perlin seed 2.5" to withSeed({ 2.5 }, perlin),
            "berlin seed -7" to withSeed({ -7.0 }, berlin),
        )
    }

    /**
     * 300 times and what the pinned `signal.mjs` answers at each, which `tools/strudel-ref/signal-vectors.mjs` recorded: the
     * waves, the random signals with both generators and with a seed. The numbers must be the same bit for bit: a last
     * digit that differs is a different sine, or a different power in perlin noise.
     */
    @Test
    fun valuesAreStrudelsBitForBit() {
        val vectors = Json.parseToJsonElement(File(ParityData.dir, "signal-vectors.json").readText()).jsonObject
        val times = vectors.getValue("times").jsonArray.map { Rational(it.jsonArray[0].jsonPrimitive.double.toLong(), it.jsonArray[1].jsonPrimitive.double.toLong()) }
        assertEquals(300, times.size)
        var compared = 0
        for (generator in listOf("legacy", "precise")) {
            Rng.mode = if (generator == "precise") RngMode.PRECISE else RngMode.LEGACY
            for (series in vectors.getValue(generator).jsonArray) {
                val name = series.jsonObject.getValue("name").jsonPrimitive.content
                val pattern = checkNotNull(vectorPatterns[name]) { "no pattern for the series '$name'" }
                val expected = series.jsonObject.getValue("values").jsonArray
                for ((i, t) in times.withIndex()) {
                    val actual = pattern.queryArc(t, t + Rational(1, 1000)).first().value
                    val wanted = expected[i].jsonPrimitive
                    val where = "$generator $name at ${t.show()}"
                    if (actual is Boolean) assertEquals(wanted.boolean, actual, where) else assertTrue(sameNumber(wanted, actual), "$where: expected ${wanted.content} but was $actual")
                    compared++
                }
            }
        }
        assertEquals(20 * 300, compared)
    }

    /** The numbers are the same bits, but `-0` and `0` are one number in the file (JSON has no negative zero). */
    private fun sameNumber(expected: JsonPrimitive, actual: Any?): Boolean =
        actual is Double && (expected.double + 0.0).toRawBits() == (actual + 0.0).toRawBits()
}
