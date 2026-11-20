package cosic.audio

import cosic.audio.dsp.DspGolden
import cosic.audio.dsp.assertClose
import cosic.player.paramSetOf
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [Biquad] and [FilterStage] against Chromium (`tools/strudel-ref/filter-oracle.mjs`, `src/test/resources/dsp/filter.json`):
 *
 * * `biquad` cases: a real `BiquadFilterNode` of every type on noise, with the special cases (cutoff 0 and Nyquist, Q 0 and negative, detune).
 * * `stage` cases: the real `createFilter` of superdough (its envelope, LFO worklet and ladder worklet) on a biquad or ladder node, a second one for
 *   `ftype '24db'`. With an update interval of 1 the stage computes its coefficients for every frame as Chromium does for a moving parameter.
 */
class FilterGoldenTest {
    private val STATIC_KEYS = setOf("cutoff", "hcutoff", "bandf", "resonance", "hresonance", "bandq", "ftype", "drive")
    private val doc = DspGolden.load("filter.json")
    private val signals: Map<String, FloatArray> = doc["signals"]!!.jsonObject.mapValues { DspGolden.floats(it.value.jsonPrimitive.content) }

    private fun JsonObject.num(key: String): Double = this[key]!!.jsonPrimitive.double
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.outputs(): List<FloatArray> = (this["output"] as JsonArray).map { DspGolden.floats(it.jsonPrimitive.content) }

    private fun typeOf(name: String) = when (name) {
        "lowpass" -> BiquadType.LOWPASS
        "highpass" -> BiquadType.HIGHPASS
        "bandpass" -> BiquadType.BANDPASS
        "notch" -> BiquadType.NOTCH
        else -> error("unknown filter type $name")
    }

    private fun keysOf(kind: String) = when (kind) {
        "lowpass" -> FilterKeys.LOWPASS
        "highpass" -> FilterKeys.HIGHPASS
        "bandpass" -> FilterKeys.BANDPASS
        else -> error("unknown filter kind $kind")
    }

