package cosic.kabel

import cosic.render.OfflineRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two documented examples of `K` (the `example "K"` entries of the parity data, whose haps ParityTest checks), played by the whole engine: the program is
 * transpiled into `worklet(...)` calls, evaluated, looked ahead over by the dry run (which compiles the programs), scheduled with their `sFreq` and `sGate`, and
 * run by the kabelsalat stage of each voice, through the rest of the chain and the orbit. The sound must be the program's, not the hidden source's, which is
 * what the same pattern plays without the `K`.
 */
class KabelExamplesTest {
    private val examples: JsonArray = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/parity/examples.json")) { "examples.json is missing" }.bufferedReader().readText(),
    ).jsonArray

    private fun example(index: Int): String =
        examples.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "K" && it.getValue("index").jsonPrimitive.content.toInt() == index }
            .getValue("code").jsonPrimitive.content

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun check(code: String, withoutK: String) {
        val sampleRate = 48000
        val cycles = 2.0
        val out = OfflineRenderer.render(code, cycles, sampleRate)
        val plain = OfflineRenderer.render(withoutK, cycles, sampleRate)
        assertTrue(out.all { it.isFinite() }, "the output is not finite")
        val peak = out.maxOf { abs(it) }
        assertTrue(peak > 0.05f, "the program is silent: peak $peak")
        // sounding in both cycles (cps 0.5: 96000 frames per cycle, 2 channels)
        for (cycle in 0 until 2) {
            val level = rms(out, 2 * (2400 + cycle * 96000), 2 * (2400 + (cycle + 1) * 96000))
            assertTrue(level > 0.005, "cycle $cycle is silent: rms $level")
        }
        // not the hidden source: the same events without the program sound different
        var difference = 0.0
        val n = minOf(out.size, plain.size)
        for (i in 0 until n) difference += (out[i].toDouble() - plain[i]).let { it * it }
        val plainLevel = rms(plain)
        assertTrue(sqrt(difference / n) > 0.2 * plainLevel, "the output is the hidden source's: difference ${sqrt(difference / n)} against its level $plainLevel")
    }

    @Test
    fun exampleOneRendersThroughTheWholeChain() {
        check(
            example(0),
            """note("A c e".fast(4)).transpose("<0 2 4 6 8>").scale("F:minor").transpose("12").s("saw")""",
        )
    }

    @Test
    fun exampleTwoRendersThroughTheWholeChain() {
        check(
            example(1),
            """n("<0 1 <2 3 2 4>>*16").scale("G#2:minor").sometimes(x => x.transpose("12 | 24")).fxr(1).room(0.3)""",
        )
    }

    @Test
    fun aProgramThatDoesNotCompileRejectsTheEvaluation() {
        val error = assertFailsWith<IllegalStateException> { OfflineRenderer.render("""note("c e g").K(sawz(sFreq).out())""", 1.0) }
        assertEquals("sawz is not defined", error.message)
    }

    @Test
    fun theProgramIsAnEffectOnTheHiddenSourceWhenItReadsAudioin() {
        val dry = OfflineRenderer.render("""note("c3 e3").s("sawtooth")""", 1.0)
        val effect = OfflineRenderer.render("""note("c3 e3").s("sawtooth").K(audioin().mul(0.5).out())""", 1.0)
        assertTrue(dry.maxOf { abs(it) } > 0.01f)
        val ratio = effect.maxOf { abs(it) } / dry.maxOf { abs(it) }
        assertEquals(0.5, ratio.toDouble(), 0.1, "audioin().mul(0.5) halves the sound")
    }
}
