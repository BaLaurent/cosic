package com.octopusman.cosic.reference

import java.text.Collator
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The autocomplete of the editor. Its list is the documented functions of Strudel with their synonyms, a name once, those Strudel's editor hides
 * left out; the ranking is CodeMirror's: `tools/strudel-ref/complete-oracle.mjs` ran the real matcher and sort over every label, for 370 words.
 */
class CompleterTest {
    private fun card(name: String, vararg synonyms: String, hidden: Boolean = false) = DocEntry(name, hidden, synonyms.toList())

    private val fixture = listOf(
        card("fast", "density"),
        card("_hidden"),
        card("slow", "sparsity"),
        card("secret", hidden = true),
        card("fastGap", "fastgap"),
        card("density"), // a synonym of `fast` that is also a function: the first card to have the label keeps it
        card("fade"),
    )

    @Test
    fun theLabelsAreTheNamesAndSynonymsOnceInTheOrderOfTheCards() {
        val labels = Completer(fixture).completions.map { it.label }
        // `_hidden` is no name to offer in Strudel either, but only the generator drops it: the cards are taken as they are
        assertEquals(listOf("fast", "density", "_hidden", "slow", "sparsity", "fastGap", "fastgap", "fade"), labels)
    }

    @Test
    fun aHiddenCardIsNotOffered() {
        assertFalse("secret" in Completer(fixture).completions.map { it.label })
    }

    @Test
    fun aSynonymHasTheCardOfItsFunctionUnderItsOwnName() {
        val density = Completer(fixture).completions.single { it.label == "density" }.card!!
        assertEquals("density", density.name)
        assertEquals(listOf("fast"), density.synonyms)
        val fast = Completer(fixture).completions.single { it.label == "fast" }.card!!
        assertEquals(listOf("density"), fast.synonyms)
    }

    @Test
    fun onlyWhatAProgramCanCallIsOffered() {
        val labels = Completer(fixture) { it != "slow" && it != "fastgap" }.completions.map { it.label }
        assertEquals(listOf("fast", "density", "_hidden", "sparsity", "fastGap", "fade"), labels)
    }

    @Test
    fun theWordInFrontOfTheCursorIsWhatIsMatched() {
        val completer = Completer(fixture)
        val text = "s(\"bd\").fa"
        val suggestions = assertNotNull(completer.suggest(text, text.length))
        assertEquals(8, suggestions.from)
        assertEquals(10, suggestions.to)
        assertEquals(listOf("fade", "fast", "fastgap", "fastGap"), suggestions.suggestions.map { it.label })
    }

    @Test
    fun theSuggestionsOfEqualScoreAreInTheOrderOfTheLocale() {
        // `fa` starts all four: -100 each; the order is then that of `localeCompare`, in which the lower case comes first
        val labels = Completer(fixture).suggest("fa", 2)!!.suggestions.map { it.label }
        assertEquals(listOf("fade", "fast", "fastgap", "fastGap"), labels)
    }

    @Test
    fun aBetterScoreComesFirstWhateverTheOrder() {
        val labels = Completer(listOf(card("xfast"), card("fast"), card("fastest"))).suggest("fast", 4)!!.suggestions.map { it.label }
        assertEquals(listOf("fast", "fastest", "xfast"), labels)
    }

    @Test
    fun theWordEndsAtTheCursorNotAtTheEndOfTheWord() {
        val completer = Completer(fixture)
        val suggestions = assertNotNull(completer.suggest("fast(2)", 2))
        assertEquals(0, suggestions.from)
        assertEquals(2, suggestions.to)
    }

    @Test
    fun noWordNoSuggestionsUnlessTheListIsAskedFor() {
        val completer = Completer(fixture)
        assertNull(completer.suggest("", 0))
        assertNull(completer.suggest("s(\"bd\") ", 8))
        assertNull(completer.suggest("fast(", 5))
        assertEquals(completer.completions.size, completer.suggest("s(", 2, explicit = true)!!.suggestions.size)
    }

