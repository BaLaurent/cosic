package cosic.sound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserImportTest {
    private fun file(path: String) = ImportedFile(path, path.substringAfterLast('/'), "url:$path")

    private fun banks(vararg paths: String) = UserImport.banks(paths.map(::file))

    private fun ImportedBank.titles() = files.map { it.title }

    @Test
    fun aFileBelongsToItsImmediateParentFolderAtAnyDepth() {
        val banks = banks("mydrums/kick/b.wav", "mydrums/kick/a.wav", "mydrums/snare/s.wav", "mydrums/top.wav", "lonely.wav", "My Dir/x.mp3")
        assertEquals(
            mapOf("kick" to listOf("a.wav", "b.wav"), "snare" to listOf("s.wav"), "mydrums" to listOf("top.wav"), "lonely" to listOf("lonely.wav"), "my_dir" to listOf("x.mp3")),
            banks.associate { it.name to it.titles() },
        )
    }

    @Test
    fun banksComeInTheOrderOfTheFirstOfTheirFilesInTheSortOfAllTitles() {
        val banks = banks("mydrums/kick/b.wav", "mydrums/kick/a.wav", "mydrums/snare/s.wav", "mydrums/top.wav", "lonely.wav", "My Dir/x.mp3")
        assertEquals(listOf("kick", "lonely", "snare", "mydrums", "my_dir"), banks.map { it.name })
    }

    @Test
    fun theGlobalSortCountsDigitsAsNumbersAndIgnoresCase() {
        val banks = banks("x/b10.wav", "y/B2.wav", "z/a.wav")
        assertEquals(listOf("z", "y", "x"), banks.map { it.name }, "a, then b2 (not after b10, and B is b), then b10")
    }

    @Test
    fun theFilesOfABankAreSortedByPlainCollationNotByNumber() {
        val bank = banks("kit/2a.wav", "kit/B.wav", "kit/10a.wav", "kit/a.wav", "kit/1a.wav").single()
        assertEquals(listOf("10a.wav", "1a.wav", "2a.wav", "a.wav", "B.wav"), bank.titles(), "digits before letters, but 10a before 1a: the digits are not a number")
    }

    @Test
    fun theFileTypesThatCountAreAudio() {
        val bank = banks("k/a.WAV", "k/b.Mp3", "k/c.ogg", "k/d.flac", "k/e.m4a", "k/f.aac", "k/g.opus", "k/h.txt", "k/i", "k/j.wav.bak").single()
        assertEquals(listOf("a.WAV", "b.Mp3", "c.ogg", "d.flac", "e.m4a", "f.aac", "g.opus"), bank.titles())
        assertTrue(UserImport.isAudioFile("x.WAV"))
        assertFalse(UserImport.isAudioFile("x"))
        assertFalse(UserImport.isAudioFile("wav"))
    }

    @Test
    fun twoFilesOfOneBankWithOneTitleCountOnce() {
        val bank = banks("a/kit/k.wav", "b/kit/k.wav", "b/kit/j.wav").single()
        assertEquals(listOf("j.wav", "k.wav"), bank.titles())
        assertEquals("b/kit/k.wav", bank.files[1].relativePath, "the later one")
    }

    @Test
    fun aFileWithNoFolderIsABankNamedByTheStartOfItsFileName() {
        val banks = banks("kick-drum.wav", "snare_1.wav", "Hat.Closed.wav")
        assertEquals(setOf("kick", "snare_1", "hat"), banks.map { it.name }.toSet())
        assertEquals("kick", banks.first { it.name == "kick" }.sourceKey)
    }

    @Test
    fun theKeyIsRegisteredLowerCaseWithUnderscoresButTheSourceKeyIsAsWritten() {
        val bank = banks("My Drums/a.wav").single()
        assertEquals("my_drums", bank.name)
        assertEquals("My Drums", bank.sourceKey)
        val table = banks("wt_digital/a.wav", "WT_x/a.wav")
        assertEquals(listOf("wt_digital", "wt_x"), table.map { it.name })
        assertEquals(listOf("wt_digital", "WT_x"), table.map { it.sourceKey }, "the wavetable check is case-sensitive on the key as written")
    }

    @Test
    fun foldersThatDifferOnlyInCaseAreTwoBanksOfOneKeyAndTheLaterWinsWhenRegistered() {
        val banks = banks("Kick/a.wav", "kick/b.wav")
        assertEquals(listOf("kick", "kick"), banks.map { it.name })
        assertEquals(listOf("a.wav", "b.wav"), banks.map { it.titles().single() })
    }

    @Test
    fun aBankWithoutANameIsLeftOut() {
        assertEquals(emptyList(), banks("/a.wav", ".hidden.wav").map { it.name })
    }

    @Test
    fun theUrlOfAFileTravelsWithIt() {
        assertEquals(listOf("url:k/a.wav"), banks("k/a.wav").single().files.map { it.url })
    }
}
