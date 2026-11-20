package cosic.player

import cosic.BankGroup
import cosic.CosicEngine
import cosic.FakeStore
import cosic.SampleData
import cosic.WaveTableBank
import cosic.audio.SR
import cosic.audio.VoiceSource
import cosic.audio.estimateFrequency
import cosic.audio.channel
import cosic.audio.peak
import cosic.audio.sources.WavetableSource
import cosic.evaluateOk
import cosic.renderFrames
import cosic.sound.RegistryLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class WavetableResolverTest {
    /** `wt_test` has two files; file 0 is two frames of 4 values, file 1 three frames of 4. Files are `wt_test/<index>`. */
    private fun library(vararg loaded: Int): RegistryLibrary {
        val files = mapOf(
            ("wt_test" to 0) to SampleData(FloatArray(8) { it.toFloat() }, 44100),
            ("wt_test" to 1) to SampleData(FloatArray(12) { -it.toFloat() }, 44100),
        ).filterKeys { it.second in loaded }
        return RegistryLibrary(FakeStore(files), emptySet()).also {
            it.register(WaveTableBank("wt_test", listOf(BankGroup(null, null, listOf("wt_test/0", "wt_test/1"))), 4, null, false))
        }
    }

    private fun lookup(library: RegistryLibrary, vararg controls: Pair<String, Any>) = SoundResolver(SR, library).resolve(mapOf(*controls))

    /** The sound of a lookup that found its table: the source that plays it. */
    private fun table(lookup: SoundLookup): WavetableSource {
        val sound = assertIs<VoiceSource.Synth>(assertIs<SoundLookup.Ready>(lookup).source)
        assertEquals("wt_test", sound.name, "a wavetable is called by the name of its bank")
        return assertIs<WavetableSource>(sound.source)
    }

    @Test
    fun aWavetableBankResolvesToTheFramesOfTheFileNPicks() {
        val library = library(0, 1)
        val first = table(lookup(library, "s" to "wt_test"))
        assertEquals(2, first.frames.frames.size)
        assertEquals(4, first.frames.frameLen)
        val second = table(lookup(library, "s" to "wt_test", "n" to 1.0))
        assertEquals(3, second.frames.frames.size)
        // n wraps around the number of files, as for samples
        val wrapped = table(lookup(library, "s" to "wt_test", "n" to 3.0))
        assertEquals(3, wrapped.frames.frames.size)
    }

    @Test
    fun theSourceReadsTheEventsControls() {
        fun first2048(vararg controls: Pair<String, Any>): FloatArray {
            val source = table(lookup(library(0), "s" to "wt_test", *controls))
            val left = FloatArray(2048)
            source.start(0)
            source.render(left, FloatArray(2048), 2048)
            return left
        }
        assertFalse(first2048("wt" to 0.0).contentEquals(first2048("wt" to 0.5)), "the position of the table is a control of the event")
        assertFalse(first2048("note" to 48.0).contentEquals(first2048("note" to 60.0)), "so is the note")
    }

    @Test
    fun everyEventGetsASourceOfItsOwn() {
        val library = library(0)
        assertSame(table(lookup(library, "s" to "wt_test")).frames, table(lookup(library, "s" to "wt_test")).frames, "the table is the library's, shared")
        val resolver = SoundResolver(SR, library)
        val a = table(resolver.resolve(mapOf("s" to "wt_test")))
        val b = table(resolver.resolve(mapOf("s" to "wt_test")))
        assertFalse(a === b, "but a source plays one note")
    }

    @Test
    fun aTableThatIsNotDecodedYetIsLoadingAndTheNoteWaits() {
        assertSame(SoundLookup.Loading, lookup(library(1), "s" to "wt_test"))
        assertIs<SoundLookup.Ready>(lookup(library(1), "s" to "wt_test", "n" to 1.0))
    }

    @Test
    fun theBankControlPrefixesTheNameAsForSamples() {
        table(lookup(library(0), "bank" to "wt", "s" to "test"))
    }

    @Test
    fun theTableIsFoundInAnyCase() {
        table(lookup(library(0), "s" to "WT_Test"))
    }

    @Test
    fun aProgramPlaysTheTableThroughTheScheduler() {
        val sine = SampleData(FloatArray(2048) { Math.sin(2 * Math.PI * it / 2048).toFloat() }, 44100)
        val store = FakeStore(mapOf(("wt_sine" to 0) to sine))
        val library = RegistryLibrary(store, emptySet()).also {
            it.register(WaveTableBank("wt_sine", listOf(BankGroup(null, null, listOf("wt_sine/0"))), 2048, null, false))
        }
        val engine = CosicEngine(SR, library)
        engine.evaluateOk("""note("a4").s("wt_sine").release(0.2)""")
        engine.play()
        val out = engine.renderFrames(SR)
        val left = channel(out, 0)
        val at = left.indexOfFirst { Math.abs(it) > 0.01f }
        assert(at >= 0) { "silence" }
        assertEquals(440.0, estimateFrequency(left, at + 3000, at + 20000), 1.0)
        assert(peak(left, at, at + 20000) > 0.1f)
    }

    @Test
    fun vibratoAndFmReachAWavetableThroughTheSchedulerToo() {
        val sine = SampleData(FloatArray(2048) { Math.sin(2 * Math.PI * it / 2048).toFloat() }, 44100)
        val library = RegistryLibrary(FakeStore(mapOf(("wt_sine" to 0) to sine)), emptySet()).also {
            it.register(WaveTableBank("wt_sine", listOf(BankGroup(null, null, listOf("wt_sine/0"))), 2048, null, false))
        }
        fun render(code: String): FloatArray {
            val engine = CosicEngine(SR, library)
            engine.evaluateOk(code)
            engine.play()
            return channel(engine.renderFrames(SR / 2), 0)
        }
        val plain = render("""note("a4").s("wt_sine").release(0.2)""")
        val fm = render("""note("a4").s("wt_sine").release(0.2).fm(2)""")
        val vib = render("""note("a4").s("wt_sine").release(0.2).vib(6).vibmod(2)""")
        assertFalse(plain.contentEquals(fm), "fm changes a wavetable's sound")
        assertFalse(plain.contentEquals(vib), "so does vibrato")
        assert(peak(fm) > 0.05f && peak(vib) > 0.05f)
    }
}
