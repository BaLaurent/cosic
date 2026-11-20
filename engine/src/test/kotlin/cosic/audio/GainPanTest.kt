package cosic.audio

import cosic.pattern.Undefined
import cosic.player.paramSetOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The level stages of a voice, CHAIN Task B: the `gain` node that starts every chain (gain x velocity, default 0.8, identity curve), the `post`
 * gain and the `dry` path, and the StereoPanner (superdough.mjs:615-633, 864-870, 946-1002). Stage-level tests read the stages directly; the
 * end-to-end ones go through [Mixer] with a constant sample, whose level at the output is the product of the gains.
 */
class GainPanTest {
    private val timing = VoiceTiming()

    private fun gainStage(vararg controls: Pair<String, Any?>, isMain: Boolean = true): GainStage {
        val stage = GainStage(SR)
        stage.reset()
        assertTrue(stage.configure(paramSetOf(mapOf(*controls)), timing, isMain), "a chain always has its gain node")
        return stage
    }

    private fun at(x: FloatArray, seconds: Double): Float = x[(seconds * SR).toInt()]

    // ------------------------------------------------------------------------------------------------ gain stage

    @Test
    fun gainDefaultsToPointEightAndVelocityToOne() {
        assertEquals(0.8, gainStage().gain.constantValue, 0.0)
        assertEquals(0.5, gainStage("gain" to 0.5).gain.constantValue, 0.0)
        assertEquals(0.8 * 0.5, gainStage("velocity" to 0.5).gain.constantValue, 1e-12)
        assertEquals(0.25, gainStage("gain" to 0.5, "velocity" to 0.5).gain.constantValue, 1e-12)
    }

    @Test
    fun everyFxElementGetsTheSameGainStageAsTheMainChain() {
        assertEquals(0.8, gainStage(isMain = false).gain.constantValue, 0.0)
        assertEquals(0.25, gainStage("gain" to 0.5, "velocity" to 0.5, isMain = false).gain.constantValue, 1e-12)
    }

    @Test
    fun aGainThatIsNoNumberIsOneAndAnUnsetGainIsTheDefault() {
        // nanFallback(gain, 1): NaN and text count as 1; only `undefined` takes the default
        assertEquals(1.0, gainStage("gain" to Double.NaN).gain.constantValue, 0.0)
        assertEquals(1.0, gainStage("gain" to "loud").gain.constantValue, 0.0)
        assertEquals(0.8, gainStage("gain" to Undefined).gain.constantValue, 0.0)
        assertEquals(0.8, gainStage("gain" to null).gain.constantValue, 0.0)
        assertEquals(0.5, gainStage("gain" to "0.5").gain.constantValue, 0.0, "a numeric string is the number")
    }

    @Test
    fun theGainCurveIsTheIdentityAndGainsAreNotClamped() {
        assertEquals(100.0, gainStage("gain" to 100.0).gain.constantValue, 0.0)
        assertEquals(-3.0, gainStage("gain" to -3.0).gain.constantValue, 0.0)
        assertEquals(0.0, gainStage("gain" to 0.0).gain.constantValue, 0.0)
    }

