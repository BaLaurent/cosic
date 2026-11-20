package cosic.audio.sources

import cosic.pattern.Undefined
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The FM graph of one voice (docs/parity/spec/superdough-sources.md section 2.12, Task C). The Chromium goldens of [FmGoldenTest] check the whole
 * thing against the real `applyFM`; these are the cases of the spec and the properties the goldens do not state: what is created, what is dropped, that
 * blocks do not matter, and that the audio thread does not allocate.
 */
class FmBankTest {
    private val sr = 48000

    private fun spec(vararg controls: Pair<String, Any?>, sampleRate: Int = sr, duration: Double = 1.0, seed: Long = 7L): SourceSpec =
        SourceSpec(sampleRate, mapOf(*controls).asSourceControls(), duration, 1.0, seed)

    private fun bank(carrierHz: Double, vararg controls: Pair<String, Any?>, duration: Double = 1.0, holdSec: Double = duration, sampleRate: Int = sr): FmBank? =
        FmBank.create(spec(*controls, sampleRate = sampleRate, duration = duration), carrierHz, holdSec)

    /** What [FmBank.add] adds to the frequency of the carrier over [frames] frames, asked for in blocks of [block] frames. */
    private fun signal(bank: FmBank, frames: Int, block: Int = 256): DoubleArray {
        val out = DoubleArray(frames)
        val bus = PitchBus(block)
        var frame = 0
        while (frame < frames) {
            val n = minOf(block, frames - frame)
            bus.clear(n)
            bank.add(bus, n)
            assertTrue(bus.frequencyActive)
            for (i in 0 until n) out[frame + i] = bus.frequencyAddHz[i]
            frame += n
        }
        return out
    }

    private fun assertClose(label: String, expected: DoubleArray, actual: DoubleArray, atol: Double, frames: Int = expected.size) {
        var worst = 0.0
        for (i in 0 until frames) {
            val error = abs(expected[i] - actual[i])
            if (error > atol || actual[i].isNaN()) fail("$label: frame $i is ${actual[i]}, expected ${expected[i]} (error $error > $atol)")
            worst = maxOf(worst, error)
        }
    }

    /** An ideal sine operator, to check the graphs against: the phase advances by `frequency / sampleRate` per frame, starting at 0. */
    private class Sine {
        private var phase = 0.0

        fun next(frequency: Double): Double {
            val v = sin(2.0 * PI * phase)
            phase += frequency / 48000.0
            phase -= floor(phase)
            return v
        }
    }

    // ---- the table of the spec -------------------------------------------------------------------------------------------------------------------

    @Test
    fun aSineOperatorAtTheCarrierFrequencyAddsIndexTimesFrequencyTimesSine() {
        val add = signal(bank(100.0, "fmi" to 2.0)!!, 4800)
        assertEquals(0.0, add[0], 1e-9)
        // 100 Hz at 48 kHz: 480 frames a period, a quarter of it is 120
        assertEquals(200.0, add[120], 0.05)
        assertEquals(0.0, add[240], 0.05)
        assertEquals(-200.0, add[360], 0.05)
        for (i in add.indices) assertEquals(200.0 * sin(2 * PI * 100.0 * i / sr), add[i], 0.01, "frame $i")
    }

    @Test
    fun fmhScalesTheOperatorFrequencyAndSoTheDeviation() {
        val add = signal(bank(100.0, "fmi" to 4.0, "fmh" to 2.0)!!, 2400)
        // the operator runs at 200 Hz (240 frames a period, a quarter is 60) and the deviation is 4 * 200
        assertEquals(800.0, add[60], 0.1)
        assertEquals(-800.0, add[180], 0.1)
        for (i in add.indices) assertEquals(800.0 * sin(2 * PI * 200.0 * i / sr), add[i], 0.02, "frame $i")
    }

    @Test
    fun aChainModulatesTheOperatorsFrequencyFrameByFrame() {
        // fmi=1, fmi2=1: operator 2 (100 Hz) modulates operator 1, which modulates the carrier; operator 2's frame i is operator 1's frequency at frame i
        val add = signal(bank(100.0, "fmi" to 1.0, "fmi2" to 1.0)!!, 2400)
        val op1 = Sine()
        val op2 = Sine()
        val expected = DoubleArray(2400) {
            val out2 = op2.next(100.0)
            val out1 = op1.next(100.0 + 1.0 * 100.0 * out2)
            1.0 * 100.0 * out1
        }
        assertClose("chain", expected, add, 5e-3)
    }

