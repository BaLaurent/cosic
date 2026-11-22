package com.octopusman.cosic.reference

import java.text.Collator
import java.util.Locale
import kotlin.math.max

/** One thing the editor suggests: the [label] to write and the [card] to show next to it (the card of the function, under the name the label is). */
internal class Completion(val label: String, val card: DocEntry?)

/** A [completion] that matches the word typed, with the ranges ([matched], `from, to` pairs) of its label that the word matched, and how well ([score]). */
internal class Suggestion(val completion: Completion, val score: Int, val matched: IntArray) {
    val label: String get() = completion.label
    val card: DocEntry? get() = completion.card
}

/** The text and cursor after a suggestion was taken. */
internal class CompletionEdit(val text: String, val caret: Int)

/**
 * What can be suggested at a place of the code: the [suggestions] for the word in front of the cursor, which starts at [from] and ends at [to]; taking
 * one writes its label in place of the word.
 */
internal class Suggestions(val from: Int, val to: Int, val suggestions: List<Suggestion>) {
    /** [text] with the word replaced by the label of [suggestion] (as `apply: label` of CodeMirror does: no parentheses), the cursor after it. */
    fun apply(text: String, suggestion: Suggestion): CompletionEdit {
        val end = to.coerceIn(0, text.length)
        val start = from.coerceIn(0, end)
        return CompletionEdit(text.replaceRange(start, end, suggestion.label), start + suggestion.label.length)
    }
}

/**
 * The autocomplete of the editor, the way Strudel's is (packages/codemirror/autocomplete.mjs): the names of the documented functions and their synonyms
 * are offered for the word in front of the cursor, ranked as CodeMirror ranks them. The labels come from the [cards] in order, each name once (the
 * first card that has it wins), those of hidden cards left out, and only those [isDefined] accepts: the editor must not offer what a program cannot call.
 */
internal class Completer(
    cards: List<DocEntry>,
    /** The words of the arguments that have a list (sounds, banks, scales...); without it only functions are suggested. */
    private val vocabulary: Vocabulary? = null,
    /** Whether `pat.name(...)` exists: after a dot only methods are offered. */
    private val isMethod: (String) -> Boolean = { true },
    isDefined: (String) -> Boolean = { true },
) {
    private val byName: Map<String, DocEntry> = HashMap<String, DocEntry>().also { map -> for (card in cards) map.putIfAbsent(card.name, card) }

    /** Every label that can be suggested, in the order of the cards (`jsdocCompletions`). */
    val completions: List<Completion> = buildList {
        val seen = HashSet<String>()
        for (card in cards) {
            if (card.hidden) continue
            for (label in listOf(card.name) + card.synonyms) {
                if (label.isNotEmpty() && isDefined(label) && seen.add(label)) add(Completion(label, if (label == card.name) card else card.asSynonym(label)))
            }
        }
    }

    /**
     * The suggestions for the word ending at [caret] in [text]: the `\w` characters in front of it, on its line. Null when there is no word and the
     * list was not asked for ([explicit], Ctrl-Space), as when the cursor is in white space. The list is the 100 best matches, best first: by score, then by
     * the order of the labels in the language (`localeCompare` of CodeMirror), and for equal ones in the order of the cards.
     */
    fun suggest(text: String, caret: Int, explicit: Boolean = false): Suggestions? {
        val to = caret.coerceIn(0, text.length)
        val context = contextAt(text, to)
        if (context is CursorContext.Comment) return null
        if (context is CursorContext.Text) return suggestWords(text, to, context)
        val lineStart = max(text.lastIndexOf('\n', to - 1), text.lastIndexOf('\r', to - 1)) + 1
        val earliest = max(lineStart, to - LOOK_BEHIND)
        var from = to
        while (from > earliest && isWordCharacter(text[from - 1])) from--
        if (from == to && !explicit) return null
        val afterDot = (context as CursorContext.Code).afterDot
        val ranked = rank(text.substring(from, to))
        return Suggestions(from, to, (if (afterDot) ranked.filter { isMethod(it.label) } else ranked).take(MAX_OPTIONS))
    }

    /**
     * The words that fit the string argument the cursor is in: the sounds for `s("...")`, the banks for `.bank("...")`, the scales after the colon of
     * `.scale("C:...")`, the note names for `note("...")`... Null when the argument is none of those: a string is not a place for function names.
     */
    private fun suggestWords(text: String, to: Int, context: CursorContext.Text): Suggestions? {
        val vocabulary = vocabulary ?: return null
        val call = context.call ?: return null
        var kind = argKindOf(byName[call], call, context.argIndex) ?: return null
        var tokenStart = to
        while (tokenStart > 0 && isTokenCharacter(text[tokenStart - 1])) tokenStart--
        var from = tokenStart
        if (kind == ArgKind.SCALE) {
            // `C:minor:pentatonic`: the root is a note, what follows the first colon is the scale
            val colon = text.substring(tokenStart, to).indexOf(':')
            if (colon < 0) kind = ArgKind.NOTE else from = tokenStart + colon + 1
        } else {
            // the index after a sample name (`bd:3`) and the gain of a note are numbers, not words of the list
            if (text.substring(tokenStart, to).contains(':')) return null
        }
        val words = vocabulary.words(kind)
        val matcher = FuzzyMatcher(text.substring(from, to))
        val found = ArrayList<Suggestion>()
        for (word in words) {
            val match = matcher.match(word) ?: continue
            found.add(Suggestion(Completion(word, null), match.score, match.matched))
        }
        found.sortWith(RANKING)
        return Suggestions(from, to, found.take(MAX_OPTIONS))
    }

    /** Every label that matches [pattern], best first: see [suggest]. */
    fun rank(pattern: String): List<Suggestion> {
        val matcher = FuzzyMatcher(pattern)
        val found = ArrayList<Suggestion>()
        for (completion in completions) {
            val match = matcher.match(completion.label) ?: continue
            found.add(Suggestion(completion, match.score, match.matched))
        }
        found.sortWith(RANKING)
        return found
    }

    private companion object {
        /** `matchBefore` of CodeMirror looks at most this many characters back. */
        const val LOOK_BEHIND = 250

        /** `maxRenderedOptions` of CodeMirror: the list is never longer than this. */
        const val MAX_OPTIONS = 100

        /** `\w`: the characters of a word of JavaScript, which are ASCII ones only. */
        fun isWordCharacter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

        /** The characters of one word of a mini-notation string: names, notes (`c#3`) and the colon of `bd:3` and `C:minor`. */
        fun isTokenCharacter(c: Char): Boolean = isWordCharacter(c) || c == '#' || c == ':'

        /**
         * `compareCompletions` of CodeMirror: the labels in the order of the locale, `localeCompare`. The root locale gives that of the browser's
         * for the identifiers of the language (`FuzzyMatcherTest` compares the order of 700 of them with Node's).
         */
        val collator: Collator = Collator.getInstance(Locale.ROOT)

        val RANKING = Comparator<Suggestion> { a, b -> if (a.score != b.score) b.score - a.score else collator.compare(a.label, b.label) }
    }
}
