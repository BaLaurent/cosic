package cosic

import cosic.audio.SR
import cosic.audio.allFinite
import cosic.audio.channel
import cosic.audio.constantSample
import cosic.audio.peak
import cosic.audio.rms
import cosic.render.emptyLibrary
import cosic.render.OfflineRenderer
import cosic.render.RenderCli
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where hits begin in a mono signal: windows of [window] frames in which a sample-to-sample step first exceeds [threshold]. A centred voice
 * is 0.8 now (no panner, no master gain), where it was 0.57; the threshold is scaled with it.
 */
private fun hitStarts(x: FloatArray, threshold: Float = 0.03f, window: Int = 48): List<Int> {
    val starts = ArrayList<Int>()
    var wasAbove = false
    var from = 0
    while (from + window <= x.size) {
        var step = 0f
        for (i in max(from, 1) until from + window) step = max(step, abs(x[i] - x[i - 1]))
        val above = step > threshold
        if (above && !wasAbove) starts.add(from)
        wasAbove = above
        from += window
    }
    return starts
}

/** The offline renderer, the WAV writer and the command line tool built on them. */
class RenderTest {
    private val tmp = kotlin.io.path.createTempDirectory("cosic-render").toFile()

    // ----------------------------------------------------------------------------- renderer

    @Test
    fun twoCyclesOfFourKicksHaveEightOnsetsOnTheClockGrid() {
        val out = OfflineRenderer.render("""s("bd*4")""", cycles = 2.0)
        assertEquals((2400 + 2 * 96_000) * 2, out.size)
        val starts = hitStarts(channel(out, 0))
        val expected = listOf(2400, 26_400, 50_400, 74_400, 98_400, 122_400, 146_400, 170_400)
        assertEquals(expected.size, starts.size, "onsets found at $starts")
        for ((found, wanted) in starts.zip(expected)) {
            assertTrue(abs(found - wanted) <= SR / 1000, "onset at $found, expected $wanted within 1 ms")
        }
        assertTrue(peak(out) <= 1.0f)
        assertTrue(allFinite(out))
        assertTrue(rms(out) > 0.01)
    }

    @Test
    fun theLengthIsTheStartDelayPlusTheRequestedCycles() {
        assertEquals((2205 + 176_400) * 2, OfflineRenderer.render("""s("bd")""", 2.0, sampleRate = 44_100).size)
        assertEquals((2400 + 72_000) * 2, OfflineRenderer.render("setcpm(60)\n\$: s(\"bd\")", 1.5).size, "1.5 cycles at one cycle per second")
    }

    @Test
    fun anEvaluationErrorIsThrownWithItsMessage() {
        val error = assertFailsWith<IllegalStateException> { OfflineRenderer.render("foo(1)", 1.0) }
        assertEquals("foo is not defined", error.message)
    }

    @Test
    fun renderingTheSameCodeTwiceGivesTheSameSamples() {
        val code = """s("bd*2, hh*8").room(0.3).pan(rand)"""
        assertContentEquals(OfflineRenderer.render(code, 1.0), OfflineRenderer.render(code, 1.0))
    }

    @Test
    fun theGivenLibraryProvidesTheBanks() {
        val library = libraryWithBank("kit", listOf(constantSample(0.5, 0.5f)))
        val out = OfflineRenderer.render("""s("kit")""", 1.0, library = library)
        assertEquals(0.4, peak(out, 2400 * 2 + 480 * 2).toDouble(), 0.01, "a constant 0.5 sample, times the gain of 0.8, on both channels")
    }

    @Test
    fun aBuiltInDrumMachinePlaysWithoutAnyLibrary() {
        for (machine in listOf("cosic808", "cosic909")) {
            val out = OfflineRenderer.render("""s("bd sd hh*2 cp").bank("$machine")""", cycles = 1.0)
            assertTrue(rms(out) > 0.01, "$machine is silent")
            assertTrue(allFinite(out), "$machine produced NaN")
            assertTrue(peak(out) <= 1.0f, "$machine clips")
        }
    }

    @Test
    fun theEmptyLibraryHasNoBanksButTheEnginesOwnSounds() {
        val library = emptyLibrary()
        assertNull(library.sound("kick"))
        assertEquals(emptyList(), library.entries(), "nothing is registered")
        assertIs<BuiltinSound>(library.sound("bd"))
        assertIs<BuiltinSound>(library.sound("sine"))
        assertEquals(0f, peak(OfflineRenderer.render("""s("kick")""", 1.0)), "an unknown sound is silent")
    }

    // ------------------------------------------------------------------------------ export

    private fun firstAbove(x: FloatArray, level: Float = 0.01f): Int = x.indexOfFirst { abs(it) > level }

