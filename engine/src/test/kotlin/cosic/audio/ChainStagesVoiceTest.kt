package cosic.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The distortion, modulation and dynamics stages of CHAIN Task D inside a voice ([Mixer]): they are in the chain when their controls are set, in superdough's
 * order, in `FX(...)` elements as in the main chain, and hostile values never make NaN or a sample beyond the master's clamp.
 */
class ChainStagesVoiceTest {
    private val saw = synth(Waveform.SAWTOOTH)

    private fun voice(vararg extra: Pair<String, Any?>, duration: Double = 0.5) =
        vp(saw, duration, "note" to 50.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0, *extra)

    private val baseline by lazy { renderLeft(voice(), SR / 2) }

    private fun maxDifference(a: FloatArray, b: FloatArray): Float {
        var worst = 0f
        for (i in a.indices) worst = maxOf(worst, abs(a[i] - b[i]))
        return worst
    }

    @Test
    fun everyStageChangesTheVoiceWhenItsControlIsSet() {
        assertTrue(peak(baseline) > 0.1f, "the baseline sounds")
        val cases = mapOf(
            "coarse" to arrayOf("coarse" to 12.0),
            "crush" to arrayOf("crush" to 3.0),
            "shape" to arrayOf("shape" to 0.9),
            "distort" to arrayOf("distort" to 3.0),
            "distort fold" to arrayOf("distort" to 2.0, "distorttype" to "fold"),
            "tremolo" to arrayOf("tremolo" to 6.0, "tremolodepth" to 0.9),
            "tremolosync" to arrayOf("tremolosync" to 4.0),
            "compressor" to arrayOf("compressor" to -30.0, "compressorRatio" to 8.0),
            "phaser" to arrayOf("phaserrate" to 3.0),
            "transient" to arrayOf("transient" to 1.0),
        )
        for ((name, controls) in cases) {
            val out = renderLeft(voice(*controls), SR / 2)
            assertTrue(allFinite(out), "$name is finite")
            assertTrue(peak(out) > 0.01f, "$name sounds")
            assertTrue(maxDifference(out, baseline) > 1e-3f, "$name changes the sound")
        }
    }

    @Test
    fun theSecondaryControlsOfAStageDoNothingWithoutItsTrigger() {
        // distorttype/distortvol without distort, tremolodepth/shape/skew/phase without tremolo, the compressor settings without compressor,
        // phaserdepth/center/sweep without phaserrate, transsustain without transient, shapevol without shape
        val out = renderLeft(
            voice(
                "distorttype" to "fold", "distortvol" to 0.3, "tremolodepth" to 0.9, "tremoloshape" to "square", "tremoloskew" to 0.2, "tremolophase" to 0.4,
                "compressorRatio" to 20.0, "compressorKnee" to 0.0, "compressorAttack" to 0.0, "compressorRelease" to 0.0,
                "phaserdepth" to 0.9, "phasercenter" to 300.0, "phasersweep" to 3000.0, "transsustain" to 0.8, "shapevol" to 0.2,
            ),
            SR / 2,
        )
        assertContentEquals(baseline, out)
        // and a phaser with depth 0 is absent although it has a rate
        assertContentEquals(baseline, renderLeft(voice("phaserrate" to 3.0, "phaserdepth" to 0.0), SR / 2))
    }

    @Test
    fun theStagesRunInSuperdoughsOrderCoarseCrushShapeDistort() {
        // DC 0.7 at the gain of 0.8 is 0.56. crush 2 (two steps per unit) then shape 0.5 gives crush(0.56) = 0.5, then 3 * 0.5 / (1 + 2 * 0.5) = 0.75.
        // The other order would give 1.0 (shape(0.56) = 0.79, crush -> 1).
        val source = dcSource(2.0, 0.7f)
        val out = renderLeft(vp(source, 1.0, "crush" to 2.0, "shape" to 0.5), SR)
        assertEquals(0.75f, out[SR / 2], 1e-4f)
        // distort after shape: scurve with distort 1 (k = e - 1) on the shaped 0.75
        val k = Math.E - 1
        val shaped = renderLeft(vp(source, 1.0, "crush" to 2.0, "shape" to 0.5, "distort" to 1.0), SR)
        assertEquals(((1 + k) * 0.75 / (1 + k * 0.75)).toFloat(), shaped[SR / 2], 1e-4f)
        // and coarse comes first: a coarse of 128 keeps one value per quantum, which crush then rounds
        val coarse = renderLeft(vp(source, 1.0, "coarse" to 128.0, "crush" to 2.0, "shape" to 0.5), SR)
        assertEquals(0.75f, coarse[SR / 2], 1e-4f)
    }