    @Test
    fun theGainStageMultipliesBothChannelsOfTheBlock() {
        val stage = gainStage("gain" to 0.5)
        val b = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR).also { it.n = 8 }
        b.l.fill(1f, 0, 8)
        b.r.fill(-1f, 0, 8)
        b.channels = 2
        stage.process(b, ctx)
        assertEquals(0.5f, b.l[7])
        assertEquals(-0.5f, b.r[7])
    }

    @Test
    fun theGainStageIsAnAutomatableParameterForModulators() {
        val stage = gainStage("gain" to 1.0)
        val b = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR).also { it.n = 100 }
        b.l.fill(1f, 0, 100)
        stage.gain.setValueAtTime(1.0, 0.0)
        stage.gain.linearRampToValueAtTime(0.0, 100.0 / SR, 0.0)
        stage.process(b, ctx)
        assertEquals(0.5f, b.l[50], 1e-4f)
        assertEquals(0.99f, b.l[1], 1e-4f)
    }

    // ------------------------------------------------------------------------------------------------ post and dry

    @Test
    fun postgainDefaultsToOneAndTheDirectPathIsThePostGainUnlessDryIsSet() {
        val post = PostStage()
        post.configure(paramSetOf(emptyMap()))
        assertEquals(1.0, post.post.constantValue, 0.0)
        assertFalse(post.dryUsed)
        assertEquals(1.0, post.dry.constantValue, 0.0)

        post.configure(paramSetOf(mapOf("postgain" to 2.0, "dry" to 0.25)))
        assertEquals(2.0, post.post.constantValue, 0.0)
        assertTrue(post.dryUsed)
        assertEquals(0.25, post.dry.constantValue, 0.0)

        post.configure(paramSetOf(mapOf("dry" to 0.0)))
        assertTrue(post.dryUsed, "dry 0 is set: the direct signal is gone")
        assertEquals(0.0, post.dry.constantValue, 0.0)
    }

    @Test
    fun theDryGainIsNotAFunctionOfThePostGain() {
        // the order is post then dry: the direct level is postgain x dry
        val level = at(renderLeft(vp(dcSource(), 1.0, "postgain" to 2.0, "dry" to 0.5)), 0.25).toDouble()
        assertEquals(0.8 * 2.0 * 0.5, level, 1e-4)
    }

    @Test
    fun postStageScalesTheBlock() {
        val post = PostStage()
        post.configure(paramSetOf(mapOf("postgain" to 0.5)))
        val b = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR).also { it.n = 4 }
        b.l.fill(1f, 0, 4)
        b.r.fill(2f, 0, 4)
        b.channels = 2
        post.process(b, ctx)
        assertEquals(0.5f, b.l[3])
        assertEquals(1f, b.r[3])
    }

    // ------------------------------------------------------------------------------------------------ end to end levels

    @Test
    fun levelsAtTheOutputFollowTheTable() {
        fun level(vararg c: Pair<String, Any?>) = at(renderLeft(vp(dcSource(), 1.0, *c)), 0.25).toDouble()
        assertEquals(0.8, level(), 1e-4)
        assertEquals(0.5, level("gain" to 0.5), 1e-4)
        assertEquals(0.25, level("gain" to 0.5, "velocity" to 0.5), 1e-4)
        assertEquals(0.5, level("gain" to 0.5, "velocity" to 0.5, "postgain" to 2.0), 1e-4)
        assertEquals(1.0, level("gain" to Double.NaN), 1e-4)
    }

    @Test
    fun aMonoVoiceReachesBothChannelsAtUnityWithoutPanAndTheOutputHasNoMasterGain() {
        val out = renderStereo(vp(dcSource(), 1.0, "gain" to 1.0))
        assertEquals(1.0, out[2 * (SR / 4)].toDouble(), 1e-4)
        assertEquals(1.0, out[2 * (SR / 4) + 1].toDouble(), 1e-4)
    }

    @Test
    fun postgainComesAfterTheWholeChainIncludingTheFxElementsAndTheFxRelease() {
        // FX element gain 0.5, main gain 0.5, post 2: 0.5 x 0.5 x 0.5... each chain starts with its own gain node (default 0.8 for the missing one)
        val out = renderLeft(vp(dcSource(), 1.0, "gain" to 0.5, "postgain" to 2.0, "FX" to listOf(mapOf("gain" to 0.5))))
        assertEquals(0.5 * 0.5 * 2.0, at(out, 0.25).toDouble(), 1e-4)
        // and the FXrelease fade is applied before post: the tail level scales with postgain
        val faded = renderLeft(vp(dcSource(), 1.0, "FXrelease" to 1.0, "postgain" to 0.5), SR * 2)
        assertEquals(0.8 * 0.5, at(faded, 0.9).toDouble(), 1e-4)
        assertEquals(0.8 * 0.5 * 0.5, at(faded, 1.005 + 0.495).toDouble(), 0.004)
    }

    // ------------------------------------------------------------------------------------------------ pan

    /** Runs a [PanStage] over a 4-frame block; returns the output frame 2, or null for the stage being inactive (the block is left alone). */
    private fun pan(pan: Double?, l: Float, r: Float?): Triple<Float, Float, Int> {
        val stage = PanStage()
        stage.reset()
        val on = stage.configure(paramSetOf(if (pan == null) emptyMap() else mapOf("pan" to pan)), timing, true)
        assertEquals(pan != null, on, "the panner exists only when pan is set")
        val b = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR).also { it.n = 4 }
        b.l.fill(l, 0, 4)
        if (r != null) {
            b.r.fill(r, 0, 4)
            b.channels = 2
        }
        if (on) stage.process(b, ctx)
        return Triple(b.l[2], b.r[2], b.channels)
    }

    @Test
    fun aMonoSignalIsPannedWithTheEqualPowerLaw() {
        val (l0, r0, c0) = pan(0.0, 1f, null)
        assertEquals(1.0, l0.toDouble(), 1e-6)
        assertEquals(0.0, r0.toDouble(), 1e-6)
        assertEquals(2, c0, "the panner makes it stereo")
        val (l5, r5, _) = pan(0.5, 1f, null)
        assertEquals(0.70711, l5.toDouble(), 1e-4)
        assertEquals(0.70711, r5.toDouble(), 1e-4)
        val (l1, r1, _) = pan(1.0, 1f, null)
        assertEquals(0.0, l1.toDouble(), 1e-6)
        assertEquals(1.0, r1.toDouble(), 1e-6)
        val (l25, r25, _) = pan(0.25, 1f, null)
        assertEquals(cos((-0.5 * 0.5 + 0.5) * PI / 2), l25.toDouble(), 1e-5)
        assertEquals(sin((-0.5 * 0.5 + 0.5) * PI / 2), r25.toDouble(), 1e-5)
    }

    @Test
    fun withoutPanTheSignalStaysMono() {
        val (l, r, channels) = pan(null, 1f, null)
        assertEquals(1f, l)
        assertEquals(0f, r)
        assertEquals(1, channels, "the orbit copies a mono signal to both sides at unity")
    }

    @Test
    fun aStereoSignalIsPannedByMovingTheCrossedChannel() {
        // L = 1, R = 0.5
        val centre = pan(0.5, 1f, 0.5f)
        assertEquals(1.0, centre.first.toDouble(), 1e-5)
        assertEquals(0.5, centre.second.toDouble(), 1e-5)
        val left = pan(0.0, 1f, 0.5f)
        assertEquals(1.5, left.first.toDouble(), 1e-5)
        assertEquals(0.0, left.second.toDouble(), 1e-5)
        val right = pan(1.0, 1f, 0.5f)
        assertEquals(0.0, right.first.toDouble(), 1e-5)
        assertEquals(1.5, right.second.toDouble(), 1e-5)
        // pan 0.25 is p = -0.5: x = 0.5 -> gains cos/sin(pi/4)
        val quarter = pan(0.25, 1f, 0.5f)
        assertEquals(1.0 + 0.5 * cos(PI / 4), quarter.first.toDouble(), 1e-5)
        assertEquals(0.5 * sin(PI / 4), quarter.second.toDouble(), 1e-5)
        // pan 0.75 is p = 0.5
        val threeQuarters = pan(0.75, 1f, 0.5f)
        assertEquals(cos(PI / 4), threeQuarters.first.toDouble(), 1e-5)
        assertEquals(0.5 + sin(PI / 4), threeQuarters.second.toDouble(), 1e-5)
    }

    @Test
    fun thePannerValueIsClampedToMinusOneToOne() {
        assertEquals(pan(1.0, 1f, null), pan(7.0, 1f, null))
        assertEquals(pan(0.0, 1f, null), pan(-7.0, 1f, null))
        assertEquals(pan(1.0, 1f, 0.5f), pan(7.0, 1f, 0.5f))
    }

    @Test
    fun theLawIsEvaluatedPerFrameFromTheAutomatedParameter() {
        val stage = PanStage()
        stage.reset()
        assertTrue(stage.configure(paramSetOf(mapOf("pan" to 0.5)), timing, true))
        stage.pan.setValueAtTime(-1.0, 0.0)
        stage.pan.linearRampToValueAtTime(1.0, 256.0 / SR, 0.0)
        val b = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR).also { it.n = 256 }
        b.l.fill(1f, 0, 256)
        stage.process(b, ctx)
        for (i in 0 until 256) {
            val p = -1.0 + 2.0 * i / 256
            val theta = (p * 0.5 + 0.5) * PI / 2
            assertEquals(cos(theta), b.l[i].toDouble(), 2e-3, "left at frame $i")
            assertEquals(sin(theta), b.r[i].toDouble(), 2e-3, "right at frame $i")
        }
    }

    @Test
    fun panEndToEndThroughTheMixer() {
        fun frame(vararg c: Pair<String, Any?>): Pair<Double, Double> {
            val out = renderStereo(vp(dcSource(), 1.0, "gain" to 1.0, *c))
            val i = 2 * (SR / 4)
            return out[i].toDouble() to out[i + 1].toDouble()
        }
        val unset = frame()
        assertEquals(1.0, unset.first, 1e-4)
        assertEquals(1.0, unset.second, 1e-4)
        val left = frame("pan" to 0.0)
        assertEquals(1.0, left.first, 1e-4)
        assertEquals(0.0, left.second, 1e-4)
        val centre = frame("pan" to 0.5)
        assertEquals(0.70711, centre.first, 1e-4)
        assertEquals(0.70711, centre.second, 1e-4)
        val right = frame("pan" to 1.0)
        assertEquals(0.0, right.first, 1e-4)
        assertEquals(1.0, right.second, 1e-4)
    }

    @Test
    fun aPanInsideAnFxElementPansThatChainAndTheMainChainPansTheResult() {
        // FX element pans hard right (mono -> stereo), then the main chain's pan 0.5 sees a stereo signal: p = 0, L = L cos, R = R + L sin at x = 0 -> pass-through
        val out = renderStereo(vp(dcSource(), 1.0, "gain" to 1.0, "FX" to listOf(mapOf("pan" to 1.0, "gain" to 1.0)), "pan" to 0.5))
        val i = 2 * (SR / 4)
        assertEquals(0.0, out[i].toDouble(), 1e-4)
        assertEquals(1.0, out[i + 1].toDouble(), 1e-4)
    }

    // ------------------------------------------------------------------------------------------------ channels

    @Test
    fun theChannelsControlRoutesTheOrbitToTheDeviceChannels() {
        fun render(channels: List<Double>): FloatArray =
            renderStereo(vp(dcSource(), 1.0, "gain" to 1.0, "pan" to 0.0, "channels" to channels, "orbit" to 5.0))
        val i = 2 * (SR / 4)
        val swapped = render(listOf(2.0, 3.0))
        assertEquals(0.0, swapped[i].toDouble(), 1e-4)
        assertEquals(1.0, swapped[i + 1].toDouble(), 1e-4, "orbit left (the panned signal) goes to output 2")
        val identity = render(listOf(3.0, 4.0))
        assertEquals(1.0, identity[i].toDouble(), 1e-4)
        assertEquals(0.0, identity[i + 1].toDouble(), 1e-4)
    }

    @Test
    fun outputChannelMapping() {
        assertContentEquals(intArrayOf(0, 1), mapOutputChannels(null, 1.0, false))
        assertContentEquals(intArrayOf(0, 1), mapOutputChannels(null, 3.0, false), "without multi-channel orbits every orbit uses 1 and 2")
        assertContentEquals(intArrayOf(2, 3), mapOutputChannels(null, 2.0, true))
        assertContentEquals(intArrayOf(0, 1), mapOutputChannels(null, 0.0, true), "orbit 0 is not multi-channel")
        assertContentEquals(intArrayOf(1, 2), mapOutputChannels(intArrayOf(2, 3), 1.0, false))
        assertContentEquals(intArrayOf(4, 5), mapOutputChannels(intArrayOf(5, 6), 2.0, true))
    }

    @Test
    fun multiChannelOrbitsFoldOntoTheStereoDeviceByChannelParity() {
        // orbit 2 plays on channels 3 and 4 -> zero-based 2, 3 -> device channels 0 and 1 (ch % 2)
        val mixer = Mixer(SR)
        mixer.multiChannelOrbits = true
        mixer.startVoice(vp(dcSource(), 1.0, "gain" to 1.0, "pan" to 0.0, "orbit" to 2.0))
        val out = mixer.renderFrames(SR / 2)
        assertEquals(1.0, out[2 * (SR / 4)].toDouble(), 1e-4)
        assertEquals(0.0, out[2 * (SR / 4) + 1].toDouble(), 1e-4)
    }
}