    @Test
    fun aRangeStartsOnItsFirstFrameAndIsAsLongAsItsCyclesPlusTheTail() {
        val library = libraryWithBank("kit", listOf(constantSample(0.05, 0.5f)))
        val out = OfflineRenderer.render("""s("kit*4")""", 1.0, 3.0, 48_000, library) // 2 cycles at 0.5 cps: 4 seconds
        val main = (2 / 0.5 * 48_000).toInt() * 2
        assertTrue(out.size >= main, "at least the cycles: ${out.size}")
        assertEquals(0, (out.size - main) % (4096 * 2), "the tail is whole blocks")
        assertTrue(out.size - main <= 10 * 48_000 * 2, "the tail is 10 seconds at the most")
        val left = channel(out, 0)
        assertTrue(firstAbove(left) in 0 until 48, "the first onset is on frame 0, not after the 50 ms of play(): ${firstAbove(left)}")
        assertEquals(0.4, peak(out, 48 * 2, 480 * 2).toDouble(), 0.01, "a constant 0.5 sample times the gain 0.8")
        // four hits per cycle are half a second apart: cycle 1.25 is the second hit, and nothing starts at the end cycle
        assertTrue(abs(left[24_000 + 100]) > 0.01f, "the second hit")
        assertEquals(0f, peak(left, 192_000 + 4096, left.size), "nothing starts after the last frame of the end cycle")
    }

    @Test
    fun aRangeFromCycleZeroHasNoStartDelayWherePlayHasOne() {
        val library = libraryWithBank("kit", listOf(constantSample(0.05, 0.5f)))
        val code = """s("kit")"""
        assertTrue(firstAbove(channel(OfflineRenderer.render(code, 0.0, 1.0, 48_000, library), 0)) < 48)
        assertTrue(firstAbove(channel(OfflineRenderer.render(code, 1.0, 48_000, library), 0)) >= 2400, "play() waits 50 ms")
    }

    @Test
    fun aRangeStartsInTheMiddleOfThePattern() {
        val code = "setcps(1)\n\$: s(\"kit\").struct(\"1 0 0 0\").add(note(0))"
        val library = libraryWithBank("kit", listOf(constantSample(0.05, 0.5f)))
        // cycle 1.5 has no hit; the next is at 2.0, half a second in
        val out = channel(OfflineRenderer.render(code, 1.5, 3.0, 48_000, library), 0)
        assertEquals(24_000, firstAbove(out).let { if (it in 24_000..24_048) 24_000 else it }, "the first hit")
    }

    @Test
    fun aRangeWaitsForTheSamplesItNeedsToBeDecoded() {
        val library = SlowLibrary(readyOnRequest = 3)
        val out = OfflineRenderer.render("""s("kit")""", 0.0, 1.0, 48_000, library)
        assertTrue(firstAbove(channel(out, 0)) in 0 until 48, "the sample played, from the first frame")
    }

