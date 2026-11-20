package cosic.soundfont.sf2

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `sfumato`'s `startPresetNote` run by Node against a recording AudioContext (tools/strudel-ref/sf2-oracle.mjs), replayed on `presetNote`. */
class PresetNoteTest {
    private val file = Sf2File.parse(sf2Resource("test.sf2"))
    private val plays = sf2Oracle.getValue("play").jsonArray

    private fun near(expected: Double, actual: Double, what: String) =
        assertEquals(expected, actual, abs(expected) * 1e-12 + 1e-15, what)

    private fun JsonElement.nullableDouble(): Double? = if (this is JsonNull) null else jsonPrimitive.double

    @Test
    fun theZonesTheirRatesLoopsPansAndEnvelopesAreThoseOfSfumato() {
        assertTrue(plays.size >= 40)
        var zonesChecked = 0
        for (play in plays) {
            val p = play.jsonObject
            val midi = p.getValue("midi").jsonPrimitive.double
            val time = p.getValue("time").jsonPrimitive.double
            val stopAt = p.getValue("stopAt").jsonPrimitive.double
            val what = "preset ${p.getValue("preset")} midi $midi time $time"
            val expected = p.getValue("zones").jsonArray
            val zones = presetNote(file.presets[p.getValue("preset").jsonPrimitive.int], midi)
            assertEquals(expected.size, zones.size, "$what: zones")
            for ((i, e) in expected.withIndex()) {
                val o = e.jsonObject
                val z = zones[i]
                near(o.getValue("rate").jsonPrimitive.double, z.rate, "$what zone $i: rate")
                assertEquals(o.getValue("loop").jsonPrimitive.boolean, z.loop, "$what zone $i: loop")
                if (z.loop) {
                    near(o.getValue("loopStart").jsonPrimitive.double, z.loopStart, "$what zone $i: loopStart")
                    near(o.getValue("loopEnd").jsonPrimitive.double, z.loopEnd, "$what zone $i: loopEnd")
                } else {
                    assertEquals(null, o.getValue("loopStart").nullableDouble(), "$what zone $i: no loop start")
                }
                assertEquals(o.getValue("sampleRate").jsonPrimitive.int, z.sampleRate, "$what zone $i: sample rate")
                assertEquals(o.getValue("frames").jsonPrimitive.int, z.data.size, "$what zone $i: frames")
                for ((k, v) in o.getValue("first").jsonArray.withIndex()) assertEquals(v.jsonPrimitive.double.toFloat(), z.data[k], "$what zone $i: sample $k")
                near(o.getValue("pan").jsonPrimitive.double, z.pan, "$what zone $i: pan")
                near(o.getValue("starts").jsonArray[0].jsonPrimitive.double, time, "$what zone $i: start")
                near(o.getValue("stops").jsonArray[0].jsonPrimitive.double, z.sourceStop(stopAt), "$what zone $i: stop")
                val events = o.getValue("events").jsonArray
                val mine = z.envelope(time, stopAt)
                assertEquals(events.size, mine.size, "$what zone $i: events")
                for ((k, event) in events.withIndex()) {
                    val parts = event.jsonArray
                    assertEquals(parts[0].jsonPrimitive.content, mine[k].first, "$what zone $i event $k")
                    for (n in 1 until parts.size) near((parts[n] as JsonPrimitive).double, mine[k].second[n - 1], "$what zone $i event $k part $n")
                }
                zonesChecked++
            }
        }
        assertTrue(zonesChecked > 40, "$zonesChecked zones")
    }

    @Test
    fun aNoteOutsideEveryKeyRangeHasNoZones() {
        assertEquals(0, presetNote(file.presets[1], 19.0).size)
        assertEquals(0, presetNote(file.presets[1], 101.0).size)
        assertEquals(2, presetNote(file.presets[1], 60.0).size)
        assertEquals(3, presetNote(file.presets[1], 45.0).size)
    }
}
