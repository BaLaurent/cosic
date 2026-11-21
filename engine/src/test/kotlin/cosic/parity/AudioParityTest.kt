package cosic.parity

import cosic.CosicEngine
import cosic.EvalReport
import cosic.audio.IrExecutor
import cosic.render.OfflineRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cosic's sound against Strudel's (Task 5.2): every program of tools/strudel-ref/audio/corpus.json (and the tunes of the pinned checkout) was rendered by
 * Strudel's own `renderPatternAudio` in headless Chromium (tools/strudel-ref/audio/render.mjs), which stored the features of the render in
 * `parity/audio/<id>.json`. This test renders the same program with [OfflineRenderer], with the sounds of the very files Chromium fetched
 * ([CacheSampleStore]), computes the same features ([AudioFeatureSpec]) and compares them under [AudioThresholds]. The thresholds are fixed by the plan: a failing entry is
 * a DSP bug to fix, never a number to loosen.
 *
 * Runs only with `-Dcosic.audioParity=true` (it needs the sample cache render.mjs fills, `~/.cache/cosic-samples`; `-Dcosic.audioParity.cache=<dir>` moves it).
 * `-Dcosic.audioParity.only=<regex>` selects entries by id. `engine/build/reports/audio-parity.md` lists every entry with its figures, and
 * `engine/build/audio-parity/<id>.f32` is Cosic's render (planar float32, left then right, like `~/.cache/cosic-audio-ref/<id>.f32`).
 */
@RunWith(Parameterized::class)
internal class AudioParityTest(private val case: AudioCase) {

    @Test(timeout = 1_800_000)
    fun soundsLikeStrudel() {
        assumeTrue("audio parity needs -Dcosic.audioParity=true and the cache of tools/strudel-ref/audio/render.mjs", enabled)
        val reference = case.load()
        val library = prebakedLibrary(store)
        val cps = (CosicEngine(SAMPLE_RATE, library, IrExecutor.INLINE).evaluate(reference.code) as? EvalReport.Ok)?.cps
        val started = System.nanoTime()
        val rendered = try {
            OfflineRenderer.render(reference.code, 0.0, reference.cycles, SAMPLE_RATE, library)
        } catch (e: IllegalStateException) {
            record(Row(case.id, "FAIL", "does not evaluate: ${e.message}", null))
            fail("${case.id}: does not evaluate: ${e.message}")
            return
        }
        val seconds = (System.nanoTime() - started) / 1e9
        if (cps == null || Math.abs(cps - reference.cps) > 1e-9) {
            record(Row(case.id, "FAIL", "tempo $cps cycles per second, Strudel's is ${reference.cps}", null))
            fail("${case.id}: tempo $cps cycles per second, Strudel's is ${reference.cps}")
        }
        val frames = reference.frames
        check(rendered.size / 2 >= frames) { "the render is ${rendered.size / 2} frames, Strudel's is $frames" }
        dump(case.id, rendered, frames)
        val got = AudioFeatureSpec.features(rendered, 2, frames, SAMPLE_RATE)
        val comparison = if (reference.random != null) AudioCompare.compareLongTerm(reference.longTerm, LongTerm.of(got), frames) else AudioCompare.compare(reference.channels, got, tonal = !reference.noisy)
        val gotPeak = rendered.take(frames * 2).maxOfOrNull { Math.abs(it) } ?: 0f
        val figures = if (reference.random != null) {
            "long-term (random by design: ${reference.random}): level %.2f dB, bands %.2f dB, %.0f s".format(comparison.rmsMedianDb, comparison.bandMedianDb, seconds)
        } else {
            "level %.2f/%.2f dB, centroid %.1f%%, bands %.2f dB, onsets %d, %d frames, %.0f s".format(
                comparison.rmsMedianDb, comparison.rmsP95Db, comparison.centroidMedianRelative * 100, comparison.bandMedianDb, comparison.referenceOnsets, comparison.activeFrames, seconds,
            )
        }
        val silent = reference.channels.all { c -> c.rms.all { it <= AudioThresholds.AUDIBLE_DB } }
        val notes = ArrayList<String>()
        if (silent) notes.add("reference is silent")
        if (reference.logs.isNotEmpty()) notes.add("Strudel logged: ${reference.logs.take(2).joinToString(" | ")}")
        record(Row(case.id, if (comparison.passed) "PASS" else "FAIL", figures + (if (notes.isEmpty()) "" else "; " + notes.joinToString("; ")), comparison.failures))
        if (silent) fail("${case.id}: the reference render is silent (Strudel logged: ${reference.logs.take(3)}); fix the corpus entry")
        if (!comparison.passed) {
            fail("${case.id} does not sound like Strudel (peak ${"%.3f".format(gotPeak)}; $figures)\n${shortened(reference.code)}\n" + comparison.failures.joinToString("\n"))
        }
    }

