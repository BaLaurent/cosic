package cosic.audio.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which controls make which FM connections (`applyFM`, packages/superdough/helpers.mjs:435-445). */
class FmRoutingTest {
    private fun edgesOf(vararg controls: Pair<String, Any?>): List<Triple<Int, Int, Double>> =
        FmRouting.edges(mapOf(*controls).asSourceControls()).map { Triple(it.source, it.target, it.amount) }

    @Test
    fun theShortNamesAreTheChainAndTheMatrixNamesTheRest() {
        val expected = mapOf(
            (1 to 0) to "fmi", (2 to 1) to "fmi2", (3 to 2) to "fmi3", (4 to 3) to "fmi4", (5 to 4) to "fmi5", (6 to 5) to "fmi6", (7 to 6) to "fmi7", (8 to 7) to "fmi8",
            (1 to 1) to "fmi11", (1 to 2) to "fmi12", (2 to 0) to "fmi20", (2 to 2) to "fmi22", (3 to 1) to "fmi31", (8 to 8) to "fmi88", (8 to 0) to "fmi80", (4 to 5) to "fmi45",
        )
        for ((connection, name) in expected) assertEquals(name, FmRouting.controlName(connection.first, connection.second), "operator ${connection.first} into ${connection.second}")
    }

    @Test
    fun eightOperatorsAndTheCarrierHaveSeventyTwoConnectionsWithEightShortNames() {
        val names = (1..8).flatMap { source -> (0..8).map { target -> FmRouting.controlName(source, target) } }
        assertEquals(72, names.size)
        assertEquals(72, names.toSet().size, "every connection has a name of its own")
        assertEquals(8, names.count { it.length <= 4 })
        assertEquals(64, names.count { it.length == 5 })
        // the matrix names of the connections that have a short name are never read
        for (i in 1..8) assertFalse("fmi$i${i - 1}" in names, "fmi$i${i - 1}")
    }

    @Test
    fun theSuffixOfAnOperatorIsEmptyForTheFirstAndItsNumberForTheOthers() {
        assertEquals("", FmRouting.suffix(1))
        assertEquals("2", FmRouting.suffix(2))
        assertEquals("8", FmRouting.suffix(8))
    }

    @Test
    fun badIndicesAreRejected() {
        assertFailsWith<IllegalArgumentException> { FmRouting.controlName(0, 0) }
        assertFailsWith<IllegalArgumentException> { FmRouting.controlName(9, 0) }
        assertFailsWith<IllegalArgumentException> { FmRouting.controlName(1, 9) }
        assertFailsWith<IllegalArgumentException> { FmRouting.controlName(1, -1) }
    }

    @Test
    fun connectionsComeInTheOrderOfTheLoopsOfApplyFm() {
        // source 1 before 2; within a source the target numbers rise: fmi20 (2 -> carrier) comes before fmi2 (2 -> 1)
        val edges = edgesOf("fmi2" to 3.0, "fmi20" to 1.0, "fmi" to 2.0, "fmi12" to 4.0, "fmi11" to 5.0)
        assertEquals(listOf(Triple(1, 0, 2.0), Triple(1, 1, 5.0), Triple(1, 2, 4.0), Triple(2, 0, 1.0), Triple(2, 1, 3.0)), edges)
    }

    @Test
    fun theNamesOfTheShortConnectionsInTheMatrixFormAreNotRead() {
        assertEquals(emptyList(), edgesOf("fmi10" to 1.0, "fmi21" to 1.0, "fmi32" to 1.0, "fmi87" to 1.0, "fmi00" to 1.0, "fmi01" to 1.0, "fmi02" to 1.0))
    }

    @Test
    fun anAmountThatIsZeroMissingOrNotANumberIsNoConnection() {
        assertEquals(emptyList(), edgesOf("fmi" to 0.0, "fmi2" to -0.0, "fmi3" to Double.NaN, "fmi4" to "x", "fmi5" to null, "fmi6" to emptyList<Any>()))
    }

    @Test
    fun aNegativeAmountAndNumbersWrittenAsTextOrBooleansAreConnections() {
        assertEquals(listOf(Triple(1, 0, -2.0), Triple(2, 1, 0.5), Triple(3, 2, 1.0)), edgesOf("fmi" to -2.0, "fmi2" to "0.5", "fmi3" to true))
    }

    @Test
    fun noControlsMakeNoConnections() {
        assertTrue(FmRouting.edges(emptyMap<String, Any?>().asSourceControls()).isEmpty())
    }
}
