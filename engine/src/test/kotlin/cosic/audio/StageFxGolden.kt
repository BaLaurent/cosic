package cosic.audio

import cosic.audio.dsp.DspGolden
import cosic.player.paramSetOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The golden vectors of `src/test/resources/dsp/stagefx.json`, written by `tools/strudel-ref/stagefx-oracle.mjs`: the compressor, tremolo, panner
 * and phaser of the real superdough, rendered by the `OfflineAudioContext` of headless Chromium (with the real `lfo-processor` worklet).
 */
internal object StageFxGolden {
    val doc: JsonObject = DspGolden.load("stagefx.json")

    fun cases(stage: String): List<StageFxCase> =
        doc["cases"]!!.jsonArray.map { StageFxCase(it.jsonObject) }.filter { it.stages.first() == stage }

    val probe: JsonObject get() = doc["probe"]!!.jsonObject

    // The same generators as the oracle (LCG noise exactly; the sines and bursts with the same formulas).
    private fun lcg(seed: Int, n: Int, amp: Double): FloatArray {
        var x = seed.toLong() and 0xFFFFFFFFL
        return FloatArray(n) {
            x = (x * 1664525L + 1013904223L) % 4294967296L
            ((x.toDouble() / 2147483648.0 - 1.0) * amp).toFloat()
        }
    }

    fun signal(spec: JsonObject, n: Int, sr: Int): FloatArray {
        fun num(key: String, default: Double): Double = spec[key]?.jsonPrimitive?.double ?: default
        return when (val kind = spec["kind"]!!.jsonPrimitive.content) {
            "lcg" -> lcg(num("seed", 0.0).toInt(), n, num("amp", 1.0))
            "dc" -> FloatArray(n) { num("value", 0.0).toFloat() }
            "sine" -> FloatArray(n) { (num("amp", 1.0) * sin(2.0 * PI * num("f", 440.0) * it / sr)).toFloat() }
            "bursts" -> {
                val f0 = num("f0", 90.0)
                val decay = num("decay", 0.04)
                val period = num("period", 0.1)
                val amp = num("amp", 0.9)
                val phase = num("phase", 0.0)
                val noise = lcg(num("seed", 1.0).toInt(), n, num("noise", 0.05))
                FloatArray(n) {
                    val tau = (it.toDouble() / sr) % period
                    (amp * sin(2.0 * PI * f0 * tau + phase) * exp(-tau / decay) + noise[it].toDouble()).toFloat()
                }
            }
            else -> fail("unknown signal kind $kind")
        }
    }
}

internal class StageFxCase(val json: JsonObject) {
    val name: String = json["name"]!!.jsonPrimitive.content
    val stages: List<String> = json["stages"]!!.jsonArray.map { it.jsonPrimitive.content }
    val sampleRate: Int = json["sampleRate"]!!.jsonPrimitive.content.toInt()
    val channels: Int = json["channels"]!!.jsonPrimitive.content.toInt()
    val frames: Int = json["frames"]!!.jsonPrimitive.content.toInt()
    val cps: Double = json["cps"]!!.jsonPrimitive.double
    val cycle: Double = json["cycle"]!!.jsonPrimitive.double
    val t: Double = json["t"]!!.jsonPrimitive.double
    val endWithRelease: Double = json["endWithRelease"]!!.jsonPrimitive.double
    val lead: Int = json["lead"]!!.jsonPrimitive.content.toInt()
    val output: List<FloatArray> = json["output"]!!.jsonArray.map { DspGolden.floats(it.jsonPrimitive.content) }
    val lrIdentical: Boolean = json["lrIdentical"]!!.jsonPrimitive.content.toBoolean()

    /** The control map of the event, as the pattern gives it (numbers as doubles, names as strings). */
    val controls: Map<String, Any?> = json["fx"]!!.jsonObject.mapValues { (_, v) ->
        val p = v as JsonPrimitive
        if (p.isString) p.content else p.double
    }

    val params: ParamSet get() = paramSetOf(controls)
    val timing: VoiceTiming get() = VoiceTiming(t, t + 1.0, endWithRelease, cps, cycle)

    /** The input channels, regenerated from their descriptions. */
    val input: List<FloatArray> = json["signals"]!!.jsonArray.map { StageFxGolden.signal(it.jsonObject, frames, sampleRate) }

    /** A fresh instance of the stage called [stage] in the chain (the case's `stages`). */
    fun stage(stage: String): ChainStage = when (stage) {
        "tremolo" -> TremoloStage(sampleRate)
        // The cases without a lead-in start a fresh node of the browser (cold); the others a node that was fed silence for a second (the settled state the stage starts voices in).
        "compressor" -> CompressorStage(sampleRate, warmStart = lead > 0)
        "phaser" -> PhaserStage(sampleRate)
        "pan" -> PanStage()
        else -> fail("unknown stage $stage")
    }

    /**
     * Runs the stages of the case over the input in blocks of the given sizes (taken in turn), the way a voice would: every stage is reset and configured
     * from the case's controls, then processes the block in place. Returns the output channels (2 when the chain made the block stereo).
     */
    fun run(blockSizes: IntArray = intArrayOf(256)): Array<FloatArray> {
        val chain = stages.map { stage(it) }
        val timing = timing
        val params = params
        val active = BooleanArray(chain.size)
        for (i in chain.indices) {
            chain[i].reset()
            active[i] = chain[i].configure(params, timing, true)
        }
        check(active.all { it }) { "$name: a stage is not in the chain" }
        val block = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(sampleRate)
        val out = Array(2) { FloatArray(frames) }
        var outChannels = channels
        var frame = 0
        var turn = 0
        while (frame < frames) {
            val n = minOf(blockSizes[turn++ % blockSizes.size], frames - frame)
            block.channels = channels
            for (ch in 0 until channels) System.arraycopy(input[ch], frame, if (ch == 0) block.l else block.r, 0, n)
            ctx.startFrame = frame.toLong()
            ctx.n = n
            for (stage in chain) stage.process(block, ctx)
            System.arraycopy(block.l, 0, out[0], frame, n)
            if (block.channels == 2) System.arraycopy(block.r, 0, out[1], frame, n)
            outChannels = block.channels
            frame += n
        }
        return if (outChannels == 2) out else arrayOf(out[0])
    }
}

/** The worst difference between two signals, failing when any frame is further off than [atol] + [rtol] * |expected|. */
internal fun worstDifference(label: String, expected: FloatArray, actual: FloatArray, atol: Double, rtol: Double = 0.0, from: Int = 0, to: Int = expected.size): Double {
    var worst = 0.0
    var worstAt = -1
    for (i in from until to) {
        val e = expected[i].toDouble()
        val a = actual[i].toDouble()
        val error = abs(a - e)
        if (a.isNaN() || error > atol + rtol * abs(e)) fail("$label: frame $i is $a, expected $e (error $error > ${atol + rtol * abs(e)})")
        if (error > worst) {
            worst = error
            worstAt = i
        }
    }
    if (System.getenv("COSIC_DSP_VERBOSE") == "true") println("$label: worst error $worst at frame $worstAt")
    return worst
}
