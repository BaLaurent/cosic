package cosic.audio

import cosic.audio.dsp.DistortProcessor
import cosic.audio.dsp.Distortion
import cosic.audio.dsp.ShapeCrushCoarse
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.tanh
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The memoryless distortion stages of a voice, CHAIN Task D: `coarse`, `crush`, `shape`, `distort` (and `distorttype`, `distortvol`) and the `transient`
 * shaper. The curves and the worklets themselves are checked against the real source by the tests of the kernels (`cosic.audio.dsp`); these tests check the
 * stages: when they take part in the chain, which control feeds which parameter, that parameters are read per quantum or per frame as the worklets read them,
 * the values of the CHAIN table, independence of the block size, and that hostile values stay finite.
 */
class DistortionStagesTest {
    private fun ramp(n: Int) = FloatArray(n) { it / 100f }

    // ------------------------------------------------------------------------------------------------ coarse

    @Test
    fun coarseIsInTheChainOnlyWhenSet() {
        assertFalse(CoarseStage(SR).isInChain())
        assertFalse(CoarseStage(SR).isInChain("crush" to 4.0))
        assertTrue(CoarseStage(SR).isInChain("coarse" to 4.0))
        assertTrue(CoarseStage(SR).isInChain("coarse" to 0.0), "even a coarse of 0 puts the node in the chain")
    }

    @Test
    fun coarseFourHoldsEveryFourthSampleOfEachQuantum() {
        val x = ramp(512)
        val y = CoarseStage(SR).startedWith("coarse" to 4.0).pushMono(x)
        for (frame in 0 until 512) {
            val n = frame % 128
            assertEquals(x[frame - n % 4], y[frame], "frame $frame (n $n)")
        }
    }

    @Test
    fun coarseThreeRestartsItsHoldAtTheStartOfTheNextQuantum() {
        val x = ramp(512)
        val y = CoarseStage(SR).startedWith("coarse" to 3.0).pushMono(x)
        for (frame in 0 until 512) assertEquals(x[frame - (frame % 128) % 3], y[frame], "frame $frame")
        // frame 126 is held for 126, 127; frame 128 takes a new value although 127 % 3 would still hold
        assertEquals(x[126], y[127])
        assertEquals(x[128], y[128])
    }

    @Test
    fun coarseBelowOneAndZeroPassTheSignalThrough() {
        val x = ramp(256)
        assertContentEquals(x, CoarseStage(SR).startedWith("coarse" to 0.0).pushMono(x))
        assertContentEquals(x, CoarseStage(SR).startedWith("coarse" to 1.0).pushMono(x))
        assertContentEquals(x, CoarseStage(SR).startedWith("coarse" to -7.0).pushMono(x))
    }

    @Test
    fun aFractionalCoarseUpdatesWhereTheRemainderIsBelowOne() {
        val x = ramp(256)
        val y = CoarseStage(SR).startedWith("coarse" to 2.5).pushMono(x)
        var held = 0f
        for (frame in 0 until 256) {
            val n = frame % 128
            if (n % 2.5 < 1.0) held = x[frame]
            assertEquals(held, y[frame], "frame $frame (n $n)")
        }
        // n = 0, 1 update, 2 holds, 3 holds (3 % 2.5 = 0.5 < 1: updates), 4 holds, 5 updates...
        assertEquals(x[3], y[3])
        assertEquals(x[3], y[4])
        assertEquals(x[5], y[5])
    }

