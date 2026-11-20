package com.octopusman.cosic.reference

import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One parameter of a documented function: its [name], the [types] it takes (`number`, `Pattern`...) and its description as the JSDoc's HTML. */
@Serializable
internal data class DocParam(val name: String, val types: List<String> = emptyList(), val description: String = "")

/** One `@example` of a function, [code] as written and the [snippet] that inserts it into a program (the `$:` label a program with labels needs). */
@Serializable
internal data class DocExample(val code: String, val snippet: String)

/**
 * The documentation of one function, as Strudel's JSDoc has it and its editor shows it in the card next to a suggestion (`Autocomplete` of
 * packages/codemirror/autocomplete.mjs): the name, the other names ([synonyms]), the [description] as HTML, the [params] and the [examples].
 * [hidden] is for the functions whose card the editor shows but whose name it does not suggest.
 *
 * The cards are made from the JSDoc of the pinned Strudel by `tools/reference/build-reference.mjs` (`docs.json`, a resource of the engine).
 */
@Serializable
internal data class DocEntry(
    val name: String,
    val hidden: Boolean = false,
    val synonyms: List<String> = emptyList(),
    val description: String = "",
    val params: List<DocParam> = emptyList(),
    val examples: List<DocExample> = emptyList(),
) {
    /** The synonyms the card lists: the other names of the function (`Synonyms: density, ...`). */
    val synonymsText: String get() = synonyms.joinToString(", ")

    /** `getSynonymDoc(doc, synonym)`: the card of one of the names of this function: it is called [synonym], and the name of this one is a synonym of it. */
    fun asSynonym(synonym: String): DocEntry = copy(name = synonym, synonyms = (listOf(name) + synonyms).filter { it.isNotEmpty() && it != synonym })
}

/** The documentation cards, and how to find one. */
internal class DocIndex(val cards: List<DocEntry>) {
    private val byName: Map<String, DocEntry> = HashMap<String, DocEntry>().also { map -> for (card in cards) map.putIfAbsent(card.name, card) }
    private val bySynonym: Map<String, DocEntry> = HashMap<String, DocEntry>().also { map ->
        for (card in cards) for (synonym in card.synonyms) map.putIfAbsent(synonym, card)
    }

    /**
     * The card of the function [word] names, a name or a synonym: the first card of that name, else the first that has it as a synonym, shown under
     * that name (the lookup of the tooltip of Strudel's editor, tooltip.mjs:50-60). Null when [word] is no documented function.
     */
    fun lookup(word: String): DocEntry? = byName[word] ?: bySynonym[word]?.asSynonym(word)

    /**
     * The card of the function at [caret] in [text], for the "documentation at the cursor" action: the word the caret touches when a card has it, else the
     * innermost call the caret is inside of (`fast` for a caret in `.fast(2|)`); null when there is none.
     */
    fun cardAtCursor(text: String, caret: Int): DocEntry? = functionAtCursor(text, caret) { lookup(it) != null }?.let { lookup(it.name) }
}

internal object DocJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** The cards in [text], the JSON `docs.json` holds. */
    fun parse(text: String): List<DocEntry> = json.decodeFromString<List<DocEntry>>(text)

    fun parse(stream: InputStream): List<DocEntry> = parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })

    /** The cards of the app: the `docs.json` the engine carries. Read once, when first needed (a quarter of a megabyte of JSON: not on the main thread). */
    val cards: List<DocEntry> by lazy {
        val stream = DocJson::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("missing resource $RESOURCE (node tools/reference/build-reference.mjs makes it)")
        parse(stream)
    }

    /** The cards with an index to find one by its name. */
    val index: DocIndex by lazy { DocIndex(cards) }

    private const val RESOURCE = "/cosic/reference/docs.json"
}
