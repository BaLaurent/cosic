package cosic.pattern.legacy

import cosic.pattern.*

import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyEuclidTest {
    private fun render(bits: BooleanArray): String = bits.joinToString("") { if (it) "x" else "." }

    @Test
    fun threeInEight() = assertEquals("x..x..x.", render(bjorklund(3, 8)))

    @Test
    fun fiveInEight() = assertEquals("x.xx.xx.", render(bjorklund(5, 8)))

    @Test
    fun twoInFive() = assertEquals("x.x..", render(bjorklund(2, 5)))

    @Test
    fun fourInTwelve() = assertEquals("x..x..x..x..", render(bjorklund(4, 12)))

    @Test
    fun nonPositiveKIsAllRests() {
        assertEquals("....", render(bjorklund(0, 4)))
        assertEquals("....", render(bjorklund(-2, 4)))
    }

    @Test
    fun kAtLeastNIsAllHits() {
        assertEquals("xxxx", render(bjorklund(4, 4)))
        assertEquals("xxxx", render(bjorklund(9, 4)))
    }

    @Test
    fun emptyWhenNIsNotPositive() {
        assertEquals(0, bjorklund(3, 0).size)
        assertEquals(0, bjorklund(3, -1).size)
    }

    @Test
    fun rotationRotatesLeft() {
        assertEquals("..x..x.x", render(bjorklund(3, 8).rotatedLeft(1)))
        assertEquals(".x..x..x", render(bjorklund(3, 8).rotatedLeft(-1)))
        assertEquals("x..x..x.", render(bjorklund(3, 8).rotatedLeft(8)))
        assertEquals("x..x..x.", render(bjorklund(3, 8).rotatedLeft(0)))
    }

    @Test
    fun euclidStructuresThePattern() {
        val haps = pure("x").euclid(3, 8).shownCycle()
        assertEquals(listOf("x@0-1/8", "x@3/8-1/2", "x@3/4-7/8"), haps)
    }

    @Test
    fun euclidRotRotatesTheStructure() {
        val haps = pure("x").euclidRot(3, 8, 1).shownCycle()
        assertEquals(listOf("x@1/4-3/8", "x@5/8-3/4", "x@7/8-1"), haps)
    }

    @Test
    fun euclidWithNoHitsIsSilent() {
        assertEquals(emptyList(), pure("x").euclid(-3, 8).shownCycle())
        assertEquals(emptyList(), pure("x").euclid(0, 8).shownCycle())
    }
}
