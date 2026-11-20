package com.octopusman.cosic.audio

import cosic.BankGroup
import cosic.BuiltinSound
import cosic.NoSampleStore
import cosic.SampleBank
import cosic.WaveTableBank
import cosic.sound.ImportedFile
import cosic.sound.RegistryLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class ImportedBanksTest {
    private val library = RegistryLibrary(NoSampleStore, setOf("sine", "bd"))
    private val imported = ImportedBanks(library)

    private fun file(path: String, url: String = "doc:$path") = ImportedFile(path, path.substringAfterLast('/'), url)

    private fun tree(id: String, vararg paths: String) = ImportedTree(id, paths.map { file("$id/$it") })

    private fun urls(name: String) = (library.sound(name) as SampleBank).groups.single().urls

    private fun defaultBank(name: String) = SampleBank(name, listOf(BankGroup(null, null, listOf("net:$name"))), "drum-machines", prebake = true).also(library::register)

    @Test
    fun everyFolderWithAudioIsABankNamedAfterItAtAnyDepth() {
        imported.replaceAll(listOf(tree("t", "kick/a.wav", "kick/b.wav", "deep/er/snare/c.wav", "top.wav", "notes.txt")))

        assertEquals(listOf("doc:t/kick/a.wav", "doc:t/kick/b.wav"), urls("kick"))
        assertEquals(listOf("doc:t/deep/er/snare/c.wav"), urls("snare"))
        assertEquals(listOf("doc:t/top.wav"), urls("t")) // a file in the picked folder: the folder is its bank
        assertEquals("t", imported.treeOf("kick"))
        assertNull(imported.treeOf("deep"))
    }

    @Test
    fun aFolderNameBecomesTheRegistryKeyAndIsPlayedByIt() {
        imported.replaceAll(listOf(tree("t", "My Drums/a.wav")))

        assertEquals(listOf("doc:t/My Drums/a.wav"), urls("my_drums"))
        assertEquals("t", imported.treeOf("my_drums"))
    }

    @Test
    fun aBankReplacesTheDefaultBankOfThatNameAndTheDefaultComesBackWhenTheFolderGoes() {
        val original = defaultBank("bd")

        imported.replaceAll(listOf(tree("t", "bd/mine.wav")))
        assertEquals(listOf("doc:t/bd/mine.wav"), urls("bd"))

        imported.replaceAll(emptyList())
        assertSame(original, library.sound("bd"))
        assertNull(imported.treeOf("bd"))
    }

    @Test
    fun aLaterFolderWinsTheNameOfAnEarlierOneAndRemovingItGivesTheNameBack() {
        val original = defaultBank("bd")
        val first = tree("one", "bd/a.wav")
        val second = tree("two", "bd/b.wav")

        imported.replaceAll(listOf(first, second))
        assertEquals(listOf("doc:two/bd/b.wav"), urls("bd"))
        assertEquals("two", imported.treeOf("bd"))

        imported.replaceAll(listOf(first))
        assertEquals(listOf("doc:one/bd/a.wav"), urls("bd"))

        imported.replaceAll(emptyList())
        assertSame(original, library.sound("bd"))
    }

    @Test
    fun aSoundThatOnlyTheFoldersMadeIsGoneWithThem() {
        imported.replaceAll(listOf(tree("t", "mykit/a.wav")))
        imported.replaceAll(emptyList())

        assertNull(library.sound("mykit"))
        assertNull(library.sound("t"))
    }

    @Test
    fun aBankCanTakeTheNameOfAnEngineSoundAndTheEngineSoundIsBehindItAgainWhenItGoes() {
        imported.replaceAll(listOf(tree("t", "sine/a.wav")))
        assertIs<SampleBank>(library.sound("sine"))

        imported.replaceAll(emptyList())
        assertIs<BuiltinSound>(library.sound("sine"))
    }

    @Test
    fun aNameThatWasFoundUnderAnAliasComesBackAsAnAlias() {
        val machine = SampleBank("RolandTR909_bd", listOf(BankGroup(null, null, listOf("net:a"))), null, prebake = true)
        library.register(machine)
        library.aliasBank("RolandTR909", "TR909")
        assertSame(machine, library.sound("tr909_bd"))

        imported.replaceAll(listOf(tree("t", "tr909_bd/mine.wav")))
        assertEquals(listOf("doc:t/tr909_bd/mine.wav"), urls("tr909_bd"))
        assertSame(machine, library.sound("RolandTR909_bd"))

        imported.replaceAll(emptyList())
        assertSame(machine, library.sound("tr909_bd"))
        assertSame(machine, library.sound("RolandTR909_bd"))
    }

    @Test
    fun aWtFolderIsAWavetableBank() {
        imported.replaceAll(listOf(tree("t", "wt_mine/a.wav", "WT_Upper/b.wav")))

        assertIs<WaveTableBank>(library.sound("wt_mine"))
        assertIs<SampleBank>(library.sound("wt_upper")) // the rule reads the name as written: upper case is a bank of samples
    }

    @Test
    fun doingItAgainWithTheSameFoldersChangesNothingTheListSees() {
        val trees = listOf(tree("t", "kick/a.wav"))
        imported.replaceAll(trees)
        val names = library.entries().map { it.name }

        imported.replaceAll(trees)

        assertEquals(names, library.entries().map { it.name })
        assertEquals(listOf("doc:t/kick/a.wav"), urls("kick"))
    }

    @Test
    fun theBanksAreNotMarkedAsComingFromTheDefaultLibrary() {
        imported.replaceAll(listOf(tree("t", "kick/a.wav")))

        val kick = library.sound("kick") as SampleBank
        assertEquals(false, kick.prebake)
        assertNull(kick.tag)
    }
}
