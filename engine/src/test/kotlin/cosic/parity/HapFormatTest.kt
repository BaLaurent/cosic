package cosic.parity

import cosic.pattern.Hap
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The formatter must print exactly what Strudel's `hap.show(true)` prints, character for character. */
class HapFormatTest {
    private fun span(beginNum: Long, beginDen: Long, endNum: Long, endDen: Long) =
        TimeSpan(Rational(beginNum, beginDen), Rational(endNum, endDen))

    private fun show(hap: Hap<Any?>) = showHap(hap, compact = true)

    @Test
    fun aFullHapShowsItsControlMap() {
        val part = span(0, 1, 1, 2)
        assertEquals("[ 0/1 \u2192 1/2 | s:bd ]", show(Hap(part, part, linkedMapOf("s" to "bd"))))
    }

    @Test
    fun anEighthOfACycleShowsAsInTheFastExample() {
        val part = span(0, 1, 1, 8)
        assertEquals("[ 0/1 \u2192 1/8 | s:bd ]", show(Hap(part, part, linkedMapOf("s" to "bd"))))
    }

    @Test
    fun aFragmentThatEndsTheWholeShowsTheOpeningArrowAndParentheses() {
        val hap = Hap<Any?>(span(0, 1, 1, 1), span(1, 2, 1, 1), 1.0)
        assertEquals("[ 0/1 \u21DC (1/2 \u2192 1/1) | 1 ]", show(hap))
    }

    @Test
    fun aFragmentThatStartsTheWholeShowsTheClosingArrow() {
        val hap = Hap<Any?>(span(1, 3, 5, 12), span(1, 3, 3, 8), linkedMapOf("note" to "Bb4", "s" to "saw"))
        assertEquals("[ (1/3 \u2192 3/8) \u21DD 5/12 | note:Bb4 s:saw ]", show(hap))
    }

    @Test
    fun aFragmentInTheMiddleShowsBothArrows() {
        val hap = Hap<Any?>(span(0, 1, 1, 1), span(1, 4, 1, 2), "x")
        assertEquals("[ 0/1 \u21DC (1/4 \u2192 1/2) \u21DD 1/1 | x ]", show(hap))
    }

    @Test
    fun negativeAndZeroFractionsKeepTheirSign() {
        assertEquals("-1/2", fractionShow(Rational(-1, 2)))
        assertEquals("0/1", fractionShow(Rational(0)))
        assertEquals("3/1", fractionShow(Rational(3)))
    }

    @Test
    fun numbersInAControlMapLoseTheirDecimalPoint() {
        assertEquals("s:hh n:0", showValue(linkedMapOf("s" to "hh", "n" to 0.0)))
        assertEquals("gain:0.5", showValue(linkedMapOf("gain" to 0.5)))
    }

    @Test
    fun nestedListsAndCommasInStringsBecomeSpaces() {
        val value = linkedMapOf("FX" to listOf(linkedMapOf("a" to 1.0), linkedMapOf("b" to "x,y")))
        assertEquals("FX:[{a:1} {b:x y}]", showValue(value))
    }

    @Test
    fun anEmptyMapLeavesTwoSpacesBetweenTheBars() {
        val part = span(0, 1, 1, 1)
        assertEquals("[ 0/1 \u2192 1/1 |  ]", show(Hap(part, part, LinkedHashMap<String, Any?>())))
    }

    @Test
    fun plainValuesPrintAsJavaScriptStrings() {
        assertEquals("c3", showValue("c3"))
        assertEquals("1", showValue(1.0))
        assertEquals("0.25", showValue(0.25))
        assertEquals("true", showValue(true))
        assertEquals("a b", showValue(listOf("a", "b")))
    }

    @Test
    fun mapKeysFollowJavaScriptOrder() {
        val value = linkedMapOf<String, Any?>("b" to 1.0, "10" to 2.0, "a" to 3.0, "2" to 4.0)
        assertEquals("""{"2":4,"10":2,"b":1,"a":3}""", jsonStringify(value))
        assertEquals("2:4 10:2 b:1 a:3", showValue(value))
    }

