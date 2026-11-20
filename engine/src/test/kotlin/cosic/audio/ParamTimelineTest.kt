package cosic.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AudioParam automation rules (Web Audio spec 1.6.2-1.6.3) and the parameter built on them. */
class ParamTimelineTest {
    @Test
    fun anEmptyTimelineHoldsTheBaseValue() {
        val t = ParamTimeline()
        assertTrue(t.isEmpty)
        assertEquals(3.0, t.valueAt(1.0, 3.0))
    }

    @Test
    fun setValueStepsAndHoldsUntilTheNextEvent() {
        val t = ParamTimeline()
        t.setValueAtTime(1.0, 1.0)
        t.setValueAtTime(2.0, 2.0)
        assertEquals(5.0, t.valueAt(0.5, 5.0), "before the first event the base value")
        assertEquals(1.0, t.valueAt(1.0, 5.0))
        assertEquals(1.0, t.valueAt(1.999, 5.0))
        assertEquals(2.0, t.valueAt(2.0, 5.0))
        assertEquals(2.0, t.valueAt(100.0, 5.0))
    }

    @Test
    fun eventsAreKeptSortedAndEqualTimesKeepInsertionOrder() {
        val t = ParamTimeline()
        t.setValueAtTime(3.0, 3.0)
        t.setValueAtTime(1.0, 1.0)
        t.setValueAtTime(2.0, 2.0)
        t.setValueAtTime(7.0, 2.0)
        assertEquals(1.0, t.valueAt(1.5, 0.0))
        assertEquals(7.0, t.valueAt(2.0, 0.0), "the later of two events at the same time wins")
        assertEquals(3.0, t.valueAt(3.5, 0.0))
        assertEquals(4, t.size)
    }

    @Test
    fun aLinearRampInterpolatesFromThePreviousEvent() {
        val t = ParamTimeline()
        t.setValueAtTime(1.0, 1.0)
        t.linearRampToValueAtTime(3.0, 3.0, 0.0, 0.0)
        assertEquals(1.0, t.valueAt(1.0, 0.0))
        assertEquals(2.0, t.valueAt(2.0, 0.0), 1e-12)
        assertEquals(3.0, t.valueAt(3.0, 0.0))
        assertEquals(3.0, t.valueAt(9.0, 0.0), "after the last event the value holds")
    }

    @Test
    fun aRampOnAnEmptyTimelineStartsFromTheBaseValueAtNow() {
        val t = ParamTimeline()
        t.linearRampToValueAtTime(1.0, 2.0, 0.0, 0.0)
        assertEquals(0.5, t.valueAt(1.0, 0.0), 1e-12)
        val u = ParamTimeline()
        u.linearRampToValueAtTime(0.0, 1.0, 0.5, 1.0) // from 1 at 0.5 to 0 at 1.0
        assertEquals(1.0, u.valueAt(0.5, 1.0))
        assertEquals(0.5, u.valueAt(0.75, 1.0), 1e-12)
        assertEquals(2, u.size)
    }

    @Test
    fun anExponentialRampIsGeometric() {
        val t = ParamTimeline()
        t.setValueAtTime(100.0, 0.0)
        t.exponentialRampToValueAtTime(400.0, 2.0, 0.0, 0.0)
        assertEquals(200.0, t.valueAt(1.0, 0.0), 1e-9)
        assertEquals(400.0, t.valueAt(2.0, 0.0), 1e-9)
    }

    @Test
    fun anExponentialRampFromZeroOrAcrossSignsHoldsTheStartValueUntilItsTime() {
        val fromZero = ParamTimeline()
        fromZero.setValueAtTime(0.0, 0.0)
        fromZero.exponentialRampToValueAtTime(1.0, 1.0, 0.0, 0.0)
        assertEquals(0.0, fromZero.valueAt(0.99, 0.0))
        assertEquals(1.0, fromZero.valueAt(1.0, 0.0))

        val acrossSigns = ParamTimeline()
        acrossSigns.setValueAtTime(-1200.0, 0.0)
        acrossSigns.exponentialRampToValueAtTime(0.001, 0.2, 0.0, 0.0)
        assertEquals(-1200.0, acrossSigns.valueAt(0.1, 0.0))
        assertEquals(0.001, acrossSigns.valueAt(0.2, 0.0))
    }

    @Test
    fun anExponentialRampToZeroIsRejected() {
        assertFailsWith<IllegalArgumentException> { ParamTimeline().exponentialRampToValueAtTime(0.0, 1.0, 0.0, 1.0) }
    }

    @Test
    fun cancelScheduledValuesDropsTheEventsAtOrAfterTheTime() {
        val t = ParamTimeline()
        t.setValueAtTime(1.0, 1.0)
        t.setValueAtTime(2.0, 2.0)
        t.setValueAtTime(3.0, 3.0)
        t.cancelScheduledValues(2.0)
        assertEquals(1, t.size)
        assertEquals(1.0, t.valueAt(5.0, 0.0))
        t.cancelScheduledValues(0.0)
        assertTrue(t.isEmpty)
        assertEquals(9.0, t.valueAt(5.0, 9.0))
    }

