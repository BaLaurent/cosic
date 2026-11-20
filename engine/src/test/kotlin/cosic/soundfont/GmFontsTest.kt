package cosic.soundfont

import cosic.NoSampleStore
import cosic.pattern.getSoundIndex
import cosic.sound.RegistryLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GmFontsTest {
    private val gm = GmFonts.all

    @Test
    fun thereAre125SoundsInTheOrderOfTheFile() {
        assertEquals(125, gm.size)
        assertEquals("gm_piano", gm.keys.first())
        assertEquals("gm_epiano1", gm.keys.elementAt(1))
        assertEquals("gm_epiano2", gm.keys.elementAt(2))
        assertEquals("gm_gunshot", gm.keys.last())
        assertTrue(gm.keys.all { it.startsWith("gm_") && it == it.lowercase() && ' ' !in it }, "names are lower case snake case with the prefix")
    }

    @Test
    fun theFirstFourPianosAreOneSoundWith32Fonts() {
        val piano = gm.getValue("gm_piano")
        assertEquals(32, piano.size)
        assertEquals("0000_JCLive_sf2_file", piano[0])
        assertEquals("0001_FluidR3_GM_sf2_file", piano[5])
        assertEquals("0031_GeneralUserGS_sf2_file", piano[31])
        assertEquals("0040_FluidR3_GM_sf2_file", gm.getValue("gm_epiano1")[1])
    }

    @Test
    fun theFilesAreThoseOfGmMjs() {
        assertEquals(869, gm.values.sumOf { it.size })
        assertEquals(868, gm.values.flatten().toSet().size, "one font is listed under two sounds")
        val shape = Regex("""\d{4}_[A-Za-z0-9_]+_(sf2|SF2)_file""")
        for ((name, fonts) in gm) {
            assertTrue(fonts.isNotEmpty(), name)
            assertTrue(fonts.all { shape.matches(it) }, "$name: ${fonts.filterNot { shape.matches(it) }}")
        }
    }

    @Test
    fun theFontPickedByNWrapsAroundAndCountsBackFromTheEnd() {
        assertEquals(0, getSoundIndex(32.0, 32))
        assertEquals(31, getSoundIndex(-1.0, 32))
        assertEquals(1, getSoundIndex(33.0, 32))
        assertEquals(3, getSoundIndex(3.4, 32))
        assertEquals(4, getSoundIndex(3.5, 32))
    }

    @Test
    fun registeringMakesASoundfontBankOfEachNameThatIsFoundInAnyCase() {
        val library = RegistryLibrary(NoSampleStore, emptySet())
        GmFonts.register(library)
        val entries = library.entries()
        assertEquals(gm.keys.toList(), entries.map { it.name })
        assertTrue(entries.all { it is SoundfontBank && it.prebake && it.tag == null })
        val flute = assertIs<SoundfontBank>(library.sound("GM_Flute"))
        assertEquals(gm.getValue("gm_flute"), flute.fonts)
        assertNull(library.sound("gm_nothing"))
    }

    @Test
    fun theLastRegistrationOfANameWins() {
        val library = RegistryLibrary(NoSampleStore, emptySet())
        GmFonts.register(library)
        library.register(SoundfontBank("gm_piano", listOf("mine")))
        assertEquals(listOf("mine"), assertIs<SoundfontBank>(library.sound("gm_piano")).fonts)
        assertEquals(125, library.entries().size)
        GmFonts.register(library)
        assertEquals(gm.getValue("gm_piano"), assertIs<SoundfontBank>(assertNotNull(library.sound("gm_piano"))).fonts)
    }

    @Test
    fun aBankKeepsACopyOfItsFontsAndNeedsAtLeastOne() {
        val fonts = mutableListOf("a", "b")
        val bank = SoundfontBank("gm_test", fonts)
        fonts += "c"
        assertEquals(listOf("a", "b"), bank.fonts)
        assertFailsWith<IllegalArgumentException> { SoundfontBank("gm_none", emptyList()) }
    }
}
