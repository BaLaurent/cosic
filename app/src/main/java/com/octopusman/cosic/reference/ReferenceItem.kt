package com.octopusman.cosic.reference

import android.net.Uri
import com.octopusman.cosic.audio.BankKind
import com.octopusman.cosic.audio.BankSummary
import cosic.CosicEngine
import cosic.script.ReferenceEntry

/**
 * The sound a reference item can be previewed as; [pitched] sounds are played at a note. [count] is
 * how many samples `name:n` can pick from, null for a synth or a drum; [variants] is how many variants
 * a drum has, each of which can be previewed on its own, 0 for any other sound.
 */
internal data class SoundTarget(val name: String, val pitched: Boolean, val count: Int?, val variants: Int = 0)

/** The two tabs of the panel: the functions and scales of the language, and the sounds. */
internal enum class PanelTab { REFERENCE, SOUNDS }

/** For a function that takes a name from a list (a sound, a scale): the group that lists them, and the button label. */
internal data class ValuesLink(val category: String, val label: String)

/** One line of the reference: a function of the language, a sound that `s("name")` can play, a drum machine `bank("name")` can pick, or a scale. */
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
    /** Set for drum machines: the sounds `bank(name)` can play, in display order. */
    val members: List<String> = emptyList(),
    /** Set for sounds imported from a folder: that folder, which the Remove button takes away. */
    val folder: Uri? = null,
)

/** The heading and the one-line description of one group of names. */
internal class GroupText(val category: String, val doc: String)

internal class ReferenceTexts(
    val drumMachines: GroupText,
    val synth: GroupText,
    val drum: GroupText,
    val instrument: GroupText,
    val percussion: GroupText,
    val imported: GroupText,
    val scale: GroupText,
    val showSounds: String,
    val showDrumMachines: String,
    val showScales: String,
)

private const val NOTES = "c3 e3 g3"

/**
 * The whole reference: the [functions] of the language, then the drum machines, the synths and drum kit
 * of the engine, the sample [banks] and the [scales], each with an example that plays it. The functions
 * that take a sound, a drum machine or a scale name lead to the group that lists them.
 */
internal fun referenceItems(
    functions: List<ReferenceEntry>,
    banks: List<BankSummary>,
    scales: List<String>,
    texts: ReferenceTexts,
): List<ReferenceItem> {
    val links = mapOf(
        "s" to ValuesLink(texts.synth.category, texts.showSounds),
        "bank" to ValuesLink(texts.drumMachines.category, texts.showDrumMachines),
        "scale" to ValuesLink(texts.scale.category, texts.showScales),
    )

    fun sound(name: String, text: GroupText, pitched: Boolean, count: Int?, snippet: String, variants: Int = 0) =
        ReferenceItem(name, emptyList(), text.category, "s(\"$name\")", text.doc, snippet, SoundTarget(name, pitched, count, variants))

    fun notes(name: String) = "\$: note(\"$NOTES\").s(\"$name\")"

    fun bank(bank: BankSummary): ReferenceItem {
        val text = when (bank.kind) {
            BankKind.IMPORTED -> texts.imported
            BankKind.INSTRUMENT -> texts.instrument
            BankKind.PERCUSSION -> texts.percussion
        }
        val snippet = if (bank.pitched) notes(bank.name) else "\$: s(\"${bank.name}:0 ${bank.name}:1\")"
        return sound(bank.name, text, bank.pitched, bank.count, snippet).copy(folder = bank.treeUri)
    }

    fun kit(kit: Kit) = ReferenceItem(
        name = kit.name,
        aliases = emptyList(),
        category = texts.drumMachines.category,
        signature = "bank(\"${kit.name}\")",
        doc = texts.drumMachines.doc,
        snippet = kit.snippet(),
        sound = SoundTarget(kit.previewSound(), pitched = false, count = null),
        members = kit.sounds,
        folder = kit.folder,
    )

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
    // The sounds of a kit are listed with the kit, not again as banks of their own.
    val samples = banks.filter { it.kit == null }
    val kitItems = kits(CosicEngine.DRUM_MACHINES, banks).map(::kit)
    // A kit the user imported also shows under Imported, where the folder was imported and can be removed.
    val importedKits = kitItems.filter { it.folder != null }.map { it.copy(category = texts.imported.category, doc = texts.imported.doc) }
    return functionItems +
        kitItems +
        CosicEngine.SYNTHS.map { sound(it, texts.synth, pitched = true, count = null, snippet = notes(it)) } +
        CosicEngine.DRUMS.map {
            sound(it, texts.drum, pitched = false, count = null, snippet = "\$: s(\"$it*4\")", variants = CosicEngine.DRUM_VARIANTS)
        } +
        BankKind.entries.flatMap { kind -> samples.filter { it.kind == kind }.map(::bank) } +
        importedKits +
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

/** A line of a list: a group heading, the group's description (sounds only), the import controls, or an item. */
internal sealed interface ListRow {
    data class Heading(val category: String) : ListRow

    data class Doc(val category: String, val text: String) : ListRow

    /** The button that imports a folder, with its hint, in the group [category]. */
    data class Import(val category: String) : ListRow

    data class Entry(val item: ReferenceItem) : ListRow
}

/**
 * The lines of the list of the items that match [query], grouped under their headings in the order of
 * the items. The group [importCategory] is listed even when it has no sounds yet, with the import row
 * under its description; the import row is no search result, so it is left out while searching.
 */
internal fun List<ReferenceItem>.rows(query: String, importCategory: String? = null): List<ListRow> {
    val groups = filter { it.matches(query) }.groupBy { it.category }
    val importing = importCategory.takeIf { query.isBlank() }
    val listed = if (importing != null && importing !in groups) groups + (importing to emptyList()) else groups
    return listed.flatMap { (category, group) ->
        val doc = group.firstOrNull()?.takeIf { it.sound != null }?.doc
        listOfNotNull(
            ListRow.Heading(category),
            doc?.let { ListRow.Doc(category, it) },
            ListRow.Import(category).takeIf { category == importing },
        ) + group.map { ListRow.Entry(it) }
    }
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
 * takes a sound, a drum machine or a scale, it opens on the list of those, since that is what is being
 * typed there; anywhere else on the item that is named, such as a drum machine's name in `bank("…")`.
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

/** The items listed in [tab]. */
internal fun List<ReferenceItem>.inTab(tab: PanelTab): List<ReferenceItem> =
    filter { (it.sound != null) == (tab == PanelTab.SOUNDS) }

/** The tab that shows [openOn]. */
internal fun List<ReferenceItem>.tabFor(openOn: OpenOn?): PanelTab {
    val isSound = when (openOn) {
        is OpenOn.Item -> openOn.item.sound != null
        is OpenOn.Group -> any { it.category == openOn.category && it.sound != null }
        null -> false
    }
    return if (isSound) PanelTab.SOUNDS else PanelTab.REFERENCE
}

/**
 * The tab the panel opens on when the editor's [button] for that tab is tapped: the sounds whenever
 * the cursor is on a sound or in `s(…)`/`bank(…)`, since that is what is being looked for, else the
 * tab of the button.
 */
internal fun List<ReferenceItem>.tabOnOpen(button: PanelTab, openOn: OpenOn?): PanelTab =
    if (tabFor(openOn) == PanelTab.SOUNDS) PanelTab.SOUNDS else button