    @Test
    fun theStagesWorkInFxElementsLikeInTheMainChain() {
        val inMain = renderLeft(voice("distort" to 3.0, "tremolo" to 6.0, "compressor" to -30.0, "phaserrate" to 3.0), SR / 2)
        val inFx = renderLeft(voice("FX" to listOf(mapOf("distort" to 3.0, "tremolo" to 6.0, "compressor" to -30.0, "phaserrate" to 3.0))), SR / 2)
        assertTrue(maxDifference(inFx, baseline) > 1e-3f)
        // the element has its own gain stage (0.8), so the level is 0.8 lower, but it is the same chain
        assertTrue(allFinite(inFx))
        assertTrue(peak(inFx) > 0.01f && peak(inMain) > 0.01f)
        // both together distort twice
        val both = renderLeft(voice("distort" to 3.0, "FX" to listOf(mapOf("distort" to 3.0))), SR / 2)
        val once = renderLeft(voice("distort" to 3.0), SR / 2)
        assertTrue(maxDifference(both, once) > 1e-3f)
    }

    @Test
    fun aCompressedMonoVoiceIsStereoWithEqualChannelsAndTheLookAhead() {
        val out = renderStereo(voice("compressor" to -30.0), SR / 2)
        val left = channel(out, 0)
        val right = channel(out, 1)
        assertContentEquals(left, right)
        // the 6 ms look-ahead (288 frames) delays the onset of the voice
        assertEquals(0f, peak(left, 0, 288))
        assertTrue(peak(left, 288, 1000) > 0.001f)
    }

    @Test
    fun hostileControlsNeverMakeNaNOrASampleBeyondTheMasterClamp() {
        val hostile = listOf(
            arrayOf("coarse" to 1e300, "crush" to 1e9, "shape" to -3.0, "distort" to 1e9),
            arrayOf("distort" to 800.0, "distorttype" to "chebyshev", "distortvol" to -4.0),
            arrayOf("tremolo" to 1e12, "tremolodepth" to -1e9, "tremolophase" to 1e300, "tremoloskew" to -9.0),
            arrayOf("compressor" to 1e30, "compressorRatio" to 0.0, "compressorKnee" to -1e30, "compressorAttack" to -5.0, "compressorRelease" to 1e30),
            arrayOf("phaserrate" to 1e12, "phasercenter" to 1e30, "phasersweep" to -1e30, "phaserdepth" to 1e300),
            arrayOf("transient" to 1e300, "transsustain" to -1e300),
            arrayOf(
                "coarse" to 2.5, "crush" to 0.0, "shape" to 7.0, "shapevol" to -2.0, "distort" to -1e9, "distorttype" to -4.0, "tremolosync" to 1e300, "compressor" to -100.0,
                "compressorRatio" to 20.0, "phaserrate" to -9.0, "transient" to -1.0,
            ),
        )
        for (controls in hostile) {
            for (extra in listOf(emptyArray<Pair<String, Any?>>(), arrayOf<Pair<String, Any?>>("FX" to listOf(mapOf(*controls))))) {
                val out = renderStereo(voice(*controls, *extra, duration = 0.2), SR / 4)
                assertTrue(allFinite(out), "controls ${controls.toList()} (with FX: ${extra.isNotEmpty()})")
                assertTrue(out.all { abs(it) <= 1f }, "the master clamps to +-1")
            }
        }
    }
}
