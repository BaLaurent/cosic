package cosic.pattern

import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The functions of util.mjs that programs can call, and `drawLine`. Strudel's own output for each is compared by the extra
 * snippets of parity/extra-snippets/2.7.json (ParityTest); the tests here are the vectors of `util.test.mjs`, what the
 * functions do with what is no good for them, and the limits Cosic puts on the ones that build lists.
 */
class UtilTest {
    /** The value of [code], an expression of a program, as the kernel sees it. */
    private fun value(code: String): Any? = evaluateProgram("pure($code)").pattern.firstCycle().single().value

    private fun failure(code: String): String = assertFailsWith<CosicError>(code) { evaluateProgram("pure($code)").pattern.firstCycle() }.message

    // ---- notes -----------------------------------------------------------------------------------------------------

    @Test
    fun isNoteTellsNoteNamesFromTheRest() {
        for (name in listOf("C3", "D3", "E3", "F3", "G3", "A3", "B3", "C4", "D5", "c5", "d5", "e5", "C#3", "D##3", "Eb3", "Fbb3", "Bb5", "C", "Bb", "c-1", "ds")) {
            assertEquals(true, value("isNote('$name')"), name)
        }
        for (name in listOf("H5", "X", "1", "", "c--1", "cc")) assertEquals(false, value("isNote('$name')"), name)
        assertEquals(false, value("isNote(1)"))
        assertEquals(false, value("isNoteWithOctave('c-1')"))
        assertEquals(true, value("isNoteWithOctave('c12')"))
    }

    @Test
    fun tokenizeNoteSplitsALetterAccidentalsAndAnOctave() {
        assertEquals(listOf("C", "#", 3.0), value("tokenizeNote('C#3')"))
        assertEquals(listOf("B", "bb", Undefined), value("tokenizeNote('Bbb')"))
        assertEquals(emptyList<Any?>(), value("tokenizeNote('X')"))
        assertEquals(emptyList<Any?>(), value("tokenizeNote(5)"))
        // the octave of `c-` is Number('-')
        assertTrue(((value("tokenizeNote('c-')") as List<*>)[2] as Double).isNaN())
    }

    @Test
    fun noteToMidiCountsFromC0() {
        for ((note, midi) in listOf(
            "A4" to 69.0, "C4" to 60.0, "Db4" to 61.0, "C3" to 48.0, "Cb3" to 47.0, "Cbb3" to 46.0, "C#3" to 49.0, "C##3" to 50.0,
            "c" to 48.0, "fs" to 54.0, "a0" to 21.0, "c-1" to 0.0,
        )) {
            assertEquals(midi, value("noteToMidi('$note')"), note)
        }
        assertEquals(76.0, value("noteToMidi('e', 5)"))
        assertEquals(66.0, value("noteToMidi('c', 4.5)"))
        assertEquals("not a note: \"Q\"", failure("noteToMidi('Q')"))
        assertEquals("not a note: \"Z\"", failure("noteToMidi('Z')"))
        // a double-quoted string is a pattern
        assertEquals("not a note: \"[object Object]\"", failure("""noteToMidi("c4")"""))
    }

    @Test
    fun frequenciesAndMidiNotes() {
        assertEquals(440.0, value("midiToFreq(69)"))
        assertEquals(220.0, value("midiToFreq(57)"))
        assertEquals(69.0, value("freqToMidi(440)"))
        assertEquals(57.0, value("freqToMidi(220)"))
        assertEquals(440.0, value("getFrequency('a4')"))
        assertEquals(220.0, value("getFrequency('a3')"))
        assertEquals(440.0, value("getFrequency(69)"))
        assertEquals(220.0, value("getFrequency(57)"))
        assertEquals(261.625565300599, value("getFreq('c4')") as Double, 1e-9)
        // a number is a frequency when the context says so
        assertEquals(123.0, value("getFrequency({value: 123, context: {type: 'frequency'}})"))
        assertEquals(123.0, value("getFrequency({value: {freq: 123}, context: {}})"))
        assertEquals("not a note or frequency: Q", failure("getFrequency('Q')"))
        assertEquals("not a note or frequency: undefined", failure("getFrequency({freq: 123})"))
    }

    @Test
    fun valueToMidiReadsAControlMap() {
        assertEquals(60.0, value("valueToMidi({note: 'c4'})"))
        assertEquals(69.0, value("valueToMidi({freq: 440})"))
        assertEquals(64.0, value("valueToMidi({note: 64})"))
        assertEquals(60.0, value("valueToMidi({}, 60)"))
        assertEquals("valueToMidi: expected freq or note to be set", failure("valueToMidi({})"))
        assertEquals("valueToMidi: expected object value", failure("valueToMidi(60)"))
    }

