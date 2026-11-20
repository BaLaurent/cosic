package cosic.sound

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LooseJsonTest {
    @Suppress("UNCHECKED_CAST")
    private fun obj(text: String, allowTrailing: Boolean = false) = LooseJson.parse(text, allowTrailing) as Map<String, Any?>

    @Test
    fun readsPlainJson() {
        val value = obj("""{"a": [1, 2.5, -3e2, true, false, null], "b": {"c": "d"}, "e": "", "f": -0.5}""")
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), value["a"])
        assertEquals(mapOf("c" to "d"), value["b"])
        assertEquals("", value["e"])
        assertEquals(-0.5, value["f"])
    }

    @Test
    fun readsTheCommaFirstObjectLiteralOfASoundfontFile() {
        val text = """
            {
              zones:[
                { midi:0 ,originalPitch:2400 ,keyRangeLow:0 ,keyRangeHigh:27 ,loopStart:133802 ,loopEnd:135153
                  ,coarseTune:0 ,fineTune:0 ,sampleRate:44100 ,ahdsr:true ,file:'SUQzBAAAAAAA' ,anchor:0.0132 //the anchor
                } ,{ midi:0 ,originalPitch:2400 ,file:'AAA=' /* inline */ ,loopStart:.5 }
              ]
            };
            console.log('after');
        """
        val zones = obj(text, allowTrailing = true)["zones"] as List<*>
        assertEquals(2, zones.size)
        val first = zones[0] as Map<*, *>
        assertEquals(listOf("midi", "originalPitch", "keyRangeLow", "keyRangeHigh", "loopStart", "loopEnd", "coarseTune", "fineTune", "sampleRate", "ahdsr", "file", "anchor"), first.keys.toList())
        assertEquals(133802.0, first["loopStart"])
        assertEquals(true, first["ahdsr"])
        assertEquals("SUQzBAAAAAAA", first["file"])
        assertEquals(0.0132, first["anchor"])
        assertEquals(0.5, (zones[1] as Map<*, *>)["loopStart"])
    }

    @Test
    fun trailingLeadingAndDoubledCommasSeparateNothing() {
        assertEquals(listOf(1.0, 2.0), LooseJson.parse("[1,2,]"))
        assertEquals(listOf(1.0, 2.0), LooseJson.parse("[,1,,2,,]"))
        assertEquals(mapOf("a" to 1.0, "b" to 2.0), obj("{,a:1,,b:2,}"))
        assertEquals(emptyList<Any?>(), LooseJson.parse("[,]"))
    }

    @Test
    fun commentsAreSkippedBetweenTokens() {
        assertEquals(mapOf("a" to listOf(1.0)), obj("// head\n{ /* a */ a /* b */ : /* c */ [ // d\n 1 /* e */ ] } // tail\n"))
    }

    @Test
    fun stringsInEitherQuoteWithTheEscapesOfJavaScript() {
        assertEquals("It's", LooseJson.parse("'It\\'s'"))
        assertEquals("say \"hi\"", LooseJson.parse("\"say \\\"hi\\\"\""))
        assertEquals("a\nb\tc\\d/e", LooseJson.parse("\"a\\nb\\tc\\\\d\\/e\""))
        assertEquals("\u00e9A", LooseJson.parse("'\\u00e9\\x41'"))
        assertEquals("ab", LooseJson.parse("'a\\\nb'"), "a backslash before a line break continues the string")
        assertEquals("q", LooseJson.parse("'\\q'"), "an unknown escape stands for the character")
        assertEquals("tab\there", LooseJson.parse("'tab\there'"))
    }

    @Test
    fun keysMayBeQuotedBareOrNumbersAndComeInJavaScriptOrder() {
        val value = obj("""{ b: 1, "a c": 2, 'd': 3, 10: 4, 2: 5, _x: 6, ${'$'}y: 7 }""")
        assertEquals(listOf("2", "10", "b", "a c", "d", "_x", "\$y"), value.keys.toList(), "whole numbers first, ascending")
    }

    @Test
    fun aRepeatedKeyKeepsItsPlaceAndTakesTheLastValue() {
        val value = obj("""{"a": 1, "b": 2, "a": 3}""")
        assertEquals(listOf("a", "b"), value.keys.toList())
        assertEquals(3.0, value["a"])
    }

    @Test
    fun undefinedReadsAsNullAndNumbersAsDoubles() {
        assertNull(LooseJson.parse("undefined"))
        assertEquals(listOf(null, null), LooseJson.parse("[null, undefined]"))
        assertEquals(1000.0, LooseJson.parse("1e3"))
        assertEquals(0.25, LooseJson.parse(".25"))
        assertEquals(-5.0, LooseJson.parse("-5"))
        assertIs<Double>(LooseJson.parse("7"))
    }

    @Test
    fun anErrorSaysWhereItIs() {
        val error = assertFailsWith<IllegalArgumentException> { LooseJson.parse("{\n  \"a\": 1,\n  \"b\" 2\n}") }
        assertTrue(error.message!!.startsWith("3:"), error.message)
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("[1, 2") }.message!!.contains("unterminated array"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("{\"a\": 1") }.message!!.contains("unterminated object"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("\"abc") }.message!!.contains("unterminated string"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("/* x") }.message!!.contains("unterminated comment"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("") }.message!!.contains("end of input"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("nope") }.message!!.contains("unexpected 'nope'"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("[1] x") }.message!!.contains("after the value"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("'\\u12'") }.message!!.contains("bad escape"))
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse("-") }.message!!.contains("expected a number"))
    }

    @Test
    fun trailingContentIsAnErrorUnlessAllowed() {
        assertFailsWith<IllegalArgumentException> { LooseJson.parse("{} ;") }
        assertEquals(emptyMap(), obj("{} ;", allowTrailing = true))
    }

    @Test
    fun nestingDeeperThanTheLimitIsRefusedInsteadOfOverflowingTheStack() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertTrue(assertFailsWith<IllegalArgumentException> { LooseJson.parse(deep) }.message!!.contains("nested deeper"))
        assertIs<List<*>>(LooseJson.parse("[".repeat(100) + "]".repeat(100)))
    }

    @Test
    fun aStringOfSeveralMegabytesIsReadInOnePass() {
        val big = "A".repeat(6_000_000)
        val started = System.nanoTime()
        val value = obj("{file:'$big',next:1}")
        assertEquals(6_000_000, (value["file"] as String).length)
        assertEquals(1.0, value["next"])
        assertTrue((System.nanoTime() - started) / 1e9 < 5.0, "took too long")
        val escaped = obj("{s:'${"a\\n".repeat(1_000_000)}'}")
        assertEquals(2_000_000, (escaped["s"] as String).length)
    }

    @Test
    fun theListOfASampleMapKeepsItsOrder() {
        val value = obj("""{"_base": "https://x/", "bd": ["a.wav", "b.wav"], "piano": {"A0": "p.mp3", "C1": ["q.mp3"]}}""")
        assertContentEquals(listOf("_base", "bd", "piano"), value.keys.toList())
    }
}
