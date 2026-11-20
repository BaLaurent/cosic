package cosic.pattern

import cosic.parity.ParityData
import cosic.script.Realm
import cosic.script.SessionHost
import cosic.script.catalogue.Catalogue
import cosic.script.evaluateProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The controls of `controls.mjs`: the table against the record the oracle made of what the file does
 * (tools/strudel-ref/controls-registry.mjs), what a control makes of a value, and the namespaces programs see.
 * What a control does to a pattern is compared with Strudel's output for every name by the extra snippets of
 * parity/extra-snippets/1.3.json (ParityTest).
 */
class ControlsTest {
    private val registry: JsonObject by lazy {
        Json.parseToJsonElement(File(ParityData.dir, "controls-registry.json").readText()).jsonObject
    }

    private fun spec(name: String): ControlSpec = checkNotNull(Controls.method(name)) { "no control method $name" }

    private fun lines(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    // ---- the table -------------------------------------------------------------------------------------------------

    @Test
    fun thereAreTheRegistrationsAndNamesOfTheFile() {
        assertEquals(411, Controls.specs.size)
        val canonical = Controls.specs.map { it.name }
        assertEquals(411, canonical.toSet().size)
        assertEquals(249, Controls.specs.flatMap { it.aliases }.toSet().minus(canonical.toSet()).size)
        // every canonical name and every alias is a method, except `ds`, which a later function of the file takes
        assertEquals(660 - 1, Controls.methodNames().size)
        assertEquals(473, Controls.globalNames().size)
    }

    @Test
    fun theDomainsAreTheOnesOfTheTable() {
        val counts = Controls.specs.filter { it.line != 742 }.groupingBy { it.domain }.eachCount()
        assertEquals(225, counts[ControlDomain.AUDIO], "A: 161 lines and 64 FM registrations")
        assertEquals(54, counts[ControlDomain.UNDOCUMENTED])
        assertEquals(15, counts[ControlDomain.MIDI])
        assertEquals(11, counts[ControlDomain.SUPERDIRT])
        assertEquals(10, counts[ControlDomain.ZZFX])
        assertEquals(8, counts[ControlDomain.TONAL])
        assertEquals(5, counts[ControlDomain.VISUAL])
        assertEquals(2, counts[ControlDomain.OSC])
        assertEquals(81, Controls.specs.count { it.line == 742 })
    }

    @Test
    fun everyRegistrationIsWhatTheOracleRecorded() {
        val registrations = registry.getValue("registrations").jsonArray
        assertEquals(registrations.size, Controls.specs.size)
        for ((index, entry) in registrations.withIndex()) {
            val o = entry.jsonObject
            val s = Controls.specs[index]
            val what = "registration $index (line ${o.getValue("line")}, ${o.getValue("names")})"
            assertEquals(o.getValue("keys").jsonArray.map { it.jsonPrimitive.content }, s.keys, what)
            assertEquals(o.getValue("multi").jsonPrimitive.boolean, s.multi, "$what: array form")
            assertEquals(o.getValue("aliases").jsonArray.map { it.jsonPrimitive.content }, s.aliases, "$what: aliases")
            assertEquals(o.getValue("exports").jsonArray.map { it.jsonPrimitive.content }.toSet(), s.globalNames, "$what: exports")
            assertEquals(o.getValue("line").jsonPrimitive.int, s.line, "$what: line")
        }
    }

    @Test
    fun theMethodNamespaceIsWhatPatternPrototypeBecomes() {
        val methods = registry.getValue("methods").jsonObject
        val registrations = Controls.specs
        for ((name, entry) in methods) {
            val o = entry.jsonObject
            if ("overriddenBy" in o) {
                assertNull(Controls.method(name), "$name is the function of a later registration, not a control")
                continue
            }
            val owner = registrations[o.getValue("owner").jsonPrimitive.int]
            assertSame(owner, Controls.method(name), "method $name")
            assertEquals(o.getValue("keys").jsonArray.map { it.jsonPrimitive.content }, owner.keys, "method $name")
        }
        assertEquals(methods.keys - "ds", Controls.methodNames())
    }

    @Test
    fun theGlobalNamespaceIsWhatTheFileExports() {
        val globals = registry.getValue("globals").jsonObject
        for ((name, entry) in globals) {
            assertSame(Controls.specs[entry.jsonObject.getValue("owner").jsonPrimitive.int], Controls.global(name), "global $name")
        }
        assertEquals(globals.keys, Controls.globalNames())
        // strudel-names.json lists the exported control names of the oracle runtime: ours, and `ds`, which is exported too
        val names = Json.parseToJsonElement(ParityData.namesFile.readText()).jsonObject
        assertEquals(names.getValue("controls").jsonArray.map { it.jsonPrimitive.content }.toSet(), Controls.globalNames() + "ds")
    }

    @Test
    fun theQuirksOfTheFileAreKept() {
        // call B of the FM registrations takes fmh1 and fmi1 from call A as methods; the exported functions stay call A's
        assertEquals(listOf("fmi", "fmh"), Controls.method("fmh1")!!.keys)
        assertEquals(listOf("fmh", "fmi"), Controls.global("fmh1")!!.keys)
        assertEquals(listOf("fmi", "fmh"), Controls.method("fmi1")!!.keys)
        assertEquals(listOf("fmi", "fmh"), Controls.global("fmi1")!!.keys)
        // `colour` is no registration; `o` is a method, not a function; `ds` is not a control method
        assertNull(Controls.global("colour"))
        assertNull(Controls.method("colour"))
        assertNull(Controls.global("o"))
        assertEquals("orbit", Controls.method("o")!!.name)
        assertNull(Controls.method("ds"))
        assertNull(Controls.global("ds"))
        assertTrue(Controls.isControlName("ds"))
        assertEquals("delaysync", Controls.canonicalName("ds"))
        // the names of the controls
        assertEquals("cutoff", Controls.canonicalName("lpf"))
        assertEquals("bandf", Controls.canonicalName("bpf"))
        assertEquals("hcutoff", Controls.canonicalName("hpf"))
        assertEquals("roomsize", Controls.canonicalName("size"))
        assertEquals("fmi", Controls.canonicalName("fmh1"))
        assertEquals("fmi", Controls.canonicalName("fmi1"))
        assertEquals("nope", Controls.canonicalName("nope"))
        assertFalse(Controls.isControlName("nope"))
        // a single-key array form and a pair of colour names
        assertTrue(spec("fmwave").multi && spec("fmwave").keys == listOf("fmwave"))
        assertEquals(listOf("color", "colour"), spec("color").keys)
        assertEquals(81, Controls.specs.count { it.name.startsWith("fmi") && it.name.length == 5 })
        assertEquals(listOf("fmi12"), Controls.specs.filter { it.aliases == listOf("fm12") }.map { it.name })
    }

    // ---- what a control makes of a value ------------------------------------------------------------------------------

    @Test
    fun aListFillsTheKeysOfAnArrayFormControlOneByOne() {
        assertEquals(mapOf("s" to "bd", "n" to 0.0, "gain" to 0.3), Controls.valueOf(spec("s"), listOf("bd", 0.0, 0.3)))
        assertEquals(mapOf("cutoff" to 1000.0, "resonance" to 10.0), Controls.valueOf(spec("lpf"), listOf(1000.0, 10.0)))
        assertEquals(listOf("cutoff", "resonance"), Controls.valueOf(spec("lpf"), listOf(1000.0, 10.0)).keys.toList())
        // more entries than keys: the rest is dropped; fewer: only the first keys are set
        assertEquals(mapOf("delay" to 1.0, "delaytime" to 2.0, "delayfeedback" to 3.0), Controls.valueOf(spec("delay"), listOf(1.0, 2.0, 3.0, 4.0)))
        assertEquals(mapOf("room" to 1.0), Controls.valueOf(spec("room"), listOf(1.0)))
        // the keys of fmi are swapped against fmh
        assertEquals(mapOf("fmi" to 1.0, "fmh" to 2.0), Controls.valueOf(spec("fmi"), listOf(1.0, 2.0)))
        assertEquals(mapOf("fmh" to 1.0, "fmi" to 2.0), Controls.valueOf(spec("fmh"), listOf(1.0, 2.0)))
    }

    @Test
    fun aListIsStoredWholeWhereTheControlIsNotOfTheArrayForm() {
        assertEquals(mapOf("gain" to listOf(1.0, 2.0)), Controls.valueOf(spec("gain"), listOf(1.0, 2.0)))
        assertEquals(mapOf("nrpnn" to listOf(1.0, 8.0)), Controls.valueOf(spec("nrpnn"), listOf(1.0, 8.0)))
        // fmwave is the array form with one key: the list is spread over that key, so only its first entry is left
        assertEquals(mapOf("fmwave" to "sine"), Controls.valueOf(spec("fmwave"), listOf("sine", 3.0)))
    }

    @Test
    fun aValueThatIsNotAListSetsTheFirstKeyOnly() {
        assertEquals(mapOf("cutoff" to 500.0), Controls.valueOf(spec("lpf"), 500.0))
        assertEquals(mapOf("s" to "bd:3"), Controls.valueOf(spec("s"), "bd:3"))
        assertEquals(mapOf("gain" to true), Controls.valueOf(spec("gain"), true))
        assertEquals(mapOf("gain" to Undefined), Controls.valueOf(spec("gain"), Undefined))
    }

    @Test
    fun anObjectWithAValueSetsTheControlAndKeepsItsOtherEntriesFirst() {
        val value = linkedMapOf<String, Any?>("value" to 3.0, "color" to "red")
        val result = Controls.valueOf(spec("n"), value)
        assertEquals(mapOf("color" to "red", "n" to 3.0), result)
        assertEquals(listOf("color", "n"), result.keys.toList())
        // the object that came in is not changed
        assertEquals(listOf("value", "color"), value.keys.toList())
        // a list in value is spread over the keys of an array form control
        assertEquals(mapOf("x" to 1.0, "cutoff" to 2.0, "resonance" to 3.0), Controls.valueOf(spec("lpf"), linkedMapOf("value" to listOf(2.0, 3.0), "x" to 1.0)))
        // an object without value, or with it undefined, is the value
        assertEquals(mapOf("gain" to mapOf("a" to 1.0)), Controls.valueOf(spec("gain"), mapOf("a" to 1.0)))
        assertEquals(mapOf("gain" to mapOf("value" to Undefined)), Controls.valueOf(spec("gain"), mapOf("value" to Undefined)))
    }

    @Test
    fun nullIsNotAValue() {
        val e = assertFailsWith<CosicError> { Controls.valueOf(spec("gain"), null) }
        assertEquals("Cannot read properties of null (reading 'value')", e.message)
    }

    // ---- what a control does to a pattern ------------------------------------------------------------------------------

    @Test
    fun aControlWithoutAPatternMakesAPatternOfItsValue() {
        assertEquals(listOf("[ 0/1 → 1/1 | gain:0.5 ]"), Controls.applyControl(spec("gain"), 0.5, null).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd n:3 ]"), Controls.applyControl(spec("s"), listOf("bd", 3.0), null).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 |  ]"), Controls.applyControl(spec("s"), Undefined, null).firstLines())
    }

    @Test
    fun aControlWithAPatternSetsItWithTheStructureOfThePattern() {
        val bd = fastcat(pure(linkedMapOf<String, Any?>("s" to "bd")), pure(linkedMapOf<String, Any?>("s" to "hh")))
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd gain:0.5 ]", "[ 1/2 → 1/1 | s:hh gain:0.5 ]"),
            Controls.applyControl(spec("gain"), 0.5, bd).firstLines(),
        )
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd gain:1 ]", "[ 1/2 → 1/1 | s:hh gain:3 ]"),
            Controls.applyControl(spec("gain"), fastcat(pure(1.0), pure(3.0)), bd).firstLines(),
        )
    }

    @Test
    fun aControlWithoutAValueTurnsTheValuesOfThePatternIntoTheControl() {
        val notes = fastcat(pure("c"), pure("e"))
        assertEquals(listOf("[ 0/1 → 1/2 | note:c ]", "[ 1/2 → 1/1 | note:e ]"), Controls.applyControl(spec("note"), Undefined, notes).firstLines())
    }

    @Test
    fun aValueThatIsConstantFragmentsTheWholesAtCycleBoundaries() {
        val slow = pure(linkedMapOf<String, Any?>("s" to "bd"))._slow(Rational(2))
        assertEquals(
            listOf("[ (0/1 → 1/1) ⇝ 2/1 | s:bd cutoff:500 ]", "[ 0/1 ⇜ (1/1 → 2/1) | s:bd cutoff:500 ]"),
            Controls.applyControl(spec("lpf"), 500.0, slow).lines(0, 2),
        )
    }

    @Test
    fun aReceiverThatIsNotAPatternIsAnError() {
        val e = assertFailsWith<CosicError> { Controls.applyControl(spec("gain"), 0.5, "x") }
        assertEquals("pat.set is not a function", e.message)
        // a falsy one is no receiver at all
        assertEquals(listOf("[ 0/1 → 1/1 | gain:0.5 ]"), Controls.applyControl(spec("gain"), 0.5, 0.0).firstLines())
    }

    @Test
    fun setComposesTwoValuesTheWayTheOperatorsDo() {
        assertEquals(mapOf("a" to 1.0, "b" to 3.0, "c" to 4.0), composeSet(mapOf("a" to 1.0, "b" to 2.0), mapOf("b" to 3.0, "c" to 4.0)))
        assertEquals(5.0, composeSet(1.0, 5.0))
        assertEquals(mapOf("value" to "c", "s" to "x"), composeSet("c", mapOf("s" to "x")))
    }

    @Test
    fun createParamsMakesControlsThatAreNotRegistered() {
        val params = Controls.createParams("foo", "bar")
        assertEquals(listOf("[ 0/1 → 1/1 | foo:1 ]"), params.getValue("foo")(1.0, null).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | bar:2 ]"), params.getValue("bar")(2.0, null).firstLines())
        assertFalse(Controls.isControlName("foo"))
    }

    // ---- what a program sees ----------------------------------------------------------------------------------------------------

    private val realm: Realm by lazy { Realm(SessionHost()).also { Catalogue.install(it) } }
    private val globals: Set<String> by lazy { realm.global.ownKeys().toSet() }
    private val prototype: Set<String> by lazy { realm.patternPrototype.ownKeys().toSet() }

    @Test
    fun theNamespacesOfAProgramAreStrudels() {
        for (name in Controls.globalNames()) assertTrue(name in globals, "global $name")
        for (name in Controls.methodNames()) assertTrue(name in prototype, "method $name")
        // methods only: aliases the file does not export
        for (name in listOf("o", "tremsync", "lpe", "fm00", "fmi00", "att")) {
            assertTrue(name in prototype, "method $name")
        }
        for (name in listOf("o", "tremsync", "fm00", "fmi00", "bb")) assertFalse(name in globals, "$name is not a function")
        // `colour` is an export that is undefined; nothing sets it on a pattern; `ds` is the function of the adsr family
        assertTrue("colour" in globals)
        assertFalse("colour" in prototype)
        assertTrue("ds" in globals && "ds" in prototype)
        val ds = assertNotNull(Registry.lookup("ds"))
        assertEquals(RegisteredKind.REGISTER, ds.kind)
        assertEquals(2, ds.arity)
        // nothing but a control is registered under the name of a control: no later category took one
        val expected = Controls.methodNames() + Controls.globalNames()
        for (name in expected) {
            val entry = Registry.lookup(name) ?: continue
            assertEquals(RegisteredKind.FUNCTION, entry.kind, "$name is registered as a control")
            assertNotNull(entry.methodImpl, "$name is registered as a control")
        }
    }

    @Test
    fun everyControlStaysAControlInAProgram() {
        // no function of a later category may shadow a control (the only name Strudel has two functions for is `ds`)
        for (name in Controls.globalNames().sorted()) {
            val expected = Controls.valueOf(checkNotNull(Controls.global(name)), 1.0)
            assertEquals(listOf(expected), evaluateProgram("$name(1)").pattern.firstCycle().map { it.value }, "$name(1)")
        }
        for (name in Controls.methodNames().sorted()) {
            val got = evaluateProgram("s(\"bd\").$name(1)").pattern.firstCycle().map { it.value }
            assertEquals(listOf(jsMerge(mapOf("s" to "bd"), Controls.valueOf(spec(name), 1.0))), got, "s(\"bd\").$name(1)")
        }
    }

    @Test
    fun aColonListInTheMiniNotationFillsTheKeys() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | s:bd n:0 ]", "[ 1/4 → 1/2 | s:bd n:1 ]", "[ 1/2 → 3/4 | s:bd n:0 gain:0.3 ]", "[ 3/4 → 1/1 | s:bd n:1 gain:1.4 ]"),
            lines("""s("bd:0 bd:1 bd:0:0.3 bd:1:1.4")"""),
        )
        assertEquals(listOf("[ 0/1 → 1/1 | cutoff:1000 resonance:0 ]"), lines("""lpf("1000:0")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | delay:0.65 delaytime:0.25 delayfeedback:0.9 ]"), lines("""delay("0.65:0.25:0.9")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd:3 ]"), lines("""s('bd:3')"""), "a string with a colon in single quotes is just a string")
    }

    @Test
    fun noteKeepsTheNamesItIsGiven() {
        assertEquals(listOf("[ 0/1 → 1/2 | note:c ]", "[ 1/2 → 1/1 | note:a4 ]"), lines("""note("c a4")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | note:60 ]"), lines("note(60)"))
        assertEquals(listOf("[ 0/1 → 1/2 | note:c n:3 ]", "[ 1/2 → 1/1 | note:e ]"), lines("""note("c:3 e")"""))
    }

    @Test
    fun theZeroArgumentMethodTurnsTheValuesIntoTheControl() {
        assertEquals(listOf("[ 0/1 → 1/2 | note:c ]", "[ 1/2 → 1/1 | note:e ]"), lines(""""c e".note()"""))
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd ]", "[ 1/2 → 1/1 | s:hh ]"), lines(""""bd hh".s()"""))
    }

    @Test
    fun methodAndGlobalOfFmh1AreDifferentFunctions() {
        assertEquals(listOf("[ 0/1 → 1/1 | fmh:1 fmi:2 ]"), lines("""fmh1("1:2")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd fmi:1 fmh:2 ]"), lines("""s("bd").fmh1("1:2")"""))
    }

    @Test
    fun dsIsTheFunctionOfTheAdsrFamily() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd decay:0.4 sustain:0.2 ]"), lines("""s("bd").ds("0.4:0.2")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd decay:0.3 sustain:0 ]"), lines("""s("bd").ds(0.3)"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd delaysync:0.5 ]"), lines("""s("bd").delaysync(0.5)"""))
    }

    @Test
    fun adsrSetsFourControlsAndClearsWhatItIsNotGiven() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.1 decay:0.2 ]"), lines("""s("bd").adsr(".1:.2")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.1 decay:0.2 sustain:0.3 release:0.4 ]"), lines("""s("bd").adsr(".1:.2:.3:.4")"""))
        // a missing entry is undefined and overwrites what was set, which keeps its place in the object
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.1 decay:0.2 ]"), lines("""s("bd").sustain(0.5).adsr(".1:.2")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd sustain:0.9 attack:0.1 decay:0.2 ]"), lines("""s("bd").sustain(0.5).adsr(".1:.2").sustain(0.9)"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.5 ]"), lines("""s("bd").adsr(0.5)"""))
    }

    @Test
    fun adAndArDefaultTheSecondValueToTheFirst() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.1 decay:0.2 ]"), lines("""s("bd").ad("0.1:0.2")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.3 decay:0.3 ]"), lines("""s("bd").ad(0.3)"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.1 release:0.2 ]"), lines("""s("bd").ar("0.1:0.2")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd attack:0.3 release:0.3 ]"), lines("""s("bd").ar(0.3)"""))
    }

    @Test
    fun controlAndSysexTakeAnArray() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ccn:1 ccv:2 ]"), lines("""s("bd").control([1, 2])"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd sysexid:1 sysexdata:2 ]"), lines("""s("bd").sysex([1, 2])"""))
        assertEquals("control expects an array of [ccn, ccv]", assertFailsWith<CosicError> { evaluateProgram("""s("bd").control(5)""") }.message)
        assertEquals("sysex expects an array of [id, data]", assertFailsWith<CosicError> { evaluateProgram("""s("bd").sysex(5)""") }.message)
    }

    @Test
    fun asNamesTheValuesAfterTheControlsOfTheMapping() {
        assertEquals(listOf("[ 0/1 → 1/2 | note:c clip:0.5 ]", "[ 1/2 → 1/1 | note:a clip:1 ]"), lines(""""c:.5 a:1".as("note:clip")"""))
        // names are canonical: lpf is cutoff
        assertEquals(listOf("[ 0/1 → 1/1 | cutoff:1 hcutoff:2 ]"), lines(""""1:2".as("lpf:hpf")"""))
        // a value that is not a list is one value; one that is missing sets nothing
        assertEquals(listOf("[ 0/1 → 1/1 | s:x ]"), lines(""""x".as("s:n")"""))
    }

    @Test
    fun theDistortionShortcutsSetThreeControls() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd distort:2 distortvol:1 distorttype:soft ]"), lines("""s("bd").soft(2)"""))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd distort:2 distortvol:0.5 distorttype:fold ]"), lines("""s("bd").fold("2:0.5")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | distort:3 distortvol:1 distorttype:chebyshev ]"), lines("chebyshev(3)"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd distort:3 distortvol:1 distorttype:hard ]"), lines("""hard(3, s("bd"))"""))
    }

    @Test
    fun aModulatorIsAnObjectInTheControlMapOfTheControlBeforeIt() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:saw cutoff:500 lfo:{0:{control:cutoff rate:4} __ids:{}} ]"),
            lines("""s("saw").lpf(500).lfo({ rate: 4 })"""),
        )
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:saw cutoff:500 env:{0:{control:cutoff attack:1 decay:2} __ids:{}} ]"),
            lines("""s("saw").lpf(500).env({ a: 1, dec: 2 })"""),
        )
        // an id picks the entry; the entries print with the numbers first
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:saw cutoff:500 lfo:{3:{control:cutoff rate:1} __ids:{} x:{control:lfo_3 depth:2}} ]"),
            lines("""s("saw").lpf(500).lfo({ rate: 1 }, 3).lfo({ depth: 2 }, 'x')"""),
        )
    }

    @Test
    fun aProgramCanMakeControlsOfItsOwn() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | zzOwnA:1 ]", "[ 0/1 → 1/1 | s:bd zzOwnA:2 ]"),
            lines("""const { zzOwnA } = registerControl('zzOwnA'); stack(zzOwnA(1), s("bd").zzOwnA(2))"""),
        )
        assertEquals(
            listOf("[ 0/1 → 1/1 | n:1 ]", "[ 0/1 → 1/1 | s:zzOwnB ]"),
            lines("""registerControl('zzOwnB', 'zzOwnAliasB'); stack(n(isControlName('zzOwnAliasB') ? 1 : 0), s(getControlName('zzOwnAliasB')))"""),
        )
    }
}
