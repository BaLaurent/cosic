package cosic.audio

import cosic.audio.dsp.DspGolden
import cosic.audio.dsp.assertClose
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The modulators against the real thing: `connectLFO`, `connectEnvelope` and `connectBusModulator` of superdough with the real worklets, rendered by headless
 * Chromium into the `offset` of a ConstantSourceNode (`tools/strudel-ref/modulators-oracle.mjs`, `src/test/resources/dsp/modulators.json`). Each case here is
 * connected to an [AParam] by [connectModulators] and the parameter is rendered in blocks.
 */
class ModulatorsGoldenTest {
    private val golden = DspGolden.load("modulators.json")
    private val stride = golden["stride"]!!.jsonPrimitive.int
    private val sampleRate = golden["sampleRate"]!!.jsonPrimitive.int
    private val busLeft = DspGolden.floats(golden["busLeft"]!!.jsonPrimitive.content)
    private val busRight = DspGolden.floats(golden["busRight"]!!.jsonPrimitive.content)

    private fun specOf(kind: ModKind, json: JsonObject): ModSpec {
        val params = json["params"]!!.jsonObject
        val numbers = LinkedHashMap<String, Double>()
        val strings = LinkedHashMap<String, String>()
        var control = ""
        var sub: String? = null
        for ((key, value) in params) {
            val primitive = value.jsonPrimitive
            when {
                key == "control" -> control = primitive.content
                key == "subControl" -> sub = primitive.content
                primitive.isString -> strings[key] = primitive.content
                else -> numbers[key] = primitive.double
            }
        }
        return ModSpec(kind, json["id"]!!.jsonPrimitive.content, "main", control, sub, numbers, strings)
    }

    /** Renders every target of a case in blocks of [block] frames; the bus signal of the block is what the bus tap hands out. */
    private fun render(case: JsonObject, block: Int): List<FloatArray> {
        val registry = NodeRegistry(sampleRate = sampleRate)
        val targets = case["targets"]!!.jsonArray.map { t ->
            val o = t.jsonObject
            AParam(o["base"]!!.jsonPrimitive.double, -3.4e38, 3.4e38).also {
                registry.register(o["node"]!!.jsonPrimitive.content, o["param"]!!.jsonPrimitive.content, it)
            }
        }
        val bus = StereoBuffer(BLOCK_FRAMES)
        val tap = BusTap { number -> if (number == 1) bus else null }
        val mods = case["mods"]!!.jsonArray.map { m ->
            val o = m.jsonObject
            specOf(
                when (val kind = o["kind"]!!.jsonPrimitive.content) {
                    "lfo" -> ModKind.LFO
                    "env" -> ModKind.ENV
                    "bmod" -> ModKind.BMOD
                    else -> error("kind $kind")
                },
                o,
            )
        }
        val bank = registry.modulators()
        bank.begin()
        connectModulators(
            mods, 0, { registry }, bank, case["begin"]!!.jsonPrimitive.double, case["end"]!!.jsonPrimitive.double,
            case["cps"]!!.jsonPrimitive.double, case["cycle"]!!.jsonPrimitive.double, tap,
        )
        val frames = case["frames"]!!.jsonPrimitive.int
        val outputs = targets.map { FloatArray(frames) }
        val ctx = BlockContext(sampleRate)
        val scratch = FloatArray(BLOCK_FRAMES)
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            ctx.startFrame = done.toLong()
            ctx.n = n
            for (i in 0 until n) {
                bus.left[i] = busLeft[done + i]
                bus.right[i] = busRight[done + i]
            }
            for ((k, target) in targets.withIndex()) {
                target.fill(scratch, ctx)
                System.arraycopy(scratch, 0, outputs[k], done, n)
            }
            done += n
        }
        return outputs
    }

    private fun strided(x: FloatArray): FloatArray = FloatArray((x.size + stride - 1) / stride) { x[it * stride] }

    @Test
    fun theControlTargetTableIsTheOneOfSuperdoughdata() {
        val table: JsonObject = golden["controlTargets"]!!.jsonObject
        assertEquals(table.size, ControlTargets.size)
        for ((control, entry) in table) {
            val underscore = control.indexOf('_')
            val found = if (underscore < 0) ControlTargets.lookup(control, null) else ControlTargets.lookup(control.substring(0, underscore), control.substring(underscore + 1))
            val expected = entry.jsonObject
            assertEquals(expected["node"]!!.jsonPrimitive.content, found?.node, "node of $control")
            assertEquals(expected["param"]!!.jsonPrimitive.content, found?.param, "param of $control")
        }
    }

    @Test
    fun everyRecordedCaseMatchesTheRealModulators() {
        val cases: JsonArray = golden["cases"]!!.jsonArray
        for (element in cases) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val expected = case["output"]!!.jsonArray.map { DspGolden.floats(it.jsonPrimitive.content) }
            // A modulated modulator parameter is read at the first frame of a render quantum; a block that starts after that frame cannot give that value
            // (see LfoOsc), so blocks that cut the quanta are only compared for the cases that have no modulator on a modulator.
            val onModulators = case["mods"]!!.jsonArray.any {
                val control = it.jsonObject["params"]!!.jsonObject["control"]!!.jsonPrimitive.content
                control.startsWith("lfo_") || control.startsWith("env_")
            }
            for (block in if (onModulators) listOf(256) else listOf(256, 97)) {
                val actual = render(case, block)
                for (k in expected.indices) {
                    assertClose("$name (target $k, blocks of $block)", expected[k], strided(actual[k]), atol = 5e-4, rtol = 5e-5)
                }
            }
        }
    }
}
