package cosic.sound

import cosic.BankGroup
import cosic.BuiltinSound
import cosic.SampleBank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SoundRegistryTest {
    private fun bank(name: String) = SampleBank(name, listOf(BankGroup(null, null, listOf("$name/0"))), null, false)

    private fun registry(vararg builtins: String) = SoundRegistry(builtins.toSet())

    @Test
    fun theKeyIsLowerCaseWithWhiteSpaceAsUnderscores() {
        assertEquals("my_drum_kit", registryKey("My  Drum\tKit"))
        assertEquals("a_b", registryKey("A\u00a0B"))
        assertEquals("rolandtr909_bd", registryKey("RolandTR909_bd"))
        assertEquals("", registryKey(""))
    }

    @Test
    fun aSoundIsFoundInAnyCase() {
        val registry = registry()
        val entry = bank("RolandTR909_bd")
        registry.register(entry)
        assertSame(entry, registry.lookup("rolandtr909_BD"))
        assertSame(entry, registry.lookup("ROLANDTR909_BD"))
        assertNull(registry.lookup("rolandtr909_sd"))
    }

    @Test
    fun registeringTheSameNameAgainReplacesItAndKeepsItsPlaceInTheList() {
        val registry = registry()
        val first = bank("a")
        val other = bank("b")
        val second = bank("A")
        registry.registerAll(listOf(first, other))
        registry.register(second)
        assertSame(second, registry.lookup("a"))
        assertEquals(listOf<Any>(second, other), registry.entries())
    }

    @Test
    fun builtInsAreFoundUntilARegistrationReplacesThem() {
        val registry = registry("sine", "Sawtooth")
        assertIs<BuiltinSound>(registry.lookup("SINE"))
        assertEquals("sine", registry.lookup("sine")!!.name)
        assertEquals("Sawtooth", registry.lookup("sawtooth")!!.name)
        val mine = bank("sine")
        registry.register(mine)
        assertSame(mine, registry.lookup("sine"), "the last registration wins, over a built-in too")
        assertEquals(listOf<Any>(mine), registry.entries(), "built-ins are not listed")
    }

    @Test
    fun aliasBankNamesTheSameEntryUnderTheAliasesOfItsBank() {
        val registry = registry()
        val tr909 = bank("RolandTR909_bd")
        val linn = bank("Linn9000_sd")
        val multi = bank("Multi_x")
        registry.registerAll(listOf(tr909, linn, multi))
        registry.aliasBank(mapOf("RolandTR909" to "TR909", "Linn9000" to "9000", "Multi" to listOf("m1", "m2")))
        assertSame(tr909, registry.lookup("tr909_bd"))
        assertSame(tr909, registry.lookup("TR909_BD"))
        assertSame(linn, registry.lookup("9000_sd"))
        assertSame(multi, registry.lookup("m1_x"))
        assertSame(multi, registry.lookup("m2_x"))
        assertSame(tr909, registry.lookup("rolandtr909_bd"), "the original name stays")
        assertEquals(listOf<Any>(tr909, linn, multi), registry.entries(), "an entry is listed once, not once per alias")
    }

    @Test
    fun aliasBankIsNotRetroactive() {
        val registry = registry()
        registry.register(bank("Box_a"))
        registry.aliasBank(mapOf("Box" to "B"))
        registry.register(bank("Box_late"))
        assertSame(registry.lookup("box_a"), registry.lookup("b_a"))
        assertNull(registry.lookup("b_late"), "sounds registered after the call are not aliased")
        registry.aliasBank(mapOf("Box" to "B"))
        assertSame(registry.lookup("box_late"), registry.lookup("b_late"), "calling it again aliases them")
    }

    @Test
    fun aKeyWithoutASecondPartIsNotAliasedAndOnlyTheFirstTwoPartsCount() {
        val registry = registry()
        registry.registerAll(listOf(bank("OberheimDMX_"), bank("a_b_c"), bank("alone")))
        registry.aliasBank(mapOf("OberheimDMX" to "DMX", "a" to "x", "alone" to "y"))
        assertNull(registry.lookup("dmx_"))
        assertNull(registry.lookup("y_"))
        assertNull(registry.lookup("x_b_c"))
        assertSame(registry.lookup("a_b_c"), registry.lookup("x_b"), "the third part is dropped")
    }

    @Test
    fun anAliasOverwritesTheSoundOfThatName() {
        val registry = registry()
        val mine = bank("x_bd")
        registry.registerAll(listOf(mine, bank("y_bd")))
        registry.aliasBank(mapOf("x" to "y"))
        assertSame(mine, registry.lookup("y_bd"))
    }

    @Test
    fun soundAliasStoresTheAliasAsWrittenSoACapitalOneCannotBeFound() {
        val registry = registry("sine")
        val bd = bank("bd")
        registry.register(bd)
        assertTrue(registry.alias("bd", "Kick"))
        assertNull(registry.lookup("kick"))
        assertNull(registry.lookup("Kick"), "lookup lowercases what it is given")
        assertSame(bd, registry.exact("Kick"))
        assertTrue(registry.alias("BD", "snare"))
        assertSame(bd, registry.lookup("SNARE"))
        assertTrue(registry.alias("sine", "wave"))
        assertIs<BuiltinSound>(registry.lookup("wave"), "built-ins can be aliased")
    }

    @Test
    fun soundAliasOfAnUnknownSoundDoesNothing() {
        val registry = registry()
        val before = registry.revision
        assertFalse(registry.alias("nope", "x"))
        assertEquals(before, registry.revision)
        assertNull(registry.lookup("x"))
    }

    @Test
    fun exactDoesNotLowerCaseAndKnowsNoBuiltIns() {
        val registry = registry("sine")
        registry.register(bank("Piano"))
        assertSame(registry.lookup("piano"), registry.exact("piano"))
        assertNull(registry.exact("Piano"), "keys are lower case")
        assertNull(registry.exact("sine"))
    }

    @Test
    fun theRevisionChangesWithEveryChange() {
        val registry = registry()
        var seen = registry.revision
        fun changed(): Boolean = (registry.revision != seen).also { seen = registry.revision }
        registry.register(bank("a"))
        assertTrue(changed())
        registry.registerAll(emptyList())
        assertFalse(changed(), "registering nothing is no change")
        registry.alias("a", "b")
        assertTrue(changed())
        registry.aliasBank(mapOf("a" to "c"))
        assertTrue(changed())
        assertTrue(registry.remove("A"))
        assertTrue(changed())
        assertFalse(registry.remove("a"))
        assertFalse(changed())
    }

    @Test
    fun removeTakesTheSoundOutButLeavesItsAliases() {
        val registry = registry()
        val a = bank("a_x")
        registry.register(a)
        registry.aliasBank(mapOf("a" to "b"))
        assertTrue(registry.remove("A_X"))
        assertNull(registry.lookup("a_x"))
        assertSame(a, registry.lookup("b_x"))
        assertNotEquals(null, registry.entries().firstOrNull())
    }
}