    @Test
    fun midi2noteNamesTheNoteWithFlats() {
        assertEquals(listOf("C4", "Db4", "C-1", "G9", "A0"), value("[midi2note(60), midi2note(61), midi2note(0), midi2note(127), midi2note(21)]"))
        // there is no name for a note that is not a whole number, and `undefined + octave` is NaN
        assertTrue((value("midi2note(72.5)") as Double).isNaN())
        assertTrue((value("midi2note(-1)") as Double).isNaN())
    }

    @Test
    fun parseNumeralAndParseFractional() {
        assertEquals(listOf(48.0, 60.0, 61.0, 61.0, 3.0), value("['c', 'c4', 'c#4', 'db4', '3'].map(parseNumeral)"))
        assertEquals("cannot parse as numeral: \"xyz\"", failure("parseNumeral('xyz')"))
        assertEquals(listOf(1.0, 0.5, 0.25, 0.125), value("['w', 'h', 'q', 'e'].map(parseFractional)"))
        assertEquals("cannot parse as fractional: \"z\"", failure("parseFractional('z')"))
        assertEquals(62.0, value("numeralArgs((a, b) => a + b)('c4', 2)"))
        assertEquals(2.25, value("fractionalArgs((a, b) => a + b)('q', 2)"))
    }

    @Test
    fun soundIndexWrapsAndRounds() {
        assertEquals(listOf(3.0, 4.0, 3.0, 0.0, 0.0), value("[getSoundIndex(3, 5), getSoundIndex(-1, 5), getSoundIndex(2.5, 5), getSoundIndex(undefined, 5), getSoundIndex(null, 5)]"))
    }

    // ---- lists and numbers -----------------------------------------------------------------------------------------

