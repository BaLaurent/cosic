package cosic.soundfont.sf2

import cosic.audio.BlockContext
import cosic.audio.SR
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetPlayerTest {
    private fun zone(
        data: FloatArray = FloatArray(SR * 4) { 1f },
        rate: Double = 1.0,
        loop: Boolean = true,
        loopStart: Double = 0.0,
        loopEnd: Double = 0.0,
        pan: Double = 0.0,
        peak: Double = 0.3,
        delay: Double = 0.01,
        attack: Double = 0.05,
        hold: Double = 0.02,
        decay: Double = 0.1,
        sustain: Double = 0.5,
        release: Double = 0.2,
    ) = PresetZoneVoice(data, SR, rate, loop, loopStart, loopEnd, peak, delay, attack, hold, decay, sustain, release, pan)

    /** Renders [seconds] of [voices] noted at 0 and stopped at [stopAt]: both channels. */
    private fun render(voices: List<PresetZoneVoice>, stopAt: Double, seconds: Double): Pair<FloatArray, FloatArray> {
        val player = PresetPlayer(SR)
        player.start(voices, 0.0, stopAt)
        val frames = (seconds * SR).toInt()
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val ctx = BlockContext(SR)
        val l = FloatArray(128)
        val r = FloatArray(128)
        var done = 0
        while (done < frames) {
            ctx.startFrame = done.toLong()
            ctx.n = minOf(128, frames - done)
            player.fill(l, r, ctx)
            l.copyInto(left, done, 0, ctx.n)
            r.copyInto(right, done, 0, ctx.n)
            done += ctx.n
        }
        return left to right
    }

    /** `cancelAndHoldAtTime` applied: the events from the cancel time on are dropped and the value that was there is set at that time. */
    private fun effective(events: List<Pair<String, DoubleArray>>): List<Pair<String, DoubleArray>> {
        val out = ArrayList<Pair<String, DoubleArray>>()
        for (event in events) {
            if (event.first != "cancelAndHoldAtTime") {
                out.add(event)
                continue
            }
            val t = event.second[0]
            val held = automation(out, t)
            while (out.isNotEmpty() && out.last().second.last() >= t) out.removeAt(out.lastIndex)
            out.add("setValueAtTime" to doubleArrayOf(held, t))
        }
        return out
    }

    /** The automation of [events] (`setValueAtTime`, exponential ramps) at [t] by the rules of Web Audio: a ramp starts at the time and value of the event before it. */
    private fun automation(events: List<Pair<String, DoubleArray>>, t: Double): Double {
        var time = events[0].second[1]
        var value = events[0].second[0]
        for (i in 1 until events.size) {
            val (method, args) = events[i]
            when (method) {
                "setValueAtTime" -> {
                    if (t < args[1]) return value
                    time = args[1]
                    value = args[0]
                }
                "exponentialRampToValueAtTime" -> {
                    if (t < args[1]) return value * (args[0] / value).pow((t - time) / (args[1] - time))
                    time = args[1]
                    value = args[0]
                }
            }
        }
        return value
    }

    @Test
    fun theGainFollowsTheExponentialEnvelopeOfSfumatoAndTheSourceStopsAfterTheRelease() {
        val voice = zone()
        val events = effective(voice.envelope(0.0, 0.5))
        val (left, right) = render(listOf(voice), stopAt = 0.5, seconds = 1.0)
        var worst = 0.0
        for (k in 0 until left.size step 5) {
            val t = k.toDouble() / SR
            // the source stops `release` after the note; the automation sits at the floor from then on, the output at 0
            val expected = if (t >= 0.5 + 0.2) 0.0 else automation(events, t) * cos(PI / 4)
            worst = maxOf(worst, abs(left[k] - expected))
            assertEquals(left[k], right[k], 1e-6f, "centred: both channels equal")
        }
        assertTrue(worst < 3e-4, "off by $worst")
        // at the end of the hold the gain is the peak, at 0.5 s the sustain level, and after the release plus the source's stop nothing
        assertEquals(0.3 * cos(PI / 4), left[(0.07 * SR).toInt()].toDouble(), 2e-3)
        assertEquals(0.15 * cos(PI / 4), left[(0.5 * SR).toInt() - 5].toDouble(), 2e-3, "sustain 0.5 of the peak")
        assertTrue(left[(0.5 * SR).toInt() + (0.3 * SR).toInt()] == 0f, "stopped")
        assertTrue(left[(0.5 * SR).toInt() + (0.1 * SR).toInt()] < left[(0.5 * SR).toInt()] , "the release is falling")
    }

    @Test
    fun aNoteThatStopsInTheDelayOrTheAttackHoldsTheValueItHadAndReleasesFromThere() {
        for (stopAt in listOf(0.005, 0.03, 0.065, 0.0)) {
            val voice = zone()
            val events = effective(voice.envelope(0.0, stopAt))
            val (left, _) = render(listOf(voice), stopAt = stopAt, seconds = 0.6)
            var worst = 0.0
            for (k in left.indices step 3) {
                val t = k.toDouble() / SR
                if (abs(t - (stopAt + 0.2)) < 2.0 / SR) continue // the frame where the source stops
                val expected = if (t >= stopAt + 0.2) 0.0 else automation(events, t) * cos(PI / 4)
                worst = maxOf(worst, abs(left[k] - expected))
            }
            assertTrue(worst < 3e-4, "stop at $stopAt: off by $worst")
        }
    }

    @Test
    fun theGainStartsAtTheFloorAndTheDelayHoldsIt() {
        val (left, _) = render(listOf(zone(delay = 0.1)), stopAt = 0.5, seconds = 0.2)
        assertEquals(1e-3 * cos(PI / 4), left[SR / 20].toDouble(), 1e-6, "the floor during the delay")
        assertTrue(left[(0.2 * SR).toInt() - 1] > 0.01f, "rising after it")
    }

    @Test
    fun aPanMovesTheZoneBetweenTheChannelsWithTheEqualPowerLaw() {
        for (pan in listOf(-0.5, -0.25, 0.0, 0.25, 0.5)) {
            val (left, right) = render(listOf(zone(pan = pan)), stopAt = 0.5, seconds = 0.2)
            val x = (pan + 1) / 2
            val k = (0.1 * SR).toInt()
            assertEquals(cos(x * PI / 2), left[k].toDouble() / right[k] * sin(x * PI / 2), 1e-4, "pan $pan")
            assertTrue(left[k] > 0f && right[k] > 0f)
        }
        val (hardLeft, hardRight) = render(listOf(zone(pan = -1.0)), stopAt = 0.5, seconds = 0.1)
        assertTrue(hardLeft[SR / 20] > 0.01f && abs(hardRight[SR / 20]) < 1e-6f)
    }

    @Test
    fun aLoopKeepsTheSoundGoingAndWithoutOneTheSampleEnds() {
        val ramp = FloatArray(4800) { 1f }
        val looping = render(listOf(zone(data = ramp, loop = true, loopStart = 0.02, loopEnd = 0.08)), stopAt = 0.5, seconds = 0.4).first
        assertTrue(looping[(0.3 * SR).toInt()] > 0.1f, "still sounding after the 0.1 s sample")
        val once = render(listOf(zone(data = ramp, loop = false)), stopAt = 0.5, seconds = 0.4).first
        assertTrue(once[(0.07 * SR).toInt()] > 0.1f)
        assertEquals(0f, once[(0.3 * SR).toInt()])
    }

    @Test
    fun theRateRepitchesTheSampleAndSeveralZonesAreAdded() {
        val sine = FloatArray(SR) { sin(2 * PI * 220 * it / SR).toFloat() }
        val (left, _) = render(listOf(zone(data = sine, rate = 2.0, attack = 0.001, delay = 0.0, hold = 0.0, decay = 0.001, sustain = 1.0)), stopAt = 0.5, seconds = 0.5)
        var crossings = 0
        for (i in 4800 until 14_400) if (left[i - 1] < 0f && left[i] >= 0f) crossings++
        assertTrue(crossings in 87..89, "440 Hz over 0.2 s, found $crossings")
        val one = render(listOf(zone(delay = 0.0, pan = -0.5)), 0.5, 0.2)
        val two = render(listOf(zone(delay = 0.0, pan = -0.5), zone(delay = 0.0, pan = -0.5)), 0.5, 0.2)
        val k = (0.15 * SR).toInt()
        assertEquals(2 * one.first[k], two.first[k], 1e-5f)
    }
}