    @Test
    fun valuesThatAreNotFiniteAreIgnored() {
        val t = ParamTimeline()
        t.setValueAtTime(Double.NaN, 1.0)
        t.setValueAtTime(1.0, Double.POSITIVE_INFINITY)
        t.linearRampToValueAtTime(Double.NaN, 1.0, 0.0, 0.0)
        assertTrue(t.isEmpty)
    }

    @Test
    fun theTimelineGrowsPastItsCapacityAndPrunesWhatNoLongerMatters() {
        val t = ParamTimeline(2)
        for (i in 0 until 20) t.setValueAtTime(i.toDouble(), i.toDouble())
        assertEquals(20, t.size)
        assertEquals(7.0, t.valueAt(7.5, 0.0))
        t.pruneBefore(10.5)
        assertEquals(10, t.size, "events 10 to 19 are left: 10 sets the value at 10.5")
        assertEquals(10.0, t.valueAt(10.5, 0.0))
        assertEquals(19.0, t.valueAt(50.0, 0.0))
    }

    @Test
    fun fillBlockEvaluatesEveryFrameLikeValueAtAcrossBlocksAndRewinds() {
        val t = ParamTimeline()
        t.setValueAtTime(0.0, 0.001)
        t.linearRampToValueAtTime(1.0, 0.003, 0.0, 0.0)
        t.exponentialRampToValueAtTime(4.0, 0.006, 0.0, 0.0)
        t.setValueAtTime(2.0, 0.008)
        val sr = 48_000
        val out = FloatArray(512)
        var frame = 0L
        for (n in intArrayOf(100, 7, 200, 205)) {
            t.fillBlock(out, frame, sr, n, 5.0)
            for (i in 0 until n) assertEquals(t.valueAt((frame + i).toDouble() / sr, 5.0).toFloat(), out[i], "frame ${frame + i}")
            frame += n
        }
        // going back in time re-evaluates from the start
        t.fillBlock(out, 0, sr, 50, 5.0)
        assertEquals(5.0f, out[0])
        assertEquals(t.valueAt(49.0 / sr, 5.0).toFloat(), out[49])
    }

    // ---------------------------------------------------------------- AParam

    private val ctx = BlockContext(48_000)

    private fun block(start: Long, n: Int): BlockContext {
        ctx.startFrame = start
        ctx.n = n
        return ctx
    }

    @Test
    fun aParamIsConstantUntilItGetsEventsOrInputs() {
        val p = AParam(0.5, -1.0, 1.0)
        assertTrue(p.isConstant)
        assertEquals(0.5, p.constantValue)
        p.setValueAtTime(0.25, 1.0)
        assertFalse(p.isConstant)
        p.reset(2.0)
        assertTrue(p.isConstant)
        assertEquals(1.0, p.constantValue, "the base value is clamped to the range of the parameter")
    }

    @Test
    fun aParamFillsAClampedValuePerFrameAndAddsItsInputs() {
        val p = AParam(0.0, -1.0, 1.0)
        p.setValueAtTime(0.9, 0.0)
        p.addInput { out, c -> for (i in 0 until c.n) out[i] += 0.5f }
        val out = FloatArray(64)
        p.fill(out, block(0, 64))
        for (i in 0 until 64) assertEquals(1.0f, out[i], "0.9 + 0.5 clamps to the range")
        p.clearInputs()
        p.fill(out, block(0, 64))
        assertEquals(0.9f, out[10])
    }

    @Test
    fun aBlockRateReaderSeesTheInputsAtTheFrameItAsksFor() {
        val p = AParam(1.0, -1e6, 1e6)
        p.addInput { out, c -> for (i in 0 until c.n) out[i] += (c.startFrame + i).toFloat() }
        val c = block(1000, 64)
        assertEquals(1.0 + 1010.0, p.sampleAt(c, 10))
        val clamped = AParam(1.0, -100.0, 100.0)
        clamped.addInput { out, ctx -> for (i in 0 until ctx.n) out[i] += (ctx.startFrame + i).toFloat() }
        assertEquals(100.0, clamped.sampleAt(c, 40), "clamped to the range")
        val plain = AParam(1.0, -100.0, 100.0)
        plain.linearRampToValueAtTime(3.0, 1.0, 0.0)
        assertEquals(plain.valueAt(c.time(5)), plain.sampleAt(c, 5))
    }

    private class CountingModulator : BlockModulator() {
        var renders = 0

        override fun render(ctx: BlockContext) {
            renders++
            for (i in 0 until ctx.n) buf[i] = (ctx.startFrame + i).toFloat()
        }
    }

    @Test
    fun aModulatorRendersOncePerBlockHoweverManyParametersTakeIt() {
        val m = CountingModulator()
        val a = AParam(0.0, -1e9, 1e9)
        val b = AParam(10.0, -1e9, 1e9)
        a.addInput(m)
        b.addInput(m)
        val oa = FloatArray(16)
        val ob = FloatArray(16)
        a.fill(oa, block(100, 16))
        b.fill(ob, ctx)
        assertEquals(1, m.renders)
        assertEquals(105f, oa[5])
        assertEquals(115f, ob[5])
        a.sampleAt(ctx, 3)
        assertEquals(1, m.renders, "reading a value in the middle of the block does not render it again")
        a.fill(oa, block(116, 16))
        assertEquals(2, m.renders, "the next block does")
    }
}
