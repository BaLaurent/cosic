package cosic.script.catalogue

import cosic.SampleBank
import cosic.SampleData
import cosic.SampleStore
import cosic.WaveTableBank
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.script.ReplSession
import cosic.script.SessionHost
import cosic.script.shown
import cosic.sound.RegistryLibrary
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library functions of a program (`samples`, `soundAlias`, `aliasBank`, `getDuration`, `tables`, `setSoundfontUrl`, `.piano()`):
 * what they register in the library of the host, with the URLs Strudel makes of the `github:`, `bubo:`, `shabda:` and `local:` forms,
 * and the errors they report. The parity harness mocks them (test/runtime.mjs), so these are the tests that run the real ones.
 */
class SampleFunctionsTest {
    /** A store with the files of a few fake servers: [texts] by URL (sample maps), [lengths] the seconds of the sample at a URL. */
    private class FakeServer(val texts: Map<String, String> = emptyMap(), val lengths: Map<String, Double> = emptyMap()) : SampleStore {
        val fetched = ArrayList<String>()

        override fun fetchText(url: String): String {
            fetched.add(url)
            return texts[url] ?: throw IOException("404 $url")
        }

        override fun sample(url: String): SampleData? = lengths[url]?.let { SampleData(FloatArray((it * 100).toInt()), 100) }

        override fun awaitSample(url: String): SampleData = sample(url) ?: throw IOException("no sample at $url")

        override fun decode(bytes: ByteArray, nameHint: String): SampleData = throw IOException("no decoder")
    }

    private class World(val library: RegistryLibrary, val server: FakeServer) {
        val host = SessionHost(library)
        private val session = ReplSession(host)

        fun run(code: String): Pattern = session.evaluate(code).pattern

        fun play(code: String, cycles: Int = 1): List<String> = shown(run(code), cycles)

        fun failure(code: String): String? = assertFailsWith<CosicError>(code) { run(code) }.message

        fun bank(name: String): SampleBank = assertIs<SampleBank>(library.sound(name), "sound $name")

        fun urls(name: String): List<String> = bank(name).groups.flatMap { it.urls }
    }

    private fun world(texts: Map<String, String> = emptyMap(), lengths: Map<String, Double> = emptyMap()): World {
        val server = FakeServer(texts, lengths)
        return World(RegistryLibrary(server, emptySet()), server)
    }

    // ---- samples ------------------------------------------------------------------------------------------------------------

