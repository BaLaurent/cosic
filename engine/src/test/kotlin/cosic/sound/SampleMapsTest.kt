package cosic.sound

import cosic.SampleBank
import cosic.SampleMapException
import cosic.SampleMapOptions
import cosic.WaveTableBank
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SampleMapsTest {
    // ---------------------------------------------------------------------------------------------- mapUrl

    @Test
    fun githubPseudoUrlsAreTheRawStrudelJsonOfTheRepository() {
        assertEquals("https://raw.githubusercontent.com/tidalcycles/dirt-samples/main/strudel.json", SampleMaps.mapUrl("github:tidalcycles/dirt-samples"))
        assertEquals("https://raw.githubusercontent.com/u/samples/main/strudel.json", SampleMaps.mapUrl("github:u"))
        assertEquals("https://raw.githubusercontent.com/cleary/samples-flbass/main/strudel.json", SampleMaps.mapUrl("github:cleary/samples-flbass/main/"))
        assertEquals("https://raw.githubusercontent.com/u/r/b/sub/dir/strudel.json", SampleMaps.mapUrl("github:u/r/b/sub/dir"))
        assertEquals("https://raw.githubusercontent.com/u/r/dev/strudel.json", SampleMaps.mapUrl("github:u/r/dev"))
    }

    @Test
    fun buboNamesAreTheDoughRepositoriesOfBubobubobubobubo() {
        assertEquals(
            "https://raw.githubusercontent.com/Bubobubobubobubo/dough-waveforms/main/strudel.json",
            SampleMaps.mapUrl("bubo:waveforms"),
        )
        assertEquals("github:Bubobubobubobubo/dough-x", SampleMaps.resolveSpecialPaths("bubo:x:ignored"))
        assertEquals("https://h/a", SampleMaps.resolveSpecialPaths("https://h/a"))
    }

    @Test
    fun localMeansTheSampleServerOfTheDevMachine() {
        assertEquals("http://localhost:5432", SampleMaps.mapUrl("local:x"))
        assertEquals("http://localhost:5432", SampleMaps.mapUrl("local:"))
    }

    @Test
    fun shabdaUrls() {
        assertEquals("https://shabda.ndre.gr/bass:4,hihat:4.json?strudel=1", SampleMaps.mapUrl("shabda:bass:4,hihat:4"))
        assertEquals("https://shabda.ndre.gr/speech/hello.json?gender=f&language=en-GB&strudel=1'", SampleMaps.mapUrl("shabda/speech:hello"))
        assertEquals("https://shabda.ndre.gr/speech/bonjour.json?gender=m&language=fr-FR&strudel=1'", SampleMaps.mapUrl("shabda/speech/fr-FR/m:bonjour"))
        assertEquals("https://shabda.ndre.gr/speech/hi.json?gender=undefined&language=fr-FR&strudel=1'", SampleMaps.mapUrl("shabda/speech/fr-FR:hi"), "a missing gender reads undefined, as in Strudel")
    }

    @Test
    fun anythingElseIsTheUrlItself() {
        assertEquals("https://example.com/x/strudel.json", SampleMaps.mapUrl("https://example.com/x/strudel.json"))
    }

    @Test
    fun githubPathNeedsTheGithubPrefixAndCanAddASubpath() {
        assertEquals("https://raw.githubusercontent.com/u/r/b/sub/f.json", SampleMaps.githubPath("github:u/r/b/sub", "f.json"))
        assertEquals("https://raw.githubusercontent.com/u/r/b/", SampleMaps.githubPath("github:u/r/b"))
        assertFailsWith<IllegalArgumentException> { SampleMaps.githubPath("u/r") }
    }

    // --------------------------------------------------------------------------------------------- baseUrlOf

    @Test
    fun theBaseOfAUrlIsItsDirectoryWithoutTheTrailingSlash() {
        assertEquals("https://x/a", SampleMaps.baseUrlOf("https://x/a/b.json"))
        assertEquals("https://x", SampleMaps.baseUrlOf("https://x/b.json"))
        assertEquals("http://localhost:5432", SampleMaps.baseUrlOf("http://localhost:5432"))
        assertEquals("https://x/a", SampleMaps.baseUrlOf("https://x/a/b.json?q=1/2#frag"))
        assertEquals("https://x/a", SampleMaps.baseUrlOf("https://x/a/"))
        assertEquals("https://x/c", SampleMaps.baseUrlOf("https://x/a/../c/d.json"), "dot segments are resolved as a URL does")
        assertEquals("https://shabda.ndre.gr", SampleMaps.baseUrlOf("https://shabda.ndre.gr/bass:4.json?strudel=1"))
        // the next ones are what getBaseURL of the pinned util.mjs answers (run in Node)
        assertEquals("https://shabda.ndre.gr/speech", SampleMaps.baseUrlOf("https://shabda.ndre.gr/speech/hello.json?gender=f&language=en-GB&strudel=1'"))
        assertEquals("https://x", SampleMaps.baseUrlOf("https://x"))
        assertEquals("https://x", SampleMaps.baseUrlOf("https://x/"))
        assertEquals("https://x/a/b", SampleMaps.baseUrlOf("https://x/a/./b/c.json"))
        assertEquals("https://x/a", SampleMaps.baseUrlOf("https://x/a/b/.."))
        assertEquals("file:///x", SampleMaps.baseUrlOf("file:///x/y.json"))
        assertEquals("https://x//a/", SampleMaps.baseUrlOf("https://x//a//b.json"), "empty segments are kept, and only one trailing slash goes")
        assertEquals("https://raw.githubusercontent.com/u/r/b/sub/dir", SampleMaps.baseUrlOf("https://raw.githubusercontent.com/u/r/b/sub/dir/strudel.json"))
    }

    @Test
    fun theBaseOfAPseudoUrlIsAllButItsLastPart() {
        assertEquals("github:u/r/b", SampleMaps.baseUrlOf("github:u/r/b/strudel.json"))
        assertEquals("", SampleMaps.baseUrlOf("strudel.json"))
    }

    // --------------------------------------------------------------------------------------------- process

    private fun process(map: Map<String, Any?>, base: String = "", options: SampleMapOptions = SampleMapOptions()) =
        SampleMaps.process(map, base, options)

    @Test
    fun aStringOrListOfFilesIsOneUnpitchedGroupUnderTheBase() {
        val entries = process(linkedMapOf("bd" to "x.wav", "sd" to listOf("a.wav", "b.wav")), "https://h/")
        val bd = assertIs<SampleBank>(entries[0])
        assertEquals("bd", bd.name)
        assertFalse(bd.pitched)
        assertEquals(listOf("https://h/x.wav"), bd.groups.single().urls)
        assertNull(bd.groups.single().noteName)
        assertEquals(listOf("https://h/a.wav", "https://h/b.wav"), assertIs<SampleBank>(entries[1]).groups.single().urls)
    }

    @Test
    fun aMapFromNotesToFilesIsAPitchedBankInTheOrderOfTheMap() {
        val entries = process(linkedMapOf("piano" to linkedMapOf("c4" to "c4.mp3", "A#0" to listOf("a.mp3", "b.mp3"), "_base" to "P/")), "B/")
        val piano = assertIs<SampleBank>(entries.single())
        assertTrue(piano.pitched)
        assertEquals(listOf("c4", "A#0"), piano.groups.map { it.noteName }, "the _base key is left out and the order is kept")
        assertEquals(listOf(60.0, 22.0), piano.groups.map { it.midi })
        assertEquals(listOf("P/c4.mp3"), piano.groups[0].urls, "the _base of the map is the base of its files")
        assertEquals(listOf("P/a.mp3", "P/b.mp3"), piano.groups[1].urls)
        assertEquals(3, piano.count)
    }

    @Test
    fun aKeyThatIsNoNoteGetsNaNAndFailsOnlyWhenPlayed() {
        val bank = assertIs<SampleBank>(process(linkedMapOf("x" to linkedMapOf("zz" to "z.wav"))).single())
        assertTrue(bank.groups.single().midi!!.isNaN())
    }

    @Test
    fun aBanksOwnBaseAlsoHoldsForTheBanksAfterIt() {
        val entries = process(
            linkedMapOf("a" to linkedMapOf("_base" to "B1/", "c4" to "a.wav"), "b" to listOf("b.wav")),
            "B0/",
        )
        assertEquals(listOf("B1/a.wav"), assertIs<SampleBank>(entries[0]).groups.single().urls)
        assertEquals(listOf("B1/b.wav"), assertIs<SampleBank>(entries[1]).groups.single().urls, "Strudel reassigns the one variable")
    }

    @Test
    fun theTopLevelBaseKeyIsNotASound() {
        val entries = process(linkedMapOf("_base" to "https://h/", "bd" to "x.wav"), "https://h/")
        assertEquals(listOf("bd"), entries.map { it.name })
    }

    @Test
    fun githubAndBuboBasesBecomeRawUrls() {
        val entries = process(linkedMapOf("bd" to "x.wav"), "github:u/r/b/sub")
        assertEquals(listOf("https://raw.githubusercontent.com/u/r/b/sub/x.wav"), assertIs<SampleBank>(entries.single()).groups.single().urls)
        val bubo = process(linkedMapOf("bd" to "x.wav"), "bubo:waveforms")
        assertEquals(listOf("https://raw.githubusercontent.com/Bubobubobubobubo/dough-waveforms/main/x.wav"), assertIs<SampleBank>(bubo.single()).groups.single().urls)
    }

    @Test
    fun aKeyThatStartsWithWtIsAWavetableBank() {
        val entries = process(linkedMapOf("wt_a" to listOf("w.wav"), "WT_b" to listOf("w.wav"), "x_wt" to "w.wav"))
        val table = assertIs<WaveTableBank>(entries[0])
        assertEquals(2048, table.frameLen)
        assertEquals(listOf("w.wav"), table.groups.single().urls)
        assertIs<SampleBank>(entries[1], "the check is case-sensitive on the key as written")
        assertIs<SampleBank>(entries[2])
    }

    @Test
    fun theOptionsReachEveryEntry() {
        val entries = process(linkedMapOf("a" to "a.wav", "wt_b" to "b.wav"), "", SampleMapOptions(prebake = true, tag = "drum-machines"))
        for (entry in entries) {
            assertTrue(entry.prebake)
            assertEquals("drum-machines", entry.tag)
        }
        assertFalse(process(linkedMapOf("a" to "a.wav")).single().prebake)
    }

    @Test
    fun aValueOfAnyOtherKindIsAFormatError() {
        assertEquals("wrong sample map format for k", assertFailsWith<SampleMapException> { process(linkedMapOf("k" to 5.0)) }.message)
        assertEquals("wrong sample map format for k", assertFailsWith<SampleMapException> { process(linkedMapOf("k" to null)) }.message)
        assertEquals("wrong sample map format for k", assertFailsWith<SampleMapException> { process(linkedMapOf("k" to linkedMapOf("c4" to 5.0))) }.message)
    }

    @Test
    fun theBanksBeforeAWrongOneAreKeptByProcessInto() {
        val out = ArrayList<cosic.SoundEntry>()
        assertFailsWith<SampleMapException> { SampleMaps.processInto(linkedMapOf("a" to "a.wav", "b" to 1.0, "c" to "c.wav"), "", SampleMapOptions(), out) }
        assertContentEquals(listOf("a"), out.map { it.name })
    }
}
