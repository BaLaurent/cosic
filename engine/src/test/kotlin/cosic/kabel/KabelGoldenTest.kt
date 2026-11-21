package cosic.kabel

import cosic.audio.AudioBlock
import cosic.audio.BlockContext
import cosic.audio.KabelStage
import cosic.audio.ParamSet
import cosic.audio.VoiceTiming
import cosic.audio.sources.SourceControls
import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import cosic.audio.sources.frequencyFromValue

/**
 * The goldens of resources/kabel/golden were rendered by the REAL kabelsalat (`@kabelsalat/core` + `@kabelsalat/lib`) and the REAL `GenericProcessor`
 * of the pinned superdough, in Node, from a program text as `superdough()` builds it (tools/strudel-ref/kabel/render-golden.mjs): interleaved float32
 * stereo, block by block with `currentTime` at each quantum. Each case checks, in order, that the text substitution made the same program text, that
 * the compiler found the same ugens with the same inputs in the same number of registers, and that the [KabelStage] reproduces the audio to 1e-4 per
 * sample whatever the block lengths it is fed in (the worklet's quanta are 128 frames aligned to the frame counter).
 */
class KabelGoldenTest {
    private val dir: File = File(checkNotNull(javaClass.getResource("/kabel/golden/ex0-h0.json")) { "the goldens are missing" }.toURI()).parentFile

    private fun JsonElement.asValue(): Any? = when {
        this is JsonNull -> null
        this is JsonPrimitive && isString -> content
        this is JsonPrimitive -> booleanOrNull ?: doubleOrNull
        else -> toString()
    }

