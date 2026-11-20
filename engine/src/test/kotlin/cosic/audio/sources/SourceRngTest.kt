package cosic.audio.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourceRngTest {
    @Test
    fun theSequenceIsSplitMix64() {
        val rng = SourceRng(0)
        assertEquals(0xE220A8397B1DCDAFUL.toLong(), rng.nextLong())
        assertEquals(0x6E789E6AA1B965F4UL.toLong(), rng.nextLong())
        assertEquals(0x06C45D188009454FUL.toLong(), rng.nextLong())
    }

    @Test
    fun theSameSeedGivesTheSameSequenceAndDoublesStayInTheUnitInterval() {
        val a = SourceRng(1234)
        val b = SourceRng(1234)
        val c = SourceRng(1235)
        var differs = false
        repeat(1000) {
            val x = a.nextDouble()
            assertEquals(x, b.nextDouble())
            assertTrue(x >= 0.0 && x < 1.0, "$x")
            if (x != c.nextDouble()) differs = true
        }
        assertTrue(differs)
        assertNotEquals(SourceRng(1).nextLong(), SourceRng(2).nextLong())
    }
}