    private fun dump(id: String, interleaved: FloatArray, frames: Int) {
        val dir = File("build/audio-parity").also { it.mkdirs() }
        val bytes = ByteBuffer.allocate(frames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (c in 0..1) for (i in 0 until frames) bytes.putFloat(interleaved[i * 2 + c])
        File(dir, "$id.f32").writeBytes(bytes.array())
    }

    private fun shortened(code: String): String = if (code.length <= 600) code else code.take(600) + "\n..."

    class Row(val id: String, val status: String, val figures: String, val failures: List<String>?)

    /** One program of the corpus; the file is read when the test runs. */
    class AudioCase(val id: String, private val file: File) {
        override fun toString(): String = id

        fun load(): Reference {
            val doc = Json.parseToJsonElement(file.readText()).jsonObject
            val features = doc.getValue("features").jsonObject
            val channels = features.getValue("channels").jsonArray.map { c ->
                val o = c.jsonObject
                ChannelFeatures(
                    DoubleArray(o.getValue("rms").jsonArray.size) { o.getValue("rms").jsonArray[it].jsonPrimitive.double },
                    DoubleArray(o.getValue("centroid").jsonArray.size) { o.getValue("centroid").jsonArray[it].jsonPrimitive.double },
                    DoubleArray(o.getValue("bands").jsonArray.size) { o.getValue("bands").jsonArray[it].jsonPrimitive.double },
                    IntArray(o.getValue("onsets").jsonArray.size) { o.getValue("onsets").jsonArray[it].jsonPrimitive.int },
                )
            }
            val longTerm = doc["longTerm"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject?.let { l ->
                LongTerm(l.getValue("levelDb").jsonPrimitive.double, DoubleArray(AudioFeatureSpec.BAND_COUNT) { l.getValue("bandsDb").jsonArray[it].jsonPrimitive.double })
            }
            return Reference(
                doc.getValue("code").jsonPrimitive.content,
                doc.getValue("cycles").jsonPrimitive.double,
                doc.getValue("cps").jsonPrimitive.double,
                doc.getValue("noisy").jsonPrimitive.boolean,
                doc["random"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull,
                longTerm ?: LongTerm.of(channels),
                features.getValue("frames").jsonPrimitive.int,
                channels,
                doc.getValue("logs").jsonArray.map { it.jsonPrimitive.contentOrNull ?: "" },
            )
        }
    }

    /**
     * [random] is why the sound is random by design (null for a sound that renders the same every time): such an entry is compared long-term, against [longTerm], the mean over
     * several renders of Strudel (the figures of the one render in [channels] for a file that has none).
     */
    class Reference(
        val code: String,
        val cycles: Double,
        val cps: Double,
        val noisy: Boolean,
        val random: String?,
        val longTerm: LongTerm,
        val frames: Int,
        val channels: List<ChannelFeatures>,
        val logs: List<String>,
    )

    companion object {
        private const val SAMPLE_RATE = 48000
        private val enabled = System.getProperty("cosic.audioParity") == "true"
        private val only: Regex? = System.getProperty("cosic.audioParity.only")?.takeIf { it.isNotBlank() }?.let { Regex(it) }
        private val store by lazy { CacheSampleStore(CacheSampleStore.defaultRoot()) }
        private val rows = ArrayList<Row>()

        @Synchronized
        private fun record(row: Row) {
            rows.add(row)
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val dir = File(ParityData.dir, "audio")
            val files = dir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name } ?: emptyList()
            return files.map { AudioCase(it.nameWithoutExtension, it) }.filter { only == null || only.containsMatchIn(it.id) }.map { arrayOf(it) }
        }

        @AfterClass
        @JvmStatic
        fun writeReport() {
            if (!enabled || rows.isEmpty()) return
            val sorted = rows.sortedBy { it.id }
            val dir = File("build/reports").also { it.mkdirs() }
            val passed = sorted.count { it.status == "PASS" }
            val text = buildString {
                appendLine("# Audio parity against Strudel's offline renderer")
                appendLine()
                appendLine("$passed of ${sorted.size} entries pass. Figures: level = median/p95 of |dB difference| of the frame level, centroid = median relative difference, bands = median |dB difference| of the audible band energies.")
                appendLine()
                appendLine("| entry | result | figures |")
                appendLine("|---|---|---|")
                for (r in sorted) {
                    appendLine("| ${r.id} | ${r.status} | ${r.figures} |")
                    for (f in r.failures.orEmpty()) appendLine("| | | ↳ $f |")
                }
            }
            File(dir, "audio-parity.md").writeText(text)
        }
    }
}
