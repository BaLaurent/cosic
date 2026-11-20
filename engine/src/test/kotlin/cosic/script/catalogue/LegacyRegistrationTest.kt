package cosic.script.catalogue

import cosic.mini.m as miniPattern
import cosic.pattern.JsFn
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Registry
import cosic.pattern.callMethod
import cosic.pattern.jsFn
import cosic.script.BuiltinKind
import cosic.script.Builtins
import cosic.script.evaluateForQuery
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The legacy builtins registered in the one catalogue behave like the old evaluator's: every old name has an entry, and
 * programs built through direct `Registry` calls give the haps of the same program run by the old evaluator.
 */
class LegacyRegistrationTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun register() = registerLegacy()

        /** The other tests of the module rely on the kernel's entries, which the legacy ones replaced. */
        @AfterClass
        @JvmStatic
        fun restoreKernel() = registerKernel()
    }

    private fun mini(s: String): Pattern = miniPattern(s, 0)

    private fun call(name: String, vararg args: Any?): Any? = Registry.call(name, args.asList())

    private fun pat(name: String, vararg args: Any?): Pattern = call(name, *args) as Pattern

    private fun fn(f: (Pattern) -> Pattern): JsFn = jsFn(1) { f(it[0] as Pattern) }

    private fun Pattern.speed2(): Pattern = callMethod("speed", 2.0)

    private fun shown(p: Pattern): List<String> =
        p.sortHapsByPart().queryArc(Rational.ZERO, Rational(3)).map { it.show(true) }

    private fun check(code: String, build: () -> Pattern) {
        val expected = shown(evaluateForQuery(code))
        assertTrue(expected.isNotEmpty(), "the old evaluator gave no haps for $code")
        assertEquals(expected, shown(build()), code)
    }

    @Test
    fun everyOldNameIsRegistered() {
        val missing = ArrayList<String>()
        for (builtin in Builtins.all) {
            if (builtin.kind == BuiltinKind.SETTING) continue
            for (name in listOf(builtin.name) + builtin.aliases) {
                if (Registry.lookup(name) == null && name !in Registry.constants()) missing.add(name)
            }
        }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun constructors() {
        check("cat(s(\"bd*2\"), s(\"sd*3\"))") { pat("cat", pat("s", mini("bd*2")), pat("s", mini("sd*3"))) }
        check("stack(s(\"bd*2\"), s(\"hh*4\"))") { pat("stack", pat("s", mini("bd*2")), pat("s", mini("hh*4"))) }
        check("seq(s(\"bd\"), s(\"sd sd\"), s(\"hh\"))") {
            pat("seq", pat("s", mini("bd")), pat("s", mini("sd sd")), pat("s", mini("hh")))
        }
        check("arrange([2, s(\"bd*2\")], [1, s(\"sd*4\")])") {
            pat("arrange", listOf(2.0, pat("s", mini("bd*2"))), listOf(1.0, pat("s", mini("sd*4"))))
        }
        check("n(run(4)).s(\"hh\")") { pat("n", pat("run", 4.0)).callMethod("s", "hh") }
        check("s(\"hh*4\").n(irand(4))") { pat("s", mini("hh*4")).callMethod("n", pat("irand", 4.0)) }
        check("s('bd')") { pat("s", "bd") }
    }

    @Test
    fun controls() {
        check("note(\"c3 e3 g3\").s(\"sine\").lpf(800).gain(0.5)") {
            pat("note", mini("c3 e3 g3")).callMethod("s", "sine").callMethod("lpf", 800.0).callMethod("gain", 0.5)
        }
        check("s(\"bd*2\").delay(0.5).delaytime(0.2)") {
            pat("s", mini("bd*2")).callMethod("delay", 0.5).callMethod("delaytime", 0.2)
        }
        check("s(\"bd sd\").lpf(\"<400 800>\")") { pat("s", mini("bd sd")).callMethod("lpf", mini("<400 800>")) }
        check("s(\"bd:2 hh\")") { pat("s", mini("bd:2 hh")) }
        check("s(\"hh*8\").gain(sine.range(0.3, 1))") {
            val sine = Registry.constants().getValue("sine") as Pattern
            pat("s", mini("hh*8")).callMethod("gain", sine.callMethod("range", 0.3, 1.0))
        }
    }

    @Test
    fun timeFunctions() {
        check("s(\"bd sd\").fast(2)") { pat("s", mini("bd sd")).callMethod("fast", 2.0) }
        check("s(\"bd sd\").fast(\"<2 4>\")") { pat("s", mini("bd sd")).callMethod("fast", mini("<2 4>")) }
        check("s(\"bd sd hh cp\").iter(4)") { pat("s", mini("bd sd hh cp")).callMethod("iter", 4.0) }
        check("s(\"bd sd\").hurry(2)") { pat("s", mini("bd sd")).callMethod("hurry", 2.0) }
        check("s(\"bd sd hh cp\").inside(2, rev)") {
            pat("s", mini("bd sd hh cp")).callMethod("inside", 2.0, fn { it.callMethod("rev") })
        }
        check("s(\"bd sd\").off(0.125, x => x.speed(2))") {
            pat("s", mini("bd sd")).callMethod("off", 0.125, fn { it.speed2() })
        }
        check("s(\"hh*8\").swingBy(1/3, 4)") { pat("s", mini("hh*8")).callMethod("swingBy", 1.0 / 3.0, 4.0) }
    }

    @Test
    fun structureFunctions() {
        check("s(\"bd*2 hh\").every(2, rev)") { pat("s", mini("bd*2 hh")).callMethod("every", 2.0, fn { it.callMethod("rev") }) }
        check("s(\"bd sd\").every(4, fast(2))") { pat("s", mini("bd sd")).callMethod("every", 4.0, call("fast", 2.0)) }
        check("s(\"bd\").euclid(3, 8)") { pat("s", mini("bd")).callMethod("euclid", 3.0, 8.0) }
        check("s(\"bd*2\").struct(\"x ~ x x\")") { pat("s", mini("bd*2")).callMethod("struct", mini("x ~ x x")) }
        check("s(\"hh*4\").mask(\"1 0 1 1\")") { pat("s", mini("hh*4")).callMethod("mask", mini("1 0 1 1")) }
        check("s(\"bd sd hh cp\").jux(rev)") { pat("s", mini("bd sd hh cp")).callMethod("jux", fn { it.callMethod("rev") }) }
        check("s(\"bd sd hh cp\").layer(rev, x => x.speed(2))") {
            pat("s", mini("bd sd hh cp")).callMethod("layer", fn { it.callMethod("rev") }, fn { it.speed2() })
        }
        check("s(\"bd sd hh cp\").chunk(4, x => x.speed(2))") {
            pat("s", mini("bd sd hh cp")).callMethod("chunk", 4.0, fn { it.speed2() })
        }
        check("s(\"bd sd\").chop(4)") { pat("s", mini("bd sd")).callMethod("chop", 4.0) }
        check("s(\"bd sd\").striate(4)") { pat("s", mini("bd sd")).callMethod("striate", 4.0) }
    }

    @Test
    fun randomAndValueFunctions() {
        check("s(\"hh*8\").sometimesBy(0.3, x => x.speed(2))") {
            pat("s", mini("hh*8")).callMethod("sometimesBy", 0.3, fn { it.speed2() })
        }
        check("s(\"hh*8\").degradeBy(0.5)") { pat("s", mini("hh*8")).callMethod("degradeBy", 0.5) }
        check("s(\"bd sd\").someCycles(rev)") { pat("s", mini("bd sd")).callMethod("someCycles", fn { it.callMethod("rev") }) }
        check("n(\"0 2 4 7\").scale(\"C:minor\").s(\"sine\")") {
            pat("n", mini("0 2 4 7")).callMethod("scale", "C:minor").callMethod("s", "sine")
        }
        check("note(\"c3 e3\").add(12).s(\"sine\")") { pat("note", mini("c3 e3")).callMethod("add", 12.0).callMethod("s", "sine") }
        check("n(\"1 2\").mul(2).s(\"hh\")") { pat("n", mini("1 2")).callMethod("mul", 2.0).callMethod("s", "hh") }
    }
}
