package cosic.soundfont.sf2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun sf2Resource(name: String): ByteArray = Sf2FileTest::class.java.getResourceAsStream("/soundfont/sf2/$name")!!.use { it.readBytes() }

internal val sf2Oracle: JsonObject = Json.parseToJsonElement(sf2Resource("oracle.json").toString(Charsets.UTF_8)).jsonObject

/** `soundfont2` read by Node on `test.sf2` (tools/strudel-ref/sf2-oracle.mjs): the parsed file as the oracle saw it. */
class Sf2FileTest {
    private val parsed = sf2Oracle.getValue("parsed").jsonObject
    private val file = Sf2File.parse(sf2Resource("test.sf2"))

    private fun JsonElement.generators(): Map<Int, Any> = jsonObject.entries.associate { (id, v) ->
        id.toInt() to if (v is JsonObject) v.getValue("lo").jsonPrimitive.int to v.getValue("hi").jsonPrimitive.int else v.jsonPrimitive.int
    }

    private fun Map<Int, Sf2Generator>.simple(): Map<Int, Any> = mapValues { (_, g) -> if (g.isRange) g.lo to g.hi else g.value }

    @Test
    fun theMetaDataIsRead() {
        assertEquals(parsed.getValue("metaData").jsonObject.getValue("version").jsonPrimitive.content, file.version)
        assertEquals(parsed.getValue("metaData").jsonObject.getValue("name").jsonPrimitive.content, file.name)
    }

    @Test
    fun theSamplesHaveTheHeadersAndDataThePackageMakes() {
        val expected = parsed.getValue("samples").jsonArray
        assertEquals(expected.size, file.samples.size)
        for ((i, e) in expected.withIndex()) {
            val o = e.jsonObject
            val h = file.samples[i].header
            assertEquals(o.getValue("name").jsonPrimitive.content, h.name)
            assertEquals(o.getValue("start").jsonPrimitive.long, h.start)
            assertEquals(o.getValue("end").jsonPrimitive.long, h.end)
            assertEquals(o.getValue("startLoop").jsonPrimitive.long, h.startLoop, "startLoop of $i is relative to the start")
            assertEquals(o.getValue("endLoop").jsonPrimitive.long, h.endLoop)
            assertEquals(o.getValue("sampleRate").jsonPrimitive.long, h.sampleRate)
            assertEquals(o.getValue("originalPitch").jsonPrimitive.int, h.originalPitch)
            assertEquals(o.getValue("pitchCorrection").jsonPrimitive.int, h.pitchCorrection)
            assertEquals(o.getValue("link").jsonPrimitive.int, h.link)
            assertEquals(o.getValue("type").jsonPrimitive.int, h.type)
            val data = file.samples[i].data
            assertEquals(o.getValue("length").jsonPrimitive.int, data.size)
            assertEquals(o.getValue("first").jsonArray.map { it.jsonPrimitive.int }, data.take(5).map { it.toInt() })
            assertEquals(o.getValue("last").jsonArray.map { it.jsonPrimitive.int }, data.takeLast(3).map { it.toInt() })
            assertEquals(o.getValue("sum").jsonPrimitive.long, data.sumOf { it.toLong() })
        }
    }

    @Test
    fun theInstrumentsHaveTheirGlobalZoneAndZones() {
        val expected = parsed.getValue("instruments").jsonArray
        assertEquals(expected.size, file.instruments.size)
        for ((i, e) in expected.withIndex()) {
            val o = e.jsonObject
            val instrument = file.instruments[i]
            assertEquals(o.getValue("name").jsonPrimitive.content, instrument.name)
            val global = o.getValue("global")
            if (global is JsonPrimitive) assertNull(instrument.globalZone) else assertEquals(global.generators(), instrument.globalZone!!.generators.simple())
            val zones = o.getValue("zones").jsonArray
            assertEquals(zones.size, instrument.zones.size, instrument.name)
            for ((j, z) in zones.withIndex()) {
                assertEquals(z.jsonObject.getValue("gens").generators(), instrument.zones[j].generators.simple(), "${instrument.name} zone $j")
                assertEquals(z.jsonObject.getValue("sample").jsonPrimitive.int, file.samples.indexOf(instrument.zones[j].sample))
            }
        }
    }

    @Test
    fun thePresetsHaveTheirHeadersGlobalZoneAndInstruments() {
        val expected = parsed.getValue("presets").jsonArray
        assertEquals(expected.size, file.presets.size)
        for ((i, e) in expected.withIndex()) {
            val o = e.jsonObject
            val preset = file.presets[i]
            assertEquals(o.getValue("name").jsonPrimitive.content, preset.header.name)
            assertEquals(o.getValue("preset").jsonPrimitive.int, preset.header.preset)
            assertEquals(o.getValue("bank").jsonPrimitive.int, preset.header.bank)
            val global = o.getValue("global")
            if (global is JsonPrimitive) assertNull(preset.globalZone) else assertEquals(global.generators(), preset.globalZone!!.generators.simple())
            val zones = o.getValue("zones").jsonArray
            assertEquals(zones.size, preset.zones.size)
            for ((j, z) in zones.withIndex()) {
                assertEquals(z.jsonObject.getValue("gens").generators(), preset.zones[j].generators.simple(), "${preset.header.name} zone $j")
                assertEquals(z.jsonObject.getValue("instrument").jsonPrimitive.int, file.instruments.indexOf(preset.zones[j].instrument))
            }
        }
    }

    @Test
    fun whatIsNoSoundFontIsAnErrorThatSaysWhy() {
        assertTrue(assertFailsWith<Sf2FormatException> { Sf2File.parse("not riff".toByteArray()) }.message!!.startsWith("Invalid file format"))
        val bytes = sf2Resource("test.sf2")
        val wrongSignature = bytes.copyOf().also { it[8] = 'x'.code.toByte() }
        assertTrue(assertFailsWith<Sf2FormatException> { Sf2File.parse(wrongSignature) }.message!!.startsWith("Invalid signature"))
        assertFailsWith<Sf2FormatException> { Sf2File.parse(bytes.copyOf(60)) }
        assertFailsWith<Sf2FormatException> { Sf2File.parse(ByteArray(0)) }
    }
}
