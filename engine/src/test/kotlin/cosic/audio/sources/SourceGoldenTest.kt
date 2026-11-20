package cosic.audio.sources

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every synth source against the real thing: `tools/strudel-ref/sources-oracle.mjs` ran the `onTrigger` of the pinned superdough's sounds (and the real source
 * worklets) in headless Chromium, and each case in `src/test/resources/sources` is what an OfflineAudioContext rendered, from the start frame of the note on.
 * The sources start at the same absolute frame (so that the render quantum grid of the worklets lines up) and are asked for the audio in blocks of several sizes.
 * `COSIC_SOURCES_VERBOSE=true` prints the worst error of every case.
 */
class SourceGoldenTest {
    /** The generator the oracle's worklet scope replaces `Math.random` with: a 32-bit LCG from 12345. */
    private fun workletRandom(): () -> Double {
        var state = 12345
        return {
            state = state * 1664525 + 1013904223
            (state.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
        }
    }

    private fun build(g: SourceGolden): SoundSource {
        val spec = g.spec()
        val sound = g.sound!!
        return when (sound) {
            "supersaw" -> SupersawSource(spec, PitchModulation.create(spec, g.durationSec), null, workletRandom())
            "pulse" -> PulseSource(spec, PitchModulation.create(spec, g.durationSec), null)
            "bytebeat" -> ByteBeatSource(spec)
            "sbd" -> SbdSource(spec, g.noise)
            "pink", "white", "brown", "crackle" -> NoiseSource(spec, NoiseType.valueOf(sound.uppercase()), g.noise)
            else -> OscillatorSource(spec, sound, PitchModulation.create(spec, g.durationSec), null, g.noise)
        }
    }

    private fun run(file: String, atol: (SourceGolden) -> Double) {
        for (g in SourceGolden.load(file)) {
            val tolerance = atol(g)
            for (blocks in listOf(intArrayOf(256), intArrayOf(97, 500, 31, 128))) {
                val (left, right) = renderSource(build(g), g.startFrame, g.frames, *blocks)
                assertClose("${g.name} left, blocks ${blocks.toList()}", g.left, left, tolerance)
                if (g.stereo) assertClose("${g.name} right, blocks ${blocks.toList()}", g.right!!, right, tolerance)
            }
        }
    }

    /**
     * Smooth waves agree with Chromium to float32 precision. A sawtooth or a square has steep edges where a frame or two a period are off by up to 8e-5 (3.2 found the
     * same against bare oscillators). A vibrato starts on an unaligned frame, where Chromium's OscillatorNode reads the first frames' sample-accurate values from the
     * quantum's first frame (see the oracle), which leaves a few 1e-3.
     */
    private fun oscillatorTolerance(g: SourceGolden): Double = when {
        g.name.startsWith("vibrato") -> 3e-3
        g.sound == "sine" || g.sound == "triangle" || g.sound == "one" -> 3e-6
        g.name.startsWith("saw-n") || g.name.startsWith("square-n") || g.name.startsWith("user") || g.name.contains("partials") -> 1e-5
        g.name.startsWith("pitch-envelope") -> 1e-4
        g.name.startsWith("noise-mix") -> 1e-4
        else -> 2e-4
    }

    @Test
    fun oscillatorsMatchChromium() = run("oscillators.json", ::oscillatorTolerance)

    @Test
    fun noisesMatchChromium() = run("noises.json") { 1e-6 }

    @Test
    fun workletSourcesMatchChromium() = run("worklets.json") { g -> if (g.name.contains("vibrato")) 1e-4 else 2e-6 }

    @Test
    fun sbdMatchesChromium() = run("sbd.json") { 1e-4 }

    @Test
    fun goldensAreLoaded() {
        assertEquals(38, SourceGolden.load("oscillators.json").size)
        assertEquals(6, SourceGolden.load("noises.json").size)
        assertEquals(41, SourceGolden.load("worklets.json").size)
        assertEquals(4, SourceGolden.load("sbd.json").size)
        assertEquals(16, SourceGolden.load("pitch.json").size)
    }
}
