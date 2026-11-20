package cosic.audio

import cosic.pattern.logger
import java.lang.management.ManagementFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `.lfo`, `.env` and `.bmod` connected to the parameters of a voice (modulators.mjs, superdough.mjs:1004-1049). */
class ModulatorsTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    private val messages = ArrayList<String>()
    private var savedLogger: ((String, String?, Any?) -> Unit)? = null

    @BeforeTest
    fun captureLog() {
        savedLogger = logger
        logger = { message, _, _ -> messages.add(message) }
    }

    @AfterTest
    fun restoreLog() {
        logger = savedLogger!!
    }

    // ------------------------------------------------------------------ a rig for the connection logic itself

    private fun spec(
        kind: ModKind, control: String, id: String = "0", fxi: String = "main", sub: String? = null,
        numbers: Map<String, Double> = emptyMap(), strings: Map<String, String> = emptyMap(),
    ) = ModSpec(kind, id, fxi, control, sub, numbers, strings)

    private fun lfo(control: String, vararg numbers: Pair<String, Double>, id: String = "0", fxi: String = "main", sub: String? = null) =
        spec(ModKind.LFO, control, id, fxi, sub, mapOf(*numbers))

    private fun env(control: String, vararg numbers: Pair<String, Double>, id: String = "0", fxi: String = "main", sub: String? = null) =
        spec(ModKind.ENV, control, id, fxi, sub, mapOf(*numbers))

    private fun bmod(control: String, vararg numbers: Pair<String, Double>, id: String = "0", fxi: String = "main", sub: String? = null) =
        spec(ModKind.BMOD, control, id, fxi, sub, mapOf(*numbers))

    /** One voice chain's registry with parameters registered by name, and what is needed to connect modulators to them and to render them. */
    private inner class Rig(val tap: BusTap = BusTap { null }) {
        val registry = NodeRegistry(sampleRate = SR)
        val bank = registry.modulators()
        val ctx = BlockContext(SR)

        fun param(node: String, name: String, base: Double, min: Double = -3.4e38, max: Double = 3.4e38): AParam =
            AParam(base, min, max).also { registry.register(node, name, it) }

        fun connect(mods: List<ModSpec>, t: Double = 0.0, end: Double = 10.0, cps: Double = 0.5, cycle: Double = 0.0) {
            bank.begin()
            connectModulators(mods, 0, { registry }, bank, t, end, cps, cycle, tap)
        }

        fun connect(vararg mods: ModSpec, t: Double = 0.0, end: Double = 10.0, cps: Double = 0.5, cycle: Double = 0.0) =
            connect(mods.toList(), t, end, cps, cycle)

        /** [frames] frames of the parameter, rendered in blocks of [block] frames from frame 0. */
        fun values(p: AParam, frames: Int, block: Int = 256): FloatArray {
            val out = FloatArray(frames)
            val buffer = FloatArray(block)
            var done = 0
            while (done < frames) {
                val n = Math.min(block, frames - done)
                ctx.startFrame = done.toLong()
                ctx.n = n
                p.fill(buffer, ctx)
                System.arraycopy(buffer, 0, out, done, n)
                done += n
            }
            return out
        }
    }

    /** The default LFO (triangle, `dcoffset` -0.5) from frame 128 on, which is its first quantum after begin 0: `(wave - 0.5) * depth` clamped. */
    private fun triangleLfo(frames: Int, hz: Double, depth: Double, min: Double, max: Double, phase0: Double = 0.0): DoubleArray {
        var phase = phase0
        val out = DoubleArray(frames)
        for (i in 128 until frames) {
            out[i] = Math.min(Math.max((lfoWave(0, phase, 0.5) - 0.5) * depth, min), max)
            phase += hz / SR
            if (phase > 1.0) phase -= 1.0
        }
        return out
    }

    private fun assertSignal(expected: DoubleArray, actual: FloatArray, base: Double, tolerance: Double, what: String) {
        var worst = 0.0
        for (i in expected.indices) worst = Math.max(worst, Math.abs(base + expected[i] - actual[i]))
        assertTrue(worst <= tolerance, "$what: worst difference $worst")
    }

    // ------------------------------------------------------------------ the table

    @Test
    fun theControlTargetTableHasTheEntriesOfSuperdoughdata() {
        assertEquals(118, ControlTargets.size)
        val cutoff = assertNotNull(ControlTargets.lookup("cutoff", null))
        assertEquals("lpf", cutoff.node)
        assertEquals("frequency", cutoff.param)
        assertEquals("post", ControlTargets.lookup("postgain", null)!!.node)
        assertEquals("djf", ControlTargets.lookup("djf", null)!!.node)
    }

    @Test
    fun aSubControlPicksTheMoreSpecificEntryAndOtherwiseTheControlItself() {
        assertEquals("depth", ControlTargets.lookup("lfo_0", "depth")!!.param, "lfo_depth")
        assertEquals("frequency", ControlTargets.lookup("lfo_0", null)!!.param, "lfo")
        assertEquals("frequency", ControlTargets.lookup("lfo_0", "nosuchsub")!!.param, "no lfo_nosuchsub: the control's own entry")
        assertEquals("attack", ControlTargets.lookup("env_a", "attack")!!.param)
        assertEquals("env", ControlTargets.lookup("env_a", "attack")!!.node)
        assertEquals("gain", ControlTargets.lookup("gain", "postgain")!!.node, "gain_postgain is no entry: the one of gain is used")
        assertNull(ControlTargets.lookup("wobble", null))
        assertNull(ControlTargets.lookup("wobble_depth", "depth"))
    }

    @Test
    fun aModSpecKnowsItsTargetFromTheStart() {
        assertEquals("lpf", lfo("cutoff").target!!.node)
        assertNull(lfo("wobble").target)
    }

    // ------------------------------------------------------------------ the LFO

    @Test
    fun anLfoOnTheCutoffSwingsAHalfDepthEitherSideOfIt() {
        val rig = Rig()
        val frequency = rig.param("lpf", "frequency", 1000.0)
        rig.connect(lfo("cutoff"))
        val values = rig.values(frequency, SR)
        assertEquals(1000f, values[0], "it is silent until the quantum after begin")
        assertEquals(1000f, values[127])
        assertSignal(triangleLfo(SR, 1.0, 1000.0, -980.0, 23000.0), values, 1000.0, 1e-3, "depth 1 * 1000, 1 Hz")
        assertEquals(1500.0, values.max().toDouble(), 1.0)
        assertEquals(500.0, values.min().toDouble(), 1.0)
    }

    @Test
    fun theOutputOfALfoOnAFrequencyIsClampedToTwentyHertzAndTwentyFourKilohertz() {
        val rig = Rig()
        val frequency = rig.param("lpf", "frequency", 1000.0)
        rig.connect(lfo("cutoff", "depthabs" to 1e9, "dcoffset" to 0.0))
        val values = rig.values(frequency, SR)
        assertEquals(24000.0, values.max().toDouble(), 0.5, "base + (24000 - base)")
        assertEquals(1000.0, values[127].toDouble(), 1e-3, "still silent")
        assertEquals(24000.0, values[300].toDouble(), 0.5, "a depth that large reaches the limit at once")
        rig.connect(lfo("cutoff", "depthabs" to 1e9, "dcoffset" to -1e9))
        assertEquals(20.0, rig.values(frequency, SR).min().toDouble(), 0.5, "base + (20 - base)")
    }

    @Test
    fun aLowFrequencyIsNotClampedAndAnythingButAFrequencyHasNoRange() {
        val rig = Rig()
        val frequency = rig.param("tremolo", "frequency", 20.0) // below 30: probably an LFO rate
        val gain = rig.param("gain", "gain", 0.5)
        rig.connect(lfo("tremolo", "depthabs" to 1000.0, "dcoffset" to 0.0, "rate" to 0.5), lfo("gain", "depthabs" to 1e6, "dcoffset" to 0.0, "rate" to 0.5, id = "1"))
        assertEquals(1020f, rig.values(frequency, SR * 2).max(), "20 + up to 1000: no 20..24000 clamp")
        assertTrue(rig.values(gain, SR * 2).max() > 5e5f, "a gain has no range either")
    }

    @Test
    fun theDepthIsRelativeToTheValueOfTheTargetUnlessDepthabsIsGiven() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 0.5)
        rig.connect(lfo("gain", "depth" to 2.0))
        val relative = rig.values(gain, SR)
        assertEquals(1.0, relative.max().toDouble(), 2e-3, "0.5 + 0.5 * (2 * 0.5)")
        assertEquals(0.0, relative.min().toDouble(), 2e-3)
        rig.connect(lfo("gain", "depthabs" to 3.0, "depth" to 100.0))
        val absolute = rig.values(gain, SR)
        assertEquals(2.0, absolute.max().toDouble(), 2e-3, "0.5 + 1.5: depthabs wins over depth")
        assertEquals(-1.0, absolute.min().toDouble(), 2e-3)
    }

    @Test
    fun aTargetOfZeroCountsAsOneForTheDepth() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 0.0)
        rig.connect(lfo("gain"))
        val values = rig.values(gain, SR)
        assertEquals(0.5, values.max().toDouble(), 2e-3, "depth 1 * (0 counted as 1), output -0.5..0.5")
    }

    @Test
    fun syncTurnsCyclesIntoHertzAndRetrigStartsThePhaseAtZero() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        // sync 2 at cps 0.5: 1 Hz
        rig.connect(lfo("gain", "sync" to 2.0, "rate" to 7.0))
        assertSignal(triangleLfo(SR, 1.0, 1.0, -0.5, 0.5), rig.values(gain, SR), 1.0, 1e-3, "sync wins over rate")
        // 3 Hz from the rate
        rig.connect(lfo("gain", "rate" to 3.0))
        assertSignal(triangleLfo(SR, 3.0, 1.0, -0.5, 0.5), rig.values(gain, SR), 1.0, 1e-3, "rate")
        // the phase is locked to the cycle: cycle 0.25 at cps 0.5 is 0.5 s, half a period of 1 Hz
        rig.connect(listOf(lfo("gain")), cycle = 0.25)
        assertSignal(triangleLfo(SR, 1.0, 1.0, -0.5, 0.5, phase0 = 0.5), rig.values(gain, SR), 1.0, 1e-3, "cycle 0.25")
        // retrig 1 starts at the beginning whatever the cycle
        rig.connect(listOf(lfo("gain", "retrig" to 1.0)), cycle = 0.25)
        assertSignal(triangleLfo(SR, 1.0, 1.0, -0.5, 0.5), rig.values(gain, SR), 1.0, 1e-3, "retrig")
    }

    @Test
    fun theShapeIsANumberOrAName() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(spec(ModKind.LFO, "gain", strings = mapOf("shape" to "square"), numbers = mapOf("dcoffset" to 0.0, "depthabs" to 1.0)))
        val square = rig.values(gain, SR)
        assertEquals(2.0f, square[200], "the square starts high")
        assertEquals(1.0f, square[(0.75 * SR).toInt()], "and is low in the second half")
        rig.connect(lfo("gain", "shape" to 2.0, "dcoffset" to 0.0, "depthabs" to 1.0))
        val ramp = rig.values(gain, SR)
        assertEquals(1.0 + (SR / 2 - 128).toDouble() / SR, ramp[SR / 2].toDouble(), 1e-3, "ramp: 1 + phase")
    }

    @Test
    fun theLfoCanBeGivenAPhaseOffsetSkewAndCurve() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(lfo("gain", "phaseoffset" to 0.5, "dcoffset" to 0.0, "depthabs" to 1.0, "shape" to 2.0))
        assertEquals(1.5f, rig.values(gain, 400)[130], 1e-2f, "a ramp that starts half way")
        rig.connect(lfo("gain", "curve" to 2.0, "dcoffset" to 0.0, "depthabs" to 1.0, "shape" to 2.0))
        assertEquals(1.25f, rig.values(gain, SR)[SR / 2], 1e-2f, "ramp at 0.5 squared")
    }

    @Test
    fun theLfoOnlySoundsBetweenBeginAndEnd() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(listOf(lfo("gain", "dcoffset" to 1.0, "depthabs" to 1.0)), t = 0.1, end = 0.2)
        val values = rig.values(gain, SR / 2)
        assertEquals(1f, values[(0.1 * SR).toInt()], "silent until the first quantum after t")
        assertTrue(values[(0.15 * SR).toInt()] > 1.4f, "between begin and end the LFO runs: 1 + (wave + 1) is at least 2")
        assertEquals(1f, values[(0.3 * SR).toInt()], "gone from the quantum that starts at or after the end")
    }

    @Test
    fun parametersAreHeldAsFloat32LikeAnAudioParam() {
        val rig = Rig()
        val frequency = rig.param("lpf", "frequency", 440.123456789)
        rig.connect(lfo("cutoff", "depth" to 0.5))
        rig.values(frequency, 512)
        val lfoFrequency = assertNotNull(rig.registry.find("lfo_0", "frequency"))
        assertEquals(1.0, lfoFrequency.base)
        val depth = assertNotNull(rig.registry.find("lfo_0", "depth"))
        assertEquals((0.5 * 440.123456789f.toDouble()).toFloat().toDouble(), depth.base, 0.0, "depth = 0.5 * the float32 the target reads as")
    }

    // ------------------------------------------------------------------ the envelope

    @Test
    fun anEnvelopeWithAnAttackOfOneSecondAddsZeroToTheValueOverASecond() {
        val rig = Rig()
        val frequency = rig.param("lpf", "frequency", 500.0)
        rig.connect(env("cutoff", "attack" to 1.0))
        val values = rig.values(frequency, SR * 2)
        assertEquals(500f, values[0])
        // block-stepped: the value is that of the start of the quantum
        val q = 188 * 128 // 0.5013 s
        assertEquals(500.0 + 500.0 * (q.toDouble() / SR), values[q].toDouble(), 0.01)
        assertEquals(values[q], values[q + 127], "constant within a quantum")
        assertEquals(1000.0, values[SR].toDouble(), 0.01, "the full depth at the end of the attack")
        assertTrue(values[(1.5 * SR).toInt()] < 700f, "then it decays towards the sustain level 0")
    }

    @Test
    fun anEnvelopeOnAFrequencyIsClampedLikeAnLfo() {
        val rig = Rig()
        val frequency = rig.param("lpf", "frequency", 1000.0)
        rig.connect(env("cutoff", "attack" to 0.01, "depthabs" to 1e9))
        assertEquals(24000.0, rig.values(frequency, SR).max().toDouble(), 0.5)
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(env("gain", "attack" to 0.01, "depthabs" to 1e9))
        assertEquals(1e9, rig.values(gain, SR).max().toDouble(), 1e4, "a gain: no range (the envelope's own limit is 1e9)")
    }

    @Test
    fun theEnvelopeCurvesAndDepthAreTakenFromTheOptions() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(env("gain", "attack" to 1.0, "acurve" to 1.0, "depthabs" to 2.0))
        val snappy = rig.values(gain, SR)[SR / 2].toDouble() - 1.0
        // _warp(0.5, 1) = 1 - (1 - 0.5)^9, times the depth, at the quantum that starts at 0.49867 s
        val phase = (187 * 128).toDouble() / SR
        assertEquals(2.0 * (1.0 - Math.pow(1.0 - phase, 9.0)), snappy, 1e-3)
    }

    // ------------------------------------------------------------------ modulating modulators

    @Test
    fun anLfoRegistersItselfAndAnotherModulatorCanTargetItsParameters() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 0.5)
        rig.connect(
            lfo("gain", id = "0"),
            lfo("lfo_0", "depthabs" to 0.1, id = "1"),
            lfo("lfo_0", id = "2", sub = "depth"),
            lfo("lfo_0", id = "3", sub = "skew"),
            lfo("lfo_0", id = "4", sub = "curve"),
            lfo("lfo_0", id = "5", sub = "dcoffset"),
        )
        for (name in listOf("frequency", "depth", "skew", "curve", "dcoffset")) {
            val p = assertNotNull(rig.registry.find("lfo_0", name), "lfo_0.$name")
            assertTrue(p.hasInputs, "lfo_0.$name is modulated")
        }
        assertTrue(gain.hasInputs)
        assertFalse(assertNotNull(rig.registry.find("lfo_1", "frequency")).hasInputs, "nothing targets lfo_1")
        assertEquals(1.0, rig.registry.find("lfo_0", "frequency")!!.base, "the LFO's own rate is what its parameter starts at")
    }

    @Test
    fun anEnvelopeParameterCanBeTheTargetOfAnLfo() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 0.5)
        rig.connect(env("gain", "attack" to 0.5, id = "e"), lfo("env_e", id = "0", sub = "attack"))
        assertTrue(assertNotNull(rig.registry.find("env_e", "attack")).hasInputs)
        assertFalse(assertNotNull(rig.registry.find("env_e", "release")).hasInputs)
        assertTrue(gain.hasInputs)
        assertEquals(0.5, rig.registry.find("env_e", "attack")!!.base)
    }

    @Test
    fun aModulatorWhoseTargetIsAnotherModulatorMovesIt() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        // lfo_0 is a 1 Hz ramp on the gain; lfo_1 adds a constant 2 to the frequency of lfo_0 (a square with skew 1 stays high: (1 + 0) * depthabs 2)
        rig.connect(
            lfo("gain", "dcoffset" to 0.0, "depthabs" to 1.0, "shape" to 2.0),
            lfo("lfo_0", "dcoffset" to 0.0, "depthabs" to 2.0, "shape" to 4.0, "skew" to 1.0, id = "1"),
        )
        val values = rig.values(gain, SR)
        for (j in listOf(4000, 10_000)) {
            assertEquals(1.0 + (j * 3.0 / SR) % 1.0, values[128 + j].toDouble(), 2e-3, "1 Hz + 2 Hz from the first quantum after begin")
        }
    }

    // ------------------------------------------------------------------ what cannot be connected

    @Test
    fun aControlThatCannotBeModulatedIsReportedAndSkipped() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(lfo("wobble"), lfo("gain", id = "1"))
        assertContains(messages, "[superdough] error: Could not find control data for target 'wobble'. It may not be modulatable.")
        assertTrue(gain.hasInputs, "the next modulator is connected as usual")
    }

    @Test
    fun aTargetTheChainDoesNotHaveIsReportedWithTheAvailableNodes() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(lfo("cutoff"))
        val message = messages.single()
        assertTrue(message.startsWith("[superdough] error: Could not connect to target 'cutoff' — it does not exist. Available targets: gain"), message)
        assertTrue(gain.isConstant, "nothing was connected")
    }

    @Test
    fun aNodeWithoutTheParameterIsReported() {
        val rig = Rig()
        rig.param("lpf", "Q", 1.0)
        rig.connect(lfo("cutoff"))
        assertEquals("[superdough] error: Could not connect to target 'lpf': it has no parameter 'frequency'", messages.single())
    }

    @Test
    fun aNinthModulatorIsIgnored() {
        val rig = Rig()
        val controls = listOf("gain", "postgain", "pan", "dry", "delay", "room", "busgain", "djf", "cutoff")
        val nodes = mapOf(
            "gain" to ("gain" to "gain"), "postgain" to ("post" to "gain"), "pan" to ("pan" to "pan"), "dry" to ("dry" to "gain"),
            "delay" to ("delay_mix" to "gain"), "room" to ("room_mix" to "gain"), "busgain" to ("bus" to "gain"), "djf" to ("djf" to "value"),
            "cutoff" to ("lpf" to "frequency"),
        )
        val params = controls.map { c -> rig.param(nodes.getValue(c).first, nodes.getValue(c).second, 1.0) }
        rig.connect(controls.mapIndexed { i, c -> lfo(c, id = i.toString()) })
        assertTrue(params.take(8).all { it.hasInputs }, "the first eight are connected")
        assertFalse(params[8].hasInputs, "the ninth is not")
        assertTrue(messages.any { it.contains("8 modulators; 1 ignored") }, messages.toString())
    }

    // ------------------------------------------------------------------ several chains

    @Test
    fun aModulatorFollowsTheChainItsFxiNames() {
        val mainRegistry = NodeRegistry(sampleRate = SR)
        val fxRegistry = NodeRegistry(sampleRate = SR)
        val mainGain = AParam(1.0, -3.4e38, 3.4e38).also { mainRegistry.register("gain", "gain", it) }
        val fxGain = AParam(1.0, -3.4e38, 3.4e38).also { fxRegistry.register("gain", "gain", it) }
        val bank = mainRegistry.modulators()
        val registries = mapOf("main" to mainRegistry, "0" to fxRegistry)
        connectModulators(listOf(lfo("gain", fxi = "0"), lfo("gain", id = "1")), 1, { registries.getValue(it) }, bank, 0.0, 10.0, 0.5, 0.0, BusTap { null })
        assertTrue(fxGain.hasInputs, "fxi 0 is the FX element's own chain")
        assertTrue(mainGain.hasInputs, "no fxi: the chain it was declared in")
        assertNotNull(mainRegistry.find("lfo_0", "frequency"), "modulators live in the main chain wherever they are declared")
        assertNull(fxRegistry.find("lfo_0", "frequency"))
    }

    @Test
    fun aChainTheVoiceDoesNotHaveIsReportedAndSkipped() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(lfo("gain", fxi = "3"), lfo("gain", fxi = "nonsense", id = "1"))
        assertFalse(gain.hasInputs)
        assertEquals(2, messages.size)
        assertTrue(messages[0].contains("no effect chain '3'"), messages[0])
    }

    @Test
    fun aLaterModulatorWithTheSameIdReplacesTheNode() {
        val rig = Rig()
        rig.param("gain", "gain", 1.0)
        rig.connect(lfo("gain", "rate" to 2.0), lfo("gain", "rate" to 5.0))
        assertEquals(5.0, rig.registry.find("lfo_0", "frequency")!!.base, "the second lfo_0 overwrote nodes.main.lfo_0")
        assertEquals(1 + 5, rig.registry.size, "the gain and the five parameters of one lfo_0 node")
    }

    // ------------------------------------------------------------------ bus modulators

    private fun constantBus(value: Float, frames: Int = BLOCK_FRAMES): BusTap {
        val buffer = StereoBuffer(frames)
        buffer.left.fill(value)
        buffer.right.fill(value)
        return BusTap { bus -> if (bus == 1) buffer else null }
    }

    @Test
    fun aBusModulatorAddsTheBusSignalScaledByTheDepthOverOneThird() {
        val rig = Rig(constantBus(0.3f))
        val gain = rig.param("gain", "gain", 0.5)
        rig.connect(bmod("gain", "bus" to 1.0))
        val values = rig.values(gain, 1024)
        // depth 1 * 0.5; (0.3 + 0) * 0.5 / 0.3 = 0.5
        assertEquals(1.0, values[0].toDouble(), 1e-5)
        assertEquals(1.0, values[1000].toDouble(), 1e-5)
        rig.connect(bmod("gain", "bus" to 1.0, "dc" to 0.1, "depth" to -2.0))
        // (0.3 + 0.1) * -2 * 0.5 / 0.3
        assertEquals(0.5 + 0.4 * -1.0 / 0.3, rig.values(gain, 256)[10].toDouble(), 1e-5)
        rig.connect(bmod("gain", "bus" to 1.0, "depthabs" to 0.3))
        assertEquals(0.8, rig.values(gain, 256)[10].toDouble(), 1e-5, "0.5 + 0.3 * 0.3 / 0.3")
    }

    @Test
    fun aBusModulatorIsConnectedAtBeginAndWithoutABusItAddsOnlyTheOffset() {
        val rig = Rig(constantBus(0.3f))
        val gain = rig.param("gain", "gain", 0.5)
        rig.connect(listOf(bmod("gain", "bus" to 1.0)), t = 0.01)
        val values = rig.values(gain, 1024)
        assertEquals(0.5f, values[479], "before begin (frame 480)")
        assertEquals(1.0, values[480].toDouble(), 1e-5)
        // a bus nobody sent to, and no bus at all: only the dc offset
        rig.connect(bmod("gain", "bus" to 7.0, "dc" to 0.3))
        assertEquals(1.0, rig.values(gain, 256)[10].toDouble(), 1e-5, "0.5 + 0.3 * 0.5 / 0.3")
        rig.connect(bmod("gain"))
        assertEquals(0.5, rig.values(gain, 256)[10].toDouble(), 1e-6, "no bus, no offset: nothing")
    }

    @Test
    fun aBusModulatorOnAFrequencyIsNotClampedBecauseSuperdoughsWaveShaperIsNeverConnected() {
        val rig = Rig(constantBus(0.3f))
        val frequency = rig.param("lpf", "frequency", 1000.0)
        // depth 1 * 1000 Hz: 0.3 * 1000 / 0.3
        rig.connect(bmod("cutoff", "bus" to 1.0))
        assertEquals(2000.0, rig.values(frequency, 256)[10].toDouble(), 0.01)
        // far beyond 24000 Hz, and below 20 Hz: superdough's clamp would stop it at 24000 and at 20 (modulators.mjs:49-60), the real signal does not
        rig.connect(bmod("cutoff", "bus" to 1.0, "depthabs" to 1e7))
        assertEquals(1e7 + 1000.0, rig.values(frequency, 256)[10].toDouble(), 4.0)
        rig.connect(bmod("cutoff", "bus" to 1.0, "depthabs" to -1e7))
        assertEquals(1000.0 - 1e7, rig.values(frequency, 256)[10].toDouble(), 4.0)
    }

    @Test
    fun theBusIsReadAgainForEveryBlock() {
        var calls = 0
        val tap = BusTap { bus -> calls++.let { StereoBuffer(BLOCK_FRAMES).also { b -> b.left.fill(0.3f * (bus + 1)); b.right.fill(0.3f * (bus + 1)) } } }
        val rig = Rig(tap)
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(bmod("gain", "bus" to 1.0))
        rig.values(gain, 1024)
        assertEquals(4, calls, "once per 256-frame block, however often the parameter is read")
    }

    // ------------------------------------------------------------------ the end of a voice

    @Test
    fun releasingTheChainDisconnectsTheModulatorsFromTheirTargets() {
        val rig = Rig()
        val orbitParam = rig.param("delay", "delayTime", 0.25)
        rig.connect(lfo("delaytime", "depthabs" to 0.1))
        assertTrue(orbitParam.hasInputs)
        rig.registry.release()
        assertFalse(orbitParam.hasInputs, "a parameter of the orbit must not keep the modulators of voices that are over")
        // and the modulators are free for the next voice
        rig.connect(lfo("delaytime", "depthabs" to 0.1))
        assertTrue(orbitParam.hasInputs)
        assertSame(rig.registry.modulators(), rig.bank)
    }

    @Test
    fun aRestartedAParamForgetsItsInputsWithoutHelp() {
        val rig = Rig()
        val gain = rig.param("gain", "gain", 1.0)
        rig.connect(lfo("gain"))
        assertTrue(gain.hasInputs)
        gain.reset(1.0)
        assertFalse(gain.hasInputs)
    }

    @Test
    fun theRegistryFindsModulatorNodesByNameWithoutBuildingStrings() {
        val registry = NodeRegistry(sampleRate = SR)
        val p = AParam(0.0, -1.0, 1.0)
        registry.registerModulator("lfo", "lpf_mod", "frequency", p)
        assertSame(p, registry.find("lfo_lpf_mod", "frequency"))
        assertNull(registry.find("lfo_lpf_mo", "frequency"))
        assertNull(registry.find("lfo_lpf_modx", "frequency"))
        assertNull(registry.find("lfo", "frequency"), "a modulator node is never found by its kind alone")
        assertTrue(registry.hasNode("lfo_lpf_mod"))
        registry.removeModulator("lfo", "lpf_mod")
        assertFalse(registry.hasNode("lfo_lpf_mod"))
        assertEquals(0, registry.size)
    }

    // ------------------------------------------------------------------ in a voice

    private fun modulators(vararg entries: Pair<String, Map<String, Any?>>): Map<String, Any?> {
        val table = LinkedHashMap<String, Any?>()
        table["__ids"] = LinkedHashSet<Any?>(entries.map { it.first })
        for ((id, options) in entries) table[id] = options
        return table
    }

    @Test
    fun anLfoOnTheGainOfAVoiceModulatesItsLevel() {
        val lfo = modulators("0" to mapOf("control" to "gain", "rate" to 2.0, "depthabs" to 0.25, "dcoffset" to 0.0, "shape" to "ramp"))
        val left = renderLeft(vp(dcSource(), 1.0, "gain" to 0.5, "lfo" to lfo), SR)
        // gain = 0.5 + 0.25 * phase of a 2 Hz ramp that starts at frame 128; the DC source times the amplitude envelope (1 by 50 ms)
        for (frame in listOf(2400, 9000, 24000, 30000)) {
            val phase = (((frame - 128).toDouble() * 2.0 / SR) % 1.0)
            assertEquals(0.5 + 0.25 * phase, left[frame].toDouble(), 2e-3, "frame $frame")
        }
    }

    @Test
    fun anEnvelopeOnTheGainOfAVoiceModulatesItsLevel() {
        val env = modulators("0" to mapOf("control" to "gain", "attack" to 0.2, "depthabs" to 0.4))
        val left = renderLeft(vp(dcSource(), 1.0, "gain" to 0.5, "env" to env), SR)
        val quantum = (0.1 * SR).toInt() / 128 * 128 // the value is that of the start of the quantum
        assertEquals(0.5 + 0.4 * (quantum.toDouble() / SR / 0.2), left[quantum].toDouble(), 2e-3, "half way up the attack")
        assertEquals(0.9, left[(0.2 * SR).toInt()].toDouble(), 2e-3, "the whole depth at the end of the attack")
    }

    @Test
    fun aModulatorDeclaredInAnFxElementMovesThatElementsChain() {
        val lfo = modulators("0" to mapOf("control" to "gain", "depthabs" to 0.1, "dcoffset" to 1.0, "shape" to "ramp"))
        // the FX element's gain (0.5) is modulated by an LFO that adds 0.1 * (1 + phase) ... the main gain 1 is not
        val plain = renderLeft(vp(dcSource(), 1.0, "gain" to 1.0, "FX" to listOf(mapOf("gain" to 0.5))), SR / 4)
        val modulated = renderLeft(vp(dcSource(), 1.0, "gain" to 1.0, "FX" to listOf(mapOf("gain" to 0.5, "lfo" to lfo))), SR / 4)
        assertEquals(0.5, plain[6000].toDouble(), 1e-4)
        val phase = (6000 - 128).toDouble() / SR // rate 1 Hz
        assertEquals((0.5 + 0.1 * (1.0 + phase)) * 1.0, modulated[6000].toDouble(), 2e-3)
    }

    @Test
    fun theModulatorsOfAVoiceThatEndedDoNotLeakIntoTheNextVoiceOfTheSlot() {
        val mixer = Mixer(SR)
        val lfo = modulators("0" to mapOf("control" to "gain", "depthabs" to 0.5, "dcoffset" to 1.0))
        mixer.startVoice(vp(dcSource(), 0.05, "gain" to 0.5, "clip" to 1.0, "lfo" to lfo, "pan" to 0.0))
        mixer.renderFrames(SR / 2) // it is over
        mixer.startVoice(vp(dcSource(), 1.0, "gain" to 0.5))
        val left = channel(mixer.renderFrames(SR / 4), 0)
        assertEquals(0.5, left[6000].toDouble(), 1e-4, "the second voice has no modulator and plays at its own gain")
    }

    @Test
    fun voicesWithModulatorsDoNotAllocateOnceTheirSlotsHaveMadeTheirNodes() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)
        val lfo = modulators("0" to mapOf("control" to "gain", "rate" to 3.0, "depthabs" to 0.2))
        val env = modulators("0" to mapOf("control" to "gain", "attack" to 0.05, "depthabs" to 0.1), "1" to mapOf("control" to "lfo_0", "attack" to 0.1))
        val bus = modulators("0" to mapOf("control" to "pan", "bus" to 2.0, "depthabs" to 0.5))
        val voices = arrayOf(
            vp(dcSource(), 0.3, "gain" to 0.5, "lfo" to lfo, "pan" to 0.4),
            vp(dcSource(), 0.3, "gain" to 0.5, "env" to env, "lfo" to lfo),
            vp(dcSource(), 0.3, "gain" to 0.5, "bmod" to bus, "pan" to 0.5),
            vp(dcSource(), 0.3, "gain" to 0.5, "FX" to listOf(mapOf("gain" to 0.5, "lfo" to lfo)), "lfo" to lfo),
        )
        fun workload() {
            for (round in 0 until 6) for (v in voices) mixer.startVoice(v)
            for (block in 0 until 150) mixer.render(out, 256)
            mixer.stopAll(5)
            mixer.render(out, 256)
        }
        repeat(5) { workload() } // every slot that will be used makes its nodes, and the JIT has seen every path
        val before = threads.currentThreadAllocatedBytes
        workload()
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "starting, rendering and ending voices with modulators allocated $allocated bytes")
    }
}