    @Test
    fun theWordIsOfTheCharactersOfAJavaScriptWord() {
        val completer = Completer(fixture)
        val code = "s(\"bd\")\n  .fa"
        assertEquals(code.length - 2, completer.suggest(code, code.length)!!.from)
        // digits and the underscore belong to a word, a dot or a dash do not
        assertEquals(2, completer.suggest("x.f_1", 5)!!.from)
        assertEquals(2, completer.suggest("a-fa", 4)!!.from)
        assertEquals(0, completer.suggest("fa", 2)!!.from)
    }

    @Test
    fun theWordIsOnTheLineOfTheCursor() {
        val completer = Completer(fixture)
        assertNull(completer.suggest("fast\n", 5))
        assertEquals(5, completer.suggest("fast\nfa", 7)!!.from)
        assertEquals(6, completer.suggest("fast\r\nfa", 8)!!.from)
    }

    @Test
    fun takingASuggestionWritesItsLabelInPlaceOfTheWord() {
        val completer = Completer(fixture)
        val text = "s(\"bd\").fa(2)"
        val suggestions = completer.suggest(text, 10)!!
        val edit = suggestions.apply(text, suggestions.suggestions.first { it.label == "fast" })
        assertEquals("s(\"bd\").fast(2)", edit.text, "no parentheses are added; the ones that are there stay")
        assertEquals(12, edit.caret)
    }

    @Test
    fun theListIsNeverLongerThanAHundred() {
        val completer = Completer(DocJson.cards)
        // asked for with no word, every label matches
        assertEquals(100, completer.suggest("s(", 2, explicit = true)!!.suggestions.size)
        assertTrue(completer.rank("").size > 500, "every label is ranked, the list is cut")
    }

    @Test
    fun typingFaOffersTheFastFamily() {
        val completer = Completer(DocJson.cards) { cosic.script.isDefined(it) }
        val labels = completer.suggest("fa", 2)!!.suggestions.map { it.label }
        for (name in listOf("fast", "fastGap", "fastcat", "fastchunk", "fastGap")) assertContains(labels, name)
        assertTrue(labels.take(9).all { it.startsWith("fa") }, labels.take(9).toString())
    }

    @Test
    fun everythingOfferedIsDefined() {
        val completer = Completer(DocJson.cards) { cosic.script.isDefined(it) }
        for (completion in completer.completions) assertTrue(cosic.script.isDefined(completion.label), completion.label)
        assertTrue(completer.completions.size > 500, "the language has ${completer.completions.size} names to offer")
        assertFalse("shiftInputBuffers" in completer.completions.map { it.label })
    }

    // ---- against CodeMirror -----------------------------------------------------------------------------------------------------

    private val oracle: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/completion/oracle.json")?.bufferedReader()?.readText()
            ?: error("/completion/oracle.json is missing: run `node tools/strudel-ref/complete-oracle.mjs`")
        Json.parseToJsonElement(text).jsonObject
    }

    private fun strings(array: JsonArray) = array.map { it.jsonPrimitive.content }

    @Test
    fun theLabelsAreTheOnesStrudelsCodeMakesOfTheCards() {
        val labels = strings(oracle["labels"]!!.jsonArray)
        assertEquals(labels, Completer(DocJson.cards).completions.map { it.label })
    }

    @Test
    fun theLabelsAreSortedAsLocaleCompareSortsThem() {
        val labels = strings(oracle["labels"]!!.jsonArray)
        val collator = Collator.getInstance(Locale.ROOT)
        assertEquals(strings(oracle["sorted"]!!.jsonArray), labels.sortedWith { a, b -> collator.compare(a, b) })
    }

    @Test
    fun everyWordIsRankedAsCodeMirrorRanksIt() {
        val labels = strings(oracle["labels"]!!.jsonArray)
        val completer = Completer(DocJson.cards)
        val words = oracle["matches"]!!.jsonArray.map { it.jsonObject }
        assertTrue(words.size > 300)
        for (word in words) {
            val pattern = word["pattern"]!!.jsonPrimitive.content
            val ranked = completer.rank(pattern)
            assertEquals(word["count"]!!.jsonPrimitive.int, ranked.size, "how many options match '$pattern'")
            val expected = word["results"]!!.jsonArray
            for ((position, result) in expected.withIndex()) {
                val (index, score, matched) = result.jsonArray
                val label = labels[index.jsonPrimitive.int]
                val actual = ranked[position]
                assertEquals(label, actual.label, "option ${position + 1} for '$pattern'")
                assertEquals(score.jsonPrimitive.int, actual.score, "score of $label for '$pattern'")
                assertContentEquals(matched.jsonArray.map { it.jsonPrimitive.int }.toIntArray(), actual.matched, "matched ranges of $label for '$pattern'")
            }
        }
    }

    @Test
    fun theListForAnEmptyWordIsTheLabelsInTheOrderOfTheLocale() {
        val labels = strings(oracle["labels"]!!.jsonArray)
        val ranked = Completer(DocJson.cards).rank("")
        val expected = oracle["explicit"]!!.jsonArray
        for ((position, result) in expected.withIndex()) {
            assertEquals(labels[result.jsonArray[0].jsonPrimitive.int], ranked[position].label)
        }
    }
}

