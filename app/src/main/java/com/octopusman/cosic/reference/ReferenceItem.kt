package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankKind
import com.octopusman.cosic.audio.BankSummary
import cosic.CosicEngine
import cosic.script.ReferenceEntry

/** The sound a reference item can be previewed as; [pitched] sounds are played at a note. */
internal data class SoundTarget(val name: String, val pitched: Boolean)

/** For a function that takes a name from a list (a sound, a scale): the group that lists them, and the button label. */
internal data class ValuesLink(val category: String, val label: String)

/** One line of the reference: a function of the language, a sound that `s("name")` can play, or a scale. */
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
    /** Set for functions whose argument is one of the names listed in another group. */
    val values: ValuesLink? = null,
)

/** The heading and the one-line description of one group of names. */
internal class GroupText(val category: String, val doc: String)

internal class ReferenceTexts(
    val synth: GroupText,
    val drum: GroupText,
    val instrument: GroupText,
    val percussion: GroupText,
    val imported: GroupText,
    val scale: GroupText,
    val showSounds: String,
    val showScales: String,
)

private const val NOTES = "c3 e3 g3"

/**
 * The whole reference: the [functions] of the language, then the synths and drum kit of the engine,
 * the sample [banks] and the [scales], each with an example that plays it. The functions that take
 * a sound or a scale name lead to the group that lists them.
 */
internal fun referenceItems(
    functions: List<ReferenceEntry>,
    banks: List<BankSummary>,
    scales: List<String>,
    texts: ReferenceTexts,
): List<ReferenceItem> {
    val links = mapOf(
        "s" to ValuesLink(texts.synth.category, texts.showSounds),
        "bank" to ValuesLink(texts.synth.category, texts.showSounds),
        "scale" to ValuesLink(texts.scale.category, texts.showScales),
    )

    fun sound(name: String, text: GroupText, pitched: Boolean, snippet: String) =
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

    fun scale(mode: String) = ReferenceItem(
        name = mode,
        aliases = emptyList(),
        category = texts.scale.category,
        signature = "scale(\"C:$mode\")",
        doc = texts.scale.doc,
        snippet = "\$: n(\"0 2 4 6\").scale(\"C:$mode\").s(\"triangle\")",
    )

    val functionItems = functions.map {
        ReferenceItem(it.name, it.aliases, it.category, it.signature, it.doc, it.snippet, values = links[it.name])
    }
    return functionItems +
        CosicEngine.SYNTHS.map { sound(it, texts.synth, pitched = true, snippet = notes(it)) } +
        CosicEngine.DRUMS.map { sound(it, texts.drum, pitched = false, snippet = "\$: s(\"$it*4\")") } +
        BankKind.entries.flatMap { kind -> banks.filter { it.kind == kind }.map(::bank) } +
        scales.map(::scale)
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

/** Where the reference opens: on one item, or at the top of a group. */
internal sealed interface OpenOn {
    data class Item(val item: ReferenceItem) : OpenOn

    data class Group(val category: String) : OpenOn
}

/**
 * Where the reference opens for what the cursor is on. Inside the parentheses of a function that
 * takes a sound or a scale, it opens on the list of those, since that is what is being typed there;
 * anywhere else on the item that is named.
 */
internal fun List<ReferenceItem>.openOn(hit: CursorHit?): OpenOn? {
    val item = hit?.let { named(it.name) } ?: return null
    val values = item.values
    return if (hit.insideCall && values != null) OpenOn.Group(values.category) else OpenOn.Item(item)
}

/**
 * The [categories] in the order of the jump chips: the sounds and scales first, since they are what is
 * looked up most while writing and come last in the list, then the [functionGroups].
 */
internal fun chipOrder(categories: List<String>, functionGroups: Set<String>): List<String> =
    categories.filter { it !in functionGroups } + categories.filter { it in functionGroups }
