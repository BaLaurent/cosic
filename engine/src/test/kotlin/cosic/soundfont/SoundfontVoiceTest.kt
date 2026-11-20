package cosic.soundfont

import cosic.SampleData
import cosic.audio.Mixer
import cosic.audio.SR
import cosic.audio.SampleSourceParams
import cosic.audio.VoiceSource
import cosic.audio.WavDecoder
import cosic.audio.channel
import cosic.audio.constantSample
import cosic.audio.peak
import cosic.audio.renderFrames
import cosic.audio.renderLeft
import cosic.audio.rms
import cosic.audio.vp
import cosic.render.OfflineRenderer
import cosic.sound.RegistryLibrary
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundfontVoiceTest {
    private fun soundfont(data: SampleData, loopStart: Double = Double.NaN, loopEnd: Double = Double.NaN) =
        VoiceSource.Sample(SampleSourceParams(data, peakGain = SOUNDFONT_PEAK_GAIN, loopStartFrame = loopStart, loopEndFrame = loopEnd, honoursSampleControls = false))

    private fun at(x: FloatArray, seconds: Double) = x[(seconds * SR).toInt()]

    // ------------------------------------------------------------------------------------------------ level and gate

    @Test
    fun aSoundfontVoiceSustainsAtThreeTenthsOfItsSampleAndEndsOneReleaseAfterTheHap() {
        val left = renderLeft(vp(soundfont(constantSample(3.0, 1f)), 0.5, "gain" to 1.0), SR)
        assertEquals(0.3f, at(left, 0.25), 1e-6f)
        assertEquals(0.3f, peak(left), 1e-6f)
        assertEquals(0.3f, at(left, 0.499), 1e-6f)
        // release 0.01 s from the end of the hap at 0.5: the envelope falls to 0 at 0.51, the source stops at 0.52
        assertTrue(at(left, 0.505) in 0.01f..0.29f, "${at(left, 0.505)} is in the middle of the release")
        assertEquals(0f, at(left, 0.52))
        assertEquals(0f, at(left, 0.9))
    }

    @Test
    fun theEnvelopeControlsShapeASoundfontLikeASample() {
        val left = renderLeft(vp(soundfont(constantSample(3.0, 1f)), 0.5, "gain" to 1.0, "attack" to 0.2, "release" to 0.2), SR)
        assertEquals(0.15f, at(left, 0.1), 5e-3f, "halfway up the attack")
        assertEquals(0.3f, at(left, 0.3), 1e-6f)
        assertEquals(0.15f, at(left, 0.6), 5e-3f, "halfway down the release")
        assertEquals(0f, at(left, 0.75))
    }

    @Test
    fun theGateIsTheHapNotTheSampleAndNotTheEnd() {
        // a sample of 0.1 s whose end control is 0.5 would make a sample's gate 0.05 s; a soundfont's gate is the hap, 0.4 s, so the loop keeps sounding
        val data = constantSample(0.1, 1f)
        val looping = renderLeft(vp(soundfont(data, loopStart = 0.02 * SR, loopEnd = 0.06 * SR), 0.4, "gain" to 1.0, "end" to 0.5), SR)
        assertEquals(0.3f, at(looping, 0.3), 1e-6f, "the loop holds the sound")
        assertEquals(0f, at(looping, 0.45))
        // without a loop the sample ends where it ends and the rest of the hap is silent
        val once = renderLeft(vp(soundfont(data), 0.4, "gain" to 1.0), SR)
        assertEquals(0.3f, at(once, 0.05), 1e-6f)
        assertEquals(0f, at(once, 0.3))
    }

    @Test
    fun theSamplerControlsDoNotChangeWhatPlays() {
        val data = SampleData(FloatArray(SR) { sin(2.0 * PI * 330.0 * it / SR).toFloat() }, SR)
        val plain = renderLeft(vp(soundfont(data), 0.3, "gain" to 1.0), SR / 2)
        val busy = renderLeft(
            vp(soundfont(data), 0.3, "gain" to 1.0, "begin" to 0.5, "end" to 0.2, "speed" to -2.0, "unit" to "c", "loop" to 1.0, "loopBegin" to 0.1, "loopEnd" to 0.2, "nudge" to 0.2),
            SR / 2,
        )
        for (i in plain.indices) assertEquals(plain[i], busy[i], "frame $i")
        assertTrue(peak(plain) > 0.29f)
    }

    @Test
    fun aSoundfontIsNotCutByTheNextVoiceOfItsCutGroup() {
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(vp(soundfont(constantSample(3.0, 1f)), 1.0, "gain" to 1.0, "cut" to 1.0)))
        mixer.renderFrames(SR / 10)
        assertTrue(mixer.startVoice(vp(soundfont(constantSample(3.0, 1f)), 1.0, "gain" to 1.0, "cut" to 1.0)))
        val left = channel(mixer.renderFrames(SR / 2), 0)
        // both play on: 0.3 + 0.3
        assertEquals(0.6f, left[SR / 4], 1e-5f)
    }

    @Test
    fun theSourceIsRepitchedByTheZonesTuning() {
        // a 220 Hz sine played 12 semitones up sounds at 440 Hz
        val data = SampleData(FloatArray(SR) { sin(2.0 * PI * 220.0 * it / SR).toFloat() }, SR)
        val params = SampleSourceParams(data, transposeSemitones = 12.0, peakGain = SOUNDFONT_PEAK_GAIN, honoursSampleControls = false)
        val left = renderLeft(vp(VoiceSource.Sample(params), 0.5, "gain" to 1.0), SR / 2)
        var crossings = 0
        for (i in 4800 until 14_400) if (left[i - 1] < 0f && left[i] >= 0f) crossings++
        assertTrue(crossings in 87..89, "440 Hz over 0.2 s is 88 cycles, found $crossings")
    }

    // ------------------------------------------------------------------------------------------------ the whole chain

    private fun wavBytes(samples: ShortArray, rate: Int): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort(s)
        val out = ByteArrayOutputStream()
        fun le(value: Int, bytes: Int) = repeat(bytes) { out.write((value shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray()); le(36 + samples.size * 2, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2); le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2)
        out.write("data".toByteArray()); le(samples.size * 2, 4); out.write(data.array())
        return out.toByteArray()
    }

    /** A font file in the shape of the real ones with one zone for every note: a recorded 440 Hz sine at MIDI note 69 (originalPitch 6900), looped in its middle. */
    private fun sineFont(rate: Int = 44_100): String {
        val frames = rate / 2
        val wav = wavBytes(ShortArray(frames) { (sin(2.0 * PI * 440.0 * it / rate) * 20_000).toInt().toShort() }, rate)
        return "console.log('load _tone_sine');\nvar _tone_sine_sf2_file={\n\tzones:[\n\t\t{\n\t\t\tmidi:0\n\t\t\t,originalPitch:6900\n\t\t\t,keyRangeLow:0\n\t\t\t,keyRangeHigh:127\n" +
            "\t\t\t,loopStart:2000\n\t\t\t,loopEnd:20000\n\t\t\t,coarseTune:0\n\t\t\t,fineTune:0\n\t\t\t,sampleRate:$rate\n\t\t\t,ahdsr:true\n" +
            "\t\t\t,file:'${Base64.getEncoder().encodeToString(wav)}'\n\t\t\t,anchor:0.0\n\t\t}\n\t]\n};\n"
    }

    @Test
    fun aProgramPlaysASoundfontThroughTheWholeEngine() {
        val store = FontStore(fallback = sineFont(), decoder = { WavDecoder.decode(it) })
        val library = RegistryLibrary(store, emptySet()).also {
            it.fontExecutor = Executor { job -> job.run() }
            GmFonts.register(it)
        }
        // c4 e4: two notes of a cycle at 0.5 cps, 1 s each: the font plays them at 261.6 Hz and 329.6 Hz, held by the loop for the whole hap
        val out = OfflineRenderer.render("""note("a4 a5").s("gm_piano")""", cycles = 1.0, library = library)
        val left = channel(out, 0)
        assertTrue(abs(left.maxOrNull()!!) <= 1f)
        val first = rms(left, (0.2 * SR).toInt(), (0.8 * SR).toInt())
        val second = rms(left, (1.2 * SR).toInt(), (1.8 * SR).toInt())
        // 0.3 * 0.8 (the default gain) * the sine's amplitude 20000/32768, rms of a sine: 1/sqrt(2)
        val expected = 0.3 * 0.8 * (20_000.0 / 32_768.0) / Math.sqrt(2.0)
        assertEquals(expected, first, expected * 0.05, "first note")
        assertEquals(expected, second, expected * 0.05, "second note")
        assertEquals(listOf("https://felixroos.github.io/webaudiofontdata/sound/0000_JCLive_sf2_file.js"), store.fetched)
        assertEquals(listOf("0000_JCLive_sf2_file#0"), store.decoded, "both notes are in the one zone")
    }
}