/** The autocomplete knows where the cursor is: the words of a string argument, the methods after a dot, nothing in a comment. */
class CompleterContextTest {
    private fun card(name: String, vararg params: String) = DocEntry(name, params = params.map { DocParam(it) })

    private val vocabulary = object : Vocabulary {
        override fun words(kind: ArgKind): List<String> = when (kind) {
            ArgKind.SOUND -> listOf("bd", "sd", "hh", "RolandTR909_bd")
            ArgKind.BANK -> listOf("RolandTR909", "cosic808")
            ArgKind.SCALE -> listOf("major", "minor:pentatonic")
            ArgKind.NOTE -> listOf("c3", "e3", "g3")
            ArgKind.VOWEL -> listOf("a", "e")
        }
    }

    private val completer = Completer(
        listOf(card("s", "sound"), card("sound", "sound"), card("bank", "bank"), card("scale", "scale"), card("note"), card("fast", "factor"), card("stack"), card("vowel", "vowel")),
        vocabulary = vocabulary,
        isMethod = { it != "stack" },
    )

    private fun labels(code: String): List<String>? {
        val caret = code.indexOf('|')
        val text = code.replace("|", "")
        return completer.suggest(text, caret)?.suggestions?.map { it.label }
    }

    @Test
    fun aSoundArgumentGetsTheSounds() {
        assertContains(labels("s(\"b|\")").orEmpty(), "bd")
        assertContains(labels("\$: s(\"bd |\")").orEmpty(), "sd")
        assertContains(labels("x.sound(\"hh*2 s|\")").orEmpty(), "sd")
    }

    @Test
    fun aBankArgumentGetsTheBanksNotTheSounds() {
        assertEquals(listOf("cosic808"), labels("s(\"bd\").bank(\"cos|\")"))
    }

    @Test
    fun aScaleGetsNotesBeforeTheColonAndScalesAfterIt() {
        assertEquals(listOf("minor:pentatonic"), labels("n(\"0\").scale(\"C:minor:p|\")")?.filter { it.startsWith("minor") })
        assertContains(labels("n(\"0\").scale(\"c|\")").orEmpty(), "c3")
    }

    @Test
    fun theIndexAfterASampleNameIsNotAWord() {
        assertNull(labels("s(\"bd:|\")"))
    }

    @Test
    fun aStringOfAFunctionWithoutAListGetsNothing() {
        assertNull(labels("s(\"bd\").fast(\"2 f|\")"))
        assertNull(labels("\"bd s|\""))
    }

    @Test
    fun theSecondArgumentIsNotTheFirst() {
        assertNull(labels("s(\"bd\", \"s|\")"))
    }

    @Test
    fun afterADotOnlyMethodsAreOffered() {
        assertContains(labels("s(\"bd\").fa|").orEmpty(), "fast")
        assertEquals(emptyList(), labels("s(\"bd\").sta|")?.filter { it == "stack" })
        assertContains(labels("sta|").orEmpty(), "stack")
    }

    @Test
    fun aQuoteInACommentOrAnotherStringDoesNotOpenAString() {
        assertContains(labels("// s(\"x\n fa|").orEmpty(), "fast")
        assertNull(labels("// a comment fa|"))
        assertContains(labels("note(\"c3 // not a comment\").fa|").orEmpty(), "fast")
        assertContains(labels("s(\"it's\").fa|").orEmpty(), "fast")
    }
}
