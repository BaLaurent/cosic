package cosic.audio.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The `transient-processor` worklet against the real processor's output (worklets.mjs:1417-1494). */
class TransientShaperTest {
    private val cases = DspGolden.cases(DspGolden.load("transient.json")["cases"] as JsonArray)

    private fun StreamCase.option(key: String, default: Double): Double = json["options"]!!.jsonObject[key]?.jsonPrimitive?.double ?: default

    private fun shaper(case: StreamCase): TransientShaper = TransientShaper(case.sampleRate).also {
        it.configure(
            case.option("attack", 0.0), case.option("sustain", 0.0), case.option("begin", 0.0), case.option("end", 0.0),
            attackTime = case.option("attackTime", 0.003), sustainTime = case.option("sustainTime", 0.08), sensitivity = case.option("sensitivity", 0.1), mix = case.option("mix", 1.0),
        )
    }

    private fun run(case: StreamCase, split: Int, startFrame: Long = 0L): Array<FloatArray> {
        val data = case.copyOfInput()
        val shaper = shaper(case)
        var from = 0
        while (from < case.frames()) {
            val to = minOf(case.frames(), from + split)
            shaper.process(data[0], data.getOrNull(1), from, to, startFrame)
            from = to
        }
        return data
    }

    @Test
    fun everyGoldenCaseMatchesTheWorklet() {
        for (case in cases) {
            val out = run(case, 128)
            for (ch in 0 until case.channels) assertClose("${case.name} ch$ch", case.output[ch], out[ch], atol = 1e-7, rtol = 1e-6)
        }
    }

    @Test
    fun callsThatSplitAQuantumDoNotChangeAMonoOutput() {
        for (case in cases.filter { it.channels == 1 }) {
            val whole = run(case, 128)
            for (split in intArrayOf(1, 7, 50, 333)) assertContentEquals(whole[0], run(case, split)[0], "${case.name} split $split")
        }
    }

    @Test
    fun aStereoQuantumSplitIntoPiecesOnlyShiftsTheSharedAverageGain() {
        // The worklet runs channel 0's whole quantum before channel 1's; the average gain they share then advances in a different order. The difference stays within about 1.5 % of the signal.
        val case = cases.first { it.channels == 2 }
        val whole = run(case, 128)
        for (split in intArrayOf(1, 7, 50)) {
            val pieces = run(case, split)
            for (ch in 0 until 2) assertClose("${case.name} split $split ch$ch", whole[ch], pieces[ch], atol = 1.5e-2)
        }
    }

    @Test
    fun theQuantaOfTheGatedCaseAreSilentBeforeBeginAndAfterEnd() {
        val case = cases.first { it.name == "gated" }
        val out = run(case, 128)[0]
        // begin 0.003 s = 144 frames: the quanta starting at 0 and 128 do not run (silence); end 0.02 s = 960 frames: from the quantum starting at 1024 it is over
        for (i in 0 until 2 * 128) assertEquals(0f, out[i], "frame $i before begin")
        assertTrue((2 * 128 until 1024).any { out[it] != 0f })
        for (i in 1024 until out.size) assertEquals(0f, out[i], "frame $i after end")
    }

    @Test
    fun neutralSettingsStillSoftClip() {
        val io = FloatArray(512) { 0.5f }
        val shaper = TransientShaper(SR)
        shaper.configure(0.0, 0.0, -1.0, 10.0)
        shaper.process(io, null, 0, 512, 0L)
        for (i in 0 until 512) assertEquals(0.5 / 1.5, io[i].toDouble(), 1e-7, "frame $i: y / (1 + |y|)")
    }

    @Test
    fun anAttackBoostRaisesTheClickAndASustainCutLowersTheTail() {
        // a click on silence: the fast follower jumps ahead of the slow one
        val click = FloatArray(1024)
        click[300] = 0.5f
        for (i in 301 until 1024) click[i] = 0.5f * Math.exp(-(i - 300) / 400.0).toFloat()
        // a loud passage that drops to a fifth: now the slow follower is ahead
        val drop = FloatArray(6000) { if (it < 3000) 0.5f else 0.1f }
        fun shape(source: FloatArray, attack: Double, sustain: Double): FloatArray {
            val io = source.copyOf()
            val shaper = TransientShaper(SR)
            shaper.configure(attack, sustain, -1.0, 10.0)
            shaper.process(io, null, 0, io.size, 0L)
            return io
        }
        val neutral = shape(click, 0.0, 0.0)
        val boosted = shape(click, 1.0, 0.0)
        assertTrue(boosted[300] > neutral[300] * 1.05, "the attack is raised: ${boosted[300]} vs ${neutral[300]}")
        val tail = shape(drop, 0.0, 0.0)
        val cut = shape(drop, 0.0, -1.0)
        assertTrue(abs(cut[3600]) < abs(tail[3600]) * 0.5, "the sustain is lowered: ${cut[3600]} vs ${tail[3600]}")
        val raised = shape(drop, 0.0, 1.0)
        assertTrue(abs(raised[3600]) > abs(tail[3600]) * 1.02, "and raised with a positive amount: ${raised[3600]} vs ${tail[3600]}")
    }

    @Test
    fun configureRestartsTheShaperAndItEndsForGoodAfterItsEnd() {
        val a = FloatArray(256) { ((it * 13) % 11 - 5) / 8f }
        val shaper = TransientShaper(SR)
        shaper.configure(1.0, -0.5, 0.0001, 0.003)
        val first = a.copyOf()
        shaper.process(first, null, 0, 256, 0L)
        // end 0.003 s = 144 frames: the quantum starting at 128 is in range, the one at 256 would not be
        assertFalse(shaper.isFinished)
        shaper.process(FloatArray(128) { 0.4f }.also { it.fill(0.4f) }, null, 0, 128, 256L)
        assertTrue(shaper.isFinished, "a quantum that starts at or after end finishes it")
        shaper.configure(1.0, -0.5, 0.0001, 0.003)
        assertFalse(shaper.isFinished)
        val second = a.copyOf()
        shaper.process(second, null, 0, 256, 0L)
        assertContentEquals(first, second, "configure clears the state")
    }

    @Test
    fun nonFiniteInputIsTakenAsSilence() {
        val clean = FloatArray(512) { ((it * 13) % 11 - 5) / 8f }
        val dirty = clean.copyOf()
        dirty[100] = Float.NaN
        dirty[300] = Float.POSITIVE_INFINITY
        val zeroed = clean.copyOf()
        zeroed[100] = 0f
        zeroed[300] = 0f
        for (io in listOf(dirty, zeroed)) {
            val shaper = TransientShaper(SR)
            shaper.configure(1.0, -0.5, -1.0, 10.0)
            shaper.process(io, null, 0, 512, 0L)
        }
        assertContentEquals(zeroed, dirty)
    }

    private companion object {
        const val SR = 48000
    }
}
