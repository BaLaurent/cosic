package cosic.dough

import cosic.CosicEngine
import cosic.EvalReport
import cosic.PLAY_START_DELAY_SECONDS
import cosic.SampleData
import cosic.SampleStore
import cosic.audio.IrExecutor
import cosic.audio.SR
import cosic.audio.peak
import cosic.engineOf
import cosic.evaluateOk
import cosic.render.OfflineRenderer
import cosic.renderFrames
import cosic.sound.RegistryLibrary
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A program that plays with dough, through the whole path: pattern, scheduler, the engine's block loop, the dough engine and the output. */
class DoughIntegrationTest {
    private val startFrame = Math.round(PLAY_START_DELAY_SECONDS * SR).toInt()

    /** What the dough engine alone makes of [text] started at [startFrame], over [frames] frames. */
    private fun direct(text: String, frames: Int, file: DoughFile? = null): FloatArray {
        val engine = DoughEngine(SR)
        if (file != null) engine.registerFile(0, file)
        val out = FloatArray(frames * 2)
        engine.render(out, startFrame, 0)
        engine.run(parseDoughEvent(text))
        engine.render(out, frames - startFrame, startFrame)
        return out
    }

    @Test
    fun aProgramThatPlaysWithDoughSoundsLikeTheDoughEngineAlone() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine").note(69).dough()""")
        engine.play()
        val out = engine.renderFrames(SR)
        // one cycle is two seconds at the default tempo: the hap's duration is 2
        assertContentEquals(direct("dough/play/s/sine/note/69/duration/2", SR), out)
        assertTrue(peak(out) > 0.05f, "silent")
    }

    @Test
    fun eventsOfTheSameFrameStartNewestFirst() {
        // two haps of one group that has room for one voice: the one that starts second steals the voice of the first. Dough walks its schedule from the end, so the later hap starts first
        val engine = engineOf()
        engine.evaluateOk("""stack(s("sine").note(60).group(1).voices(1), s("sine").note(72).group(1).voices(1)).dough()""")
        engine.play()
        val out = engine.renderFrames(SR / 2)
        val expected = DoughEngine(SR)
        val reference = FloatArray(SR / 2 * 2)
        expected.render(reference, startFrame, 0)
        expected.run(parseDoughEvent("dough/play/s/sine/note/72/group/1/voices/1/duration/2"))
        expected.run(parseDoughEvent("dough/play/s/sine/note/60/group/1/voices/1/duration/2"))
        expected.render(reference, SR / 2 - startFrame, startFrame)
        assertContentEquals(reference, out)
    }

    @Test
    fun theHapDoesNotSoundInTheMainEngineToo() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine").note(69).dough()""")
        engine.play()
        val with = engine.renderFrames(SR)
        val plain = engineOf().also { it.evaluateOk("""s("sine").note(69)""") }.also { it.play() }.renderFrames(SR)
        assertTrue(peak(plain) > 0.05f)
        // different sounds: the main engine's sine is louder than dough's (0.2 gain, half mix) and not the same signal
        assertTrue(with.indices.any { with[it] != plain[it] })
    }

    @Test
    fun stoppingFadesTheEngineOutAndSilencesIt() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine").note(69).dough()""")
        engine.play()
        engine.renderFrames(SR / 2)
        engine.stop()
        val after = engine.renderFrames(SR / 2)
        assertEquals(0f, peak(after, SR / 4, after.size), "still sounding after the fade")
        engine.play()
        // a new play starts from nothing: no tail of the old notes
        val again = engine.renderFrames(startFrame)
        assertEquals(0f, peak(again), "a tail came back")
    }

    @Test
    fun anUnknownSoundWarnsAndPlaysATriangle() {
        val engine = engineOf()
        val ok = engine.evaluateOk("""s("bd").dough()""")
        assertEquals(listOf("sound bd not found in soundMap"), ok.warnings)
        engine.play()
        val out = engine.renderFrames(SR / 2)
        assertTrue(peak(out) > 0.05f, "silent")
    }

    @Test
    fun theDoughGlobalsExistAndTheControlsWork() {
        val engine = engineOf()
        engine.evaluateOk("await initDough()\n\$: note(\"c3 e3\").s(\"sine\").group(1).voices(2).glide(0.5).dough()")
        val ok = engine.evaluateOk("s(\"saw\").note(\"c3\").dough().lpf(800)")
        assertEquals(emptyList(), ok.warnings)
    }

    @Test
    fun theProgramFailsWhenANoteNameIsWrong() {
        val failed = assertIs<EvalReport.Failed>(engineOf().evaluate("""s("sine").note("zz").dough()"""))
        assertTrue(failed.message.contains("not a note"), failed.message)
    }

    @Test
    fun offlineRenderingPlaysDough() {
        val out = OfflineRenderer.render("""s("sine").note("c4").dough()""", 0.5)
        assertTrue(peak(out) > 0.05f, "silent")
    }

    // ---- doughsamples ---------------------------------------------------------------------------------------------------------

    private class UrlStore(private val data: Map<String, SampleData>, private val texts: Map<String, String> = emptyMap()) : SampleStore {
        val awaited = ArrayList<String>()

        override fun fetchText(url: String): String = texts[url] ?: throw IOException("no text at $url")

        override fun sample(url: String): SampleData? = data[url]

        override fun awaitSample(url: String): SampleData {
            synchronized(awaited) { awaited += url }
            return data[url] ?: throw IOException("no sample at $url")
        }

        override fun decode(bytes: ByteArray, nameHint: String): SampleData = throw IOException("no decoder")
    }

    private fun burst(): SampleData = SampleData(FloatArray(4800) { i -> (Math.sin(i * 0.2) * (1 - i / 4800.0)).toFloat() }, SR, 1)

    private fun awaitFile(engine: CosicEngine, index: Int) {
        val end = System.nanoTime() + 5_000_000_000L
        while (engine.dough.engine.file(index) == null && System.nanoTime() < end) Thread.sleep(5)
        assertNotNull(engine.dough.engine.file(index), "file $index was not loaded")
    }

    @Test
    fun aSoundOfDoughsamplesPlaysAsAFile() {
        val store = UrlStore(mapOf("https://x.test/k/a.wav" to burst()))
        val engine = CosicEngine(SR, RegistryLibrary(store, CosicEngine.BUILTIN_SOUNDS), IrExecutor.INLINE)
        // the dry run of evaluating starts loading the file the program asks for
        val ok = engine.evaluateOk("doughsamples({ kick: ['a.wav'] }, 'https://x.test/k/')\n\$: s(\"kick\").dough()")
        assertEquals(emptyList(), ok.warnings)
        awaitFile(engine, 0)
        engine.play()
        val out = engine.renderFrames(SR / 2)
        assertContentEquals(direct("dough/play/s/kick/file/0/duration/2", SR / 2, toDoughFile(burst(), SR)), out)
        assertTrue(peak(out) > 0.05f, "silent")
        assertEquals(listOf("https://x.test/k/a.wav"), store.awaited)
    }

    @Test
    fun aMapAtAUrlIsFetchedWithItsBase() {
        val text = """{"_base": "https://x.test/m/", "kick": ["a.wav"], "bad": "b.wav"}"""
        val store = UrlStore(mapOf("https://x.test/m/a.wav" to burst()), mapOf("https://x.test/m/strudel.json" to text))
        val engine = CosicEngine(SR, RegistryLibrary(store, CosicEngine.BUILTIN_SOUNDS), IrExecutor.INLINE)
        engine.evaluateOk("doughsamples('https://x.test/m/strudel.json')")
        assertEquals(listOf("https://x.test/m/a.wav"), engine.dough.sounds.urls("kick"))
        assertEquals(null, engine.dough.sounds.urls("bad"), "an entry that is no list is not registered")
        val failed = assertIs<EvalReport.Failed>(engine.evaluate("doughsamples('https://x.test/none.json')"))
        assertEquals("error loading \"https://x.test/none.json\"", failed.message)
    }

    @Test
    fun theSoundOfAnotherEngineIsNotDoughs() {
        // doughsamples() does not feed superdough's registry, and a name only superdough has is unknown to dough
        val store = UrlStore(emptyMap())
        val library = RegistryLibrary(store, CosicEngine.BUILTIN_SOUNDS)
        val engine = CosicEngine(SR, library, IrExecutor.INLINE)
        engine.evaluateOk("doughsamples({ kick: ['a.wav'] }, 'https://x.test/')")
        assertEquals(null, library.sound("kick"))
    }
}