    @Test
    fun coarseHoldsAreCountedFromTheAbsoluteFrameWhateverTheBlocks() {
        val x = ramp(1000)
        val whole = CoarseStage(SR).startedWith("coarse" to 5.0).pushMono(x)
        for (split in listOf(intArrayOf(97), intArrayOf(1), intArrayOf(33, 17, 200, 1, 255), intArrayOf(128))) {
            assertContentEquals(whole, CoarseStage(SR).startedWith("coarse" to 5.0).pushMono(x, split), "blocks ${split.toList()}")
        }
        // a voice that starts in the middle of a quantum: frame 50 of the engine is n = 50 of its quantum
        val late = CoarseStage(SR).startedWith("coarse" to 4.0).pushMono(ramp(100), startFrame = 50)
        for (i in 0 until 100) {
            val n = ((50 + i) % 128)
            // n % 4 < 1 takes the input; else the previous output, which is 0 before the first frame of the voice
            val expected = if (n % 4 == 0) ramp(100)[i] else if (i == 0) 0f else late[i - 1]
            assertEquals(expected, late[i], "frame ${50 + i}")
        }
    }

    @Test
    fun coarseProcessesBothChannelsAndLeavesAMonoBlocksRightChannelAlone() {
        val x = ramp(256)
        val stereo = CoarseStage(SR).startedWith("coarse" to 4.0).pushThrough(x, x.reversedArray())
        assertContentEquals(CoarseStage(SR).startedWith("coarse" to 4.0).pushMono(x), stereo[0])
        assertContentEquals(CoarseStage(SR).startedWith("coarse" to 4.0).pushMono(x.reversedArray()), stereo[1])

        val stage = CoarseStage(SR).startedWith("coarse" to 4.0)
        val block = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR)
        block.channels = 1
        for (i in 0 until 128) {
            block.l[i] = x[i]
            block.r[i] = 7f
        }
        ctx.n = 128
        stage.process(block, ctx)
        assertTrue((0 until 128).all { block.r[it] == 7f }, "a mono block's right channel is not touched")
    }

    @Test
    fun aMovingCoarseIsReadAtTheStartOfEveryQuantum() {
        val stage = CoarseStage(SR).startedWith("coarse" to 2.0)
        stage.coarse.linearRampToValueAtTime(30.0, 1.0, 0.0) // 2 at t = 0 rising to 30 at t = 1 s
        val x = ramp(1024)
        val y = stage.pushMono(x)
        // reference: the kernel, one call per quantum, with the parameter at the first frame of the quantum (as a float32)
        val expected = x.copyOf()
        val reference = cosic.audio.dsp.CoarseProcessor()
        for (q in 0 until 8) {
            val value = stage.coarse.valueAt(q * 128.0 / SR).toFloat().toDouble()
            reference.process(expected, null, q * 128, q * 128 + 128, value, 0L)
        }
        assertContentEquals(expected, y)
        assertTrue(abs(stage.coarse.valueAt(0.0) - stage.coarse.valueAt(7 * 128.0 / SR)) > 0.1, "the parameter does move")
    }

    // ------------------------------------------------------------------------------------------------ crush

    @Test
    fun crushIsInTheChainOnlyWhenSet() {
        assertFalse(CrushStage(SR).isInChain("coarse" to 4.0))
        assertTrue(CrushStage(SR).isInChain("crush" to 4.0))
    }

    @Test
    fun crushThreeRoundsHalfwayValuesTheJavaScriptWay() {
        val stage = CrushStage(SR).startedWith("crush" to 3.0)
        val y = stage.pushMono(floatArrayOf(0.625f, -0.625f, 0.5f, 0.3f, -0.3f, 0.9f, 0f))
        // 4 steps per unit: round(0.625 * 4) = 3 (half up) -> 0.75; round(-2.5) = -2 -> -0.5
        assertEquals(0.75f, y[0])
        assertEquals(-0.5f, y[1])
        assertEquals(0.5f, y[2])
        assertEquals(0.25f, y[3])
        assertEquals(-0.25f, y[4])
        assertEquals(1.0f, y[5])
        assertEquals(0f, y[6])
    }

    @Test
    fun crushZeroIsCrushOne() {
        val x = FloatArray(200) { (it - 100) / 100f }
        val zero = CrushStage(SR).startedWith("crush" to 0.0).pushMono(x)
        assertContentEquals(CrushStage(SR).startedWith("crush" to 1.0).pushMono(x), zero)
        assertTrue(zero.all { it == -1f || it == 0f || it == 1f }, "one step per unit: levels -1, 0, 1")
        assertContentEquals(zero, CrushStage(SR).startedWith("crush" to -4.0).pushMono(x))
    }

    @Test
    fun crushIsBlockIndependentAndTakesTheParameterAtTheQuantumStart() {
        val x = FloatArray(1000) { (it - 500) / 520f }
        val stage = CrushStage(SR).startedWith("crush" to 2.0)
        stage.crush.linearRampToValueAtTime(10.0, 0.02, 0.0)
        val whole = stage.pushMono(x)
        // the value changes only at the quantum boundaries: piecewise constant crush over each 128 frames
        for (q in 0 until 7) {
            val expected = FloatArray(128) { x[q * 128 + it] }
            ShapeCrushCoarse.crush(expected, null, 0, 128, stage.crush.valueAt(q * 128.0 / SR).toFloat().toDouble())
            assertContentEquals(expected, whole.copyOfRange(q * 128, q * 128 + 128), "quantum $q")
        }
        for (split in listOf(intArrayOf(97), intArrayOf(33, 17, 200, 1, 255))) {
            val again = CrushStage(SR).startedWith("crush" to 2.0).also { it.crush.linearRampToValueAtTime(10.0, 0.02, 0.0) }.pushMono(x, split)
            assertContentEquals(whole.copyOfRange(0, 128 * 7), again.copyOfRange(0, 128 * 7), "blocks ${split.toList()}")
        }
    }

    // ------------------------------------------------------------------------------------------------ shape

    @Test
    fun shapeIsInTheChainOnlyWhenSetAndShapevolAloneDoesNotCount() {
        assertFalse(ShapeStage(SR).isInChain("shapevol" to 0.5))
        assertTrue(ShapeStage(SR).isInChain("shape" to 0.0))
    }

    @Test
    fun shapeHalfIsAThreeQuartersOfAHalf() {
        // shape 0.5: k = 2 * 0.5 / (1 - 0.5) = 2; (1 + 2) * 0.5 / (1 + 2 * 0.5) = 0.75
        assertEquals(0.75f, ShapeStage(SR).startedWith("shape" to 0.5).pushMono(floatArrayOf(0.5f))[0], 1e-6f)
        assertEquals(-0.75f, ShapeStage(SR).startedWith("shape" to 0.5).pushMono(floatArrayOf(-0.5f))[0], 1e-6f)
    }

    @Test
    fun shapeOneIsHardClippingAndShapeZeroTheIdentity() {
        val y = ShapeStage(SR).startedWith("shape" to 1.0).pushMono(floatArrayOf(0.01f, -0.01f, 0.5f, -0.9f))
        assertEquals(1f, y[0], 1e-3f)
        assertEquals(-1f, y[1], 1e-3f)
        assertEquals(1f, y[2], 1e-6f)
        assertEquals(-1f, y[3], 1e-6f)
        assertEquals(1f, ShapeStage(SR).startedWith("shape" to 7.0).pushMono(floatArrayOf(0.3f))[0], 1e-6f, "above 1 is the same as 1")
        val x = FloatArray(100) { (it - 50) / 50f }
        assertContentEquals(x, ShapeStage(SR).startedWith("shape" to 0.0).pushMono(x))
    }

    @Test
    fun shapevolScalesTheOutputAndIsClamped() {
        assertEquals(0.375f, ShapeStage(SR).startedWith("shape" to 0.5, "shapevol" to 0.5).pushMono(floatArrayOf(0.5f))[0], 1e-6f)
        assertEquals(0.75f, ShapeStage(SR).startedWith("shape" to 0.5, "shapevol" to 4.0).pushMono(floatArrayOf(0.5f))[0], 1e-6f, "above 1: 1")
        assertEquals(0.00075f, ShapeStage(SR).startedWith("shape" to 0.5, "shapevol" to 0.0).pushMono(floatArrayOf(0.5f))[0], 1e-7f, "below 0.001: 0.001")
    }

    @Test
    fun shapeAndShapevolAreReadAtTheQuantumStart() {
        val x = FloatArray(512) { 0.5f }
        val stage = ShapeStage(SR).startedWith("shape" to 0.1)
        stage.shape.linearRampToValueAtTime(0.9, 0.01, 0.0)
        stage.postgain.linearRampToValueAtTime(0.2, 0.01, 0.0)
        val y = stage.pushMono(x)
        for (q in 0 until 4) {
            val t = q * 128.0 / SR
            val expected = FloatArray(128) { 0.5f }
            ShapeCrushCoarse.shape(expected, null, 0, 128, stage.shape.valueAt(t).toFloat().toDouble(), stage.postgain.valueAt(t).toFloat().toDouble())
            assertContentEquals(expected, y.copyOfRange(q * 128, q * 128 + 128), "quantum $q")
        }
    }

    // ------------------------------------------------------------------------------------------------ distort

    @Test
    fun distortIsInTheChainOnlyWhenDistortIsSet() {
        assertFalse(DistortStage(SR).isInChain("distortvol" to 0.5, "distorttype" to "fold"))
        assertTrue(DistortStage(SR).isInChain("distort" to 0.0))
    }

    private fun curve(type: Any?, distort: Double, x: Float, vararg more: Pair<String, Any?>): Float {
        val controls = mutableListOf<Pair<String, Any?>>("distort" to distort)
        controls.addAll(more)
        if (type != null) controls.add("distorttype" to type)
        return DistortStage(SR).startedWith(*controls.toTypedArray()).pushMono(floatArrayOf(x))[0]
    }

    @Test
    fun theCurvesHaveTheValuesOfTheChainTableAtDistortOne() {
        // distort 1: k = expm1(1) = e - 1, x = 0.5
        assertEquals(0.7310586f, curve("scurve", 1.0, 0.5f), 1e-6f)
        assertEquals(0.7310586f, curve(null, 1.0, 0.5f), 1e-6f, "scurve is the default")
        assertEquals(0.87619f, curve("soft", 1.0, 0.5f), 1e-5f)
        assertEquals(1.0f, curve("hard", 1.0, 0.5f), 1e-7f)
        assertEquals(0.9159f, curve("cubic", 1.0, 0.5f), 1e-3f)
        assertEquals(0.9295705f, curve("fold", 1.0, 0.5f), 1e-6f)
        assertEquals(0.99389f, curve("sinefold", 1.0, 0.5f), 2e-4f)
        // what the pinned helpers.mjs computes (node, the real text): the table's hand-derived 0.5457, 0.4777 and 0.7715 are within 2e-3 of these
        assertEquals(0.915907f, curve("cubic", 1.0, 0.5f), 2e-6f)
        assertEquals(0.545689f, curve("diode", 1.0, 0.5f), 2e-6f)
        assertEquals(0.477792f, curve("asym", 1.0, 0.5f), 2e-6f)
        assertEquals(0.771403f, curve("chebyshev", 1.0, 0.5f), 2e-6f)
        val k = expm1(1.0)
        for ((index, name) in Distortion.names.withIndex()) {
            assertEquals(Distortion.apply(index, 0.5, k).toFloat(), curve(name, 1.0, 0.5f), 1e-6f, name)
        }
    }

    @Test
    fun distortZeroIsTheIdentityForTheSCurveAndTanhForSoft() {
        for (x in listOf(-0.9f, -0.2f, 0f, 0.4f, 0.8f)) {
            assertEquals(x, curve("scurve", 0.0, x), 1e-7f)
            assertEquals(tanh(x.toDouble()).toFloat(), curve("soft", 0.0, x), 1e-6f)
        }
    }

    @Test
    fun distorttypeIsANameOrANumberThatWrapsAroundTheNineCurves() {
        val x = 0.37f
        val scurve = curve("scurve", 1.5, x)
        val soft = curve("soft", 1.5, x)
        assertEquals(soft, curve(1.0, 1.5, x))
        assertEquals(scurve, curve(0.0, 1.5, x))
        assertEquals(scurve, curve(9.0, 1.5, x), "9 wraps to the first curve")
        assertEquals(soft, curve(10.0, 1.5, x))
        assertEquals(curve("chebyshev", 1.5, x), curve(8.0, 1.5, x))
        assertEquals(curve("chebyshev", 1.5, x), curve(-1.0, 1.5, x), "a negative number wraps with a positive modulo")
        assertEquals(scurve, curve("nonsense", 1.5, x), "an unknown name is the first curve")
        assertEquals(scurve, curve("Soft", 1.5, x), "names are case-sensitive")
    }

    @Test
    fun distortvolScalesTheOutputAndIsClamped() {
        val full = curve("scurve", 1.0, 0.5f)
        assertEquals(full * 0.5f, curve("scurve", 1.0, 0.5f, "distortvol" to 0.5), 1e-6f)
        assertEquals(full, curve("scurve", 1.0, 0.5f, "distortvol" to 3.0), 1e-7f)
        assertEquals(full * 0.001f, curve("scurve", 1.0, 0.5f, "distortvol" to 0.0), 1e-9f)
    }

    @Test
    fun aMovingDistortIsAppliedPerFrame() {
        val stage = DistortStage(SR).startedWith("distort" to 0.0, "distorttype" to "soft")
        stage.distort.linearRampToValueAtTime(4.0, 0.02, 0.0)
        stage.postgain.linearRampToValueAtTime(0.5, 0.02, 0.0)
        val x = FloatArray(1000) { 0.3f }
        val y = stage.pushMono(x)
        for (i in 0 until 1000) {
            val t = i.toDouble() / SR
            val expected = (stage.postgain.valueAt(t).toFloat().toDouble().coerceIn(0.001, 1.0) * Distortion.apply(1, 0.3f.toDouble(), expm1(stage.distort.valueAt(t).toFloat().toDouble()))).toFloat()
            assertEquals(expected, y[i], 1e-6f, "frame $i")
        }
        assertTrue(abs(y[999] - y[0]) > 0.01f, "the output moves with the parameter")
    }

    @Test
    fun distortIsBlockIndependentAndProcessesBothChannelsAlike() {
        val x = FloatArray(700) { (it % 50 - 25) / 25f }
        for (type in listOf("scurve", "diode", "chebyshev")) {
            val whole = DistortStage(SR).startedWith("distort" to 1.3, "distorttype" to type).pushMono(x)
            assertContentEquals(whole, DistortStage(SR).startedWith("distort" to 1.3, "distorttype" to type).pushMono(x, intArrayOf(97, 33)), type)
            val stereo = DistortStage(SR).startedWith("distort" to 1.3, "distorttype" to type).pushThrough(x, x)
            assertContentEquals(whole, stereo[0], type)
            assertContentEquals(whole, stereo[1], type)
        }
    }

    @Test
    fun theStageMatchesTheProcessorWithItsConstantParameters() {
        val x = FloatArray(512) { (it % 37 - 18) / 18f }
        val expected = x.copyOf()
        DistortProcessor(Distortion.algorithmIndex("fold")).process(expected, null, 0, 512, 0.8f.toDouble(), 0.6f.toDouble())
        assertContentEquals(expected, DistortStage(SR).startedWith("distort" to 0.8, "distorttype" to "fold", "distortvol" to 0.6).pushMono(x))
    }

    // ------------------------------------------------------------------------------------------------ transient

    @Test
    fun transientIsInTheChainOnlyWhenTransientIsSet() {
        assertFalse(TransientStage(SR).isInChain("transsustain" to 0.5))
        assertTrue(TransientStage(SR).isInChain("transient" to 0.0))
        assertTrue(TransientStage(SR).isInChain("transient" to -0.4, "transsustain" to 0.5))
    }

    @Test
    fun aNeutralTransientStillSoftClipsAndLosesTheFirstQuantum() {
        val stage = TransientStage(SR).startedWith("transient" to 0.0, timing = stageTiming(t = 0.0, endWithRelease = 10.0))
        val y = stage.pushMono(FloatArray(2048) { 0.5f })
        // the quantum of the onset has `currentTime <= begin`: the processor returns without writing, which is silence
        assertTrue((0 until 128).all { y[it] == 0f }, "the first quantum is silent")
        for (i in 256 until 2048) assertEquals(1f / 3f, y[i], 1e-3f, "0.5 / (1 + 0.5) at frame $i")
    }

    @Test
    fun aVoiceThatStartsInTheMiddleOfAQuantumLosesTheRestOfIt() {
        val t = 50.0 / SR
        val stage = TransientStage(SR).startedWith("transient" to 0.0, timing = stageTiming(t = t, endWithRelease = 10.0))
        val y = stage.pushMono(FloatArray(512) { 0.5f }, startFrame = 50)
        // frames 50..127 are the rest of quantum 0, whose currentTime (0) is not after begin
        for (i in 0 until 78) assertEquals(0f, y[i], "frame ${50 + i}")
        assertEquals(1f / 3f, y[78 + 10], 1e-3f)
    }

    @Test
    fun theTransientProcessorStopsForGoodAtItsEnd() {
        val end = 0.01 // frame 480: the quantum that starts at 384 is still inside, the one at 512 is not
        val stage = TransientStage(SR).startedWith("transient" to 0.0, timing = stageTiming(t = 0.0, endWithRelease = end))
        val y = stage.pushMono(FloatArray(1024) { 0.5f })
        assertEquals(1f / 3f, y[300], 1e-3f)
        assertTrue((512 until 1024).all { y[it] == 0f }, "silent from the first quantum at or after the end")
    }

    @Test
    fun theAttackAmountMovesTheLoudPartOfAnAttack() {
        // a sharp burst on top of a quieter bed: attack > 0 boosts the burst, attack < 0 softens it
        val x = FloatArray(4096) { if (it in 2000 until 2100) 0.8f else 0.1f }
        val up = TransientStage(SR).startedWith("transient" to 1.0).pushMono(x)
        val flat = TransientStage(SR).startedWith("transient" to 0.0).pushMono(x)
        val down = TransientStage(SR).startedWith("transient" to -1.0).pushMono(x)
        assertTrue(peak(up, 2000, 2200) > peak(flat, 2000, 2200) && peak(flat, 2000, 2200) > peak(down, 2000, 2200), "up ${peak(up, 2000, 2200)} flat ${peak(flat, 2000, 2200)} down ${peak(down, 2000, 2200)}")
    }

    @Test
    fun theTransientIsBlockIndependentForMonoAndWholeQuantaForStereo() {
        val x = FloatArray(2048) { if (it % 400 < 40) 0.7f else 0.05f }
        val whole = TransientStage(SR).startedWith("transient" to 0.6, "transsustain" to -0.5).pushMono(x)
        assertContentEquals(whole, TransientStage(SR).startedWith("transient" to 0.6, "transsustain" to -0.5).pushMono(x, intArrayOf(97, 33)))
        val stereo = TransientStage(SR).startedWith("transient" to 0.6, "transsustain" to -0.5).pushThrough(x, x, intArrayOf(128))
        assertEquals(2, stereo.size)
        // the average gain is shared by the channels (the worklet runs channel 0's whole quantum first), so identical channels differ a little
        for (i in 256 until 2048) assertEquals(stereo[0][i], stereo[1][i], 0.05f * maxOf(1f, abs(stereo[0][i])), "frame $i")
    }

    // ------------------------------------------------------------------------------------------------ parameters

    @Test
    fun theStagesPutTheirParametersWhereModulatorsLookThem() {
        val registry = NodeRegistry()
        val coarse = CoarseStage(SR).startedWith("coarse" to 3.0).also { it.registerParams(registry) }
        val crush = CrushStage(SR).startedWith("crush" to 4.0).also { it.registerParams(registry) }
        val shape = ShapeStage(SR).startedWith("shape" to 0.4, "shapevol" to 0.7).also { it.registerParams(registry) }
        val distort = DistortStage(SR).startedWith("distort" to 1.2, "distortvol" to 0.6).also { it.registerParams(registry) }
        assertSame(coarse.coarse, registry.find("coarse", "coarse"))
        assertSame(crush.crush, registry.find("crush", "crush"))
        assertSame(shape.shape, registry.find("shape", "shape"))
        assertSame(shape.postgain, registry.find("shape", "postgain"))
        assertSame(distort.distort, registry.find("distort", "distort"))
        assertSame(distort.postgain, registry.find("distort", "postgain"))
        assertEquals(3.0, coarse.coarse.constantValue)
        assertEquals(4.0, crush.crush.constantValue)
        assertEquals(0.4, shape.shape.constantValue)
        assertEquals(0.7, shape.postgain.constantValue)
        assertEquals(1.2, distort.distort.constantValue)
        assertEquals(0.6, distort.postgain.constantValue)
        assertNotNull(registry.find("coarse", "coarse"))
    }

    // ------------------------------------------------------------------------------------------------ hostile values

    @Test
    fun hostileParametersNeverLetNaNOrInfinityOut() {
        val noise = FloatArray(1024) { (((it * 7919) % 2001) - 1000) / 1000f }
        val hostile = listOf(
            CrushStage(SR).startedWith("crush" to 1e9),
            CrushStage(SR).startedWith("crush" to 2000.0),
            CrushStage(SR).startedWith("crush" to Double.MAX_VALUE),
            ShapeStage(SR).startedWith("shape" to -3.0),
            ShapeStage(SR).startedWith("shape" to -1.0, "shapevol" to 1e30),
            ShapeStage(SR).startedWith("shape" to 1e300),
            ShapeStage(SR).startedWith("shape" to Double.NEGATIVE_INFINITY),
            DistortStage(SR).startedWith("distort" to 1e9, "distorttype" to "soft"),
            DistortStage(SR).startedWith("distort" to 800.0, "distorttype" to "scurve"),
            DistortStage(SR).startedWith("distort" to 1e300, "distorttype" to "chebyshev"),
            DistortStage(SR).startedWith("distort" to -1e9, "distorttype" to "fold", "distortvol" to -5.0),
            DistortStage(SR).startedWith("distort" to 3.0, "distorttype" to 1e300),
            DistortStage(SR).startedWith("distort" to 3.0, "distorttype" to Double.POSITIVE_INFINITY),
            CoarseStage(SR).startedWith("coarse" to 1e300),
            CoarseStage(SR).startedWith("coarse" to Double.NEGATIVE_INFINITY),
            TransientStage(SR).startedWith("transient" to 1e300, "transsustain" to -1e300),
        )
        for ((i, stage) in hostile.withIndex()) {
            val mono = stage.pushMono(noise)
            assertTrue(allFinite(mono), "hostile stage #$i is not finite")
            val stereo = stage.pushThrough(noise, noise.reversedArray())
            assertTrue(stereo.all { allFinite(it) }, "hostile stage #$i is not finite in stereo")
        }
        // infinite input does not stay in the transient shaper
        val shaper = TransientStage(SR).startedWith("transient" to 0.5)
        val spiky = FloatArray(1024) { if (it % 100 == 0) Float.POSITIVE_INFINITY else if (it % 100 == 1) Float.NaN else 0.4f }
        assertTrue(allFinite(shaper.pushMono(spiky)))
    }
}
