package cosic.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The AudioParam automation the orbit's `duck` is built on, against a Chromium GainNode given the same events (`orbit-automation.json`), and [Orbit.duck] itself
 * against the formulas of superdoughoutput.mjs.
 */
class OrbitAutomationTest {
    private fun play(param: AParam, frames: Int, sr: Int): FloatArray {
        val ctx = BlockContext(sr)
        val out = FloatArray(frames)
        val block = FloatArray(BLOCK_FRAMES)
        var pos = 0
        while (pos < frames) {
            val n = minOf(BLOCK_FRAMES, frames - pos)
            ctx.startFrame = pos.toLong()
            ctx.n = n
            param.fill(block, ctx)
            block.copyInto(out, pos, 0, n)
            pos += n
        }
        return out
    }

    @Test
    fun theTimelineMatchesChromiumForTheEventsOfADuck() {
        for (case in OrbitGolden.cases("automation")) {
            val name = OrbitGolden.string(case, "name")
            val sr = OrbitGolden.int(case, "sr")
            val param = AParam(OrbitGolden.number(case, "base"), -3.4e38, 3.4e38, 16)
            for (e in OrbitGolden.array(case, "events").map { it.jsonObject }) {
                val t = OrbitGolden.int(e, "frame").toDouble() / sr
                when (OrbitGolden.string(e, "type")) {
                    "cancel" -> param.cancelScheduledValues(t)
                    "set" -> param.setValueAtTime(OrbitGolden.number(e, "value"), t)
                    "lin" -> param.linearRampToValueAtTime(OrbitGolden.number(e, "value"), t, 0.0)
                    "exp" -> param.exponentialRampToValueAtTime(OrbitGolden.number(e, "value"), t, 0.0)
                }
            }
            val actual = play(param, OrbitGolden.int(case, "frames"), sr)
            assertWithin(name, OrbitGolden.outputs(case)[0], actual, 2e-6)
        }
    }

    /** A voice on orbit 2 that holds a constant level, so that its orbit's output gain can be read off the master output. */
    private fun mixerWithADuckedOrbit(vararg duck: Pair<String, Any?>): Pair<Mixer, FloatArray> {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(10.0), 10.0, "orbit" to 2.0, "gain" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0))
        val before = mixer.renderFrames(SR / 2)
        mixer.startVoice(vp(synth(Waveform.SINE), 0.01, "gain" to 0.0, *duck))
        val after = mixer.renderFrames(SR)
        return mixer to channel(before + after, 0)
    }

    @Test
    fun aDuckGoesToOnePercentAtItsEventAndComesBackOverTheAttack() {
        val (_, out) = mixerWithADuckedOrbit("duckorbit" to 2.0, "duckattack" to 0.2, "duckdepth" to 1.0)
        val t0 = SR / 2
        assertEquals(1.0, out[t0 - 10].toDouble(), 1e-4)
        assertEquals(0.01, out[t0].toDouble(), 1e-4)
        // 0.01 * 100 ^ (0.1 / 0.2) = 0.1: 20 dB below full scale at half the attack
        assertEquals(0.1, out[t0 + SR / 10].toDouble(), 0.1 * (Math.pow(10.0, 1.0 / 20) - 1))
        assertEquals(1.0, out[t0 + SR / 5].toDouble(), 1e-3)
        assertEquals(1.0, out[t0 + SR / 2].toDouble(), 1e-6)
    }

    @Test
    fun aShallowDuckStopsAt1MinusTheSquareRootOfItsDepth() {
        val (_, out) = mixerWithADuckedOrbit("duckorbit" to 2.0, "duckattack" to 0.2, "duckdepth" to 0.5, "duckonset" to 0.05)
        val t0 = SR / 2
        val floor = 1 - Math.sqrt(0.5)
        assertEquals(floor, out[t0 + (0.05 * SR).toInt()].toDouble(), 2e-3)
        assertTrue(out.slice(t0 until t0 + SR / 4).min() >= (floor - 1e-3).toFloat())
    }

    @Test
    fun theAttackIsAtLeastTwoMilliseconds() {
        val (_, out) = mixerWithADuckedOrbit("duckorbit" to 2.0, "duckattack" to 0.0, "duckdepth" to 1.0)
        val t0 = SR / 2
        assertEquals(0.01, out[t0].toDouble(), 1e-4)
        assertEquals(1.0, out[t0 + 96].toDouble(), 1e-4)
        assertTrue(out[t0 + 48] in 0.05f..0.2f, "half way through the 96 frame attack: ${out[t0 + 48]}")
    }

    @Test
    fun aDuckThatMissesItsOrbitIsReportedOnceAndChangesNothing() {
        val messages = ArrayList<String>()
        val previous = cosic.pattern.logger
        cosic.pattern.logger = { message, _, _ -> messages.add(message) }
        try {
            val (_, out) = mixerWithADuckedOrbit("duckorbit" to 9.0, "duckdepth" to 1.0)
            assertEquals(1.0, out[SR].toDouble(), 1e-4)
            assertEquals(1, messages.count { "duck target orbit 9 does not exist" in it }, messages.toString())
        } finally {
            cosic.pattern.logger = previous
        }
    }

    @Test
    fun severalTargetsAreDuckedWithPerTargetSettings() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(10.0), 10.0, "orbit" to 2.0, "gain" to 0.5, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0))
        mixer.startVoice(vp(dcSource(10.0), 10.0, "orbit" to 3.0, "gain" to 0.5, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0))
        mixer.renderFrames(SR / 4)
        mixer.startVoice(vp(synth(Waveform.SINE), 0.01, "gain" to 0.0, "duckorbit" to listOf(2.0, 3.0), "duckdepth" to listOf(1.0, 0.5), "duckattack" to 0.5))
        val out = channel(mixer.renderFrames(100), 0)
        // orbit 2 at 0.01, orbit 3 at 1 - sqrt(.5), straight after the event
        assertEquals(0.5 * 0.01 + 0.5 * (1 - Math.sqrt(0.5)), out[2].toDouble(), 2e-3)
    }
}
