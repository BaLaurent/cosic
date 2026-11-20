package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankKind
import com.octopusman.cosic.audio.BankSummary
import cosic.CosicEngine
import cosic.script.ReferenceEntry

/** The sound a reference item can be previewed as; [pitched] sounds are played at a note. */
internal data class SoundTarget(val name: String, val pitched: Boolean)

/** One line of the reference: a function of the language, or a sound that `s("name")` can play. */
internal data class ReferenceItem(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val signature: String,
    val doc: String,
    /** What the Insert button adds to the code: a line that plays when it is evaluated. */
    val snippet: String,
    /** Set for sounds only. */
    val sound: SoundTarget? = null,
)

/** The group heading and the one-line description of one kind of sound. */
internal class SoundText(val category: String, val doc: String)

internal class SoundTexts(
    val synth: SoundText,
    val drum: SoundText,
    val instrument: SoundText,
    val percussion: SoundText,
    val imported: SoundText,
)

private const val NOTES = "c3 e3 g3"

/**
 * The whole reference: the [functions] of the language, then the synths and drum kit of the engine
 * and the sample [banks], each with an example that plays it.
 */
internal fun referenceItems(functions: List<ReferenceEntry>, banks: List<BankSummary>, texts: SoundTexts): List<ReferenceItem> {
    fun sound(name: String, text: SoundText, pitched: Boolean, snippet: String) =
        ReferenceItem(name, emptyList(), text.category, "s(\"$name\")", text.doc, snippet, SoundTarget(name, pitched))

    fun notes(name: String) = "\$: note(\"$NOTES\").s(\"$name\")"

    fun bank(bank: BankSummary): ReferenceItem {
        val text = when (bank.kind) {
            BankKind.IMPORTED -> texts.imported
            BankKind.INSTRUMENT -> texts.instrument
            BankKind.PERCUSSION -> texts.percussion
        }
        val snippet = if (bank.pitched) notes(bank.name) else "\$: s(\"${bank.name}:0 ${bank.name}:1\")"
        return sound(bank.name, text, bank.pitched, snippet)
    }

    val functionItems = functions.map { ReferenceItem(it.name, it.aliases, it.category, it.signature, it.doc, it.snippet) }
    return functionItems +
        CosicEngine.SYNTHS.map { sound(it, texts.synth, pitched = true, snippet = notes(it)) } +
        CosicEngine.DRUMS.map { sound(it, texts.drum, pitched = false, snippet = "\$: s(\"$it*4\")") } +
        BankKind.entries.flatMap { kind -> banks.filter { it.kind == kind }.map(::bank) }
}

/** True when [query], ignoring case and surrounding spaces, occurs in the name, an alias or the category. */
internal fun ReferenceItem.matches(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() ||
        name.contains(needle, ignoreCase = true) ||
        category.contains(needle, ignoreCase = true) ||
        aliases.any { it.contains(needle, ignoreCase = true) }
}

/** The item whose name or alias is [word], the first one when a function and a sound share a name. */
internal fun List<ReferenceItem>.named(word: String): ReferenceItem? = firstOrNull { it.name == word || word in it.aliases }
