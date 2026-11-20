package cosic.visual

import cosic.pattern.Hap
import cosic.pattern.HapContext
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToString
import cosic.pattern.silence
import cosic.pattern.tag
import cosic.script.evaluateProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compares the renderers, the draw loops and the drawer with the real drawing code of Strudel. `tools/strudel-ref/visual-oracle.mjs` runs `__pianoroll`, the
 * painters of the punchcard, wordfall and spiral, `pitchwheel`, `drawTimeScope`, `drawFrequencyScope`, `Pattern.prototype.draw` (with `pianoroll`, `scope`, `fscope`,
 * `spectrum` and `print`) and the `Drawer` of packages/draw at the pinned commit against a canvas that records what it is told; here the same cases run on the
 * Kotlin code and the recorded calls, the memories of the loops and the haps in view must be the same (numbers to a relative 1e-9: the sine and logarithm of the JVM
 * and of V8 may differ in the last digit).
 */
class VisualGoldenTest {
    private val doc: JsonObject by lazy {
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/visual/golden.json")?.bufferedReader()?.readText()
                ?: error("/visual/golden.json is missing: run `node tools/strudel-ref/visual-oracle.mjs`"),
        ).jsonObject
    }

    // ---- JSON to the values of the kernel ---------------------------------------------------------------------------------------------

    private fun valueOf(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            else -> e.double
        }
        is JsonArray -> e.map(::valueOf)
        is JsonObject -> LinkedHashMap<String, Any?>().also { map -> for ((k, v) in e) map[k] = valueOf(v) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun optionsOf(e: JsonElement?): VisualOptions = VisualOptions((e?.let(::valueOf) as? Map<String, Any?>) ?: emptyMap())

    private fun spanOf(e: JsonElement?): TimeSpan? {
        if (e == null || e is JsonNull) return null
        val a = e.jsonArray
        return TimeSpan(Rational.of(a[0].jsonPrimitive.content), Rational.of(a[1].jsonPrimitive.content))
    }

    private fun hapOf(e: JsonObject): Hap {
        val tags = e["tags"]?.takeIf { it !is JsonNull }?.jsonArray?.map { jsToString(valueOf(it)) }
        return Hap(spanOf(e["whole"]), spanOf(e["part"])!!, valueOf(e.getValue("value")), HapContext(tags = tags))
    }

    private fun floats(e: JsonElement?): FloatArray? =
        e?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonPrimitive.double.toFloat() }?.toFloatArray()

    // ---- comparing recorded calls --------------------------------------------------------------------------------------------------------

    private fun near(x: Double, y: Double): Boolean = x == y || Math.abs(x - y) <= 1e-9 * Math.max(1.0, Math.max(Math.abs(x), Math.abs(y)))

    private fun same(expected: JsonElement, actual: Any?): Boolean = when {
        expected is JsonArray -> actual is List<*> && expected.size == actual.size && expected.indices.all { same(expected[it], actual[it]) }
        expected is JsonPrimitive && expected.isString -> (if (actual is Double) jsNumberToString(actual) else actual) == expected.content
        expected is JsonPrimitive -> actual is Double && near(expected.double, actual)
        else -> false
    }

    private fun callsDiffer(expected: JsonArray, canvas: RecordingCanvas): String? {
        val actual = canvas.calls
        val common = Math.min(expected.size, actual.size)
        for (i in 0 until common) {
            if (!same(expected[i], actual[i])) return "call $i: expected ${expected[i]}, actual ${canvas.text()[i]}"
        }
        if (expected.size != actual.size) {
            return "${actual.size} calls here, ${expected.size} in Strudel; the first one missing or extra is " +
                (if (expected.size > actual.size) "${expected[common]}" else canvas.text()[common])
        }
        return null
    }

    private fun shown(haps: List<Hap>): List<String> = haps.map { it.show(true) }

    private fun listDiffer(what: String, expected: JsonElement?, actual: List<String>): String? {
        val e = expected?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
        if (e == actual) return null
        val first = e.zip(actual).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) Math.min(e.size, actual.size) else it }
        return "$what differ at $first (${actual.size} here, ${e.size} in Strudel): expected ${e.getOrNull(first)}, actual ${actual.getOrNull(first)}"
    }

    private fun failIfAny(failures: List<String>, total: Int, what: String) {
        if (failures.isNotEmpty()) fail("${failures.size} of $total $what differ from Strudel" + failures.take(20).joinToString("\n  ", "\n  "))
        println("$what: $total cases match Strudel")
    }

    // ---- tests ---------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun theDefaultThemeIsTheOneStrudelsReplInstalls() {
        val theme = doc.getValue("theme").jsonObject
        assertEquals(theme.getValue("foreground").jsonPrimitive.content, VisualTheme.foreground)
        assertEquals(theme.getValue("gutterForeground").jsonPrimitive.content, VisualTheme.gutterForeground)
        assertEquals(theme.getValue("background").jsonPrimitive.content, VisualTheme.background)
    }

    private fun painterOf(kind: String, options: VisualOptions): Painter {
        val session = VisualSession()
        val pattern = when (kind) {
            "punchcard" -> session.punchcard(silence, options)
            "wordfall" -> session.wordfall(silence, options)
            "spiral" -> session.spiral(silence, options)
            else -> error(kind)
        }
        val found = ArrayList<Any?>()
        pattern.queryArc(Rational.ZERO, Rational.ZERO, mapOf("painters" to found))
        return found.single() as Painter
    }

    @Test
    fun theRenderersDrawWhatStrudelDraws() {
        val cases = doc.getValue("renderers").jsonArray.map { it.jsonObject }
        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val kind = c.getValue("kind").jsonPrimitive.content
            val size = c.getValue("canvas").jsonObject
            val canvas = RecordingCanvas(size.getValue("width").jsonPrimitive.double, size.getValue("height").jsonPrimitive.double)
            val options = optionsOf(c["options"])
            val time = c.getValue("time").jsonPrimitive.double
            val haps = c.getValue("haps").jsonArray.map { hapOf(it.jsonObject) }
            val drawTime = c["drawTime"]?.jsonArray?.map { it.jsonPrimitive.double }
            val data = floats(c["data"])
            try {
                when (kind) {
                    "pianoroll" -> drawPianoroll(canvas, time, haps, options)
                    "punchcard", "wordfall", "spiral" -> painterOf(kind, options).paint(canvas, time, haps, drawTime!![0], drawTime[1])
                    "pitchwheel" -> drawPitchwheel(canvas, haps, options)
                    "timescope" -> drawTimeScope(canvas, data, options)
                    "freqscope" -> drawFrequencyScope(canvas, data!!, options)
                    else -> error("unknown kind $kind")
                }
            } catch (e: Exception) {
                failures.add("$name: ${e::class.simpleName}: ${e.message}")
                continue
            }
            callsDiffer(c.getValue("calls").jsonArray, canvas)?.let { failures.add("$name: $it") }
        }
        failIfAny(failures, cases.size, "renderer cases")
    }

    @Test
    fun theDrawLoopsRememberAndDrawWhatStrudelDoes() {
        val cases = doc.getValue("loops").jsonArray.map { it.jsonObject }
        val failures = ArrayList<String>()
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val kind = c.getValue("kind").jsonPrimitive.content
            val id = c.getValue("id").jsonPrimitive.content
            val size = c.getValue("canvas").jsonObject
            val options = optionsOf(c["options"])
            val program: Pattern = evaluateProgram(c.getValue("code").jsonPrimitive.content).pattern.tag(id)
            val feedData = floats(c["data"])
            val feed = AnalyserFeed { _, _ -> feedData }
            val session = VisualSession()
            val receiver = when (kind) {
                "pianoroll" -> session.pianoroll(program, options)
                "scope" -> session.scope(program, options, feed = feed)
                "fscope" -> session.fscope(program, options, feed = feed)
                "spectrum" -> session.spectrum(program, options, feed = feed)
                "print" -> session.printWidget(program, options, id)
                else -> error(kind)
            }
            listDiffer("the haps of the returned pattern", c["analyzed"], shown(receiver.queryArc(Rational.ZERO, Rational.ONE)))?.let { failures.add("$name: $it") }
            val bg = session.surfaces.first()
            for ((index, frame) in c.getValue("out").jsonArray.withIndex()) {
                val f = frame.jsonObject
                val canvas = RecordingCanvas(size.getValue("width").jsonPrimitive.double, size.getValue("height").jsonPrimitive.double)
                try {
                    session.tick(f.getValue("time").jsonPrimitive.double, true)
                    session.paint(bg, canvas)
                } catch (e: Exception) {
                    failures.add("$name, frame $index: ${e::class.simpleName}: ${e.message}")
                    break
                }
                listDiffer("memory", f["memory"], shown(session.loopMemory(id)))?.let { failures.add("$name, frame $index: $it") }
                f["lines"]?.let { lines -> listDiffer("lines", lines, session.printLines(id))?.let { failures.add("$name, frame $index: $it") } }
                callsDiffer(f.getValue("calls").jsonArray, canvas)?.let { failures.add("$name, frame $index: $it") }
            }
        }
        failIfAny(failures, cases.size, "draw loops")
    }

    @Test
    fun theDrawerKeepsTheHapsInViewAsStrudelsDoes() {
        val cases = doc.getValue("drawers").jsonArray.map { it.jsonObject }
        val failures = ArrayList<String>()
        val query = SafeQuery { }
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val window = HapWindow()
            fun run(code: String, invalidateAt: Double, out: JsonObject, label: String) {
                val program = evaluateProgram(code).pattern
                window.invalidate(program, invalidateAt, query)
                val invalidated = out.getValue("invalidated").jsonObject
                listDiffer("$label haps in view after invalidate", invalidated["visible"], shown(window.visibleHaps))?.let { failures.add("$name: $it") }
                assertEquals(invalidated.getValue("painters").jsonPrimitive.double.toInt(), window.painters.size, "$name: painters")
                for ((index, frame) in out.getValue("frames").jsonArray.withIndex()) {
                    val f = frame.jsonObject
                    val current = window.frame(program, f.getValue("time").jsonPrimitive.double, query)
                    val expected = f["frame"]?.takeIf { it !is JsonNull }?.jsonObject
                    if ((expected == null) != (current == null)) {
                        failures.add("$name, $label frame $index: ${if (expected == null) "Strudel draws no frame yet" else "Strudel draws a frame"}")
                        continue
                    }
                    if (expected != null && current != null) {
                        listDiffer("haps of the frame", expected["haps"], shown(current.haps))?.let { failures.add("$name, $label frame $index: $it") }
                        if (!near(expected.getValue("time").jsonPrimitive.double, current.time)) {
                            failures.add("$name, $label frame $index: time ${current.time}, Strudel ${expected["time"]}")
                        }
                        assertEquals(expected.getValue("painters").jsonPrimitive.double.toInt(), window.painters.size, "$name: painters in frame $index")
                    }
                    listDiffer("haps in view", f["visible"], shown(window.visibleHaps))?.let { failures.add("$name, $label frame $index: $it") }
                }
            }
            c["earlier"]?.jsonObject?.let { earlier ->
                run(earlier.getValue("code").jsonPrimitive.content, earlier.getValue("invalidateAt").jsonPrimitive.double, earlier.getValue("out").jsonObject, "earlier")
            }
            run(c.getValue("code").jsonPrimitive.content, c.getValue("invalidateAt").jsonPrimitive.double, c.getValue("out").jsonObject, "now")
        }
        failIfAny(failures, cases.size, "drawers")
        assertTrue(cases.isNotEmpty())
    }
}
