package com.octopusman.cosic.reference

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The matcher of CodeMirror's autocomplete: the vectors of the plan, and more that were run through the real matcher of @codemirror/autocomplete 6.18.4
 * (`tools/strudel-ref/complete-oracle.mjs` runs it over 370 words and every label the app offers, which `CompleterTest` compares with).
 */
class FuzzyMatcherTest {
    private fun match(pattern: String, word: String): FuzzyMatch? = FuzzyMatcher(pattern).match(word)

    private fun FuzzyMatch?.has(score: Int, vararg ranges: Int) {
        assertNotNull(this)
        assertEquals(score, this.score)
        assertContentEquals(ranges, this.matched)
    }

    @Test
    fun aPrefixIsAGoodMatchAndAWholeWordTheBest() {
        match("fa", "fast").has(-100, 0, 2)
        match("fast", "fast").has(0, 0, 4)
        match("n", "note").has(-100, 0, 1)
        match("n", "n").has(0, 0, 1)
    }

    @Test
    fun aCharacterOfAnotherCaseCostsTwoHundred() {
        match("N", "note").has(-300, 0, 1)
        match("N", "n").has(-200, 0, 1)
        match("FA", "fast").has(-304, 0, 2)
    }

    @Test
    fun aMatchThatDoesNotStartTheWordCostsSevenHundred() {
        match("ff", "off").has(-703, 1, 3)
        match("ast", "fast").has(-704, 1, 4)
        match("pf", "lpf").has(-703, 1, 3)
    }

    @Test
    fun theStartsOfWordsMatchBySingleCharacters() {
        // `fG` are the word starts of `fastGap`: the letters are not next to each other, the match is two ranges of one character
        match("fG", "fastGap").has(-107, 0, 1, 4, 5)
        match("sG", "sineGap").has(-107, 0, 1, 4, 5)
    }

    @Test
    fun charactersThatAreOnlyInOrderAreTheWorstMatch() {
        match("fap", "fastgap").has(-1307, 0, 2, 6, 7)
        match("lpf", "lowpassfilter").has(-1313, 0, 1, 3, 4, 7, 8)
        match("LPF", "lowpassfilter").has(-1313, 0, 1, 3, 4, 7, 8)
        match("rb", "reverb").has(-706, 4, 6)
    }

    @Test
    fun aWordThatDoesNotHaveTheCharactersInOrderDoesNotMatch() {
        assertNull(match("fz", "fast"))
        assertNull(match("x", "fast"))
        assertNull(match("ab", "ba"))
        assertNull(match("fast", "fa"))
    }

    @Test
    fun twoCharactersApartAreNoMatchButThreeAre() {
        assertNull(match("fp", "fastgap"))
        assertNotNull(match("fap", "fastgap"))
    }

    @Test
    fun anEmptyPatternMatchesEverythingAtMinusOneHundred() {
        match("", "anything").has(-100)
    }

    @Test
    fun theBufferOfAMatchByWordStartsOutlivesTheWordItWasMadeFor() {
        // CodeMirror's matcher keeps `byWord` between words and an array does not shrink: after `fastGap` has matched `fG` by word starts, a word whose first
        // word start does not match, `xFoG`, is no longer "adjacent" (-1100), where a matcher that has not seen a word before calls it so
        val reused = FuzzyMatcher("fG")
        reused.match("fastGap").has(-107, 0, 1, 4, 5)
        reused.match("xFoG").has(-2104, 1, 2, 3, 4)
        FuzzyMatcher("fG").match("xFoG").has(-1004, 1, 2, 3, 4)
        FuzzyMatcher("fG").apply { match("xFoG").has(-1004, 1, 2, 3, 4) }.match("xFoG").has(-2104, 1, 2, 3, 4)
        FuzzyMatcher("fG").apply { match("fastGap") }.match("xxFoo_G").has(-2107, 2, 3, 6, 7)
        FuzzyMatcher("fG").match("xxFoo_G").has(-1007, 2, 3, 6, 7)
    }

    @Test
    fun charactersBeyondTheBasicPlaneAreOneCharacter() {
        match("\uD83C\uDFB5a", "\uD83C\uDFB5ab").has(-100, 0, 3)
        match("\uD83C\uDFB5", "\uD83C\uDFB5").has(0, 0, 2)
        assertNull(match("\uD83C\uDFB5", "x"))
    }
}