    @Test
    fun theMatrixNamesConnectOperatorsAsTheyAreCalled() {
        // fmi12: operator 1 into operator 2, fmi20: operator 2 into the carrier
        val add = signal(bank(100.0, "fmi12" to 1.0, "fmi20" to 2.0)!!, 2400)
        val op1 = Sine()
        val op2 = Sine()
        val expected = DoubleArray(2400) {
            val out1 = op1.next(100.0)
            val out2 = op2.next(100.0 + 1.0 * 100.0 * out1)
            2.0 * 100.0 * out2
        }
        assertClose("matrix", expected, add, 5e-3)
    }

    @Test
    fun theOperatorsOfSeveralConnectionsSumIntoTheirTarget() {
        // operators 1 and 2 (the latter twice as fast) both modulate the carrier: fmi, fmi20 and fmh2
        val add = signal(bank(100.0, "fmi" to 1.5, "fmi20" to 0.5, "fmh2" to 2.0)!!, 2400)
        val op1 = Sine()
        val op2 = Sine()
        val expected = DoubleArray(2400) { 1.5 * 100.0 * op1.next(100.0) + 0.5 * 200.0 * op2.next(200.0) }
        assertClose("fan in", expected, add, 5e-3)
    }

    @Test
    fun anExponentialEnvelopeRisesFromOneThousandthAndMultipliesTheDeviation() {
        // fmattack 0.1: the gain goes from 0.001 to 1 exponentially: 0.001 * 1000^(t / 0.1), 0.0316228 at 0.05 s
        assertEquals(0.0316228, 0.001 * 1000.0.pow(0.5), 1e-7)
        val add = signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.1)!!, 6000)
        for (i in add.indices) {
            val t = i.toDouble() / sr
            val gain = if (t < 0.1) 0.001 * 1000.0.pow(t / 0.1) else 1.0
            assertEquals(4.0 * 100.0 * gain * sin(2 * PI * 100.0 * t), add[i], 0.02, "frame $i")
        }
        // a quarter of a period after 0.05 s the sine peaks: the amplitude is gain * 4 * modHz
        assertEquals(4.0 * 100.0 * 0.001 * 1000.0.pow(0.525), add[2520], 0.02)
    }

    @Test
    fun aLinearEnvelopeRisesFromZero() {
        val add = signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.1, "fmenv" to "lin")!!, 6000)
        for (i in add.indices) {
            val t = i.toDouble() / sr
            val gain = if (t < 0.1) t / 0.1 else 1.0
            assertEquals(4.0 * 100.0 * gain * sin(2 * PI * 100.0 * t), add[i], 0.02, "frame $i")
        }
        assertEquals(4.0 * 100.0 * 0.525, add[2520], 0.05)
    }

    @Test
    fun anEnvelopeTypeThatIsNotExpMeansLinear() {
        val linear = signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.05, "fmenv" to "lin")!!, 2400)
        for (other in listOf("linear", "exponential", "", "EXP", 0.0, true)) {
            assertContentEquals(linear, signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.05, "fmenv" to other)!!, 2400), "fmenv $other")
        }
        val exponential = signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.05, "fmenv" to "exp")!!, 2400)
        assertContentEquals(exponential, signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.05)!!, 2400))
        assertFalse(linear.contentEquals(exponential))
    }

    @Test
    fun theEnvelopeReleasesAtTheHoldTimeNotTheDurationOfTheSource() {
        // 0.02 s hold and 0.01 s release: at 0.05 s only the 0.001 floor of the exponential envelope is left, 400 * 0.001 at most
        val short = signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.01, "fmrelease" to 0.01, duration = 1.0, holdSec = 0.02)!!, 4800)
        for (i in 2400 until 4800) assertTrue(abs(short[i]) <= 0.4 + 1e-6, "frame $i: ${short[i]}")
        val long = signal(bank(100.0, "fmi" to 4.0, "fmattack" to 0.01, "fmrelease" to 0.01, duration = 1.0)!!, 4800)
        assertTrue((2400 until 4800).maxOf { abs(long[it]) } > 390.0)
    }

    @Test
    fun anOperatorHasItsOwnWaveRatioAndEnvelopeByItsNumber() {
        // operator 2: 3 times the carrier frequency, a sawtooth, with an attack; operator 1 has none of that
        val withSuffix = signal(bank(100.0, "fmi" to 1.0, "fmi2" to 2.0, "fmh2" to 3.0, "fmwave2" to "sawtooth", "fmattack2" to 0.01)!!, 2400)
        val plain = signal(bank(100.0, "fmi" to 1.0, "fmi2" to 2.0)!!, 2400)
        assertFalse(withSuffix.contentEquals(plain))
        // the controls of operator 1 are the unsuffixed ones, and operator 2's are not read for it
        val wrongOperator = signal(bank(100.0, "fmi" to 1.0, "fmi2" to 2.0, "fmh1" to 3.0, "fmwave1" to "sawtooth", "fmattack1" to 0.01)!!, 2400)
        assertContentEquals(plain, wrongOperator)
        val first = signal(bank(100.0, "fmi" to 1.0, "fmi2" to 2.0, "fmh" to 3.0)!!, 2400)
        assertFalse(first.contentEquals(plain))
    }

    // ---- what is not created ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun noConnectionToTheCarrierCreatesNothing() {
        assertNull(bank(100.0))
        assertNull(bank(100.0, "fmi" to 0.0))
        assertNull(bank(100.0, "fmh" to 2.0, "fmwave" to "square", "fmattack" to 0.1)) // operator controls without an amount
        assertNull(bank(100.0, "fmi21" to 1.0)) // a name that is never read
        assertNull(bank(100.0, "fmi23" to 1.0, "fmi45" to 2.0)) // operators that reach nothing
        assertNull(bank(100.0, "fmi" to Double.NaN))
        assertNull(bank(100.0, "fmi" to "x"))
    }

    @Test
    fun onlyTheOperatorsThatReachTheCarrierAreBuilt() {
        assertEquals(1, bank(100.0, "fmi" to 1.0)!!.operatorCount)
        assertEquals(1, bank(100.0, "fmi" to 1.0, "fmi45" to 2.0, "fmi23" to 3.0)!!.operatorCount)
        assertEquals(2, bank(100.0, "fmi" to 1.0, "fmi2" to 2.0, "fmi45" to 2.0)!!.operatorCount)
        assertEquals(8, bank(100.0, "fmi" to 1.0, "fmi2" to 1.0, "fmi3" to 1.0, "fmi4" to 1.0, "fmi5" to 1.0, "fmi6" to 1.0, "fmi7" to 1.0, "fmi8" to 1.0)!!.operatorCount)
    }

    @Test
    fun anOperatorThatIsNoiseHasNoFrequencyInputAndTheConnectionIntoItIsDropped() {
        val fm = bank(100.0, "fmi" to 1.0, "fmi2" to 1.0, "fmwave" to "white")!!
        assertEquals(listOf("[superdough] control fmi2 failed to connect FM 2 to target 1 due to missing frequency parameter (likely because fm1 is noise)"), fm.warnings)
        assertEquals(1, fm.operatorCount, "operator 2 feeds a noise operator: it reaches nothing")
        // what is left is the noise of operator 1: amount * frequency * the looped buffer, in float32 gains
        val buffer = NoiseBuffers.get(NoiseType.WHITE, sr)
        val add = signal(fm, 1000)
        for (i in 0 until 1000) assertEquals(((buffer[i] * 1f) * 100f).toDouble(), add[i], 1e-9, "frame $i")
        // a target that is not noise draws no warning
        assertEquals(emptyList(), bank(100.0, "fmi" to 1.0, "fmi2" to 1.0)!!.warnings)
    }

    @Test
    fun aNoiseOperatorPlaysTheCachedLoopFromItsFirstFrameAndCrackleIsBuiltFromTheSeed() {
        for ((name, type) in listOf("white" to NoiseType.WHITE, "pink" to NoiseType.PINK, "brown" to NoiseType.BROWN)) {
            val add = signal(bank(100.0, "fmi" to 3.0, "fmwave" to name)!!, 600)
            val buffer = NoiseBuffers.get(type, sr)
            for (i in 0 until 600) assertEquals(((buffer[i] * 3f) * 100f).toDouble(), add[i], 1e-9, "$name frame $i")
        }
        // crackle operator 1 is built from spec.seed + 1 with density 2 (probability 0.02 a frame)
        val crackle = NoiseBuffers.get(NoiseType.CRACKLE, sr, 2.0, 7L + 1)
        val add = signal(bank(100.0, "fmi" to 3.0, "fmwave" to "crackle")!!, 4800)
        for (i in 0 until 4800) assertEquals(((crackle[i] * 3f) * 100f).toDouble(), add[i], 1e-9, "crackle frame $i")
        assertTrue(add.count { it != 0.0 } in 50..150, "about 96 clicks in 4800 frames: ${add.count { it != 0.0 }}")
    }

    @Test
    fun theNoiseTypesAreTheExactNamesAndEveryOtherWaveStringIsASine() {
        val sine = signal(bank(100.0, "fmi" to 2.0)!!, 1200)
        for (name in listOf("sin", "saw", "Square", "SINE", "", "custom", "noise", "bytebeat")) {
            assertContentEquals(sine, signal(bank(100.0, "fmi" to 2.0, "fmwave" to name)!!, 1200), "fmwave $name")
        }
        assertContentEquals(sine, signal(bank(100.0, "fmi" to 2.0, "fmwave" to 3.0)!!, 1200), "a number")
        assertContentEquals(sine, signal(bank(100.0, "fmi" to 2.0, "fmwave" to "sine")!!, 1200))
        for (name in listOf("square", "sawtooth", "triangle")) {
            assertFalse(sine.contentEquals(signal(bank(100.0, "fmi" to 2.0, "fmwave" to name)!!, 1200)), name)
        }
    }

    @Test
    fun valuesTheAudioParamsCouldNotHoldDropTheOperatorOrTheConnectionAndDoNotThrow() {
        assertNull(bank(100.0, "fmi" to Double.POSITIVE_INFINITY))
        assertNull(bank(100.0, "fmi" to 1e300)) // beyond float32
        assertNull(bank(100.0, "fmi" to 1.0, "fmh" to 1e300))
        assertNull(bank(Double.NaN, "fmi" to 1.0))
        assertNull(bank(Double.POSITIVE_INFINITY, "fmi" to 1.0))
        // the connection that cannot be made goes, the others stay
        val fm = bank(100.0, "fmi" to 1.0, "fmi20" to 1e300)!!
        assertEquals(1, fm.operatorCount)
        assertTrue(fm.warnings.single().startsWith("[superdough] control fmi20"), fm.warnings.toString())
    }

    // ---- amounts, frequencies, cycles --------------------------------------------------------------------------------------------------------------

    @Test
    fun aNegativeAmountFlipsTheModulation() {
        val up = signal(bank(100.0, "fmi" to 2.0)!!, 1200)
        val down = signal(bank(100.0, "fmi" to -2.0)!!, 1200)
        for (i in up.indices) assertEquals(-up[i], down[i], 1e-12)
    }

    @Test
    fun theOperatorsFrequencyIsTheFloat32OfTheCarrierTimesFmh() {
        // 65.40639132514966 Hz (the default note) as the AudioParam holds it, times 3, rounded again by the oscillator and the gain
        val carrier = 65.40639132514966
        val frequency = (carrier.toFloat().toDouble() * 3.0).toFloat()
        val add = signal(bank(carrier, "fmi" to 2.0, "fmh" to 3.0)!!, 1000)
        for (i in 0 until 1000) assertEquals(2.0 * frequency * sin(2 * PI * frequency.toDouble() * i / sr), add[i], 0.01, "frame $i")
    }

    @Test
    fun aCycleIsNotMutedItsClosingConnectionReadsTheQuantumBefore() {
        val self = bank(100.0, "fmi11" to 1.0, "fmi" to 1.0)!!
        assertEquals(1, self.delayedConnectionCount)
        val acyclic = signal(bank(100.0, "fmi" to 1.0)!!, 1024)
        val looped = signal(self, 1024)
        // the first 128 frames, the first render quantum, see nothing of the loop
        for (i in 0 until 128) assertEquals(acyclic[i], looped[i], 1e-12, "frame $i")
        assertTrue((128 until 1024).any { abs(acyclic[it] - looped[it]) > 1.0 }, "after the first quantum the feedback bends the operator")

        // a loop of two: the connection that closes it is the one into the operator the carrier asked first (operator 1 is pulled, pulls operator 2,
        // which asks operator 1 for its output: that is the delayed connection); the first quantum is the chain of the same operators
        val loop = bank(100.0, "fmi" to 1.0, "fmi2" to 1.0, "fmi12" to 1.0)!!
        assertEquals(1, loop.delayedConnectionCount)
        val chain = signal(bank(100.0, "fmi" to 1.0, "fmi2" to 1.0)!!, 1024)
        val closed = signal(loop, 1024)
        for (i in 0 until 128) assertEquals(chain[i], closed[i], 1e-12, "frame $i")
        assertTrue((128 until 1024).any { abs(chain[it] - closed[it]) > 1.0 })
        assertTrue(closed.all { it.isFinite() })
    }

    @Test
    fun aGraphWithoutCyclesDelaysNothing() {
        assertEquals(0, bank(100.0, "fmi" to 1.0, "fmi2" to 1.0, "fmi3" to 1.0)!!.delayedConnectionCount)
        assertEquals(0, bank(100.0, "fmi" to 1.0, "fmi12" to 1.0, "fmi20" to 1.0)!!.delayedConnectionCount) // a diamond is no cycle
    }

    // ---- the engine's side ---------------------------------------------------------------------------------------------------------------------------

    @Test
    fun theBlockSizeDoesNotChangeASingleSample() {
        val controls = arrayOf<Pair<String, Any?>>(
            "fmi" to 2.0, "fmi2" to 1.5, "fmi23" to 0.7, "fmi3" to 1.0, "fmi11" to 0.5, "fmh2" to 1.5, "fmwave2" to "square",
            "fmattack" to 0.01, "fmdecay2" to 0.02, "fmsustain2" to 0.4, "fmwave3" to "white",
        )
        val reference = signal(bank(110.0, *controls)!!, 6000, block = 256)
        for (block in intArrayOf(1, 7, 37, 100, 127, 128, 129, 255, 1000)) {
            assertContentEquals(reference, signal(bank(110.0, *controls)!!, 6000, block = block), "blocks of $block")
        }
    }

    @Test
    fun resetStartsTheVoiceOver() {
        val fm = bank(110.0, "fmi" to 2.0, "fmi2" to 1.5, "fmi11" to 0.5, "fmattack" to 0.01, "fmwave2" to "pink")!!
        val first = signal(fm, 3000)
        fm.reset()
        assertContentEquals(first, signal(fm, 3000))
        // and again in the middle of a quantum
        fm.reset()
        val partial = signal(fm, 200)
        assertContentEquals(first.copyOf(200), partial)
    }

    @Test
    fun addAddsIntoWhatIsThereAndSetsTheFlag() {
        val fm = bank(100.0, "fmi" to 2.0)!!
        val bus = PitchBus(256)
        bus.clear(256)
        for (i in 0 until 256) bus.frequencyAddHz[i] = 1000.0
        fm.add(bus, 256)
        assertTrue(bus.frequencyActive)
        assertFalse(bus.detuneActive)
        assertEquals(1000.0 + 200.0 * sin(2 * PI * 100.0 * 120 / sr), bus.frequencyAddHz[120], 0.05)
        // nothing to add, nothing changed
        val untouched = PitchBus(8)
        untouched.clear(8)
        fm.add(untouched, 0)
        assertFalse(untouched.frequencyActive)
    }

    @Test
    fun theOscillatorPlaysTheModulationAsFmAudio() {
        // a sine carrier at 100 Hz with a 100 Hz operator of index 2: the instantaneous frequency swings 100 +- 200 Hz, through zero and below
        val wave = PeriodicWave.basic(BasicWave.SINE, sr)
        val fm = bank(100.0, "fmi" to 2.0)!!
        val audio = renderOscillator(wave, 100.0, 4800, bus = PitchBus(256)) { b, _, n -> fm.add(b, n) }
        // the phase is the integral of the frequency: 100 t + 2 sin(2 pi 100 t) / (2 pi) * ... : cos form, index 2
        var phase = 0.0
        for (i in 0 until 4800) {
            val expected = sin(2 * PI * phase)
            assertEquals(expected, audio[i].toDouble(), 5e-3, "frame $i")
            phase += (100.0 + 200.0 * sin(2 * PI * 100.0 * i / sr)) / sr
        }
        assertTrue(audio.all { abs(it) <= 1.0001f })
    }

    @Test
    fun anyControlsGiveABankThatRendersFiniteNumbersOrNoBank() {
        // 400 graphs from the 72 amounts and the per-operator controls with values the language can produce: numbers of every size, text, booleans, lists
        val rng = SourceRng(20240601)
        val values = listOf<Any?>(
            0.0, 1.0, -2.5, 3.0, 0.5, 0.001, 100.0, 1e5, 1e300, -1e300, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "2", "x", "", true, false,
            null, Undefined, emptyList<Any?>(), listOf(1.0, 2.0),
        )
        val waves = listOf<Any?>("sine", "square", "sawtooth", "triangle", "white", "pink", "brown", "crackle", "saw", "custom", "", 3.0, null)
        val envTypes = listOf<Any?>("exp", "lin", "", 0.0, true, null)
        fun pick(list: List<Any?>): Any? = list[(rng.nextDouble() * list.size).toInt()]
        val usable = listOf<Any?>(1.0, -2.5, 3.0, 0.5, 0.001, 100.0, 1e5, "2", true)
        val amounts = (1..8).flatMap { s -> (0..8).map { t -> FmRouting.controlName(s, t) } }
        var created = 0
        repeat(400) { round ->
            val controls = LinkedHashMap<String, Any?>()
            // one usable connection into the carrier, so that most graphs exist; the rest is whatever
            controls[FmRouting.controlName(1 + (rng.nextDouble() * 8).toInt(), 0)] = pick(usable)
            repeat((rng.nextDouble() * 6).toInt()) { controls[amounts[(rng.nextDouble() * amounts.size).toInt()]] = pick(values) }
            repeat((rng.nextDouble() * 6).toInt()) {
                val k = FmRouting.suffix(1 + (rng.nextDouble() * 8).toInt())
                when ((rng.nextDouble() * 7).toInt()) {
                    0 -> controls["fmh$k"] = pick(values)
                    1 -> controls["fmwave$k"] = pick(waves)
                    2 -> controls["fmattack$k"] = pick(values)
                    3 -> controls["fmdecay$k"] = pick(values)
                    4 -> controls["fmsustain$k"] = pick(values)
                    5 -> controls["fmrelease$k"] = pick(values)
                    else -> controls["fmenv$k"] = pick(envTypes)
                }
            }
            val carrier = if (round % 17 == 0) Double.NaN else if (round % 19 == 0) 0.0 else 20.0 + rng.nextDouble() * 4000.0
            val duration = if (round % 23 == 0) Double.NaN else rng.nextDouble() * 0.2
            val fm = FmBank.create(SourceSpec(sr, controls.asSourceControls(), duration, 1.0, round.toLong()), carrier, duration)
            if (fm != null) {
                created++
                val out = signal(fm, 700, block = 97)
                for (i in out.indices) assertTrue(out[i].isFinite(), "round $round, frame $i: ${out[i]} for $controls")
                fm.reset()
            }
        }
        assertTrue(created > 300, "only $created of 400 graphs were created")
    }

    @Test
    fun addAndResetDoNotAllocate() {
        val banks = listOf(
            bank(110.0, "fmi" to 2.0),
            bank(110.0, "fmi" to 2.0, "fmi2" to 1.5, "fmi3" to 1.0, "fmh2" to 1.5, "fmwave2" to "square", "fmattack" to 0.01, "fmdecay2" to 0.02),
            bank(110.0, "fmi" to 2.0, "fmi11" to 0.5, "fmi2" to 1.0, "fmi23" to 1.0, "fmenv2" to "lin", "fmrelease2" to 0.1),
            bank(110.0, "fmi" to 2.0, "fmwave" to "pink", "fmi2" to 1.0, "fmwave2" to "crackle", "fmattack" to 0.01),
        ).map { assertNotNull(it) }
        val bus = PitchBus(256)
        for ((index, fm) in banks.withIndex()) {
            fun run() {
                bus.clear(256)
                fm.add(bus, 256)
                bus.clear(100) // an odd block, across a quantum boundary
                fm.add(bus, 100)
                fm.reset()
            }
            repeat(50) { run() } // warm up: class loading, JIT
            val allocated = steadyAllocation { repeat(20) { run() } }
            assertEquals(0L, allocated, "bank $index allocated $allocated bytes")
        }
    }

    @Test
    fun aSelfLoopOnTheLastOperatorOfAFullChainIsTheOnlyDelayedConnection() {
        // all eight operators in the chain 8 -> 7 -> ... -> 1 -> carrier, and operator 8 feeding itself: that connection is the only one to close a cycle
        val fm = bank(100.0, "fmi" to 1.0, "fmi2" to 1.0, "fmi3" to 1.0, "fmi4" to 1.0, "fmi5" to 1.0, "fmi6" to 1.0, "fmi7" to 1.0, "fmi8" to 1.0, "fmi88" to 1.0)!!
        assertEquals(8, fm.operatorCount)
        assertEquals(1, fm.delayedConnectionCount)
    }
}
