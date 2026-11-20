package cosic.visual

import cosic.pattern.Hap
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import kotlin.test.Test
import kotlin.test.assertEquals

class PrintLinesTest {
    private fun hap(begin: String, end: String, value: Any?, partBegin: String = begin): Hap =
        Hap(TimeSpan(Rational.of(begin), Rational.of(end)), TimeSpan(Rational.of(partBegin), Rational.of(end)), value)

    private val bd = mapOf("s" to "bd")

    @Test
    fun theTimeIsRoundedToFourDigitsAndPaddedSoTheColonsLineUp() {
        assertEquals("0.25    : s:bd", printLine(hap("1/4", "1/2", bd), 4.0, 3.0))
        assertEquals("1       : s:bd", printLine(hap("1", "2", bd), 4.0, 3.0))
        assertEquals("0.3333  : s:bd", printLine(hap("1/3", "2/3", bd), 4.0, 3.0))
        assertEquals("10.5 : s:bd", printLine(hap("21/2", "11", bd), 1.0, 3.0))
    }

    @Test
    fun roundZeroShowsTheCycleOnly() {
        assertEquals("0  : s:bd", printLine(hap("1/4", "1/2", bd), 0.0, 3.0))
        assertEquals("12 : s:bd", printLine(hap("25/2", "13", bd), 0.0, 3.0))
        assertEquals("3    : s:bd", printLine(hap("3", "4", bd), 0.0, 5.0))
    }

    @Test
    fun aHapWithoutAWholeIsTimedByItsPart() {
        val continuous = Hap(null, TimeSpan(Rational.of("1/2"), Rational.of("1")), 0.5)
        assertEquals("0.5     : 0.5", printLine(continuous, 4.0, 3.0))
    }

    @Test
    fun valuesAreWrittenLikeHapShowDoes() {
        assertEquals("0       : s:bd n:3", printLine(hap("0", "1", mapOf("s" to "bd", "n" to 3.0)), 4.0, 3.0))
        assertEquals("0       : c3", printLine(hap("0", "1", "c3"), 4.0, 3.0))
    }

    @Test
    fun padEndFollowsJavaScript() {
        assertEquals("ab ", padEnd("ab", 3.0))
        assertEquals("abcd", padEnd("abcd", 3.0))
        assertEquals("ab", padEnd("ab", -1.0))
        assertEquals("ab ", padEnd("ab", 3.9))
        assertEquals("ab", padEnd("ab", Double.NaN))
    }

    @Test
    fun aWidgetKeepsTheLastLinesAndWritesEveryHapOnce() {
        val lines = PrintLines(3, 4.0, 3.0, false)
        val a = hap("0", "1", bd)
        val b = hap("1", "2", bd)
        lines.append(listOf(a, b))
        lines.append(listOf(a, b))
        assertEquals(listOf("0       : s:bd", "1       : s:bd"), lines.snapshot)
        val c = hap("2", "3", bd)
        val d = hap("3", "4", bd)
        lines.append(listOf(b, c, d))
        assertEquals(listOf("1       : s:bd", "2       : s:bd", "3       : s:bd"), lines.snapshot)
    }

    @Test
    fun twoHapsThatLookAlikeAreTwoLinesBecauseStrudelRemembersThemByIdentity() {
        val lines = PrintLines(10, 4.0, 3.0, false)
        lines.append(listOf(hap("0", "1", bd), hap("0", "1", bd)))
        assertEquals(2, lines.snapshot.size)
    }
}
