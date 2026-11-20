package cosic.audio.sources

import cosic.pattern.CosicError
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tables of docs/parity/spec/superdough-sources.md section 4, task A, plus the behaviour that the Chromium goldens do not single out. */
class SourcesSpecTest {
    private val sr = 48000

    private fun spec(vararg controls: Pair<String, Any?>, durationSec: Double = 0.05, seed: Long = 1L, sampleRate: Int = sr) =
        SourceSpec(sampleRate, mapOf(*controls).asSourceControls(), durationSec, 1.0, seed)

    private fun render(source: SoundSource, frames: Int, startFrame: Long = 0L, vararg blocks: Int): Pair<FloatArray, FloatArray> = renderSource(source, startFrame, frames, *blocks)

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (to - from))
    }

    // ---- additive waves

    @Test
    fun anAdditiveSawtoothIsMinusASine() {
        val wave = additiveWave(BasicWave.SAWTOOTH, doubleArrayOf(1.0), null, sr, false)
        assertEquals(-1.0f, wave.table(0)[1024], 1e-6f)
        assertEquals(0.0f, wave.table(0)[0], 1e-6f)
        assertEquals(1.0f, wave.table(0)[3072], 1e-6f)
    }

    private fun f32(x: Double) = x.toFloat().toDouble()

    private fun assertSameWave(expected: PeriodicWave, actual: PeriodicWave) {
        for (r in 0 until expected.rangeCount) assertContentEquals(expected.table(r), actual.table(r), "table $r")
    }

    @Test
    fun anAdditiveSquareHasTheOddPartialsOverTheirNumber() {
        val wave = additiveWave(BasicWave.SQUARE, doubleArrayOf(1.0, 1.0, 1.0), null, sr, false)
        assertSameWave(PeriodicWave.custom(DoubleArray(4), doubleArrayOf(0.0, 1.0, 0.0, f32(1.0 / 3.0)), sr), wave)
    }

    @Test
    fun anAdditiveTriangleIsACosineThatStartsAtItsPeak() {
        val wave = additiveWave(BasicWave.TRIANGLE, doubleArrayOf(1.0), null, sr, false)
        assertEquals(1.0f, wave.table(0)[0], 1e-6f)
        assertEquals(0.0f, wave.table(0)[1024], 1e-6f)
        assertSameWave(PeriodicWave.custom(doubleArrayOf(0.0, 1.0), doubleArrayOf(0.0, 0.0), sr), wave)
    }

    @Test
    fun aUserWaveTakesThePartialsAsTheyAre() {
        val wave = additiveWave(null, doubleArrayOf(1.0, 0.0, 0.3), null, sr, true)
        assertSameWave(PeriodicWave.custom(DoubleArray(4), doubleArrayOf(0.0, 1.0, 0.0, f32(0.3)), sr), wave)
    }

    @Test
    fun aPhaseRotatesTheCoefficient() {
        // a quarter turn of -sin is cos: the table starts at its peak
        val wave = additiveWave(BasicWave.SAWTOOTH, doubleArrayOf(1.0), doubleArrayOf(0.25), sr, false)
        assertEquals(1.0f, wave.table(0)[0], 1e-6f)
        val unrotated = additiveWave(BasicWave.SAWTOOTH, doubleArrayOf(1.0), doubleArrayOf(0.0), sr, false)
        assertSameWave(additiveWave(BasicWave.SAWTOOTH, doubleArrayOf(1.0), null, sr, false), unrotated)
    }

    @Test
    fun theSameWaveComesBackFromTheCache() {
        val a = additiveWave(BasicWave.SQUARE, doubleArrayOf(1.0, 0.5, 0.25, 0.125), null, sr, false)
        val b = additiveWave(BasicWave.SQUARE, doubleArrayOf(1.0, 0.5, 0.25, 0.125), null, sr, false)
        assertTrue(a === b)
        assertTrue(a !== additiveWave(BasicWave.SQUARE, doubleArrayOf(1.0, 0.5, 0.25, 0.126), null, sr, false))
    }

    @Test
    fun anEmptyOrNegativeCountOfPartialsIsTheErrorWebAudioGives() {
        assertFailsWith<CosicError> { additiveWave(BasicWave.SAWTOOTH, DoubleArray(0), null, sr, false) }
        assertFailsWith<CosicError> { OscillatorSource(spec("partials" to -3.0), "sawtooth", null, null) }
        assertFailsWith<CosicError> { OscillatorSource(spec("partials" to 0.5), "sawtooth", null, null) }
        assertFailsWith<CosicError> { OscillatorSource(spec("note" to "x"), "sine", null, null) }
        OscillatorSource(spec("note" to "x"), "one", null, null) // `one` never reads the pitch
    }

    // ---- which wave an oscillator plays

    private fun play(sound: String, vararg controls: Pair<String, Any?>, frames: Int = 2400): FloatArray =
        render(StockSynths.create(sound, spec(*controls))!!, frames).first

    @Test
    fun aCountOfPartialsIsThatManyEqualOnes() {
        assertContentEquals(play("sawtooth", "n" to 4.0, "note" to 57.0), play("sawtooth", "partials" to listOf(1.0, 1.0, 1.0, 1.0), "note" to 57.0))
        assertContentEquals(play("square", "partials" to 3.0, "note" to 57.0), play("square", "partials" to listOf(1.0, 1.0, 1.0), "note" to 57.0))
    }

    @Test
    fun partialsWinOverNAndZeroMeansTheStockWave() {
        assertContentEquals(play("sawtooth", "n" to 7.0, "partials" to listOf(1.0, 0.5), "note" to 50.0), play("sawtooth", "partials" to listOf(1.0, 0.5), "note" to 50.0))
        assertContentEquals(play("sawtooth", "n" to 0.0, "note" to 50.0), play("sawtooth", "note" to 50.0))
        assertContentEquals(play("sawtooth", "partials" to emptyList<Double>(), "note" to 50.0), play("sawtooth", "note" to 50.0))
    }

    @Test
    fun userWithoutPartialsIsATriangleAndSineIgnoresThem() {
        assertContentEquals(play("user", "note" to 52.0), play("triangle", "note" to 52.0))
        assertContentEquals(play("sine", "partials" to listOf(1.0, 1.0, 1.0), "note" to 60.0), play("sine", "note" to 60.0))
    }

    @Test
    fun aliasesPlayTheSameWave() {
        assertContentEquals(play("saw", "note" to 40.0), play("sawtooth", "note" to 40.0))
        assertContentEquals(play("tri", "note" to 40.0), play("triangle", "note" to 40.0))
        assertContentEquals(play("sqr", "note" to 40.0), play("square", "note" to 40.0))
        assertContentEquals(play("sin", "note" to 40.0), play("sine", "note" to 40.0))
    }

    @Test
    fun theDefaultEnvelopeOfAStockWave() {
        val out = render(StockSynths.create("sawtooth", spec("note" to 69.0, durationSec = 1.0))!!, 48000).first
        var peak = 0.0
        for (i in 24000 until 40000) peak = maxOf(peak, abs(out[i].toDouble()))
        // sustain 0.6 of a 0.3 gain, times the height of the band-limited sawtooth
        assertTrue(peak in 0.6 * 0.3 * 0.8..0.6 * 0.3 * 1.05, "peak $peak")
    }

    @Test
    fun aStockWaveEndsTenMillisecondsAfterItsRelease() {
        val s = StockSynths.create("sine", spec(durationSec = 1.0))!!
        s.start(0L)
        val l = FloatArray(48960)
        val r = FloatArray(48960)
        assertEquals(48959, s.render(l, r, 48959))
        assertFalse(s.isFinished)
        assertEquals(1, s.render(l, r, 5))
        assertTrue(s.isFinished)
        assertEquals(0, s.render(l, r, 100))
    }

    @Test
    fun aNoiseMixWeighsTheOscillatorAndThePinkNoise() {
        val buffer = NoiseBuffers.get(NoiseType.PINK, sr)
        val plain = render(OscillatorSource(spec("note" to 45.0), "sawtooth", null, null), 2400).first
        val noise = render(NoiseSource(spec(), NoiseType.PINK, buffer), 2400).first
        val mixed = render(OscillatorSource(spec("note" to 45.0, "noise" to 0.3), "sawtooth", null, null, buffer), 2400).first
        // wetFade(0.3) = 1 for the oscillator and wetFade(0.7) = 0.6 for the noise, and both have the same envelope
        for (i in 0 until 2400) assertEquals(plain[i] + 0.6f * noise[i], mixed[i], 2e-6f, "frame $i")
        assertFalse(mixed.contentEquals(plain))
    }

    @Test
    fun theNoiseControlIsNotClamped() {
        assertEquals(1.0, wetFade(0.3), 1e-12)
        assertEquals(0.6, wetFade(0.7), 1e-12)
        assertEquals(-1.0, wetFade(1.5), 1e-12)
    }

    // ---- supersaw

    @Test
    fun supersawOffsetsSpreadTheDetuneAroundTheNote() {
        assertContentEquals(doubleArrayOf(-6.0, 0.0, 6.0), supersawOffsets(3.0, 12.0))
        assertContentEquals(doubleArrayOf(0.0), supersawOffsets(1.0, 7.0))
        assertContentEquals(doubleArrayOf(-0.09, 0.09), supersawOffsets(2.0, 0.18))
        assertEquals(3, supersawOffsets(2.5, 0.4).size)
        assertContentEquals(doubleArrayOf(0.0, 0.0), supersawOffsets(1.5, 0.4))
    }

    @Test
    fun supersawVoicesAlternateBetweenTheSides() {
        val (l0, r0) = supersawPan(0, 0.6)
        assertEquals(0.4472136, l0, 1e-7)
        assertEquals(0.8944272, r0, 1e-7)
        val (l1, r1) = supersawPan(1, 0.6)
        assertEquals(r0, l1, 0.0)
        assertEquals(l0, r1, 0.0)
        val (cl, cr) = supersawPan(0, 0.0)
        assertEquals(cl, cr, 1e-12)
    }

    private fun lcgPhase(vararg values: Double): RandomSource {
        var i = 0
        return RandomSource { values[i++ % values.size] }
    }

    @Test
    fun aSingleSupersawVoiceFromPhaseZero() {
        // a flat envelope at the peak 0.3 (sustain 1); the worklet starts on the first quantum after the note: frame 128 for a note on frame 0
        val s = SupersawSource(spec("freq" to 100.0, "unison" to 1.0, "sustain" to 1.0), null, null, lcgPhase(0.0))
        val (l, r) = render(s, 300)
        assertEquals(0.0f, l[127])
        assertEquals(0.0f, l[128], 1e-9f)
        // (2 dt - 1) of a voice that is one step in, times the middle gain sqrt(0.5) and the peak 0.3; the spec's table rounds this to -0.704151
        assertEquals((2.0 * (100.0 / 48000.0) - 1.0) * sqrt(0.5) * 0.3, l[129].toDouble(), 2e-7)
        assertContentEquals(l, r, "one voice is in the middle")
    }

    @Test
    fun aWorkletStartsOnTheQuantumAfterItsNote() {
        fun firstSound(source: SoundSource, startFrame: Long): Int {
            val (l, _) = render(source, 600, startFrame)
            return l.indexOfFirst { it != 0f }
        }
        // The begin of the node is a float32: a note on frame 128 (a quantum boundary) has a begin that rounds below the exact time, so that quantum already
        // starts after it and sounds at once; 48000 (1 s) is exact in float32, so a note on that boundary waits a whole quantum, as the golden of Chromium shows.
        for ((startFrame, expected) in listOf(0L to 128, 750L to 18, 48000L to 128, 128L to 1, 129L to 127, 255L to 1)) {
            val controls = arrayOf("note" to 45.0, "sustain" to 1.0)
            assertEquals(expected, firstSound(SupersawSource(spec(*controls), null, null, lcgPhase(0.3)), startFrame), "supersaw at $startFrame")
            assertEquals(expected, firstSound(PulseSource(spec(*controls), null, null), startFrame), "pulse at $startFrame")
            assertEquals(expected, firstSound(ByteBeatSource(spec("n" to 4.0, "sustain" to 1.0)), startFrame), "bytebeat at $startFrame")
        }
        // a native oscillator starts on its own frame
        assertEquals(1, firstSound(OscillatorSource(spec("note" to 45.0), "sine", null, null), 750L))
    }

    // ---- pulse

    @Test
    fun theFirstFrameOfAPulse() {
        val (l, r) = render(PulseSource(spec("freq" to 100.0, "pw" to 0.5, "sustain" to 1.0), null, null), 200)
        assertEquals(-0.01501662, l[128].toDouble(), 1e-7)
        assertContentEquals(l, r)
    }

    @Test
    fun aPulseWidthSweepNeedsADepth() {
        val plain = render(PulseSource(spec("note" to 45.0), null, null), 3000).first
        assertContentEquals(plain, render(PulseSource(spec("note" to 45.0, "pwsweep" to 0.0), null, null), 3000).first)
        assertContentEquals(plain, render(PulseSource(spec("note" to 45.0, "pwsweep" to 0.0, "pwrate" to 4.0), null, null), 3000).first)
        assertFalse(plain.contentEquals(render(PulseSource(spec("note" to 45.0, "pwrate" to 2.0), null, null), 3000).first), "pwrate alone sweeps by 0.3")
        assertFalse(plain.contentEquals(render(PulseSource(spec("note" to 45.0, "pwsweep" to 0.2), null, null), 3000).first))
    }

    @Test
    fun theWidthIsClampedWhereTheWorkletReadsIt() {
        val wide = render(PulseSource(spec("note" to 45.0, "pw" to 5.0), null, null), 3000).first
        assertContentEquals(render(PulseSource(spec("note" to 45.0, "pw" to 0.99), null, null), 3000).first, wide)
        val narrow = render(PulseSource(spec("note" to 45.0, "pw" to -5.0), null, null), 3000).first
        assertContentEquals(render(PulseSource(spec("note" to 45.0, "pw" to -0.99), null, null), 3000).first, narrow)
    }

    // ---- bytebeat

    @Test
    fun aBytebeatOfTIsARampThroughTheByteRange() {
        // frequency 187.5 at 48 kHz advances one step per frame; the counter starts at 0 on the first frame that sounds
        val s = ByteBeatSource(spec("freq" to 187.5, "byteBeatExpression" to "t", "byteBeatStartTime" to 0.0, "sustain" to 1.0))
        val (l, r) = render(s, 128 + 300)
        assertEquals(-0.2, l[128].toDouble(), 1e-6)
        assertEquals(0.000784, l[128 + 128].toDouble(), 1e-6)
        assertEquals(0.2, l[128 + 255].toDouble(), 1e-6)
        assertContentEquals(l, r)
    }

    private fun beat(code: String, t: Double): Double? = compileByteBeat(code)?.eval(t)

    @Test
    fun theDefaultBeatsOfTheWorkletCompile() {
        for ((i, code) in DEFAULT_BYTE_BEATS.withIndex()) assertNotNull(compileByteBeat(code), "beat $i: $code")
        assertEquals(15, DEFAULT_BYTE_BEATS.size)
    }

    @Test
    fun bytebeatExpressionsFollowJavaScript() {
        assertEquals(128.0, beat("t&128", 200.0))
        assertEquals(0.0, beat("t&128", 100.0))
        assertEquals(255.0, beat("(t%255 >= t/255%255)*255", 300.0))
        assertEquals(254.0, beat("(sin(t*PI/128)*127+127)", 64.0))
        assertEquals(138.0, beat("((t^t/2+t+64)%128*2)", 10.0))
        assertEquals(9.0, beat("t*(t >> 10^t)", 3.0))
        assertEquals(0.0, beat("((t^t/2+t+64*(cos >> 0))%127.85*2)", 0.0), "a function used as a number is NaN, which `>>` makes 0")
        assertEquals(-0.9975, beat("(t*(t*8%60 <= 300)|(-t)*(t*4%512 < 256))+t/400", 1.0)!!, 1e-12)
        assertEquals(7.0, beat("1+2*3", 0.0))
        assertEquals(512.0, beat("2**3**2", 0.0))
        assertEquals(0.25, beat("2**-2", 0.0))
        assertEquals(1.0, beat("7%3", 0.0))
        assertEquals(-1.0, beat("-7%3", 0.0))
        assertEquals(-2147483648.0, beat("1<<31", 0.0))
        assertEquals(4294967295.0, beat("-1>>>0", 0.0))
        assertEquals(-6.0, beat("~5", 0.0))
        assertEquals(1.0, beat("!0", 0.0))
        assertEquals(0.0, beat("5>3>1", 0.0))
        assertEquals(3.0, beat("1,2,3", 0.0))
        assertEquals(10.0, beat("t ? 10 : 20", 1.0))
        assertEquals(20.0, beat("t ? 10 : 20", 0.0))
        assertEquals(5.0, beat("0 || 5", 0.0))
        assertEquals(0.0, beat("0 && 5", 0.0))
        assertEquals(16.0, beat("0x10", 0.0))
        assertEquals(1000.0, beat("1e3", 0.0))
        assertEquals(0.5, beat(".5", 0.0))
        assertEquals(1000.0, beat("1_000", 0.0))
        assertEquals(Math.PI, beat("PI", 0.0)!!, 0.0)
        assertEquals(Math.PI, beat("Math.PI", 0.0)!!, 0.0)
        assertEquals(5.0, beat("max(1,5,3)", 0.0))
        assertEquals(Double.POSITIVE_INFINITY, beat("min()", 0.0))
        assertEquals(3.0, beat("int(3.7)", 0.0))
        assertEquals(3.0, beat("Math.floor(3.7)", 0.0))
        assertEquals(128.0, beat("br(1)", 0.0))
        assertEquals(9.0, beat("bitC(5,4,9)", 0.0))
        assertEquals(1.0, beat("sinf(64)", 0.0)!!, 1e-12)
        assertEquals(3.0, beat("t /* the time */ // and more", 3.0))
        assertEquals(0.0, beat("   ", 5.0), "blank text is the expression 0")
        assertEquals(3.0, beat("round(2.5)", 0.0))
        assertEquals(-2.0, beat("round(-2.5)", 0.0))
        assertEquals(Double.NaN, beat("sin()", 0.0)!!)
        assertEquals(3.0, beat("(1+2)", 0.0))
    }

    @Test
    fun bytebeatCodeThatIsNotAnExpressionDoesNotCompile() {
        for (code in listOf("t +* 3", "foo(t)", "t = 3", "'a'", "[1,2]", "{}", "regG(t, /1/)", "-2**2", "t.x", "(t", "t)", "function(){}", "x")) {
            assertNull(compileByteBeat(code), code)
        }
        assertNull(compileByteBeat(null))
        assertNull(compileByteBeat("(".repeat(500) + "t" + ")".repeat(500)), "too deep")
    }

    @Test
    fun aBytebeatWithoutAProgramIsSilent() {
        for (controls in listOf(arrayOf("byteBeatExpression" to "t +* 3"), arrayOf("n" to 2.5), arrayOf("n" to -1.0), arrayOf("byteBeatExpression" to 5.0))) {
            val (l, _) = render(ByteBeatSource(spec(*controls)), 1000)
            assertTrue(l.all { it == 0f }, controls.toList().toString())
        }
    }

    @Test
    fun brWithATooLargeSizeSilencesTheVoice() {
        val (l, _) = render(ByteBeatSource(spec("byteBeatExpression" to "br(t, 40)", "sustain" to 1.0)), 1000)
        assertTrue(l.all { it == 0f })
    }

    @Test
    fun theDefaultBeatIsNModuloFifteen() {
        val a = render(ByteBeatSource(spec("n" to 3.0)), 1000).first
        val b = render(ByteBeatSource(spec("n" to 18.0)), 1000).first
        val c = render(ByteBeatSource(spec("byteBeatExpression" to DEFAULT_BYTE_BEATS[3])), 1000).first
        assertContentEquals(a, b)
        assertContentEquals(a, c)
    }

    // ---- sbd

    @Test
    fun aBassDrumEndsWithItsDecayAndStartsWithANoiseBurst() {
        val s = SbdSource(spec(durationSec = 0.6))
        s.start(0L)
        val l = FloatArray(30000)
        val r = FloatArray(30000)
        assertEquals(24480, s.render(l, r, 30000), "decay 0.5 + 0.01 s")
        assertTrue(s.isFinished)
        for (i in 24000 until 24480) assertTrue(abs(l[i].toDouble()) <= 1e-3, "frame $i")
        // the noise burst is what the brown loop adds: level 1.2 falling to 0.001 within 25 ms (the tone is the same in both renders, and the mix is linear)
        val silentNoise = FloatArray(96000)
        val tonal = render(SbdSource(spec(durationSec = 0.6), silentNoise), 2400).first
        val full = render(SbdSource(spec(durationSec = 0.6)), 2400).first
        val burst = FloatArray(2400) { full[it] - tonal[it] }
        assertTrue(rms(burst, 0, 240) > 0.01, "the first 5 ms: ${rms(burst, 0, 240)}")
        assertTrue(rms(burst, 1500, 2400) < 2e-3, "after 30 ms: ${rms(burst, 1500, 2400)}")
    }

    @Test
    fun clipCutsABassDrumShort() {
        val s = SbdSource(spec("clip" to 0.3, durationSec = 1.0))
        s.start(0L)
        val l = FloatArray(30000)
        val r = FloatArray(30000)
        assertEquals(14400, s.render(l, r, 30000), "t + clip * duration")
        assertEquals(0.0f, l[14399], 1e-3f)
    }

    // ---- the factory

    @Test
    fun theFactoryKnowsTheStockNamesAndTheirAliases() {
        assertEquals(StockSynths.names.toSet(), StockSynths.names.mapNotNull { StockSynths.canonical(it) }.toSet())
        assertEquals("sawtooth", StockSynths.canonical("SAW"))
        assertEquals("sine", StockSynths.canonical("Sin"))
        assertNull(StockSynths.canonical("supersaw2"))
        assertNull(StockSynths.canonical("bd"))
        assertNull(StockSynths.create("bd", spec()))
        for (name in StockSynths.names) {
            val source = assertNotNull(StockSynths.create(name, spec("note" to 40.0)), name)
            assertEquals(if (name in setOf("supersaw", "pulse", "bytebeat")) 2 else 1, source.channels, name)
        }
        assertTrue(StockSynths.isPitched("tri") && StockSynths.isPitched("supersaw") && StockSynths.isPitched("pulse"))
        assertFalse(StockSynths.isPitched("pink") || StockSynths.isPitched("bytebeat") || StockSynths.isPitched("sbd") || StockSynths.isPitched("one"))
    }

    @Test
    fun theFactoryAddsVibratoAndThePitchEnvelopeToPitchedSounds() {
        val plain = play("sawtooth", "note" to 52.0, "attack" to 0.0, "decay" to 0.0, "sustain" to 1.0)
        assertFalse(plain.contentEquals(play("sawtooth", "note" to 52.0, "vib" to 5.0, "vibmod" to 1.0, "sustain" to 1.0)))
        assertFalse(plain.contentEquals(play("sawtooth", "note" to 52.0, "penv" to 12.0, "sustain" to 1.0)))
        // a noise has no pitch: the same control changes nothing
        assertContentEquals(play("white", "vib" to 5.0, "penv" to 12.0), play("white"))
    }

    @Test
    fun theFactoryPassesFmToThePitchedSources() {
        var asked: Triple<Double, Double, Boolean>? = null
        val fm = FmFactory { s, hz, hold ->
            asked = Triple(hz, hold, s.controls.number("fmi") != null)
            object : FrequencyModulator {
                override fun add(bus: PitchBus, n: Int) {
                    for (i in 0 until n) bus.frequencyAddHz[i] += 50.0
                    bus.frequencyActive = true
                }
            }
        }
        val source = StockSynths.create("sine", spec("note" to 69.0, "fmi" to 2.0, durationSec = 0.2), fm)!!
        assertEquals(Triple(440.0, 0.2, true), asked)
        val shifted = render(source, 2400).first
        assertFalse(shifted.contentEquals(play("sine", "note" to 69.0)))
        asked = null
        StockSynths.create("white", spec(), fm)
        StockSynths.create("one", spec(), fm)
        assertNull(asked, "noises and `one` have no frequency to modulate")
    }

    // ---- no allocation once a source exists

    private fun assertNoAllocation(label: String, source: SoundSource) {
        val l = FloatArray(256)
        val r = FloatArray(256)
        val bytes = steadyAllocation {
            source.start(750L)
            for (block in 0 until 8) source.render(l, r, 256)
        }
        assertEquals(0L, bytes, "$label allocates")
    }

    @Test
    fun startingAndRenderingAllocateNothing() {
        val bus = arrayOf("note" to 52.0, "vib" to 5.0, "vibmod" to 1.0, "penv" to 7.0, "pattack" to 0.02)
        val modulated = spec(*bus, durationSec = 0.1)
        fun pitch() = PitchModulation.create(modulated, 0.1)
        assertNoAllocation("sine", OscillatorSource(spec("note" to 52.0, durationSec = 0.1), "sine", null, null))
        assertNoAllocation("sawtooth with partials, noise and pitch", OscillatorSource(spec("note" to 52.0, "partials" to listOf(1.0, 0.5, 0.25), "noise" to 0.4, "vib" to 5.0, "penv" to 7.0, durationSec = 0.1), "sawtooth", pitch(), null))
        assertNoAllocation("one", OscillatorSource(spec(durationSec = 0.1), "one", null, null))
        assertNoAllocation("pink", NoiseSource(spec(durationSec = 0.1), NoiseType.PINK))
        assertNoAllocation("supersaw", SupersawSource(modulated, pitch(), null))
        assertNoAllocation("pulse", PulseSource(spec("note" to 45.0, "pwrate" to 3.0, durationSec = 0.1), null, null))
        assertNoAllocation("pulse with pitch", PulseSource(modulated, pitch(), null))
        assertNoAllocation("bytebeat", ByteBeatSource(spec("n" to 9.0, durationSec = 0.1)))
        assertNoAllocation("bytebeat with huge values", ByteBeatSource(spec("byteBeatExpression" to "t*t*t*t*t*t", durationSec = 0.1)))
        assertNoAllocation("sbd", SbdSource(spec(durationSec = 0.1)))
    }

    // ---- absurd input

    @Test
    fun everySoundStaysFiniteForAbsurdControlsAndDurations() {
        val absurd: List<Array<Pair<String, Any?>>> = listOf(
            arrayOf("note" to 1e9), arrayOf("note" to -1e9), arrayOf("freq" to 1e300), arrayOf("freq" to -50.0), arrayOf("freq" to 1e-300), arrayOf("octave" to 500.0),
            arrayOf("vib" to 1e9, "vibmod" to 1e9), arrayOf("penv" to 1e9, "pcurve" to 1.0), arrayOf("penv" to -1e9), arrayOf("penv" to 0.0, "panchor" to 0.0, "pattack" to 0.0),
            arrayOf("unison" to 1e9, "spread" to 1e9, "detune" to 1e9), arrayOf("unison" to -5.0), arrayOf("pw" to 1e9, "pwrate" to 1e9, "pwsweep" to 1e9),
            arrayOf("attack" to 1e9, "decay" to -1.0, "sustain" to 1e9, "release" to -5.0), arrayOf("noise" to 1e9),
            arrayOf("partials" to listOf(1e300, Double.NaN, -1e300), "phases" to listOf(1e300, Double.NaN)), arrayOf("partials" to 100000.0, "phases" to listOf(1e300)),
            arrayOf("byteBeatExpression" to "1/0"), arrayOf("byteBeatExpression" to "t*t*t*t*t*t*t*t"), arrayOf("byteBeatExpression" to "br(t,32)+br(t,0)+br(t)"),
            arrayOf("decay" to 0.0, "pdecay" to 0.0, "penv" to 0.0), arrayOf("density" to 1e9), arrayOf("density" to -1.0), arrayOf("clip" to 1e300, "decay" to 1e300),
        )
        for (name in StockSynths.names) for (controls in absurd) for (duration in doubleArrayOf(0.05, 0.0, -1.0, Double.NaN, 1e9)) {
            val label = "$name ${controls.toList()} for $duration s"
            val source = try {
                StockSynths.create(name, spec(*controls, durationSec = duration))!!
            } catch (e: CosicError) {
                continue // an error the sound would throw in Strudel too
            }
            val (l, r) = render(source, 1500, 750L)
            for (i in l.indices) {
                assertTrue(l[i] - l[i] == 0f && r[i] - r[i] == 0f, "$label: frame $i is ${l[i]} / ${r[i]}")
            }
        }
    }
}
