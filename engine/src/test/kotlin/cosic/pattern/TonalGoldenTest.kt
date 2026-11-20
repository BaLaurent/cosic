package cosic.pattern

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Kotlin port of the tonal functions against what the real libraries answer. The expected values are in
 * `resources/tonal/golden.json`, made by tools/strudel-ref/tonal-oracle.mjs from `@tonaljs/tonal` 4.10.0 and Strudel's
 * `packages/tonal`; the load-time state (the scale types, the voicing dictionaries with their aliases) is in `load-state.json`
 * (tools/strudel-ref/tonal-data.mjs).
 */
class TonalGoldenTest {
    // the registry is global, and the parity cases that run before this one add dictionaries to it and change its ranges
    @BeforeTest
    fun freshRegistry() {
        Voicings.restore()
    }

    private fun resource(name: String): JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/tonal/$name")) { "missing $name" }.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject

    private val golden: JsonObject by lazy { resource("golden.json") }
    private val loadState: JsonObject by lazy { resource("load-state.json") }

    private fun section(name: String): List<JsonElement> = golden.getValue(name).jsonArray

    // ---- JSON and kernel values in one form ---------------------------------------------------------------------------------

    /** JSON as the plain values the comparison uses: numbers as doubles (the text `NaN` and the like too), objects in key order. */
    private fun canon(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonArray -> e.map { canon(it) }
        is JsonObject -> e.entries.associate { it.key to canon(it.value) }.toList()
        is JsonPrimitive -> when {
            e.isString -> e.content.let { s -> if (s == "NaN" || s == "Infinity" || s == "-Infinity") s.toDouble() else s }
            e.content == "true" -> true
            e.content == "false" -> false
            else -> e.content.toDouble()
        }
    }

    /** A value of the kernel in the same form: a map is a list of its entries, so that the order of its keys is compared too. */
    private fun canonKernel(v: Any?): Any? = when (v) {
        null, Undefined -> null
        is Map<*, *> -> v.entries.map { it.key.toString() to canonKernel(it.value) }
        is List<*> -> v.map { canonKernel(it) }
        is Number -> v.toDouble()
        else -> v
    }

    private fun assertSame(expected: JsonElement, actual: Any?, what: String) {
        val a = canonKernel(actual)
        val e = canon(expected)
        if (e is Double && a is Double && e.isNaN() && a.isNaN()) return
        assertEquals(e, a, what)
    }

    /** Equal as numbers: 0 and -0 are, NaN and NaN are (JSON cannot keep the sign of zero). */
    private fun same(a: Double, b: Double): Boolean = (a.isNaN() && b.isNaN()) || a == b

    private fun numberOrNull(e: JsonElement?): Double? = if (e == null || e is JsonNull) null else canon(e) as Double

    private fun errorOf(e: JsonElement): String? = (e as? JsonObject)?.get("error")?.jsonPrimitive?.content

    /** Runs [block]; a [CosicError] is the answer `{error: message}`. */
    private fun outcome(block: () -> Any?): Any? = try {
        block()
    } catch (e: CosicError) {
        listOf("error" to e.message)
    }

    // ---- notes and intervals -------------------------------------------------------------------------------------------------

    @Test
    fun noteGet() {
        for (case in section("notes")) {
            val c = case.jsonObject
            val src = c.getValue("src").jsonPrimitive.content
            val n = Tonal.note(src)
            val what = "Note.get(\"$src\")"
            assertEquals(c.getValue("empty").jsonPrimitive.content == "true", n.empty, what)
            assertEquals(c.getValue("name").jsonPrimitive.content, n.name, what)
            assertEquals(c.getValue("pc").jsonPrimitive.content, n.pc, what)
            assertEquals(c.getValue("acc").jsonPrimitive.content, n.acc, what)
            assertEquals(numberOrNull(c["oct"]), n.oct, "$what oct")
            if (!n.empty) {
                assertEquals(numberOrNull(c["step"]), n.step.toDouble(), "$what step")
                assertEquals(numberOrNull(c["alt"]), n.alt.toDouble(), "$what alt")
                assertEquals(numberOrNull(c["chroma"]), n.chroma!!.toDouble(), "$what chroma")
                assertEquals(numberOrNull(c["height"]), n.height, "$what height")
            }
            assertEquals(numberOrNull(c["midi"]), n.midi?.toDouble(), "$what midi")
        }
    }