    @Test
    fun theListHelpers() {
        assertEquals(listOf(0.0, 2.0, 4.0, Double.NaN), value("zipWith((a, b) => a + b, [0, 1, 2, 3], [0, 1, 2])"))
        assertEquals(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0, 5.0)), value("splitAt(2, [1, 2, 3, 4, 5])"))
        assertEquals(
            listOf(listOf(listOf(1.0, 2.0), listOf(3.0)), listOf(emptyList<Any?>(), listOf(1.0, 2.0))),
            value("[splitAt(-1, [1, 2, 3]), splitAt(0, [1, 2])]"),
        )
        assertEquals(listOf(3.0, 4.0, 5.0, 1.0, 2.0), value("rotate([1, 2, 3, 4, 5], 2)"))
        assertEquals(listOf(4.0, 5.0, 1.0, 2.0, 3.0), value("rotate([1, 2, 3, 4, 5], -2)"))
        // a rotation of the whole length or more leaves the list alone, where an undefined one doubles it
        assertEquals(listOf(1.0, 2.0, 3.0), value("rotate([1, 2, 3], 10)"))
        assertEquals(listOf(1.0, 2.0, 3.0, 1.0, 2.0, 3.0), value("rotate([1, 2, 3])"))
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), value("listRange(1, 5)"))
        assertEquals(emptyList<Any?>(), value("listRange(5, 1)"))
        assertEquals(listOf(listOf(1.0, 2.0), listOf(2.0, 3.0)), value("pairs([1, 2, 3])"))
        // flatten goes down one level
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, listOf(5.0)), value("flatten([[1, 2], [3], 4, [[5]]])"))
        assertEquals(listOf(1.0, 2.0, 0.0, false), value("removeUndefineds([1, undefined, null, 2, 0, false])"))
        assertEquals(2.5, value("averageArray([1, 2, 3, 4])"))
        assertEquals("Reduce of empty array with no initial value", failure("averageArray([])"))
        assertEquals(listOf(3.0, 0.0, 2.0), value("[clamp(5, 0, 3), clamp(-1, 0, 3), clamp(2, 0, 3)]"))
        assertEquals(listOf(2.0, 1.0, 1.5, 2.0), value("[_mod(-1, 3), _mod(7, 3), _mod(5.5, 2), _mod(-7, 3)]"))
    }

    @Test
    fun listRangeIsLimited() {
        assertEquals(MAX_COUNT, (value("listRange(1, $MAX_COUNT)") as List<*>).size)
        val e = failure("listRange(1, ${MAX_COUNT + 1})")
        assertTrue(e.startsWith("listRange: at most $MAX_COUNT"), e)
    }

    @Test
    fun uniqIsBrokenInStrudelAndKeepsTheBreakage() {
        assertEquals(emptyList<Any?>(), value("uniq([])"))
        assertEquals("seen.hasOwn is not a function", failure("uniq([1, 2])"))
    }

    @Test
    fun uniqsortSortsByTheTextOfTheValues() {
        assertEquals(listOf(1.0, 10.0, 9.0, "a", "b"), value("uniqsort([10, 9, 1, 10, 'b', 'a', 'a'])"))
        assertEquals(listOf(1.0, 2.0, Undefined), value("uniqsort([undefined, 2, undefined, 1])"))
    }

    @Test
    fun functionsThatMakeFunctions() {
        assertEquals(11.0, value("pipe(x => x + 1, x => x * 2)(5)"))
        assertEquals(12.0, value("compose(x => x + 1, x => x * 2)(5)"))
        assertEquals(7.0, value("pipe()(7)"))
        assertEquals(6.0, value("curry((a, b, c) => a + b + c)(1)(2)(3)"))
        assertEquals(listOf(0.0, 1.0, 3.0), value("(() => { const f = curry((a, b) => a + b, (fn, args) => { fn.n = args.length }); return [f.n, f(1).n, f(1)(2)] })()"))
        assertEquals(6.0, value("mapArgs((a, b) => a * b, x => x + 1)(1, 2)"))
    }

    @Test
    fun theLengthOfEveryFunctionIsTheOneOfItsDefinition() {
        val lengths = mapOf(
            "isNote" to 1, "noteToMidi" to 1, "valueToMidi" to 2, "midi2note" to 1, "_mod" to 2, "nanFallback" to 1, "getSoundIndex" to 2,
            "getFrequency" to 1, "getFreq" to 1, "rotate" to 2, "pipe" to 0, "compose" to 0, "curry" to 2, "zipWith" to 3, "splitAt" to 2,
            "clamp" to 3, "listRange" to 2, "sol2note" to 1, "stringifyValues" to 1, "drawLine" to 1, "bjorklund" to 2, "squeeze" to 2, "pick" to 2,
            "objectMap" to 2, "mapArgs" to 2, "numeralArgs" to 1, "id" to 1, "constant" to 2,
        )
        for ((name, length) in lengths) assertEquals(length.toDouble(), value("$name.length"), name)
    }

    // ---- encoding --------------------------------------------------------------------------------------------------

    @Test
    fun codeBecomesTextThatFitsALink() {
        assertEquals("aGVsbG8=", value("unicodeToBase64('hello')"))
        assertEquals("hello", value("base64ToUnicode('aGVsbG8=')"))
        assertEquals("hello", value("base64ToUnicode('aGVsbG8')"))
        assertEquals("cygiYmQgaGgiKQ%3D%3D", value("""code2hash('s("bd hh")')"""))
        assertEquals("""s("bd")""", value("hash2code('cygiYmQiKQ%3D%3D')"))
        val code = "note(\"c e g\").s(\"sawtooth\") // \u2713 ?/+ \uD834\uDD1E"
        assertEquals(code, evaluateProgram("pure(hash2code(code2hash(${quoted(code)})))").pattern.firstCycle().single().value)
        assertEquals("URI malformed", failure("hash2code('%E0%A4%A')"))
        assertEquals("The string to be decoded is not correctly encoded.", failure("base64ToUnicode('a\$b')"))
    }

    private fun quoted(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"

    // ---- drawLine --------------------------------------------------------------------------------------------------

    @Test
    fun drawLineDrawsEachCycleWithAColumnForTheShortestEvent() {
        assertEquals("|0--123|0--123", value("""drawLine("0 [1 2 3]", 10)"""))
        assertEquals("|a..a..a.|a..a..a.|a..a..a.", value("""drawLine("a(3,8)", 20)"""))
        assertEquals("|ab|ab|ab|ab\n|c-|c-|c-|c-", value("""drawLine("[a b, c]", 12)"""))
        assertEquals(60.0, (value("""drawLine("a b").length""")))
        assertEquals("", value("""drawLine("a b", 0)"""))
    }

    @Test
    fun drawLineNeedsAPatternWithEventsThatStartSomething() {
        assertEquals("Cannot read properties of undefined (reading 'inverse')", failure("drawLine(silence, 5)"))
        assertEquals("pat.queryArc is not a function", failure("drawLine('a b', 5)"))
    }

    @Test
    fun drawLineIsLimited() {
        val e = failure("""drawLine("a b", ${MAX_COUNT + 1})""")
        assertTrue(e.startsWith("drawLine: at most $MAX_COUNT"), e)
        assertTrue(failure("""drawLine("a*${MAX_COUNT + 1}", 5)""").isNotEmpty())
    }
}