    private fun numberOrNull(o: JsonObject, key: String): Double? = o[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull

    private fun describe(input: UgenInput): String = when (input) {
        is UgenInput.Num -> jsNumberToString(input.value)
        is UgenInput.Str -> "\"${input.value}\""
        is UgenInput.Reg -> "r[${input.index}]"
        UgenInput.Missing -> "undefined"
    }

    private fun inputText(e: JsonElement): String = if (e is JsonNull) "undefined" else if (e is JsonPrimitive && e.isString) e.content else (e as JsonPrimitive).content.let { text ->
        // the oracle writes the numbers JSON has no form for as strings, and the others as JSON numbers: both read as JavaScript prints them
        text.toDoubleOrNull()?.let { jsNumberToString(it) } ?: text
    }

    /** The output of a [KabelStage] for the case, in blocks of the given lengths (taken in turn): interleaved left and right. */
    private fun render(o: JsonObject, program: KabelProgram, blockSizes: IntArray): FloatArray {
        val sampleRate = o.getValue("sampleRate").jsonPrimitive.content.toInt()
        val frames = o.getValue("frames").jsonPrimitive.content.toInt()
        val ramp = o.getValue("input").jsonPrimitive.content == "ramp"
        val instance = KabelInstance(program, sampleRate, KabelRuntime(), Mulberry32(o.getValue("rngSeed").jsonPrimitive.content.toInt()))
        val stage = KabelStage()
        val timing = VoiceTiming(
            o.getValue("start").jsonPrimitive.content.toDouble(), o.getValue("gateEnd").jsonPrimitive.content.toDouble(),
            o.getValue("end").jsonPrimitive.content.toDouble(), 0.5, 0.0,
        )
        stage.reset()
        assertTrue(stage.configure(ParamSet(kabel = instance), timing, true))
        val block = AudioBlock(256)
        val ctx = BlockContext(sampleRate)
        val out = FloatArray(frames * 2)
        var frame = 0
        var turn = 0
        while (frame < frames) {
            val n = minOf(blockSizes[turn++ % blockSizes.size], frames - frame)
            block.clear(n)
            if (ramp) for (i in 0 until n) block.l[i] = ((((frame + i) % 100) / 100.0) - 0.5).toFloat()
            ctx.startFrame = frame.toLong()
            ctx.n = n
            stage.process(block, ctx)
            for (i in 0 until n) {
                out[2 * (frame + i)] = block.l[i]
                out[2 * (frame + i) + 1] = block.r[i]
            }
            frame += n
        }
        return out
    }

    @Test
    fun theStageReproducesTheRealWorkletOnEveryCase() {
        val cases = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(cases.size >= 50, "the goldens look incomplete: ${cases.size}")
        val failures = ArrayList<String>()
        val report = StringBuilder()
        for (file in cases) {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            val sampleRate = o.getValue("sampleRate").jsonPrimitive.content.toInt()
            // 1. the program text
            val controls = HashMap<String, Any?>()
            for (key in listOf("note", "freq", "octave")) o[key]?.takeIf { it !is JsonNull }?.let { controls[key] = it.asValue() }
            val freq = frequencyFromValue(object : SourceControls {
                override fun get(key: String): Any? = controls[key] ?: Undefined
            })
            val inputs = o.getValue("workletInputs").jsonArray.map { it.asValue() }
            val text = substituteWorklet(o.getValue("workletSrc").jsonPrimitive.content, inputs, freq)
            val gateId = Regex("strudel-gate-\\d+")
            val expectedText = o.getValue("codeAfterSubstitution").jsonPrimitive.content.replace(gateId, "strudel-gate-0")
            if (text.replace(gateId, "strudel-gate-0") != expectedText) failures.add("$id: the substituted text differs:\n  expected $expectedText\n  got      $text")
            // 2. the compiled program
            val program = try {
                compileKabel(text, sampleRate)
            } catch (e: Exception) {
                failures.add("$id: does not compile: ${e.message}")
                continue
            }
            val types = o.getValue("ugenTypes").jsonArray.map { it.jsonPrimitive.content }
            if (types != program.ugens.map { it.type }) failures.add("$id: ugens ${program.ugens.map { it.type }}, expected $types")
            if (program.nodeCount != o.getValue("registers").jsonPrimitive.content.toInt()) failures.add("$id: ${program.nodeCount} nodes, expected ${o.getValue("registers")}")
            for ((k, u) in o.getValue("ugenInputs").jsonArray.withIndex()) {
                val expected = u.jsonArray.map { inputText(it) }
                val got = program.ugens.getOrNull(k)?.inputs?.map { describe(it) }
                if (got != null && expected.map { it.replace("strudel-gate-4242", "strudel-gate-0") } != got) failures.add("$id: ugen $k inputs $expected, got $got")
            }

            // 3. the audio, and the same audio whatever the blocks
            val expectedBytes = ByteBuffer.wrap(File(dir, "$id.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val expected = FloatArray(expectedBytes.remaining()).also { expectedBytes.get(it) }
            val whole = render(o, program, intArrayOf(256))
            val split = render(o, program, intArrayOf(128, 37, 91, 200))
            if (!whole.contentEquals(split)) failures.add("$id: the output depends on how the blocks are cut")
            if (whole.size != expected.size) {
                failures.add("$id: ${whole.size} samples, expected ${expected.size}")
                continue
            }
            var worst = 0.0
            var sum = 0.0
            var worstAt = 0
            for (i in whole.indices) {
                val d = abs(whole[i].toDouble() - expected[i].toDouble())
                if (d > worst) {
                    worst = d
                    worstAt = i
                }
                sum += d * d
            }
            val rms = sqrt(sum / whole.size)
            report.append("  %-18s max %.3e at %d (rms %.3e)\n".format(id, worst, worstAt, rms))
            if (worst >= 1e-4 || rms >= 1e-5) failures.add("$id: max error $worst at sample $worstAt, rms $rms")
            val peak = o.getValue("peak").jsonPrimitive.content.toDouble()
            val peakGot = whole.maxOf { abs(it.toDouble()) }
            if (peak > 1e-3 && abs(peakGot - peak) > 1e-4) failures.add("$id: the peak is $peakGot, the real worklet's $peak")
        }
        println("golden errors:\n$report")
        assertEquals(emptyList(), failures, failures.joinToString("\n"))
    }

    @Test
    fun aChangedProgramDoesNotMatch() {
        // a negative control: the comparison above can fail
        val o = Json.parseToJsonElement(File(dir, "ex0-h0.json").readText()).jsonObject
        val gateId = Regex("strudel-gate-\\d+")
        val text = o.getValue("codeAfterSubstitution").jsonPrimitive.content.replace(gateId, "strudel-gate-0").replace(".mul(0.3)", ".mul(0.31)")
        val changed = render(o, compileKabel(text), intArrayOf(256))
        val bytes = ByteBuffer.wrap(File(dir, "ex0-h0.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val expected = FloatArray(bytes.remaining()).also { bytes.get(it) }
        var worst = 0.0
        for (i in expected.indices) worst = maxOf(worst, abs(changed[i].toDouble() - expected[i].toDouble()))
        assertTrue(worst > 1e-3, "a 3% louder program differs by $worst")
    }

    @Test
    fun theOutputOfTwoRunsIsTheSame() {
        val o = Json.parseToJsonElement(File(dir, "noise-family.json").readText()).jsonObject
        val program = compileKabel(o.getValue("codeAfterSubstitution").jsonPrimitive.content.replace(Regex("strudel-gate-\\d+"), "strudel-gate-0"))
        assertContentEquals(render(o, program, intArrayOf(256)), render(o, program, intArrayOf(256)))
    }
}