    @Test
    fun intervalGet() {
        for (case in section("intervals")) {
            val c = case.jsonObject
            val src = c.getValue("src").jsonPrimitive.content
            val i = Tonal.interval(src)
            val what = "Interval.get(\"$src\")"
            assertEquals(c.getValue("empty").jsonPrimitive.content == "true", i.empty, what)
            assertEquals(c.getValue("name").jsonPrimitive.content, i.name, what)
            if (!i.empty) {
                val coord = c.getValue("coord").jsonArray.map { it.jsonPrimitive.content.toDouble() }
                assertTrue(same(coord[0], i.fifths), "$what fifths: ${coord[0]} != ${i.fifths}")
                assertTrue(same(coord[1], i.octs), "$what octs: ${coord[1]} != ${i.octs}")
                assertEquals(c.getValue("q").jsonPrimitive.content, i.q, what)
                assertEquals(c.getValue("type").jsonPrimitive.content, i.type, what)
                assertEquals(numberOrNull(c["alt"]), i.alt.toDouble(), "$what alt")
                assertEquals(numberOrNull(c["dir"]), i.dir.toDouble(), "$what dir")
                val semitones = numberOrNull(c["semitones"])
                if (semitones != null && !semitones.isNaN()) assertTrue(same(semitones, i.semitones), "$what semitones: $semitones != ${i.semitones}")
                val chroma = numberOrNull(c["chroma"])
                if (chroma != null && !chroma.isNaN()) assertEquals(chroma, i.chroma!!.toDouble(), "$what chroma")
            }
        }
    }

    @Test
    fun noteTranspose() {
        for (case in section("transpose")) {
            val (note, interval, expected) = case.jsonArray
            assertSame(expected, Tonal.transpose(note.jsonPrimitive.content, interval.jsonPrimitive.content), "Note.transpose(${note.jsonPrimitive.content}, ${interval.jsonPrimitive.content})")
        }
    }

    @Test
    fun intervalArithmetic() {
        for (case in section("intervalAdd")) {
            val (a, b, expected) = case.jsonArray
            assertSame(expected, Tonal.intervalAdd(a.jsonPrimitive.content, b.jsonPrimitive.content), "Interval.add(${a.jsonPrimitive.content}, ${b.jsonPrimitive.content})")
        }
        for (case in section("intervalSubstract")) {
            val (a, b, expected) = case.jsonArray
            assertSame(expected, Tonal.intervalSubstract(a.jsonPrimitive.content, b.jsonPrimitive.content), "Interval.substract(${a.jsonPrimitive.content}, ${b.jsonPrimitive.content})")
        }
        for (case in section("fromSemitones")) {
            val (semitones, expected) = case.jsonArray
            assertEquals(expected.jsonPrimitive.content, Tonal.intervalFromSemitones(canon(semitones) as Double), "Interval.fromSemitones(${semitones.jsonPrimitive.content})")
        }
        for (case in section("semitones")) {
            val (name, expected) = case.jsonArray
            val actual = Tonal.intervalSemitones(name.jsonPrimitive.content)
            val e = numberOrNull(expected)
            if (e == null) assertEquals(null, actual?.takeIf { !it.isNaN() }, "Interval.semitones(${name.jsonPrimitive.content})")
            else if (!e.isNaN()) assertTrue(actual != null && e == actual, "Interval.semitones(${name.jsonPrimitive.content}): $e != $actual")
        }
    }

    @Test
    fun enharmonicChromaAndRanges() {
        for (case in section("enharmonic")) {
            val (a, b, expected) = case.jsonArray
            assertSame(expected, Tonal.enharmonic(a.jsonPrimitive.content, b.jsonPrimitive.content), "Note.enharmonic(${a.jsonPrimitive.content}, ${b.jsonPrimitive.content})")
        }
        for (case in section("chromaMidi")) {
            val (name, chroma, midi) = case.jsonArray
            val n = name.jsonPrimitive.content
            assertEquals(numberOrNull(chroma)?.takeIf { !it.isNaN() }, Tonal.noteChroma(n)?.toDouble(), "Note.chroma($n)")
            assertEquals(numberOrNull(midi), Tonal.noteMidi(n)?.toDouble(), "Note.midi($n)")
        }
        for (case in section("ranges")) {
            val (input, expected) = case.jsonArray
            val range = input.jsonArray.map { e -> if (e.jsonPrimitive.isString) e.jsonPrimitive.content else e.jsonPrimitive.content.toDouble() }
            assertSame(expected, Tonal.rangeChromatic(range), "Range.chromatic($range)")
        }
    }

