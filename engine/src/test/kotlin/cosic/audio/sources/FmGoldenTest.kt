package cosic.audio.sources

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [FmBank] against the real `applyFM` of the pinned superdough run in headless Chromium (`tools/strudel-ref/fm-oracle.mjs` writes
 * `src/test/resources/sources/fm.json`: the real helpers.mjs, an OfflineAudioContext, the samples every case rendered).
 *
 * A "sum" case renders the frequency parameter the FM graph is connected to (a ConstantSourceNode's `offset`), that is `carrierHz` plus the signal
 * [FmBank.add] adds; an "osc" case renders an OscillatorNode that has the graph on its `frequency`, which is [PeriodicOscillator] with the same bus.
 * Every case is played with several block sizes, because the result must not depend on how the engine cuts the audio.
 */
class FmGoldenTest {
    private class Case(
        val name: String,
        val mode: String,
        val wave: String?,
        val sampleRate: Int,
        val frames: Int,
        val carrierHz: Double,
        val controls: Map<String, Any?>,
        val durationSec: Double,
        val crackleSeed: Long?,
        val output: FloatArray,
    )

    private fun load(): List<Case> {
        val text = checkNotNull(FmGoldenTest::class.java.getResourceAsStream("/sources/fm.json")) { "missing golden fm.json" }.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parseToJsonElement(text).jsonObject
        return root["cases"]!!.jsonArray.map { element ->
            val c = element.jsonObject
            val value = c["value"]!!.jsonObject
            val controls = value.mapValues { (_, v) -> primitive(v.jsonPrimitive) }
            val bytes = Base64.getDecoder().decode(c["output"]!!.jsonPrimitive.content)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            Case(
                name = c["name"]!!.jsonPrimitive.content,
                mode = c["mode"]!!.jsonPrimitive.content,
                wave = c["wave"]?.jsonPrimitive?.content,
                sampleRate = c["sampleRate"]!!.jsonPrimitive.content.toInt(),
                frames = c["frames"]!!.jsonPrimitive.content.toInt(),
                carrierHz = c["carrierHz"]!!.jsonPrimitive.double,
                controls = controls,
                durationSec = value["duration"]!!.jsonPrimitive.double,
                crackleSeed = c["crackleSeed"]?.jsonPrimitive?.content?.toLong(),
                output = FloatArray(buffer.remaining()).also { buffer.get(it) },
            )
        }
    }

    private fun primitive(p: JsonPrimitive): Any = if (p.isString) p.content else p.double

    /** Crackle operator `k` is seeded `spec.seed + k`; the oracle seeded the case with `4242 + k`. */
    private val seed = 4242L

    private fun bankOf(c: Case): FmBank? =
        FmBank.create(SourceSpec(c.sampleRate, c.controls.asSourceControls(), c.durationSec, 1.0, seed), c.carrierHz, c.durationSec)

    /** What the case rendered, played by the engine's pieces in blocks of [blockSize]. */
    private fun render(c: Case, blockSize: Int): FloatArray {
        val bank = bankOf(c)
        val carrier = c.carrierHz.toFloat().toDouble()
        if (c.mode == "osc") {
            val wave = PeriodicWave.basic(BasicWave.valueOf(c.wave!!.uppercase()), c.sampleRate)
            val bus = PitchBus(blockSize)
            return renderOscillator(wave, carrier, c.frames, blockSize, bus) { b, _, n -> bank?.add(b, n) }
        }
        val out = FloatArray(c.frames)
        val bus = PitchBus(blockSize)
        var frame = 0
        while (frame < c.frames) {
            val n = minOf(blockSize, c.frames - frame)
            bus.clear(n)
            bank?.add(bus, n)
            for (i in 0 until n) out[frame + i] = (carrier + (if (bus.frequencyActive) bus.frequencyAddHz[i] else 0.0)).toFloat()
            frame += n
        }
        return out
    }

    /**
     * What a case may differ by: `atol + rtol * |expected|`, over the first [frames] frames.
     *
     * Chromium plays an oscillator with float32 arithmetic (the frequency of every frame, the phase increment `frequency * (4096 / sampleRate)`), which makes
     * its modulators about 1e-7 faster or slower than doubles do, so the difference grows with time (3e-4 Hz in the first 512 frames of a 200 Hz modulation,
     * 6e-4 after 2400) and every level of modulation multiplies it: 1e-4 of the modulation range after three operators in a chain, and chaos in feedback
     * (`oscCycle`, `cycle3`), where it grows by a factor of ten every thousand frames. A modulator that is a saw or a square differs from Chromium's by a
     * frame or two a period as well (see [PeriodicOscillator]).
     */
    private class Tolerance(val atol: Double, val rtol: Double, val frames: Int = Int.MAX_VALUE)

    /** The peak of the signal the FM graph adds: errors are judged against it, as they come from the phase of oscillators that modulate at this scale. */
    private fun scaleOf(c: Case): Double {
        var peak = 0.0
        for (v in c.output) peak = maxOf(peak, abs(v.toDouble() - c.carrierHz))
        return maxOf(peak, 1.0)
    }

    private fun toleranceOf(c: Case): Tolerance {
        val scale = scaleOf(c)
        return when {
            c.name == "cycle3" -> Tolerance(2e-5 * scale, 0.0, frames = 2048)
            c.name == "oscCycle" -> Tolerance(4e-3, 0.0, frames = 2048)
            c.name == "fastModulatorClamped" -> Tolerance(1e-4 * scale, 0.0, frames = 64) // the operator swings to 160 kHz: its phase is all noise soon
            c.name in DEEP -> Tolerance(2e-4 * scale, 0.0)
            c.name.startsWith("random") -> Tolerance(1e-3 * scale + 1e-3, 0.0) // up to eight operators, waves, envelopes, loops: at most 4e-4 observed, a wrong graph is off by 1e-1
            c.name.startsWith("cycle") || c.name.startsWith("selfLoop") -> Tolerance(1e-4 * scale, 0.0)
            c.mode == "osc" -> Tolerance(4e-3, 1e-4)
            c.name.startsWith("wave") && c.name != "waveInvalid" && c.name != "waveUppercase" -> Tolerance(1e-3 * scale, 0.0)
            else -> Tolerance(1e-5 * scale + 1e-4, 0.0)
        }
    }

    private val DEEP = setOf("chain3", "chain5", "twoChains", "sampleRate22050")

    /** A message when [actual] is further from the case than [tolerance] allows, null when it is close enough. */
    private fun mismatch(c: Case, actual: FloatArray, tolerance: Tolerance, label: String): String? {
        var worst = 0.0
        var worstAt = -1
        var failure: String? = null
        for (i in 0 until minOf(tolerance.frames, c.frames)) {
            val e = c.output[i].toDouble()
            val a = actual[i].toDouble()
            val error = abs(a - e)
            if (failure == null && (a.isNaN() || error > tolerance.atol + tolerance.rtol * abs(e))) {
                failure = "${c.name} ($label): frame $i is $a, expected $e (error $error > ${tolerance.atol + tolerance.rtol * abs(e)})"
            }
            if (error > worst) {
                worst = error
                worstAt = i
            }
        }
        if (System.getenv("COSIC_FM_VERBOSE") == "true") {
            println("${c.name} ($label): worst error $worst at frame $worstAt (allowed ${tolerance.atol} + ${tolerance.rtol} |x|)")
            val windows = (0 until c.frames step 512).joinToString(" ") { w ->
                "%.1e".format((w until minOf(w + 512, c.frames)).maxOf { abs(actual[it].toDouble() - c.output[it].toDouble()) })
            }
            println("    per 512 frames: $windows")
        }
        return failure
    }

    @Test
    fun everyCaseMatchesChromium() {
        val cases = load()
        assertTrue(cases.size >= 50, "only ${cases.size} cases")
        val failures = cases.mapNotNull { c -> mismatch(c, render(c, 256), toleranceOf(c), "blocks of 256") }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} cases differ from Chromium:\n" + failures.joinToString("\n"))
    }

    @Test
    fun theBlockSizeDoesNotMatter() {
        for (c in load()) {
            val reference = render(c, 256)
            for (blockSize in intArrayOf(128, 100, 37, 7)) {
                val other = render(c, blockSize)
                for (i in reference.indices) {
                    if (reference[i] != other[i]) fail("${c.name}: frame $i is ${other[i]} in blocks of $blockSize and ${reference[i]} in blocks of 256")
                }
            }
        }
    }

    @Test
    fun graphsWithoutAConnectionToTheCarrierCreateNoBank() {
        val cases = load().associateBy { it.name }
        for (name in listOf("noEdges", "fmiZero")) {
            val c = cases.getValue(name)
            // fmiZero has an operator 2 whose amount reaches nothing: Chromium plays the plain carrier
            assertNull(bankOf(c), name)
            for (v in c.output) assertEquals(c.carrierHz.toFloat(), v, name)
        }
    }

    @Test
    fun cyclesAreNotMutedAndDelayTheConnectionThatClosesThem() {
        val cases = load().associateBy { it.name }
        for ((name, delayed) in mapOf("selfLoop" to 1, "cycle2" to 1, "cycle2OtherWay" to 1, "cycle3" to 1, "selfLoopInChain" to 1, "cycleBehindChain" to 1, "cycleTwoInputs" to 1)) {
            val bank = assertNotNull(bankOf(cases.getValue(name)), name)
            assertEquals(delayed, bank.delayedConnectionCount, "$name: connections that close a cycle")
        }
        for (name in listOf("simple", "chain2", "chain3", "chain5", "chain8", "matrix", "fanIn", "diamond", "twoChains")) {
            assertEquals(0, assertNotNull(bankOf(cases.getValue(name)), name).delayedConnectionCount, "$name has no cycle")
        }
        val random = load().filter { it.name.startsWith("random") }
        assertEquals(60, random.size)
        for (c in random.filter { it.name.startsWith("randomAcyclic") }) {
            assertEquals(0, assertNotNull(bankOf(c), c.name).delayedConnectionCount, "${c.name}: every connection goes from a higher operator to a lower one")
        }
        val general = random.filter { it.name.startsWith("randomGeneral") }.mapNotNull { bankOf(it) }
        assertTrue(general.count { it.delayedConnectionCount > 0 } >= 5, "the random general graphs contain loops")
    }
}
