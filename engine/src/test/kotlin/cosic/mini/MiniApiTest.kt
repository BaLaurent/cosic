package cosic.mini

import cosic.SourceRange
import cosic.pattern.Rational
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniApiTest {
    @Test
    fun leafLocationsAreShiftedByTheStartOfTheQuotedString() {
        val leaves = getLeafLocations("\"bd sd*2\"", 10).map { it.toList() }
        assertEquals(listOf(listOf(11, 13), listOf(14, 16), listOf(17, 18)), leaves)
    }

    @Test
    fun mPutsTheLocationsOfTheAtomsOnTheirHaps() {
        val haps = m("bd sd", 4).queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(listOf(listOf(SourceRange(5, 7)), listOf(SourceRange(8, 10))), haps.map { it.context.locations })
    }

    @Test
    fun miniHasNoLocations() {
        val haps = mini("bd sd", "hh").queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(listOf("bd", "sd", "hh"), haps.map { it.value })
        assertEquals(listOf(emptyList(), emptyList(), emptyList()), haps.map { it.context.locations })
    }
}