    @Test
    fun jsonStringifyFollowsJsonStringify() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", jsonStringify("a\"b\\c\n\u0001"))
        assertEquals("[1,null,null,\"x\"]", jsonStringify(listOf(1.0, Double.NaN, Double.POSITIVE_INFINITY, "x")))
        assertEquals("0", jsonStringify(-0.0))
        assertEquals("""{"a":null}""", jsonStringify(mapOf("a" to null)))
        assertEquals("\"\\ud800\"", jsonStringify("\uD800"))
    }

    @Test
    fun numbersPrintLikeJavaScript() {
        val vectors = listOf(
            0.5 to "0.5",
            100.0 to "100",
            1e21 to "1e+21",
            1e-7 to "1e-7",
            0.000001 to "0.000001",
            123456789012345680000.0 to "123456789012345680000",
            -0.0 to "0",
            0.1 + 0.2 to "0.30000000000000004",
            1.0 / 3 to "0.3333333333333333",
            0.1 to "0.1",
            4.35 to "4.35",
            -1.5 to "-1.5",
            123456.789 to "123456.789",
            1e15 to "1000000000000000",
            1.2e21 to "1.2e+21",
            2e-7 to "2e-7",
            5e-324 to "5e-324",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            Double.NaN to "NaN",
            Double.NEGATIVE_INFINITY to "-Infinity",
        )
        for ((number, text) in vectors) assertEquals(text, jsNumberToString(number), "jsNumberToString($number)")
    }

    @Test
    fun floatsAreRoundedLikeTheTuneSnapshots() {
        assertEquals("s:x cutoff:300.052264864011", roundFloatStrings("s:x cutoff:300.0522648640110000"))
        assertEquals("a:0.1 b:-0", roundFloatStrings("a:0.1 b:-0.0000000000004"))
        assertEquals("gain:0.3", roundFloatStrings("gain:0.30000000000000004"))
        assertEquals("[ 1/3 \u2192 3/8 | n:12 ]", roundFloatStrings("[ 1/3 \u2192 3/8 | n:12 ]"))
        assertEquals("velocity:0.685215570033", roundFloatStrings("velocity:0.6852155700325966"))
    }

    @Test
    fun hapsAreSortedByPartThenWhole() {
        val late = Hap<Any?>(span(1, 2, 1, 1), span(1, 2, 1, 1), "late")
        val early = Hap<Any?>(span(0, 1, 1, 1), span(0, 1, 1, 1), "early")
        val shortOne = Hap<Any?>(span(0, 1, 1, 1), span(0, 1, 1, 2), "short")
        assertEquals(listOf("short", "early", "late"), sortHapsByPart(listOf(late, early, shortOne)).map { it.value })
    }

    @Test
    fun theSortKeepsTheQueryOrderOfEqualHaps() {
        val a = Hap<Any?>(span(0, 1, 1, 1), span(0, 1, 1, 1), "a")
        val b = Hap<Any?>(span(0, 1, 1, 1), span(0, 1, 1, 1), "b")
        assertEquals(listOf("a", "b"), sortHapsByPart(listOf(a, b)).map { it.value })
        assertEquals(listOf("b", "a"), sortHapsByPart(listOf(b, a)).map { it.value })
    }

    @Test
    fun aHapWithoutAWholeBreaksTheSortOfTwoOrMoreHaps() {
        val continuous = Hap<Any?>(null, span(0, 1, 1, 1), 0.5)
        val discrete = Hap<Any?>(span(0, 1, 1, 1), span(0, 1, 1, 1), "x")
        assertFailsWith<HapsWithoutWholeSortError> { sortHapsByPart(listOf(continuous, discrete)) }
        assertEquals(1, sortHapsByPart(listOf(continuous)).size)
        assertEquals("[ ~0/1 \u2192 1/1 | 0.5 ]", show(continuous))
    }

    // ---- the exported data ---------------------------------------------------------------------------------------

    @Test
    fun theExportKeepsHapStringsVerbatim() {
        val line = "\"[ 0/1 \u2192 1/8 | s:bd ]\""
        val text = File(ParityData.dir, "examples.json").readText()
        assertTrue(text.contains(line), "examples.json should contain $line verbatim")
        val fast = ParityData.cases.single { it.id == "examples:fast#0" }
        assertEquals("[ 0/1 \u2192 1/8 | s:bd ]", fast.expected!!.haps.first())
        assertEquals(4, fast.cycles)
    }

    @Test
    fun theExportedCountsMatchTheMetadata() {
        val meta = File(ParityData.dir, "meta.json").readText()
        fun count(key: String): Int = Regex("\"$key\": (\\d+)").find(meta)!!.groupValues[1].toInt()
        val examples = ParityData.cases.filter { it.source == CaseSource.EXAMPLES }
        val tunes = ParityData.cases.filter { it.source == CaseSource.TUNES }
        assertEquals(count("examples"), examples.size)
        assertEquals(count("examplesWithSnapshot"), examples.count { it.expected != null })
        assertEquals(count("tunes"), tunes.size)
        assertEquals(count("tunesWithSnapshot"), tunes.count { it.expected != null })
        assertTrue(examples.size > 500 && tunes.size >= 31, "the export should hold Strudel's examples and tunes")
    }

    // ---- the formatter against everything Strudel printed --------------------------------------------------------

    private val strudelHapStrings: List<String> by lazy {
        ParityData.cases.filter { it.source != CaseSource.EXTRA }.flatMap { it.expected?.haps.orEmpty() }
    }

    private val spansPattern =
        Regex("^(?:(-?\\d+/\\d+) \u21DC )?(\\()?(-?\\d+/\\d+) \u2192 (-?\\d+/\\d+)(\\))?(?: \u21DD (-?\\d+/\\d+))?$")

    private fun fraction(text: String) = Rational(text.substringBefore('/').toLong(), text.substringAfter('/').toLong())

    @Test
    fun theSpansOfEveryStrudelHapAreReproduced() {
        for (line in strudelHapStrings) {
            val spans = line.removePrefix("[ ").substringBefore(" | ")
            val m = spansPattern.matchEntire(spans) ?: error("unrecognised spans in $line")
            val partBegin = fraction(m.groupValues[3])
            val partEnd = fraction(m.groupValues[4])
            val wholeBegin = m.groupValues[1].takeIf(String::isNotEmpty)?.let(::fraction) ?: partBegin
            val wholeEnd = m.groupValues[6].takeIf(String::isNotEmpty)?.let(::fraction) ?: partEnd
            val hap = Hap<Any?>(TimeSpan(wholeBegin, wholeEnd), TimeSpan(partBegin, partEnd), "x")
            assertEquals("[ $spans | x ]", show(hap), line)
        }
        assertTrue(strudelHapStrings.size > 10_000, "expected the snapshots to hold thousands of haps")
    }

    @Test
    fun theNumbersStrudelPrintedAreWhatJsNumberToStringPrints() {
        val number = Regex("(?<![\\w.])(?:-?\\d+\\.\\d+(?:e[+-]?\\d+)?|-?\\d+e[+-]?\\d+)(?![\\w.])")
        val seen = HashSet<String>()
        for (line in strudelHapStrings) {
            for (token in number.findAll(line.substringAfter(" | ")).map { it.value }) {
                if (seen.add(token)) assertEquals(token, jsNumberToString(token.toDouble()), "in $line")
            }
        }
        assertTrue(seen.size > 500, "expected the snapshots to hold hundreds of distinct decimal numbers, got ${seen.size}")
    }

    @Test
    fun tuneSnapshotsAreFixedPointsOfRoundFloatStrings() {
        val longDecimal = Regex("-?\\d+\\.\\d{10,}")
        var covered = 0
        for (tune in ParityData.cases.filter { it.source == CaseSource.TUNES }) {
            for (line in tune.expected!!.haps) {
                assertEquals(line, roundFloatStrings(line), tune.id)
                covered += longDecimal.findAll(line).count()
            }
        }
        assertTrue(covered > 1000, "expected the tunes to hold thousands of long decimals, got $covered")
    }
}