    // ---- scales ----------------------------------------------------------------------------------------------------------------

    @Test
    fun scaleTypesAndTheirKeys() {
        val types = loadState.getValue("scaleTypes").jsonArray
        assertEquals(types.size, Tonal.scaleTypes().size)
        for ((i, t) in types.withIndex()) {
            val o = t.jsonObject
            val mine = Tonal.scaleTypes()[i]
            assertEquals(o.getValue("name").jsonPrimitive.content, mine.name)
            assertEquals(o.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }, mine.aliases, mine.name)
            assertEquals(o.getValue("intervals").jsonArray.map { it.jsonPrimitive.content }, mine.intervals, mine.name)
            assertEquals(o.getValue("setNum").jsonPrimitive.content.toInt(), mine.setNum, "setNum of ${mine.name}")
            assertEquals(o.getValue("chroma").jsonPrimitive.content, mine.chroma, "chroma of ${mine.name}")
        }
        assertEquals(loadState.getValue("scaleTypeKeys").jsonArray.map { it.jsonPrimitive.content }.toSet(), Tonal.scaleTypeKeys())
    }

    @Test
    fun scaleNames() {
        for (case in section("scaleTokenize")) {
            val (name, expected) = case.jsonArray
            val (tonic, type) = Tonal.scaleTokenize(name.jsonPrimitive.content)
            assertSame(expected, listOf(tonic, type), "Scale.tokenize(\"${name.jsonPrimitive.content}\")")
        }
        for (case in section("scaleGet")) {
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val s = Tonal.scaleGet(name)
            assertEquals(c.getValue("empty").jsonPrimitive.content == "true", s.empty, "Scale.get(\"$name\")")
            if (!s.empty) {
                assertEquals(c.getValue("scaleName").jsonPrimitive.content, s.name, "Scale.get(\"$name\").name")
                assertEquals(c.getValue("type").jsonPrimitive.content, s.type, "Scale.get(\"$name\").type")
                assertEquals(c.getValue("tonic").jsonPrimitive.content, s.tonic, "Scale.get(\"$name\").tonic")
                assertEquals(c.getValue("intervals").jsonArray.map { it.jsonPrimitive.content }, s.intervals, "Scale.get(\"$name\").intervals")
                assertEquals(c.getValue("notes").jsonArray.map { it.jsonPrimitive.content }, s.notes, "Scale.get(\"$name\").notes")
            }
        }
    }

    // ---- the functions of tonal.mjs ------------------------------------------------------------------------------------------------

    /** The value of the JSON a test program gave, as the kernel holds it (maps in order, numbers as doubles). */
    private fun kernelValue(e: JsonElement): Any? = when (e) {
        is JsonNull -> Undefined
        is JsonArray -> e.map { kernelValue(it) }
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in e) m[k] = if (v is JsonNull) null else kernelValue(v) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            else -> e.content.toDouble()
        }
    }

    /** The haps `pat` gives in its first cycle, as the oracle writes them: `[{value, scale}]`. */
    private fun firstCycle(pat: Pattern): List<Any?> = pat.firstCycle().map { listOf("value" to canonKernel(it.value), "scale" to it.context.extra["scale"]) }

    private fun expectedHaps(result: JsonElement): List<Any?> = result.jsonArray.map { h ->
        val o = h.jsonObject
        listOf("value" to canon(o.getValue("value")), "scale" to canon(o.getValue("scale")))
    }

    private fun checkHaps(expected: JsonElement, actual: () -> Pattern, what: String) {
        val error = errorOf(expected)
        if (error != null) {
            try {
                val haps = actual().firstCycle()
                fail("$what: expected an error ($error) but got ${haps.map { it.show(true) }}")
            } catch (_: CosicError) {
            }
        } else {
            val e = expectedHaps(expected)
            val a = firstCycle(actual())
            // NaN is not equal to itself
            assertEquals(e.toString().replace("NaN", "nan"), a.toString().replace("NaN", "nan"), what)
        }
    }

    @Test
    fun scaleOfValues() {
        for (case in section("scaleHaps")) {
            val c = case.jsonObject
            val scale: Any? = c.getValue("scale").let { s -> if (s is JsonArray) s.map { it.jsonPrimitive.content } else s.jsonPrimitive.content }
            val kind = c.getValue("as").jsonPrimitive.content
            val raw = kernelValue(c.getValue("value"))
            val value = when (kind) {
                "n" -> linkedMapOf("n" to raw)
                "note" -> linkedMapOf("note" to raw)
                else -> raw
            }
            checkHaps(c.getValue("result"), { pure(value).scale(scale) }, "scale $scale of $kind $raw")
        }
    }

    @Test
    fun scaleTransposeOfScaledValues() {
        for (case in section("scaleTranspose")) {
            val c = case.jsonObject
            val scale = c.getValue("scale").takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val note = c.getValue("note")
            val offset = kernelValue(c.getValue("offset"))
            val value: Any? = if (note is JsonObject) linkedMapOf("n" to kernelValue(note.getValue("n")), "s" to "x") else kernelValue(note)
            checkHaps(c.getValue("result"), { (if (scale == null) pure(value) else pure(value).scale(scale)).scaleTranspose(offset) }, "scaleTranspose($offset) of $value in $scale")
        }
    }

    @Test
    fun transposeOfValues() {
        for (case in section("transposeHaps")) {
            val c = case.jsonObject
            val value = kernelValue(c.getValue("value"))
            val amount = kernelValue(c.getValue("amount"))
            checkHaps(c.getValue("result"), { pure(value).transpose(amount) }, "transpose($amount) of $value")
        }
    }

    @Test
    fun transposeOfNotesWithoutOctave() {
        for (case in section("transposeNoOctave")) {
            val (note, amount, expected) = case.jsonArray
            val n = note.jsonPrimitive.content
            val a = kernelValue(amount)
            val result = outcome { pure(n).transpose(a).firstCycle()[0].value }
            val error = errorOf(expected)
            if (error != null) {
                assertTrue(result is List<*> && (result.firstOrNull() as? Pair<*, *>)?.first == "error", "transpose($a) of $n should fail ($error), was $result")
            } else {
                assertSame(expected, result, "transpose($a) of $n")
            }
        }
    }

    // ---- tonleiter and chord-voicings ------------------------------------------------------------------------------------------------

    private fun dictionaryNamed(name: String, inline: JsonElement?): Any? = when {
        inline != null -> kernelValue(inline)
        name == "numbers" -> linkedMapOf<String, Any?>("" to listOf("7 12 16", "0 7 16", "4 7 12"), "m" to listOf("0 3 7"), "7" to listOf(listOf(0.0, 4.0, 7.0, 10.0)))
        else -> checkNotNull(Voicings.entry(name)) { "no dictionary $name" }["dictionary"]
    }

    /** `undefined` for an entry the oracle left out or wrote as `null` (it records `undefined` as `null`), the value otherwise. */
    private fun optional(c: JsonObject, key: String): Any? = c[key]?.takeIf { it !is JsonNull }?.let { kernelValue(it) } ?: Undefined

    @Test
    fun renderVoicingOfChords() {
        var checked = 0
        for (case in section("renderVoicing")) {
            val c = case.jsonObject
            val chord = c.getValue("chord").jsonPrimitive.content
            val dictionary = dictionaryNamed(c.getValue("dictionary").jsonPrimitive.content, c["inline"])
            val anchor: Any? = c.getValue("anchor").let { if (it is JsonNull) null else kernelValue(it) }
            val mode = c.getValue("mode").jsonPrimitive.content
            val what = "renderVoicing(${c.getValue("dictionary")} $chord anchor $anchor mode $mode offset ${c["offset"]} n ${c["n"]} octaves ${c["octaves"]})"
            val result = c.getValue("result")
            if (errorOf(result) != null) {
                try {
                    renderVoicing(chord, dictionary, optional(c, "offset"), optional(c, "n"), mode, anchor, optional(c, "octaves"))
                    fail("$what: expected an error (${errorOf(result)})")
                } catch (_: CosicError) {
                }
            } else {
                assertSame(result, renderVoicing(chord, dictionary, optional(c, "offset"), optional(c, "n"), mode, anchor, optional(c, "octaves")), what)
            }
            checked++
        }
        assertTrue(checked > 4000, "$checked voicings checked")
    }

    @Test
    fun voicingsInRangeAndTheirPick() {
        for (case in section("voicingsInRange")) {
            val c = case.jsonObject
            val chord = c.getValue("chord").jsonPrimitive.content
            val dictionary = dictionaryNamed(c.getValue("dictionary").jsonPrimitive.content, null)
            val range = c.getValue("range").takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }
            val what = "voicingsInRange(${c.getValue("dictionary")} $chord $range pick ${c["pick"]} last ${c["last"]})"
            val result = c.getValue("result")
            if (c["pick"] != null) {
                val last = c.getValue("last").takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }
                assertSame(result, bestVoicing(chord, dictionary, range, last), what)
            } else if (errorOf(result) != null) {
                try {
                    voicingsInRange(chord, dictionary, range)
                    fail("$what: expected an error")
                } catch (_: CosicError) {
                }
            } else {
                assertSame(result, voicingsInRange(chord, dictionary, range), what)
            }
        }
    }

    @Test
    fun tonleiterHelpers() {
        for (case in section("stepInNamedScale")) {
            val c = case.jsonObject
            val scale = c.getValue("scale").jsonPrimitive.content
            val step = c.getValue("step").jsonPrimitive.content.toDouble()
            val anchor = c.getValue("anchor").let { if (it is JsonNull) Undefined else kernelValue(it) }
            val what = "stepInNamedScale($step, $scale, $anchor)"
            val result = c.getValue("result")
            if (errorOf(result) != null) {
                try {
                    stepInNamedScale(step, scale, anchor)
                    fail("$what: expected an error")
                } catch (_: CosicError) {
                }
            } else {
                assertSame(result, stepInNamedScale(step, scale, anchor), what)
            }
        }
        for (case in section("scaleStep")) {
            val c = case.jsonObject
            val notes = c.getValue("notes").jsonArray.map { kernelValue(it) }
            val offset = c.getValue("offset").jsonPrimitive.content.toDouble()
            val octaves = c.getValue("octaves").let { if (it is JsonNull) 1.0 else it.jsonPrimitive.content.toDouble() }
            val what = "scaleStep($notes, $offset, $octaves)"
            val result = c.getValue("result")
            if (errorOf(result) != null) {
                try {
                    scaleStep(notes, offset, octaves)
                    fail("$what: expected an error")
                } catch (_: CosicError) {
                }
            } else {
                assertSame(result, scaleStep(notes, offset, octaves), what)
            }
        }
        for (case in section("nearestNumberIndex")) {
            val c = case.jsonObject
            val numbers = c.getValue("numbers").jsonArray.map { it.jsonPrimitive.content.toDouble() }
            val preferHigher = c.getValue("preferHigher").let { it !is JsonNull && it.jsonPrimitive.content == "true" }
            assertEquals(c.getValue("result").jsonPrimitive.content.toInt(), nearestNumberIndex(c.getValue("target").jsonPrimitive.content.toDouble(), numbers, preferHigher), "$c")
        }
        for (case in section("midi2note")) {
            val c = case.jsonObject
            val midi = c.getValue("midi").jsonPrimitive.content.toDouble()
            assertSame(c.getValue("result"), midi2note(midi, c.getValue("sharp").jsonPrimitive.content == "true"), "midi2note($midi, ${c.getValue("sharp")})")
        }
        for (case in section("tokenizeChord")) {
            val (chord, expected) = case.jsonArray
            assertSame(expected, tokenizeChord(chord.jsonPrimitive.content), "tokenizeChord(${chord.jsonPrimitive.content})")
        }
    }

    @Test
    fun theRegistryAfterItsAliasesIsTheOracles() {
        val registry = loadState.getValue("registry").jsonObject
        assertEquals(registry.keys.toList(), Voicings.names().take(registry.size))
        for ((name, expected) in registry) {
            val entry = checkNotNull(Voicings.entry(name))
            val o = expected.jsonObject
            @Suppress("UNCHECKED_CAST")
            val dictionary = entry["dictionary"] as Map<String, Any?>
            assertEquals(o.getValue("symbols").jsonArray.map { it.jsonPrimitive.content }, dictionary.keys.toList(), "symbols of $name")
            assertSame(o.getValue("range"), entry["range"], "range of $name")
            assertSame(o.getValue("mode"), entry["mode"], "mode of $name")
            assertSame(o.getValue("anchor"), entry["anchor"], "anchor of $name")
        }
        val aliased = loadState.getValue("iRealAliased").jsonObject
        for ((registryName, key) in listOf("ireal" to "simple", "ireal-ext" to "complex")) {
            @Suppress("UNCHECKED_CAST")
            val dictionary = checkNotNull(Voicings.entry(registryName))["dictionary"] as Map<String, Any?>
            for ((symbol, voicings) in aliased.getValue(key).jsonObject) assertSame(voicings, dictionary[symbol], "$registryName[$symbol]")
        }
    }
}