    @Test
    fun everyBiquadCaseMatchesChromium() {
        var worstOverall = 0.0
        for (entry in doc["biquad"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c.str("name")
            val rate = c.num("sampleRate").toInt()
            val frames = c.num("frames").toInt()
            val channels = c.num("channels").toInt()
            val nyquist = rate / 2.0
            // AudioParam values are float32; the frequency lives in [0, nyquist]
            val frequency = Math.min(Math.max(c.num("frequency").toFloat().toDouble(), 0.0), nyquist)
            val q = c.num("q").toFloat().toDouble()
            val detune = c.num("detune").toFloat().toDouble()
            val expected = c.outputs()
            val biquad = Biquad(rate)
            biquad.setCoefficients(typeOf(c.str("type")), frequency, detune, q)
            for (ch in 0 until channels) {
                val data = signals[(c["signals"] as JsonArray)[ch].jsonPrimitive.content]!!.copyOf(frames)
                biquad.process(data, frames, ch)
                worstOverall = Math.max(worstOverall, assertClose("$name ch$ch", expected[ch], data, atol = 1e-7, rtol = 1e-6))
            }
        }
        println("biquad: worst error against Chromium $worstOverall")
    }

    /** Whether the controls give the cutoff an envelope or an LFO (anything but the plain cutoff, Q, model and drive). */
    private fun isMoving(controls: Map<String, Any?>): Boolean = controls.keys.any { it !in STATIC_KEYS }

    private fun caseControls(c: JsonObject): Map<String, Any?> =
        c["controls"]!!.jsonObject.mapValues { (_, v) -> (v as JsonPrimitive).let { if (it.isString) it.content else it.double } }

    /** The frame the voice of a stage case starts on. */
    private fun onsetOf(c: JsonObject): Int = Math.round(c.num("start") * c.num("sampleRate")).toInt()

    /** The input of a stage case from its onset on (the golden input is silent before it); the stage is run from frame [onsetOf] like a voice. */
    private fun stageInput(c: JsonObject): List<FloatArray> {
        val frames = c.num("frames").toInt()
        val onset = onsetOf(c)
        return (0 until c.num("channels").toInt()).map { ch -> signals[if (ch == 0) "noiseA" else "noiseB"]!!.copyOfRange(onset, frames) }
    }

    private fun runCase(stage: FilterStage, c: JsonObject, blockSize: Int = 256): List<FloatArray> = runStage(stage, stageInput(c), blockSize, onsetOf(c).toLong())

    /** The golden output from the onset on. */
    private fun expectedFrom(c: JsonObject): List<FloatArray> = c.outputs().map { it.copyOfRange(onsetOf(c), it.size) }

    private fun stageFor(c: JsonObject, interval: Int): Pair<FilterStage, Boolean> {
        val controls = caseControls(c)
        val p = paramSetOf(controls)
        val timing = VoiceTiming()
        timing.set(c.num("start"), c.num("end"), c.num("end") + 1.0, c.num("cps"), c.num("cycle"))
        val stage = FilterStage(c.num("sampleRate").toInt(), typeOf(c.str("kind")), keysFor(c), interval)
        return stage to stage.configure(p, timing, true)
    }

    private fun keysFor(c: JsonObject) = keysOf(c.str("kind"))

    @Test
    fun everyStageCaseMatchesCreateFilterWithPerFrameCoefficients() {
        var worstExact = 0.0
        for (entry in doc["stage"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c.str("name")
            val loose = c["loose"]?.jsonPrimitive?.content == "true"
            val (stage, active) = stageFor(c, 1)
            assertTrue(active, "$name: the stage is in the chain")
            val expected = expectedFrom(c)
            val actual = runCase(stage, c)
            for (ch in actual.indices) {
                if (loose) {
                    // event times that are not on a frame: Chromium rounds them up to one, this stage does not
                    val rel = relativeRms(expected[ch], actual[ch])
                    assertTrue(rel < 0.03, "$name ch$ch: relative RMS difference $rel")
                } else {
                    // static settings are bit for bit Chromium's; a moving parameter differs by a float ulp here and there (the ramp values), which a
                    // filter with poles near 1 turns into rounding noise of up to a few 1e-6
                    val atol = if (isMoving(caseControls(c))) 4e-6 else 1e-7
                    worstExact = Math.max(worstExact, assertClose("$name ch$ch", expected[ch], actual[ch], atol = atol, rtol = 1e-6))
                }
            }
        }
        println("stage (interval 1): worst error against Chromium $worstExact")
    }

    @Test
    fun theDefaultUpdateIntervalStaysCloseToChromium() {
        for (entry in doc["stage"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c.str("name")
            val (stage, _) = stageFor(c, FILTER_UPDATE_INTERVAL)
            val expected = expectedFrom(c)
            val actual = runCase(stage, c)
            for (ch in actual.indices) {
                val rel = relativeRms(expected[ch], actual[ch])
                assertTrue(rel < 0.15, "$name ch$ch: relative RMS difference $rel with an interval of $FILTER_UPDATE_INTERVAL")
            }
        }
    }

    @Test
    fun theOutputDoesNotDependOnHowTheRenderingIsSplitIntoBlocks() {
        for (entry in doc["stage"]!!.jsonArray) {
            val c = entry.jsonObject
            val name = c.str("name")
            val reference = runCase(stageFor(c, FILTER_UPDATE_INTERVAL).first, c, 256)
            for (size in intArrayOf(128, 77, 100, 31, 1)) {
                val actual = runCase(stageFor(c, FILTER_UPDATE_INTERVAL).first, c, size)
                for (ch in actual.indices) {
                    for (i in actual[ch].indices) {
                        if (actual[ch][i] != reference[ch][i]) {
                            throw AssertionError("$name ch$ch: blocks of $size differ from blocks of 256 at frame $i (${actual[ch][i]} against ${reference[ch][i]})")
                        }
                    }
                }
            }
        }
    }

    private fun relativeRms(expected: FloatArray, actual: FloatArray): Double {
        var diff = 0.0
        var ref = 0.0
        for (i in expected.indices) {
            val d = (expected[i] - actual[i]).toDouble()
            diff += d * d
            ref += expected[i].toDouble() * expected[i]
        }
        return if (ref == 0.0) sqrt(diff) else sqrt(diff / ref)
    }
}
