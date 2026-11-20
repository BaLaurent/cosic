package cosic.sound

import java.text.Collator
import java.util.Locale

/**
 * An audio file in a folder the user imported. [relativePath] has `/` separators and starts with the name of the picked folder
 * (`mydrums/kick/a.wav`, like `webkitRelativePath`); [title] is the file name with its extension; [url] is whatever the
 * sample store loads the file from.
 */
class ImportedFile(val relativePath: String, val title: String, val url: String)

/**
 * A bank made from imported files: [name] is the key it is registered under (see [registryKey]), [sourceKey] the folder name as it
 * was (a `wt_` folder makes a wavetable bank only when written in lower case, as in Strudel), [files] in the order they play as `n`.
 */
class ImportedBank(val name: String, val files: List<ImportedFile>, val sourceKey: String = name)

/**
 * How Strudel turns a folder of sound files into banks (`registerSamplesFromDB`, website/src/repl/idbutils.mjs:33-77): the bank of a
 * file is its immediate parent folder, at any depth, the picked folder included; a file with no folder (a path of one part) is a bank
 * named by the part of its file name before the first character that is not a letter, digit or `_`. Files of one folder become one
 * bank, sorted by title (`localeCompare`), and the banks come in the order of the first of their files in a sort of all titles that
 * counts digit runs as numbers and ignores case.
 *
 * Differences: the file types that count are wav, mp3, ogg, flac, m4a, aac and opus in any case (Strudel's list in lower case is
 * shorter); sorting uses the collation rules of Java for English, not those of the browser; a bank without a name is left out.
 */
object UserImport {
    private val EXTENSIONS = setOf("wav", "mp3", "ogg", "flac", "m4a", "aac", "opus")
    private val NON_WORD = Regex("\\W+")
    private val primary: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
    private val tertiary: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.TERTIARY }

    /** Whether [title] has the extension of a sound file the import accepts. */
    fun isAudioFile(title: String): Boolean = title.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** The banks of [files], in the order described above. Files that are not audio are skipped; two files of one bank with one title count as one. */
    fun banks(files: List<ImportedFile>): List<ImportedBank> {
        val sorted = files.filter { isAudioFile(it.title) }.sortedWith { a, b -> naturalCompare(a.title, b.title) }
        val byFolder = LinkedHashMap<String, LinkedHashMap<String, ImportedFile>>()
        for (file in sorted) {
            val parts = file.relativePath.split('/')
            val folder = if (parts.size >= 2) parts[parts.size - 2] else file.title.split(NON_WORD).first()
            byFolder.getOrPut(folder) { LinkedHashMap() }[file.title] = file
        }
        val banks = ArrayList<ImportedBank>(byFolder.size)
        for ((folder, byTitle) in byFolder) {
            if (folder.isBlank()) continue
            val ordered = byTitle.values.sortedWith { a, b -> tertiary.compare(a.title, b.title).let { if (it != 0) it else a.title.compareTo(b.title) } }
            banks.add(ImportedBank(registryKey(folder), ordered, folder))
        }
        return banks
    }

    /** `a.localeCompare(b, undefined, {numeric: true, sensitivity: 'base'})`: digit runs by value, letters without regard to case or accents; ties stay as they were (the sort is stable). */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val digitA = a[i] in '0'..'9'
            val digitB = b[j] in '0'..'9'
            if (digitA != digitB) return if (digitA) -1 else 1
            val endA = runEnd(a, i, digitA)
            val endB = runEnd(b, j, digitB)
            if (digitA) {
                val numberA = a.substring(i, endA).trimStart('0')
                val numberB = b.substring(j, endB).trimStart('0')
                if (numberA.length != numberB.length) return numberA.length.compareTo(numberB.length)
                val byDigits = numberA.compareTo(numberB)
                if (byDigits != 0) return byDigits
            } else {
                val byLetters = primary.compare(a.substring(i, endA), b.substring(j, endB))
                if (byLetters != 0) return byLetters
            }
            i = endA
            j = endB
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    /** The end of the run of digits (or of non-digits) that starts at [from]. */
    private fun runEnd(s: String, from: Int, digits: Boolean): Int {
        var end = from
        while (end < s.length && (s[end] in '0'..'9') == digits) end++
        return end
    }
}
