package com.octopusman.cosic.audio

import cosic.sound.UserImport
import kotlin.test.Test
import kotlin.test.assertEquals

class TreeWalkTest {
    /** A folder tree in memory: ids are paths, a folder's children are the entries whose parent it is. */
    private class Tree(vararg entries: String) {
        private val all = entries.toList()

        fun children(id: String): List<TreeEntry> {
            val prefix = if (id.isEmpty()) "" else "$id/"
            return all.filter { it != prefix && it.startsWith(prefix) && '/' !in it.removePrefix(prefix).trimEnd('/') }.map {
                val name = it.removePrefix(prefix).trimEnd('/')
                TreeEntry("$prefix$name", name, isDirectory = it.endsWith("/"))
            }
        }
    }

    private fun walk(tree: Tree) = walkTree("mykit", "", tree::children) { "doc:$it" }

    @Test
    fun everyAudioFileAtAnyDepthIsFoundWithAPathThatStartsWithThePickedFolder() {
        val files = walk(
            Tree("a.wav", "b.MP3", "readme.txt", "kick/", "kick/k1.wav", "kick/k2.ogg", "nested/", "nested/deep/", "nested/deep/x.flac", "nested/deep/y.pdf"),
        )

        assertEquals(
            listOf("mykit/a.wav", "mykit/b.MP3", "mykit/kick/k1.wav", "mykit/kick/k2.ogg", "mykit/nested/deep/x.flac").sorted(),
            files.map { it.relativePath }.sorted(),
        )
        assertEquals("doc:kick/k1.wav", files.first { it.title == "k1.wav" }.url)
    }

    @Test
    fun theBanksAreTheFoldersOfTheFilesTheirTitlesInOrder() {
        val files = walk(Tree("b.wav", "a.wav", "kick/", "kick/c.wav", "kick/a.wav", "kick/b.wav", "deep/", "deep/er/", "deep/er/x.wav"))

        val banks = UserImport.banks(files)

        assertEquals(listOf("mykit", "kick", "er").toSet(), banks.map { it.name }.toSet())
        assertEquals(listOf("a.wav", "b.wav", "c.wav"), banks.first { it.name == "kick" }.files.map { it.title })
        assertEquals(listOf("a.wav", "b.wav"), banks.first { it.name == "mykit" }.files.map { it.title })
    }

    @Test
    fun aFolderWithoutAudioMakesNoBank() {
        assertEquals(emptyList(), UserImport.banks(walk(Tree("docs/", "docs/a.txt", "docs/b.pdf"))))
    }

    @Test
    fun aFolderThatListsItselfIsVisitedOnce() {
        val files = walkTree("loop", "root", children = { id ->
            if (id == "root") listOf(TreeEntry("root", "again", true), TreeEntry("f", "f.wav", false)) else emptyList()
        }, urlOf = { "doc:$it" })

        assertEquals(listOf("loop/f.wav"), files.map { it.relativePath })
    }

    @Test
    fun folderLevelsBeyondTheLimitAreNotFollowed() {
        // 40 folders deep: the file at the bottom is out of reach, the one at level 5 is not
        val files = walkTree("deep", "0", children = { id ->
            val level = id.toInt()
            listOf(TreeEntry("${level + 1}", "d${level + 1}", true)) + if (level == 5 || level == 39) listOf(TreeEntry("f$level", "f$level.wav", false)) else emptyList()
        }, urlOf = { "doc:$it" })

        assertEquals(1, files.size)
        assertEquals("deep/d1/d2/d3/d4/d5/f5.wav", files.single().relativePath)
    }
}