    @Test
    fun aRangeReportsItsProgressAndCanBeCancelled() {
        val seen = ArrayList<Double>()
        OfflineRenderer.render("""s("bd")""", 0.0, 1.0, 48_000, emptyLibrary(), progress = { seen.add(it) })
        assertEquals(1.0, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a }, "progress only grows")
        assertFailsWith<java.util.concurrent.CancellationException> {
            OfflineRenderer.render("""s("bd")""", 0.0, 1.0, 48_000, emptyLibrary(), cancelled = { true })
        }
    }

    @Test
    fun aRangeRejectsBadCyclesAndCodeWithErrors() {
        assertFailsWith<IllegalArgumentException> { OfflineRenderer.render("""s("bd")""", 2.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { OfflineRenderer.render("""s("bd")""", -1.0, 1.0) }
        assertEquals("foo is not defined", assertFailsWith<IllegalStateException> { OfflineRenderer.render("foo(1)", 0.0, 1.0) }.message)
    }

    // -------------------------------------------------------------------------------- WAV

    @Test
    fun writeWavMakesASixteenBitStereoPcmFile() {
        val interleaved = floatArrayOf(0f, 0f, 0.25f, -0.25f, 1f, -1f, 2f, -2f, Float.NaN, 0.75f)
        val file = File(tmp, "out.wav")
        OfflineRenderer.writeWav(file, interleaved, 44_100)

        val info = AudioSystem.getAudioFileFormat(file)
        assertEquals(AudioFileFormat.Type.WAVE, info.type)
        assertEquals(5, info.frameLength)
        assertEquals(2, info.format.channels)
        assertEquals(16, info.format.sampleSizeInBits)
        assertEquals(44_100f, info.format.sampleRate)
        assertEquals(44 + interleaved.size * 2L, file.length())

        val bytes = AudioSystem.getAudioInputStream(file).readAllBytes()
        val samples = ShortArray(interleaved.size) { ((bytes[it * 2 + 1].toInt() shl 8) or (bytes[it * 2].toInt() and 0xFF)).toShort() }
        assertContentEquals(shortArrayOf(0, 0, 8_192, -8_192, 32_767, -32_767, 32_767, -32_767, 0, 24_575), samples)
    }

    // --------------------------------------------------------------------------------- CLI

    private class Run(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = RenderCli.run(arrayOf(*args), PrintStream(out, true), PrintStream(err, true))
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun theCliRendersCodeToAWavFile() {
        val wav = File(tmp, "beat.wav")
        val run = cli("--code", """s("bd*4, hh*8").room(0.2)""", "--cycles", "2", "--out", wav.path)
        assertEquals(0, run.code, run.err)
        val info = AudioSystem.getAudioFileFormat(wav)
        assertEquals(2400 + 2 * 96_000, info.frameLength)
        assertEquals(48_000f, info.format.sampleRate)
        assertContains(run.out, wav.path)
    }

    @Test
    fun theCliTakesTheCodeFromAFileAndTheSampleRateFromAnOption() {
        val source = File(tmp, "tune.cosic").apply { writeText("setcpm(60)\n\$: s(\"bd\")\n") }
        val wav = File(tmp, "tune.wav")
        val run = cli("--file", source.path, "--cycles", "1", "--sr", "44100", "--out", wav.path)
        assertEquals(0, run.code, run.err)
        val info = AudioSystem.getAudioFileFormat(wav)
        assertEquals(2205 + 44_100, info.frameLength)
        assertEquals(44_100f, info.format.sampleRate)
    }

    @Test
    fun theCliDefaultsToFourCycles() {
        val wav = File(tmp, "default.wav")
        assertEquals(0, cli("--code", """s("bd")""", "--out", wav.path).code)
        assertEquals(2400 + 4 * 96_000, AudioSystem.getAudioFileFormat(wav).frameLength)
    }

    @Test
    fun anEvaluationErrorIsPrintedAndExitsWithOne() {
        val wav = File(tmp, "never.wav")
        val run = cli("--code", "foo(1)", "--out", wav.path)
        assertEquals(1, run.code)
        assertContains(run.err.lines(), "foo is not defined")
        assertTrue(!wav.exists(), "nothing is written")
    }

    @Test
    fun theCliReadsEachSubfolderOfTheSamplesFolderAsABank() {
        val bank = File(tmp, "samples/Kick Drum").apply { mkdirs() }
        OfflineRenderer.writeWav(File(bank, "a.wav"), FloatArray(2 * 4800), 48_000) // silent, so it sorts first and is n 0
        OfflineRenderer.writeWav(File(bank, "B.wav"), FloatArray(2 * 4800) { 0.5f }, 48_000) // n 1
        File(tmp, "samples/empty").mkdirs()
        val loud = File(tmp, "loud.wav")
        val quiet = File(tmp, "quiet.wav")
        assertEquals(0, cli("--code", """s("kick_drum:1")""", "--cycles", "1", "--samples", File(tmp, "samples").path, "--out", loud.path).code)
        assertEquals(0, cli("--code", """s("kick_drum:0")""", "--cycles", "1", "--samples", File(tmp, "samples").path, "--out", quiet.path).code)
        fun peakOf(file: File) = AudioSystem.getAudioInputStream(file).readAllBytes().let { bytes ->
            (0 until bytes.size / 2).maxOf { abs(((bytes[it * 2 + 1].toInt() shl 8) or (bytes[it * 2].toInt() and 0xFF)).toShort().toInt()) }
        }
        assertTrue(peakOf(loud) > 8_000, "B.wav sorts after a.wav, so n 1 is the loud one")
        assertEquals(0, peakOf(quiet))
    }

    @Test
    fun badArgumentsPrintTheUsageAndExitWithTwo() {
        val wav = File(tmp, "x.wav").path
        for (args in listOf(
            arrayOf("--out", wav),
            arrayOf("--code", "s(\"bd\")"),
            arrayOf("--code", "s(\"bd\")", "--file", "x", "--out", wav),
            arrayOf("--code", "s(\"bd\")", "--out", wav, "--cycles", "0"),
            arrayOf("--code", "s(\"bd\")", "--out", wav, "--cycles", "many"),
            arrayOf("--code", "s(\"bd\")", "--out", wav, "--sr", "1"),
            arrayOf("--code", "s(\"bd\")", "--out", wav, "--samples", File(tmp, "missing").path),
            arrayOf("--code", "s(\"bd\")", "--out", wav, "--bogus"),
            arrayOf("--code"),
        )) {
            val run = cli(*args)
            assertEquals(2, run.code, args.joinToString(" "))
            assertContains(run.err, "usage:")
        }
    }
}
