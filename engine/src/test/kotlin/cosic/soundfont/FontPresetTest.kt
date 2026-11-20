package cosic.soundfont

import cosic.SampleData
import java.util.Base64
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontPresetTest {
    private fun zone(low: Int, high: Int) = "{ keyRangeLow:$low, keyRangeHigh:$high, originalPitch:6000, coarseTune:0, fineTune:0, loopStart:5, loopEnd:50, sampleRate:44100, file:'AAAA' }"

    private fun font(vararg zones: String) = "var _tone_x_sf2_file={zones:[${zones.joinToString(",")}]};"

    private fun tune(originalPitch: Double, coarse: Double = 0.0, fine: Double = 0.0, loopStart: Double = 0.0, loopEnd: Double = 0.0, rate: Double = 44_100.0, dataRate: Int = 44_100) =
        FontZone(SampleData(FloatArray(100), dataRate), originalPitch, coarse, fine, loopStart, loopEnd, rate)

    // ------------------------------------------------------------------------------------------------ reading

    @Test
    fun aRecordedFontIsReadZoneByZone() {
        val preset = FontPreset.parse(fontFixture("0160_Chaos_sf2_file"))
        assertEquals(6, preset.zones.size)
        assertEquals(listOf(0.0 to 54.0, 55.0 to 72.0, 73.0 to 83.0, 84.0 to 96.0, 97.0 to 107.0, 108.0 to 127.0), preset.zones.map { it.keyRangeLow to it.keyRangeHigh })
        val last = preset.zones[5]
        assertEquals(9600.0, last.originalPitch)
        assertEquals(0.0, last.coarseTune)
        assertEquals(2.0, last.fineTune)
        assertEquals(9.0, last.loopStart)
        assertEquals(51.0, last.loopEnd)
        assertEquals(44_100.0, last.sampleRate)
        val file = assertNotNull(last.file)
        assertTrue(file.startsWith("SUQz"), "the payload is base64 of an mp3 (ID3 tag)")
        assertNull(last.sample)
        val bytes = Base64.getDecoder().decode(file)
        assertEquals("ID3", bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1))
    }

    @Test
    fun aFontWithoutALoopHasTheNumbersOfOneThatCannotLoop() {
        val zone = FontPreset.parse(fontFixture("1152_FluidR3_GM_sf2_file")).zones.single()
        assertEquals(0.0, zone.keyRangeLow)
        assertEquals(127.0, zone.keyRangeHigh)
        assertEquals(9700.0, zone.originalPitch)
        assertEquals(4.0, zone.coarseTune)
        assertEquals(-1.0, zone.fineTune)
        assertEquals(-1.0, zone.loopStart)
        assertEquals(-2.0, zone.loopEnd)
    }

    @Test
    fun theTextAroundTheObjectLiteralDoesNotMatter() {
        // the shape of the real files: a console.log line, comma-first members, // comments, tabs, a `;` after the object
        val text = "console.log('load _tone_x');\nvar _tone_x_sf2_file={\n\tzones:[\n\t\t{\n\t\t\tmidi:0\n\t\t\t,originalPitch:2400 //a comment\n\t\t\t,keyRangeLow:0\n" +
            "\t\t\t,keyRangeHigh:27\n\t\t\t,loopStart:133802\n\t\t\t,loopEnd:135153\n\t\t\t,coarseTune:0\n\t\t\t,fineTune:0\n\t\t\t,sampleRate:44100\n\t\t\t,ahdsr:true\n" +
            "\t\t\t,file:'QUJD'\n\t\t\t,anchor:0.0132\n\t\t\t//_tone.Gd_pno\n\t\t}\n\t]\n};\n"
        val zone = FontPreset.parse(text).zones.single()
        assertEquals(2400.0, zone.originalPitch)
        assertEquals(133_802.0, zone.loopStart)
        assertEquals("QUJD", zone.file)
    }

    @Test
    fun onlyTheTextBetweenTheFirstTwoObjectStartsIsRead() {
        // `preset.split('={')` keeps the first two pieces: a second assignment ends the object
        val text = "var a={zones:[${zone(0, 10)}]};\nvar b={zones:[${zone(20, 30)}]};"
        assertEquals(1, FontPreset.parse(text).zones.size)
        assertEquals(0.0, FontPreset.parse(text).zones[0].keyRangeLow)
    }

    @Test
    fun aMissingNumberIsNotANumberAndAMissingPayloadIsNull() {
        val zone = FontPreset.parse("var a={zones:[{keyRangeLow:'3', keyRangeHigh:true, file:'', sample:'QQ=='}]}").zones.single()
        assertEquals(3.0, zone.keyRangeLow, "a string is converted as JavaScript's comparison would")
        assertEquals(1.0, zone.keyRangeHigh)
        assertTrue(zone.originalPitch.isNaN())
        assertTrue(zone.loopStart.isNaN())
        assertNull(zone.file, "an empty string is falsy")
        assertEquals("QQ==", zone.sample)
    }

    @Test
    fun whatIsNoFontIsAnErrorThatSaysWhy() {
        val noMarker = assertFailsWith<FontFormatException> { FontPreset.parse("<html>404 Not Found</html>") }
        assertTrue("={" in noMarker.message!!, noMarker.message)
        assertFailsWith<FontFormatException> { FontPreset.parse("var a={nothing:[]};") }
        assertFailsWith<FontFormatException> { FontPreset.parse("var a={zones:5};") }
        assertFailsWith<FontFormatException> { FontPreset.parse("var a={zones:[1]};") }
        val broken = assertFailsWith<FontFormatException> { FontPreset.parse("var a={zones:[{keyRangeLow:}") }
        assertTrue(broken.message!!.startsWith("not a soundfont"), broken.message)
        assertEquals(0, FontPreset.parse("var a={zones:[]};").zones.size)
    }

    // ------------------------------------------------------------------------------------------------ findZone

    @Test
    fun findZoneTakesTheFirstZoneThatStartsBelowTheNoteAndEndsAtOrAboveItsPredecessor() {
        // keyRangeLow <= midi && keyRangeHigh + 1 >= midi: a zone owns the note one above its range when no earlier zone takes it
        val preset = FontPreset.parse(font(zone(0, 27), zone(28, 60)))
        assertEquals(0, preset.findZone(0.0))
        assertEquals(0, preset.findZone(27.0))
        assertEquals(0, preset.findZone(28.0), "27 + 1 >= 28: the first zone still takes it")
        assertEquals(1, preset.findZone(28.5))
        assertEquals(1, preset.findZone(60.0))
        assertEquals(1, preset.findZone(61.0))
        assertEquals(-1, preset.findZone(61.5))
        assertEquals(-1, preset.findZone(200.0))
        assertEquals(-1, preset.findZone(-1.0))
        assertEquals(-1, preset.findZone(Double.NaN))
        assertEquals(-1, preset.findZone(Double.POSITIVE_INFINITY))
    }

    @Test
    fun findZoneOfAnOverlappingFontTakesTheEarlierOne() {
        val preset = FontPreset.parse(font(zone(40, 50), zone(0, 127)))
        assertEquals(1, preset.findZone(10.0))
        assertEquals(0, preset.findZone(45.0))
        assertEquals(0, preset.findZone(51.0), "a zone one above its range wins over a later one that covers the note")
        assertEquals(1, preset.findZone(52.0))
    }

    @Test
    fun aZoneWithABackwardsRangeNeverMatches() {
        val preset = FontPreset.parse(font(zone(109, 100)))
        assertEquals(-1, preset.findZone(100.0))
        assertEquals(-1, preset.findZone(105.0))
        assertEquals(-1, preset.findZone(110.0))
    }

    // ------------------------------------------------------------------------------------------------ tuning

    @Test
    fun theRateIsTwoToThePitchDistanceInOctaves() {
        // originalPitch 2400 is MIDI note 24: it plays at rate 1 there and at 2 an octave higher
        val c1 = tune(2400.0)
        assertEquals(1.0, c1.playbackRate(24.0), 1e-12)
        assertEquals(2.0, c1.playbackRate(36.0), 1e-12)
        assertEquals(0.5, c1.playbackRate(12.0), 1e-12)
        // coarse and fine tuning move the base: 6000 - 100 * 1 - 50 = 5850, so MIDI note 60 plays 150 cents up
        val tuned = tune(6000.0, coarse = 1.0, fine = 50.0)
        assertEquals(5850.0, tuned.baseDetune)
        assertEquals(2.0.pow(150.0 / 1200.0), tuned.playbackRate(60.0), 1e-12)
        assertEquals(1.0905077326652577, tuned.playbackRate(60.0), 1e-12)
        assertEquals(1.5, tuned.transposeSemitones(60.0), 1e-12)
        // the semitones are what the sampler turns into the same rate
        assertEquals(tuned.playbackRate(60.0), 2.0.pow(tuned.transposeSemitones(60.0) / 12.0), 1e-12)
    }

    @Test
    fun aZoneLoopsWhenItStartsAfterFrameOneAndBeforeItEnds() {
        assertTrue(tune(0.0, loopStart = 133_802.0, loopEnd = 135_153.0).loops)
        assertTrue(tune(0.0, loopStart = 2.0, loopEnd = 3.0).loops)
        for ((start, end) in listOf(1.0 to 100.0, 100.0 to 100.0, 0.0 to 50.0, -1.0 to -2.0, 200.0 to 100.0, Double.NaN to 100.0, 50.0 to Double.NaN)) {
            assertEquals(false, tune(0.0, loopStart = start, loopEnd = end).loops, "$start..$end")
        }
    }

    @Test
    fun theLoopIsInFramesOfTheDecodedDataWhateverTheRateOfTheFont() {
        val same = tune(0.0, loopStart = 1000.0, loopEnd = 2000.0)
        assertEquals(1000.0, same.loopStartFrame, 1e-9)
        assertEquals(2000.0, same.loopEndFrame, 1e-9)
        // the decoder gave 22.05 kHz for a 44.1 kHz font: the same seconds are half the frames
        val half = tune(0.0, loopStart = 1000.0, loopEnd = 2000.0, dataRate = 22_050)
        assertEquals(500.0, half.loopStartFrame, 1e-9)
        assertEquals(1000.0, half.loopEndFrame, 1e-9)
        assertTrue(tune(0.0, loopStart = -1.0, loopEnd = -2.0).loopStartFrame.isNaN())
        assertTrue(tune(0.0, loopStart = -1.0, loopEnd = -2.0).loopEndFrame.isNaN())
    }
}