    @Test
    fun anObjectOfFilesIsRegisteredUnderTheBaseUrl() {
        val w = world()
        val haps = w.play(
            """
            await samples({ bd: '808bd/BD0000.WAV', sd: ['808sd/SD0010.WAV', '808sd/SD0020.WAV'] }, 'https://example.test/Dirt/')
            s("bd sd:1")
            """.trimIndent(),
        )
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd ]", "[ 1/2 → 1/1 | s:sd n:1 ]"), haps)
        assertEquals(listOf("https://example.test/Dirt/808bd/BD0000.WAV"), w.urls("bd"))
        assertEquals(listOf("https://example.test/Dirt/808sd/SD0010.WAV", "https://example.test/Dirt/808sd/SD0020.WAV"), w.urls("sd"))
    }

    @Test
    fun theBaseDefaultsToTheUnderscoreBaseOfTheObjectAndTheLastRegistrationWins() {
        val w = world()
        w.run("samples({ _base: 'https://a.test/', kick: 'k.wav' })")
        assertEquals(listOf("https://a.test/k.wav"), w.urls("kick"))
        w.run("samples({ kick: 'other.wav' }, 'https://b.test/')")
        assertEquals(listOf("https://b.test/other.wav"), w.urls("kick"))
    }

    @Test
    fun aNestedMapIsAPitchedBankInTheOrderOfTheMap() {
        val w = world()
        w.run("samples({ pad: { c2: ['c2.wav'], 'e2': 'e2.wav' } }, 'https://a.test/')")
        val bank = w.bank("pad")
        assertTrue(bank.pitched)
        assertEquals(listOf("c2", "e2"), bank.groups.map { it.noteName })
        assertEquals(listOf("https://a.test/e2.wav"), bank.groups[1].urls)
    }

    @Test
    fun theOptionsMarkTheBanksAsPrebakedAndTagged() {
        val w = world()
        w.run("samples({ bd: 'bd.wav' }, 'https://a.test/', { prebake: true, tag: 'drums' })")
        assertEquals("drums", w.bank("bd").tag)
        assertTrue(w.bank("bd").prebake)
    }

    @Test
    fun aUrlIsFetchedAndItsUnderscoreBaseIsUsed() {
        val w = world(mapOf("https://x.test/maps/strudel.json" to """{ "_base": "https://cdn.test/files/", "kick": ["k1.wav", "k2.wav"], "snare": "s.wav" }"""))
        w.run("await samples('https://x.test/maps/strudel.json')")
        assertEquals(listOf("https://cdn.test/files/k1.wav", "https://cdn.test/files/k2.wav"), w.urls("kick"))
        assertEquals(listOf("https://cdn.test/files/s.wav"), w.urls("snare"))
    }

    @Test
    fun aMapWithoutABaseUsesTheFolderOfItsUrl() {
        val w = world(mapOf("https://x.test/maps/strudel.json" to """{ "kick": ["/k.wav"] }"""))
        w.run("samples('https://x.test/maps/strudel.json')")
        assertEquals(listOf("https://x.test/maps/k.wav"), w.urls("kick"))
    }

    @Test
    fun theBaseUrlArgumentReplacesTheBaseOfAFetchedMap() {
        val w = world(mapOf("https://x.test/strudel.json" to """{ "_base": "https://cdn.test/", "kick": ["k.wav"] }"""))
        w.run("samples('https://x.test/strudel.json', 'https://other.test/')")
        assertEquals(listOf("https://other.test/k.wav"), w.urls("kick"))
    }

    @Test
    fun theSpecialUrlFormsAreResolvedLikeStrudelDoesIt() {
        val forms = mapOf(
            "github:tidalcycles/dirt-samples" to "https://raw.githubusercontent.com/tidalcycles/dirt-samples/main/strudel.json",
            "github:tidalcycles/dirt-samples/dev/" to "https://raw.githubusercontent.com/tidalcycles/dirt-samples/dev/strudel.json",
            "github:user" to "https://raw.githubusercontent.com/user/samples/main/strudel.json",
            "bubo:waveforms" to "https://raw.githubusercontent.com/Bubobubobubobubo/dough-waveforms/main/strudel.json",
            "shabda:bass:3,piano" to "https://shabda.ndre.gr/bass:3,piano.json?strudel=1",
            "local:" to "http://localhost:5432",
        )
        for ((spec, url) in forms) {
            val w = world(mapOf(url to """{ "_base": "https://cdn.test/", "x": "x.wav" }"""))
            w.run("samples('$spec')")
            assertEquals(listOf(url), w.server.fetched, spec)
            assertEquals(listOf("https://cdn.test/x.wav"), w.urls("x"), spec)
        }
    }

    @Test
    fun shabdaSpeechBuildsItsQueryFromTheLanguageAndTheGender() {
        val url = "https://shabda.ndre.gr/speech/hello,world.json?gender=m&language=fr-FR&strudel=1'"
        val w = world(mapOf(url to """{ "_base": "https://cdn.test/", "hello": ["h.wav"] }"""))
        w.run("samples('shabda/speech/fr-FR/m:hello,world')")
        assertEquals(listOf(url), w.server.fetched)
    }

    @Test
    fun aMapThatCannotBeLoadedIsAnErrorOfTheProgram() {
        val w = world(mapOf("https://x.test/bad.json" to "not json"))
        assertEquals("error loading \"https://x.test/bad.json\"", w.failure("samples('https://x.test/bad.json')"))
        assertEquals("error loading \"https://x.test/missing.json\"", w.failure("samples('https://x.test/missing.json')"))
    }

    @Test
    fun aBankThatIsNotAListOrAStringIsAnErrorAndTheBanksBeforeItStay() {
        val w = world()
        assertEquals("wrong sample map format for bad", w.failure("samples({ good: 'g.wav', bad: 5 }, 'https://a.test/')"))
        assertEquals(listOf("https://a.test/g.wav"), w.urls("good"))
    }

    // ---- soundAlias, aliasBank ----------------------------------------------------------------------------------------------

    @Test
    fun soundAliasGivesASoundAnotherNameThatKeepsItsCase() {
        val w = world()
        w.run("samples({ bd: 'bd.wav' }, 'https://a.test/'); soundAlias('bd', 'kick'); soundAlias('bd', 'Kicker')")
        assertEquals(w.library.sound("bd"), w.library.sound("kick"))
        assertNull(w.library.sound("Kicker")) // the alias is stored as written: a capital one cannot be found again
        assertNull(w.library.sound("kicker"))
    }

    @Test
    fun soundAliasOfAnUnknownSoundOnlyLogs() {
        val w = world()
        w.run("soundAlias('nothing', 'x')")
        assertTrue(w.host.recentLines().any { it.message == "soundAlias: original sound not found" })
        assertNull(w.library.sound("x"))
    }

    @Test
    fun aliasBankHasTheThreeForms() {
        val w = world(mapOf("https://x.test/alias.json" to """{ "roland": ["r", "rr"] }"""))
        w.run(
            """
            samples({ roland_bd: 'bd.wav', roland_sd: 'sd.wav', casio_bd: 'cb.wav', boss_bd: 'bb.wav' }, 'https://a.test/')
            aliasBank({ roland: 'tr' })
            aliasBank('casio', ['cas', 'cs'])
            aliasBank('https://x.test/alias.json')
            """.trimIndent(),
        )
        for (name in listOf("tr_bd", "tr_sd", "cas_bd", "cs_bd", "r_bd", "rr_sd")) assertEquals(true, w.library.sound(name) != null, name)
        assertEquals(w.library.sound("roland_bd"), w.library.sound("tr_bd"))
        assertNull(w.library.sound("tr_bb"))
    }

    @Test
    fun aliasBankChecksTheNumberOfArguments() {
        val w = world()
        assertEquals("aliasMap expects 1 or 2 arguments, received 3", w.failure("aliasBank('a', 'b', 'c')"))
        assertEquals("aliasMap expects 1 or 2 arguments, received 0", w.failure("aliasBank()"))
    }

    // ---- getDuration --------------------------------------------------------------------------------------------------------

    @Test
    fun getDurationIsTheLengthOfASampleInSeconds() {
        val w = world(lengths = mapOf("https://a.test/s0.wav" to 0.5, "https://a.test/s1.wav" to 2.0))
        val haps = w.play(
            """
            samples({ sax: ['s0.wav', 's1.wav'] }, 'https://a.test/')
            let k = await getDuration('sax')
            let j = await getDur('sax', 1)
            s("sax").cps(1/k).speed(j)
            """.trimIndent(),
        )
        assertEquals(listOf("[ 0/1 → 1/1 | s:sax cps:2 speed:2 ]"), haps)
    }

    @Test
    fun getDurationOfASoundThatIsNotLoadedIsAnError() {
        val w = world()
        assertEquals("sound nothing not found! Is it loaded?", w.failure("getDuration('nothing')"))
    }

    // ---- tables -------------------------------------------------------------------------------------------------------------

    @Test
    fun tablesRegistersWavFilesAsWavetableBanksAndSkipsTheRest() {
        val w = world()
        w.run("tables('https://t.test/waves/', 1024, { wt_a: ['a.wav', 'a.txt'], wt_b: 'b.WAV', other: ['c.mp3'] })")
        val a = assertIs<WaveTableBank>(w.library.sound("wt_a"))
        assertEquals(1024, a.frameLen)
        assertEquals(listOf("https://t.test/waves/a.wav"), a.groups.single().urls)
        assertEquals(listOf("https://t.test/waves/b.WAV"), assertIs<WaveTableBank>(w.library.sound("wt_b")).groups.single().urls)
        assertNull(w.library.sound("other"))
    }

    @Test
    fun tablesFromAUrlUsesItsBaseAndTheDefaultFrameLength() {
        val w = world(mapOf("https://raw.githubusercontent.com/u/r/main/strudel.json" to """{ "_base": "github:u/r/", "wt_x": ["x.wav"] }"""))
        w.run("await tables('github:u/r')")
        val bank = assertIs<WaveTableBank>(w.library.sound("wt_x"))
        assertEquals(2048, bank.frameLen)
        assertEquals(listOf("https://raw.githubusercontent.com/u/r/main/x.wav"), bank.groups.single().urls)
    }

    @Test
    fun tablesThatCannotBeLoadedAreAnError() {
        val w = world()
        assertEquals("error loading \"https://t.test/strudel.json\"", w.failure("tables('https://t.test/strudel.json')"))
        assertEquals("wrong json format for wt_a", world().failure("tables('https://t.test/', 2048, { wt_a: 3 })"))
    }

    // ---- setSoundfontUrl ----------------------------------------------------------------------------------------------------

    @Test
    fun setSoundfontUrlChangesWhereTheFontsComeFrom() {
        val w = world()
        w.run("setSoundfontUrl('https://fonts.test/sound')")
        assertEquals("https://fonts.test/sound", w.library.soundfontUrl)
    }

    // ---- piano --------------------------------------------------------------------------------------------------------------

    @Test
    fun pianoSetsTheSoundTheClipTheReleaseAndPansByPitch() {
        val w = world()
        assertEquals(
            listOf(
                "[ 0/1 → 1/2 | note:c4 clip:1 s:piano release:0.1 pan:0.5277777777777778 ]",
                "[ 1/2 → 1/1 | note:c8 clip:1 s:piano release:0.1 pan:0.75 ]",
            ),
            w.play("""note("c4 c8").piano()"""),
        )
    }

    @Test
    fun pianoKeepsTheClipAndTheOldPanOfTheValue() {
        val w = world()
        assertEquals(
            listOf("[ 0/1 → 1/1 | note:c8 clip:2 pan:0.375 s:piano release:0.1 ]"),
            w.play("""note("c8").clip(2).pan(0.5).piano()"""),
        )
    }
}
