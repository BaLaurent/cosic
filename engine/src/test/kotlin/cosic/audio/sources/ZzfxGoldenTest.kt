package cosic.audio.sources

import cosic.pattern.Undefined
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
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

/**
 * ZZFX against the real `registerZZFXSounds` and `buildSamples` of the pinned superdough, run in Node by `tools/strudel-ref/zzfx-oracle.mjs`
 * (`src/test/resources/sources/zzfx.json`): the buffer each case plays, to the sample. The random start frequency is drawn from the same SplitMix64 on both sides.
 */
class ZzfxGoldenTest {
    private class Case(json: JsonObject) {
        val kind = json["kind"]!!.jsonPrimitive.content
        val name = json["name"]!!.jsonPrimitive.content
        val sampleRate = json["sampleRate"]!!.jsonPrimitive.content.toInt()
        val seed = json["seed"]!!.jsonPrimitive.content.toLong()
        val length = json["length"]?.jsonPrimitive?.content?.toInt()
        val error = json["error"]?.jsonPrimitive?.content
        val samples: FloatArray? = json["samples"]?.jsonPrimitive?.content?.let { SourceGolden.floats(it) }
        val sound = json["sound"]?.jsonPrimitive?.content
        val controls: Map<String, Any?> = json["controls"]?.jsonObject?.mapValues { plain(it.value) } ?: emptyMap()
        val durationSec = json["durationSec"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val args: DoubleArray = json["args"]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: fail("not a number") }?.toDoubleArray() ?: DoubleArray(0)

        override fun toString() = name

        private fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonArray -> e.map { plain(it) }
            is JsonObject -> fail("an object control")
            // JSON has no undefined: the oracle writes an undefined entry of a list as the string "undefined"
            is JsonPrimitive -> if (e.isString) (if (e.content == "undefined") Undefined else e.content) else e.booleanOrNull ?: e.doubleOrNull
        }
    }

    private val root: JsonObject by lazy {
        val text = checkNotNull(ZzfxGoldenTest::class.java.getResourceAsStream("/sources/zzfx.json")) { "missing golden zzfx.json" }.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }
    private val cases: List<Case> by lazy { root["cases"]!!.jsonArray.map { Case(it.jsonObject) } }

    private val verbose = System.getenv("COSIC_SOURCES_VERBOSE") == "true"

    private fun spec(c: Case) = SourceSpec(c.sampleRate, c.controls.asSourceControls(), c.durationSec, 1.0, c.seed)

    private fun complete(args: DoubleArray) = DoubleArray(ZZFX_PARAMS) { if (it < args.size) args[it] else ZZFX_DEFAULTS[it] }

    @Test
    fun theSoundsAreTheOnesTheOracleRegistered() {
        val names = root["sounds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(names, ZzfxSynths.names)
    }

    @Test
    fun theOracleHasEveryKindOfCase() {
        assertTrue(cases.size >= 40, "only ${cases.size} cases")
        assertTrue(cases.any { it.kind == "build" } && cases.any { it.kind == "sound" })
    }

    @Test
    fun parametersAndBuffersMatchTheRealSounds() {
        for (c in cases) {
            val expected = assertNotNull(c.samples, c.name)
            val params = if (c.kind == "sound") zzfxParams(c.sound!!, c.controls.asSourceControls(), c.durationSec) else complete(c.args)
            val built = buildZzfx(c.sampleRate, SourceRng(c.seed), params)
            assertEquals(c.length, built.size, "${c.name}: length")
            assertClose("${c.name} (buildZzfx)", expected, built, ATOL, RTOL)
        }
    }

    @Test
    fun theSourcePlaysTheSameBufferWhateverTheBlockSize() {
        for (c in cases.filter { it.kind == "sound" }) {
            val expected = assertNotNull(c.samples, c.name)
            val source = assertNotNull(ZzfxSynths.create(c.sound!!, spec(c)), c.name)
            val frames = expected.size + 300
            val (left, right) = renderSource(source, 750L, frames, 128, 100, 1, 4096)
            assertClose("${c.name} (source)", expected, left, ATOL, RTOL, 0, expected.size)
            for (i in expected.size until frames) assertEquals(0f, left[i], "${c.name}: frame $i after the end")
            assertTrue(source.isFinished, c.name)
            assertEquals(left.toList(), right.toList(), "${c.name}: mono")
        }
    }

    @Test
    fun aSourceWhoseBufferCouldNotBeCreatedIsSilent() {
        // the oracle reports no case of this kind; a negative sustain brings the length below 0, where superdough's createBuffer throws
        val source = ZzfxSynths.create("zzfx", SourceSpec(48000, mapOf("zzfx" to listOf(1.0, 0.0, 220.0, -1.0, -1.0, -1.0)).asSourceControls(), 0.1, 1.0, 1L))!!
        source.start(0L)
        val left = FloatArray(64) { 1f }
        assertEquals(0, source.render(left, FloatArray(64), 64))
        assertTrue(source.isFinished)
        assertTrue(left.all { it == 0f })
    }

    @Test
    fun theOracleReportsNoErrors() {
        assertNull(cases.firstOrNull { it.error != null }?.name)
    }

    private companion object {
        const val ATOL = 2e-6
        const val RTOL = 1e-5
    }
}
