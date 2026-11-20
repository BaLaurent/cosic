package cosic.parity

import cosic.pattern.Hap
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import cosic.pattern.jsNumberToString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exported reference data (Task 0.1) against the engine's formatter: Strudel's own hap strings must survive the
 * export verbatim, and the engine must be able to print every span and number Strudel printed.
 */
class ExportedDataTest {
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

    @Test
    fun floatsAreRoundedLikeTheTuneSnapshots() {
        assertEquals("s:x cutoff:300.052264864011", roundFloatStrings("s:x cutoff:300.0522648640110000"))
        assertEquals("a:0.1 b:-0", roundFloatStrings("a:0.1 b:-0.0000000000004"))
        assertEquals("gain:0.3", roundFloatStrings("gain:0.30000000000000004"))
        assertEquals("[ 1/3 \u2192 3/8 | n:12 ]", roundFloatStrings("[ 1/3 \u2192 3/8 | n:12 ]"))
        assertEquals("velocity:0.685215570033", roundFloatStrings("velocity:0.6852155700325966"))
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
            val hap = Hap(TimeSpan(wholeBegin, wholeEnd), TimeSpan(partBegin, partEnd), "x")
            assertEquals("[ $spans | x ]", hap.show(true), line)
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
