package cosic.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [StageQuanta]: blocks are cut at the 128-frame quanta of the absolute frame counter, and parameters are read at the first frame of the quantum. */
class StageQuantaTest {
    private fun pieces(startFrame: Long, n: Int): List<Triple<Int, Int, Int>> {
        val ctx = BlockContext(SR)
        ctx.startFrame = startFrame
        ctx.n = n
        val out = ArrayList<Triple<Int, Int, Int>>()
        StageQuanta.forEachPiece(ctx) { from, to, quantumStart -> out.add(Triple(from, to, quantumStart)) }
        return out
    }

    @Test
    fun aBlockOfWholeQuantaIsCutIntoThem() {
        assertEquals(listOf(Triple(0, 128, 0), Triple(128, 256, 128)), pieces(0, 256))
        assertEquals(listOf(Triple(0, 128, 0), Triple(128, 256, 128)), pieces(128 * 9, 256))
        assertEquals(listOf(Triple(0, 100, 0)), pieces(128 * 4, 100))
    }

    @Test
    fun aBlockInTheMiddleOfAQuantumHasAFirstPieceWhoseQuantumStartedBeforeIt() {
        // frame 200 is n = 72 of the quantum that starts at 128: the first piece ends at 256, and the quantum's first frame is 72 frames before the block
        assertEquals(listOf(Triple(0, 56, -72), Triple(56, 184, 56), Triple(184, 200, 184)), pieces(200, 200))
        assertEquals(listOf(Triple(0, 1, -127)), pieces(127, 1))
        assertEquals(listOf(Triple(0, 1, -127), Triple(1, 129, 1)), pieces(255, 129))
    }

    @Test
    fun theTilesCoverTheBlockWithoutGapsAndStayInsideOneQuantum() {
        for (start in listOf(0L, 1L, 127L, 128L, 5000L, 123_456L)) {
            for (n in listOf(1, 7, 97, 128, 200, 256)) {
                var next = 0
                for ((from, to, quantumStart) in pieces(start, n)) {
                    assertEquals(next, from)
                    assertTrue(to > from)
                    assertEquals((start + from) / 128, (start + to - 1) / 128, "one quantum: start $start n $n piece $from..$to")
                    assertEquals(((start + from) / 128) * 128 - start, quantumStart.toLong(), "the quantum's first frame relative to the block")
                    next = to
                }
                assertEquals(n, next)
            }
        }
    }

    @Test
    fun aParameterIsReadAtTheQuantumStartAsAFloat() {
        val ctx = BlockContext(SR)
        val param = AParam(0.0, -1e30, 1e30, 2)
        param.reset(0.1)
        param.linearRampToValueAtTime(10.0, 1.0, 0.0)
        ctx.startFrame = 200
        ctx.n = 100
        // the quantum started at frame 128, 72 frames before the block: the value at that frame, not at the block's first frame
        val expected = param.valueAt(128.0 / SR).toFloat().toDouble()
        assertEquals(expected, StageQuanta.valueAt(param, ctx, -72))
        assertTrue(StageQuanta.valueAt(param, ctx, 0) > expected)
        // a constant parameter is its value as a float
        assertEquals(0.1f.toDouble(), StageQuanta.valueAt(AParam(0.1, -1.0, 1.0, 2), ctx, -72))
    }

    @Test
    fun nonFiniteSamplesBecomeZero() {
        val x = floatArrayOf(1f, Float.NaN, Float.POSITIVE_INFINITY, -3f, Float.NEGATIVE_INFINITY, 0f)
        StageQuanta.finiteOrZero(x, 1, 6)
        assertEquals(listOf(1f, 0f, 0f, -3f, 0f, 0f), x.toList())
        val y = floatArrayOf(Float.NaN, Float.NaN)
        StageQuanta.finiteOrZero(y, 0, 1)
        assertTrue(y[1].isNaN(), "only the given range")
    }
}
