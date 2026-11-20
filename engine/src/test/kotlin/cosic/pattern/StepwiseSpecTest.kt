package cosic.pattern

import cosic.parity.ParityWorld
import cosic.parity.evaluateForQuery
import cosic.script.catalogue.Catalogue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The stepwise functions (Stepwise.kt, script/catalogue/Stepwise.kt): `pace`, `take`, `drop`, `replicate`, `contract`,
 * `shrink`, `grow`, `tour`, `zip`, `polymeter`, `stepalt`, `stackLeft` and its relatives, `arrange` and `seqPLoop`.
 *
 * The expected steps and haps of the cases were produced by running the same program through Strudel itself (the oracle
 * checkout at the pinned commit, `test/runtime.mjs`'s `evaluate` and `queryArc` of the pattern sorted by part), so they hold
 * its quirks too. Every case is run twice: built with the functions of the kernel, and as the program the oracle ran.
 * `take`, `drop` and the others that zoom into a pattern or repeat its cycles use `zoom` and `repeatCycles` of the time
 * category: their cases are skipped while those are not registered.
 */
class StepwiseSpecTest {
    companion object {
        /** The whole catalogue, with the functions of the stepwise category. */
        @BeforeClass
        @JvmStatic
        fun register() {
            Catalogue.installAll()
        }
    }

    private fun seq(vararg values: String): Pattern = fastcat(values.map { pure(it) })

    private val a = pure("a")
    private val b = pure("b")
    private val c = pure("c")
    private val d = pure("d")
    private val e = pure("e")
    private val f = pure("f")
    private val j = pure("j")
    private val k = pure("k")
    private val l = pure("l")
    private val m = pure("m")
    private val bd = pure("bd")
    private val mt = pure("mt")

    private val ab = seq("a", "b")
    private val abc = seq("a", "b", "c")
    private val abcd = seq("a", "b", "c", "d")
    private val abcde = seq("a", "b", "c", "d", "e")
    private val cd = seq("c", "d")
    private val cde = seq("c", "d", "e")
    private val de = seq("d", "e")
    private val ef = seq("e", "f")
    private val efg = seq("e", "f", "g")
    private val fg = seq("f", "g")
    private val cg = seq("c", "g")
    private val gfec = seq("g", "f", "e", "c")
    private val gfeca = seq("g", "f", "e", "c", "a")
    private val ghi = seq("g", "h", "i")
    private val xy = seq("x", "y")
    private val bdcp = seq("bd", "cp")

    // ---- what is compared ---------------------------------------------------------------------------------------------

    /** One program of the oracle: its steps (null for none), the haps of [cycles] cycles, and the same pattern built with the kernel. */
    private class Case(val name: String, val cycles: Int, val steps: String?, val program: String, val haps: List<String>, val build: () -> Pattern)

    /** A program that answers a list of patterns, each with its steps and the haps of the first cycle. */
    private class ListCase(val name: String, val expected: List<Pair<String, List<String>>>, val build: () -> List<Pattern>)

    private fun case(name: String, cycles: Int, steps: String?, program: String, haps: List<String>, build: () -> Pattern) =
        Case(name, cycles, steps, program, haps, build)

    private fun list(name: String, expected: List<Pair<String, List<String>>>, build: () -> List<Pattern>) = ListCase(name, expected, build)

    /** The haps of [cycles] cycles as Strudel's snapshot tests print them; a query that fails answers nothing, as `queryArc` does. */
    private fun Pattern.hapLines(cycles: Int): List<String> =
        sortHapsByPart().queryArcLogged(Rational.ZERO, Rational(cycles.toLong())).map { it.show(true) }

    private fun stepsText(p: Pattern): String? = p.steps?.compact()

    private fun difference(name: String, how: String, steps: String?, haps: List<String>, actual: Pattern, cycles: Int): String? {
        val actualSteps = stepsText(actual)
        val actualHaps = actual.hapLines(cycles)
        if (actualSteps == steps && actualHaps == haps) return null
        return buildString {
            append("$name ($how)")
            if (actualSteps != steps) append(": steps are ${actualSteps ?: "undefined"}, not ${steps ?: "undefined"}")
            if (actualHaps != haps) append("\n    expected ${haps.joinToString(" ")}\n    actual   ${actualHaps.joinToString(" ")}")
        }
    }

    private fun check(cases: List<Any>, programs: Boolean) {
        val differences = ArrayList<String>()
        for (case in cases) {
            try {
                when (case) {
                    is Case -> {
                        if (programs) {
                            val made = evaluateForQuery(case.program)
                            difference(case.name, "as a program", case.steps, case.haps, made, case.cycles)?.let(differences::add)
                        } else {
                            difference(case.name, "built", case.steps, case.haps, case.build(), case.cycles)?.let(differences::add)
                        }
                    }
                    is ListCase -> if (!programs) {
                        val made = case.build()
                        val actual = made.map { stepsText(it) to it.hapLines(1) }
                        if (actual != case.expected) differences.add("${case.name}: expected ${case.expected}, got $actual")
                    }
                }
            } catch (failure: Exception) {
                val name = if (case is Case) case.name else (case as ListCase).name
                differences.add("$name: ${failure::class.simpleName}: ${failure.message}")
            }
        }
        assertTrue(differences.isEmpty(), differences.joinToString("\n"))
    }

    /** `zoom` and `repeatCycles` are functions of the time category. */
    private fun requireTimeFunctions() = assumeTrue(
        "needs zoom and repeatCycles of the time category",
        Registry.lookup("zoom") != null && Registry.lookup("repeatCycles") != null,
    )

    // ---- the cases ----------------------------------------------------------------------------------------------------

    private val independent: List<Any> = listOf(
        case(
            "pace.4", 1, "4",
            """seq("a","b","c").pace(4)""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | a ]",
            ),
        ) { abc._pace(4.0) },
        case(
            "pace.2", 1, "2",
            """seq("a","b","c","d").pace(2)""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 1/2 → 1/1 | b ]",
            ),
        ) { abcd._pace(2.0) },
        case(
            "pace.fraction", 1, "3/2",
            """seq("a","b","c").pace(1.5)""",
            listOf(
                "[ 0/1 → 2/3 | a ]",
                "[ (2/3 → 1/1) ⇝ 4/3 | b ]",
            ),
        ) { abc._pace(1.5) },
        case(
            "pace.patterned", 2, null,
            """seq("a","b","c").pace("<4 2>")""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | a ]",
                "[ 1/1 → 3/2 | c ]",
                "[ 3/2 → 2/1 | a ]",
            ),
        ) { abc.callMethod("pace", slowcat(4.0, 2.0)) },
        case(
            "pace.nosteps", 1, null,
            """seq("a","b","c").setSteps(undefined).pace(4)""",
            listOf(
                "[ 0/1 → 1/3 | a ]",
                "[ 1/3 → 2/3 | b ]",
                "[ 2/3 → 1/1 | c ]",
            ),
        ) { abc.setSteps(null)._pace(4.0) },
        case(
            "pace.nothing", 1, "0",
            """nothing.pace(4)""",
            emptyList(),
        ) { nothing._pace(4.0) },
        case(
            "extend.cat", 4, "2",
            """cat("a","b").extend(2)""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 1/2 → 1/1 | b ]",
                "[ 1/1 → 3/2 | a ]",
                "[ 3/2 → 2/1 | b ]",
                "[ 2/1 → 5/2 | a ]",
                "[ 5/2 → 3/1 | b ]",
                "[ 3/1 → 7/2 | a ]",
                "[ 7/2 → 4/1 | b ]",
            ),
        ) { slowcat(a, b).callMethod("extend", 2.0) },
        case(
            "expand.3", 1, "6",
            """seq("a","b").expand(3)""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 1/2 → 1/1 | b ]",
            ),
        ) { ab.callMethod("expand", 3.0) },
        case(
            "contract.2", 1, "2",
            """seq("a","b","c","d").contract(2)""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { abcd.callMethod("contract", 2.0) },
        case(
            "contract.patterned", 1, "3",
            """seq("a","b","c","d").contract("2 4")""",
            listOf(
                "[ 0/1 → 1/6 | a ]",
                "[ 1/6 → 1/3 | b ]",
                "[ 1/3 → 1/2 | c ]",
                "[ 1/2 → 2/3 | d ]",
                "[ 2/3 → 3/4 | a ]",
                "[ 3/4 → 5/6 | b ]",
                "[ 5/6 → 11/12 | c ]",
                "[ 11/12 → 1/1 | d ]",
            ),
        ) { abcd.callMethod("contract", fastcat(2.0, 4.0)) },
        case(
            "tour.3", 1, "44",
            """seq("c","g").tour(seq("e","f"), seq("e","f","g"), seq("g","f","e","c"))""",
            listOf(
                "[ 0/1 → 1/44 | e ]",
                "[ 1/44 → 1/22 | f ]",
                "[ 1/22 → 3/44 | e ]",
                "[ 3/44 → 1/11 | f ]",
                "[ 1/11 → 5/44 | g ]",
                "[ 5/44 → 3/22 | g ]",
                "[ 3/22 → 7/44 | f ]",
                "[ 7/44 → 2/11 | e ]",
                "[ 2/11 → 9/44 | c ]",
                "[ 9/44 → 5/22 | c ]",
                "[ 5/22 → 1/4 | g ]",
                "[ 1/4 → 3/11 | e ]",
                "[ 3/11 → 13/44 | f ]",
                "[ 13/44 → 7/22 | e ]",
                "[ 7/22 → 15/44 | f ]",
                "[ 15/44 → 4/11 | g ]",
                "[ 4/11 → 17/44 | c ]",
                "[ 17/44 → 9/22 | g ]",
                "[ 9/22 → 19/44 | g ]",
                "[ 19/44 → 5/11 | f ]",
                "[ 5/11 → 21/44 | e ]",
                "[ 21/44 → 1/2 | c ]",
                "[ 1/2 → 23/44 | e ]",
                "[ 23/44 → 6/11 | f ]",
                "[ 6/11 → 25/44 | c ]",
                "[ 25/44 → 13/22 | g ]",
                "[ 13/22 → 27/44 | e ]",
                "[ 27/44 → 7/11 | f ]",
                "[ 7/11 → 29/44 | g ]",
                "[ 29/44 → 15/22 | g ]",
                "[ 15/22 → 31/44 | f ]",
                "[ 31/44 → 8/11 | e ]",
                "[ 8/11 → 3/4 | c ]",
                "[ 3/4 → 17/22 | c ]",
                "[ 17/22 → 35/44 | g ]",
                "[ 35/44 → 9/11 | e ]",
                "[ 9/11 → 37/44 | f ]",
                "[ 37/44 → 19/22 | e ]",
                "[ 19/22 → 39/44 | f ]",
                "[ 39/44 → 10/11 | g ]",
                "[ 10/11 → 41/44 | g ]",
                "[ 41/44 → 21/22 | f ]",
                "[ 21/22 → 43/44 | e ]",
                "[ 43/44 → 1/1 | c ]",
            ),
        ) { tour(listOf(cg, ef, efg, gfec)) },
        case(
            "tour.pair", 1, "9",
            """seq("c","g").tour([2, seq("e","f")])""",
            listOf(
                "[ 0/1 → 1/9 | e ]",
                "[ 1/9 → 2/9 | f ]",
                "[ 2/9 → 1/3 | c ]",
                "[ 1/3 → 4/9 | g ]",
                "[ 4/9 → 5/9 | c ]",
                "[ 5/9 → 2/3 | g ]",
                "[ 2/3 → 7/9 | 2 ]",
                "[ 7/9 → 8/9 | e ]",
                "[ 8/9 → 1/1 | f ]",
            ),
        ) { tour(listOf(cg, listOf(2.0, ef))) },
        case(
            "tour.none", 1, "2",
            """seq("c","g").tour()""",
            listOf(
                "[ 0/1 → 1/2 | c ]",
                "[ 1/2 → 1/1 | g ]",
            ),
        ) { tour(listOf(cg)) },
        case(
            "tour.one", 1, "8",
            """seq("c","g").tour(seq("e","f"))""",
            listOf(
                "[ 0/1 → 1/8 | e ]",
                "[ 1/8 → 1/4 | f ]",
                "[ 1/4 → 3/8 | c ]",
                "[ 3/8 → 1/2 | g ]",
                "[ 1/2 → 5/8 | c ]",
                "[ 5/8 → 3/4 | g ]",
                "[ 3/4 → 7/8 | e ]",
                "[ 7/8 → 1/1 | f ]",
            ),
        ) { tour(listOf(cg, ef)) },
        case(
            "tour.global", 1, "21",
            """tour(seq("c","g"), seq("e","f"), seq("e","f","g"))""",
            listOf(
                "[ 0/1 → 1/21 | e ]",
                "[ 1/21 → 2/21 | f ]",
                "[ 2/21 → 1/7 | e ]",
                "[ 1/7 → 4/21 | f ]",
                "[ 4/21 → 5/21 | g ]",
                "[ 5/21 → 2/7 | c ]",
                "[ 2/7 → 1/3 | g ]",
                "[ 1/3 → 8/21 | e ]",
                "[ 8/21 → 3/7 | f ]",
                "[ 3/7 → 10/21 | c ]",
                "[ 10/21 → 11/21 | g ]",
                "[ 11/21 → 4/7 | e ]",
                "[ 4/7 → 13/21 | f ]",
                "[ 13/21 → 2/3 | g ]",
                "[ 2/3 → 5/7 | c ]",
                "[ 5/7 → 16/21 | g ]",
                "[ 16/21 → 17/21 | e ]",
                "[ 17/21 → 6/7 | f ]",
                "[ 6/7 → 19/21 | e ]",
                "[ 19/21 → 20/21 | f ]",
                "[ 20/21 → 1/1 | g ]",
            ),
        ) { tour(listOf(cg, ef, efg)) },
        case(
            "zip.3", 1, "30",
            """zip(seq("e","f"), seq("e","f","g"), seq("g","f","e","c","a"))""",
            listOf(
                "[ 0/1 → 1/30 | e ]",
                "[ 1/30 → 1/15 | e ]",
                "[ 1/15 → 1/10 | g ]",
                "[ 1/10 → 2/15 | f ]",
                "[ 2/15 → 1/6 | f ]",
                "[ 1/6 → 1/5 | f ]",
                "[ 1/5 → 7/30 | e ]",
                "[ 7/30 → 4/15 | g ]",
                "[ 4/15 → 3/10 | e ]",
                "[ 3/10 → 1/3 | f ]",
                "[ 1/3 → 11/30 | e ]",
                "[ 11/30 → 2/5 | c ]",
                "[ 2/5 → 13/30 | e ]",
                "[ 13/30 → 7/15 | f ]",
                "[ 7/15 → 1/2 | a ]",
                "[ 1/2 → 8/15 | f ]",
                "[ 8/15 → 17/30 | g ]",
                "[ 17/30 → 3/5 | g ]",
                "[ 3/5 → 19/30 | e ]",
                "[ 19/30 → 2/3 | e ]",
                "[ 2/3 → 7/10 | f ]",
                "[ 7/10 → 11/15 | f ]",
                "[ 11/15 → 23/30 | f ]",
                "[ 23/30 → 4/5 | e ]",
                "[ 4/5 → 5/6 | e ]",
                "[ 5/6 → 13/15 | g ]",
                "[ 13/15 → 9/10 | c ]",
                "[ 9/10 → 14/15 | f ]",
                "[ 14/15 → 29/30 | e ]",
                "[ 29/30 → 1/1 | a ]",
            ),
        ) { zip(listOf(ef, efg, gfeca)) },
        case(
            "zip.2", 3, "6",
            """zip(seq("a","b"), seq("c","d","e"))""",
            listOf(
                "[ 0/1 → 1/6 | a ]",
                "[ 1/6 → 1/3 | c ]",
                "[ 1/3 → 1/2 | b ]",
                "[ 1/2 → 2/3 | d ]",
                "[ 2/3 → 5/6 | a ]",
                "[ 5/6 → 1/1 | e ]",
                "[ 1/1 → 7/6 | b ]",
                "[ 7/6 → 4/3 | c ]",
                "[ 4/3 → 3/2 | a ]",
                "[ 3/2 → 5/3 | d ]",
                "[ 5/3 → 11/6 | b ]",
                "[ 11/6 → 2/1 | e ]",
                "[ 2/1 → 13/6 | a ]",
                "[ 13/6 → 7/3 | c ]",
                "[ 7/3 → 5/2 | b ]",
                "[ 5/2 → 8/3 | d ]",
                "[ 8/3 → 17/6 | a ]",
                "[ 17/6 → 3/1 | e ]",
            ),
        ) { zip(listOf(ab, cde)) },
        case(
            "zip.nosteps", 1, "2",
            """zip(seq("a","b").setSteps(undefined), seq("c","d"))""",
            listOf(
                "[ 0/1 → 1/2 | c ]",
                "[ 1/2 → 1/1 | d ]",
            ),
        ) { zip(listOf(ab.setSteps(null), cd)) },
        case(
            "zip.none", 1, null,
            """zip()""",
            emptyList(),
        ) { zip(emptyList()) },
        case(
            "stackLeft.3", 1, "5",
            """stackLeft(seq("a","b","c","d"), seq("e","f"), seq(seq("g","h","i"),"j","k","l","m"))""",
            listOf(
                "[ 0/1 → 1/15 | g ]",
                "[ 0/1 → 1/5 | a ]",
                "[ 0/1 → 1/5 | e ]",
                "[ 1/15 → 2/15 | h ]",
                "[ 2/15 → 1/5 | i ]",
                "[ 1/5 → 2/5 | b ]",
                "[ 1/5 → 2/5 | f ]",
                "[ 1/5 → 2/5 | j ]",
                "[ 2/5 → 3/5 | c ]",
                "[ 2/5 → 3/5 | k ]",
                "[ 3/5 → 4/5 | d ]",
                "[ 3/5 → 4/5 | l ]",
                "[ 4/5 → 1/1 | m ]",
            ),
        ) { stackLeft(listOf(abcd, ef, fastcat(ghi, j, k, l, m))) },
        case(
            "stackRight.3", 1, "5",
            """stackRight(seq("a","b","c","d"), seq("e","f"), seq(seq("g","h","i"),"j","k","l","m"))""",
            listOf(
                "[ 0/1 → 1/15 | g ]",
                "[ 1/15 → 2/15 | h ]",
                "[ 2/15 → 1/5 | i ]",
                "[ 1/5 → 2/5 | a ]",
                "[ 1/5 → 2/5 | j ]",
                "[ 2/5 → 3/5 | b ]",
                "[ 2/5 → 3/5 | k ]",
                "[ 3/5 → 4/5 | c ]",
                "[ 3/5 → 4/5 | e ]",
                "[ 3/5 → 4/5 | l ]",
                "[ 4/5 → 1/1 | d ]",
                "[ 4/5 → 1/1 | f ]",
                "[ 4/5 → 1/1 | m ]",
            ),
        ) { stackRight(listOf(abcd, ef, fastcat(ghi, j, k, l, m))) },
        case(
            "stackCenter.3", 1, "5",
            """stackCenter(seq("a","b","c","d"), seq("e","f"), seq(seq("g","h","i"),"j","k","l","m"))""",
            listOf(
                "[ 0/1 → 1/15 | g ]",
                "[ 1/15 → 2/15 | h ]",
                "[ 1/10 → 3/10 | a ]",
                "[ 2/15 → 1/5 | i ]",
                "[ 1/5 → 2/5 | j ]",
                "[ 3/10 → 1/2 | b ]",
                "[ 3/10 → 1/2 | e ]",
                "[ 2/5 → 3/5 | k ]",
                "[ 1/2 → 7/10 | c ]",
                "[ 1/2 → 7/10 | f ]",
                "[ 3/5 → 4/5 | l ]",
                "[ 7/10 → 9/10 | d ]",
                "[ 4/5 → 1/1 | m ]",
            ),
        ) { stackCenter(listOf(abcd, ef, fastcat(ghi, j, k, l, m))) },
        case(
            "stackCentre.3", 1, "5",
            """stackCentre(seq("a","b","c","d"), seq("e","f"), seq(seq("g","h","i"),"j","k","l","m"))""",
            listOf(
                "[ 0/1 → 1/15 | g ]",
                "[ 1/15 → 2/15 | h ]",
                "[ 1/10 → 3/10 | a ]",
                "[ 2/15 → 1/5 | i ]",
                "[ 1/5 → 2/5 | j ]",
                "[ 3/10 → 1/2 | b ]",
                "[ 3/10 → 1/2 | e ]",
                "[ 2/5 → 3/5 | k ]",
                "[ 1/2 → 7/10 | c ]",
                "[ 1/2 → 7/10 | f ]",
                "[ 3/5 → 4/5 | l ]",
                "[ 7/10 → 9/10 | d ]",
                "[ 4/5 → 1/1 | m ]",
            ),
        ) { stackCenter(listOf(abcd, ef, fastcat(ghi, j, k, l, m))) },
        case(
            "stackRepeat.3", 1, "5",
            """stackRepeat(seq("a","b","c","d"), seq("e","f"), seq(seq("g","h","i"),"j","k","l","m"))""",
            listOf(
                "[ 0/1 → 1/15 | g ]",
                "[ 0/1 → 1/5 | a ]",
                "[ 0/1 → 1/5 | e ]",
                "[ 1/15 → 2/15 | h ]",
                "[ 2/15 → 1/5 | i ]",
                "[ 1/5 → 2/5 | b ]",
                "[ 1/5 → 2/5 | f ]",
                "[ 1/5 → 2/5 | j ]",
                "[ 2/5 → 3/5 | c ]",
                "[ 2/5 → 3/5 | e ]",
                "[ 2/5 → 3/5 | k ]",
                "[ 3/5 → 4/5 | d ]",
                "[ 3/5 → 4/5 | f ]",
                "[ 3/5 → 4/5 | l ]",
                "[ 4/5 → 1/1 | a ]",
                "[ 4/5 → 1/1 | e ]",
                "[ 4/5 → 1/1 | m ]",
            ),
        ) { stackRepeat(listOf(abcd, ef, fastcat(ghi, j, k, l, m))) },
        case(
            "stackLeft.spec", 1, "4",
            """stackLeft(seq("a","b","c","d"), seq("x","y"))""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/4 | x ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/4 → 1/2 | y ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { stackLeft(listOf(abcd, xy)) },
        case(
            "stackLeft.same", 1, "2",
            """stackLeft(seq("a","b"), seq("c","d"))""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 0/1 → 1/2 | c ]",
                "[ 1/2 → 1/1 | b ]",
                "[ 1/2 → 1/1 | d ]",
            ),
        ) { stackLeft(listOf(ab, cd)) },
        case(
            "stackLeft.one", 1, "2",
            """stackLeft(seq("a","b"))""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 1/2 → 1/1 | b ]",
            ),
        ) { stackLeft(listOf(ab)) },
        case(
            "stackLeft.none", 1, "1",
            """stackLeft()""",
            emptyList(),
        ) { stackLeft(emptyList()) },
        case(
            "stackLeft.method", 1, "3",
            """seq("a","b","c").stackLeft(seq("d"))""",
            listOf(
                "[ 0/1 → 1/3 | a ]",
                "[ 0/1 → 1/3 | d ]",
                "[ 1/3 → 2/3 | b ]",
                "[ 2/3 → 1/1 | c ]",
            ),
        ) { stackLeft(listOf(abc, d)) },
        case(
            "stackRight.method", 1, "3",
            """seq("a","b","c").stackRight(seq("d"))""",
            listOf(
                "[ 0/1 → 1/3 | a ]",
                "[ 1/3 → 2/3 | b ]",
                "[ 2/3 → 1/1 | c ]",
                "[ 2/3 → 1/1 | d ]",
            ),
        ) { stackRight(listOf(abc, d)) },
        case(
            "stackLeft.list", 1, "3",
            """stackLeft(seq("a","b","c"), ["d","e"])""",
            listOf(
                "[ 0/1 → 1/3 | a ]",
                "[ 0/1 → 1/3 | d ]",
                "[ 1/3 → 2/3 | b ]",
                "[ 1/3 → 2/3 | e ]",
                "[ 2/3 → 1/1 | c ]",
            ),
        ) { stackLeft(listOf(abc, listOf(d, e))) },
        case(
            "stackCenter.odd", 1, "5",
            """stackCenter(seq("a","b","c","d","e"), seq("f","g"))""",
            listOf(
                "[ 0/1 → 1/5 | a ]",
                "[ 1/5 → 2/5 | b ]",
                "[ 3/10 → 1/2 | f ]",
                "[ 2/5 → 3/5 | c ]",
                "[ 1/2 → 7/10 | g ]",
                "[ 3/5 → 4/5 | d ]",
                "[ 4/5 → 1/1 | e ]",
            ),
        ) { stackCenter(listOf(abcde, fg)) },
        case(
            "stackBy.left", 1, "4",
            """stackBy("left", seq("a","b","c","d"), seq("e","f"))""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/4 | e ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/4 → 1/2 | f ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { stackBy(pure("left"), listOf(abcd, ef)) },
        case(
            "stackBy.alternate", 2, "4",
            """stackBy("<left right>", seq("a","b","c","d"), seq("e","f"))""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/4 | e ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/4 → 1/2 | f ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | d ]",
                "[ 1/1 → 5/4 | a ]",
                "[ 5/4 → 3/2 | b ]",
                "[ 3/2 → 7/4 | c ]",
                "[ 3/2 → 7/4 | e ]",
                "[ 7/4 → 2/1 | d ]",
                "[ 7/4 → 2/1 | f ]",
            ),
        ) { stackBy(slowcat(pure("left"), pure("right")), listOf(abcd, ef)) },
        case(
            "stackBy.expand", 1, "4",
            """stackBy("expand", seq("a","b","c","d"), seq("e","f"))""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/2 | e ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 1/2 → 1/1 | f ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { stackBy(pure("expand"), listOf(abcd, ef)) },
        case(
            "stackBy.centre", 1, "4",
            """stackBy("centre", seq("a","b","c","d"), seq("e","f"))""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/4 → 1/2 | e ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 1/2 → 3/4 | f ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { stackBy(pure("centre"), listOf(abcd, ef)) },
        case(
            "stackBy.repeat", 1, "4",
            """stackBy("repeat", seq("a","b","c","d"), seq("e","f"))""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/4 | e ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/4 → 1/2 | f ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 1/2 → 3/4 | e ]",
                "[ 3/4 → 1/1 | d ]",
                "[ 3/4 → 1/1 | f ]",
            ),
        ) { stackBy(pure("repeat"), listOf(abcd, ef)) },
        case(
            "stackBy.sequence", 1, "4",
            """stackBy("left right", seq("a","b","c","d"), seq("e","f"))""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/4 | e ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/4 → 1/2 | f ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 1/2 → 3/4 | e ]",
                "[ 3/4 → 1/1 | d ]",
                "[ 3/4 → 1/1 | f ]",
            ),
        ) { stackBy(fastcat(pure("left"), pure("right")), listOf(abcd, ef)) },
        case(
            "stackBy.unknown", 1, "2",
            """stackBy("nope", seq("a","b"), seq("c"))""",
            emptyList(),
        ) { stackBy(pure("nope"), listOf(ab, c)) },
        case(
            "polymeter.2", 1, "6",
            """polymeter(seq("a","b","c"), seq("d","e"))""",
            listOf(
                "[ 0/1 → 1/6 | a ]",
                "[ 0/1 → 1/6 | d ]",
                "[ 1/6 → 1/3 | b ]",
                "[ 1/6 → 1/3 | e ]",
                "[ 1/3 → 1/2 | c ]",
                "[ 1/3 → 1/2 | d ]",
                "[ 1/2 → 2/3 | a ]",
                "[ 1/2 → 2/3 | e ]",
                "[ 2/3 → 5/6 | b ]",
                "[ 2/3 → 5/6 | d ]",
                "[ 5/6 → 1/1 | c ]",
                "[ 5/6 → 1/1 | e ]",
            ),
        ) { polymeter(listOf(abc, de)) },
        case(
            "polymeter.3", 1, "6",
            """polymeter(seq("a","b"), seq("c","d","e"), seq("f"))""",
            listOf(
                "[ 0/1 → 1/6 | a ]",
                "[ 0/1 → 1/6 | c ]",
                "[ 0/1 → 1/6 | f ]",
                "[ 1/6 → 1/3 | b ]",
                "[ 1/6 → 1/3 | d ]",
                "[ 1/6 → 1/3 | f ]",
                "[ 1/3 → 1/2 | a ]",
                "[ 1/3 → 1/2 | e ]",
                "[ 1/3 → 1/2 | f ]",
                "[ 1/2 → 2/3 | b ]",
                "[ 1/2 → 2/3 | c ]",
                "[ 1/2 → 2/3 | f ]",
                "[ 2/3 → 5/6 | a ]",
                "[ 2/3 → 5/6 | d ]",
                "[ 2/3 → 5/6 | f ]",
                "[ 5/6 → 1/1 | b ]",
                "[ 5/6 → 1/1 | e ]",
                "[ 5/6 → 1/1 | f ]",
            ),
        ) { polymeter(listOf(ab, cde, f)) },
        case(
            "polymeter.nosteps", 1, "3",
            """polymeter(seq("a","b").setSteps(undefined), seq("c","d","e"))""",
            listOf(
                "[ 0/1 → 1/3 | c ]",
                "[ 1/3 → 2/3 | d ]",
                "[ 2/3 → 1/1 | e ]",
            ),
        ) { polymeter(listOf(ab.setSteps(null), cde)) },
        case(
            "polymeter.string", 1, "2",
            """polymeter('a b', seq('c','d'))""",
            listOf(
                "[ 0/1 → 1/2 | c ]",
                "[ 1/2 → 1/1 | d ]",
            ),
        ) { polymeter(listOf("a b", cd)) },
        case(
            "polymeter.none", 1, "1",
            """polymeter()""",
            emptyList(),
        ) { polymeter(emptyList()) },
        case(
            "polymeter.zero", 1, "0",
            """polymeter(nothing)""",
            emptyList(),
        ) { polymeter(listOf(nothing)) },
        case(
            "polymeter.array", 1, "6",
            """polymeter(["a","b","c"], ["d","e"])""",
            listOf(
                "[ 0/1 → 1/3 | a ]",
                "[ 0/1 → 1/3 | d ]",
                "[ 1/3 → 2/3 | b ]",
                "[ 1/3 → 2/3 | e ]",
                "[ 2/3 → 1/1 | c ]",
                "[ 2/3 → 1/1 | d ]",
            ),
        ) { polymeter(listOf(listOf(a, b, c), listOf(d, e))) },
        case(
            "polymeter.nested", 1, "6",
            """polymeter([["a","b"],"c"], ["d","e","f"])""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/2 | d ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 1/1 | c ]",
                "[ 1/2 → 1/1 | e ]",
            ),
        ) { polymeter(listOf(listOf(listOf(a, b), c), listOf(d, e, f))) },
        case(
            "polymeter.pm", 1, "6",
            """pm(seq("a","b","c"), seq("d","e"))""",
            listOf(
                "[ 0/1 → 1/6 | a ]",
                "[ 0/1 → 1/6 | d ]",
                "[ 1/6 → 1/3 | b ]",
                "[ 1/6 → 1/3 | e ]",
                "[ 1/3 → 1/2 | c ]",
                "[ 1/3 → 1/2 | d ]",
                "[ 1/2 → 2/3 | a ]",
                "[ 1/2 → 2/3 | e ]",
                "[ 2/3 → 5/6 | b ]",
                "[ 2/3 → 5/6 | d ]",
                "[ 5/6 → 1/1 | c ]",
                "[ 5/6 → 1/1 | e ]",
            ),
        ) { polymeter(listOf(abc, de)) },
        case(
            "_polymeterListSteps.4", 1, "6",
            """_polymeterListSteps(4, ["a","b","c"], ["d","e"])""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 0/1 → 1/4 | d ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/4 → 1/2 | e ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 1/2 → 3/4 | d ]",
                "[ 3/4 → 1/1 | a ]",
                "[ 3/4 → 1/1 | e ]",
            ),
        ) { _polymeterListSteps(4.0, listOf(listOf(a, b, c), listOf(d, e))) },
        case(
            "_polymeterListSteps.empty", 1, "2",
            """_polymeterListSteps(0, [], ["a","b"])""",
            emptyList(),
        ) { _polymeterListSteps(0.0, listOf(emptyList<Any?>(), listOf(a, b))) },
        case(
            "_polymeterListSteps.none", 1, "1",
            """_polymeterListSteps(3)""",
            emptyList(),
        ) { _polymeterListSteps(3.0, emptyList()) },
        case(
            "stepalt.spec", 1, "5",
            """stepalt([seq("bd","cp"), "mt"], "bd")""",
            listOf(
                "[ 0/1 → 1/5 | bd ]",
                "[ 1/5 → 2/5 | cp ]",
                "[ 2/5 → 3/5 | bd ]",
                "[ 3/5 → 4/5 | mt ]",
                "[ 4/5 → 1/1 | bd ]",
            ),
        ) { stepalt(listOf(listOf(bdcp, mt), bd)) },
        case(
            "stepalt.abc", 1, "4",
            """stepalt(["a", "b"], "c")""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | c ]",
                "[ 1/2 → 3/4 | b ]",
                "[ 3/4 → 1/1 | c ]",
            ),
        ) { stepalt(listOf(listOf(a, b), c)) },
        case(
            "stepalt.lists", 1, "12",
            """stepalt(["a","b"], ["c","d","e"])""",
            listOf(
                "[ 0/1 → 1/12 | a ]",
                "[ 1/12 → 1/6 | c ]",
                "[ 1/6 → 1/4 | b ]",
                "[ 1/4 → 1/3 | d ]",
                "[ 1/3 → 5/12 | a ]",
                "[ 5/12 → 1/2 | e ]",
                "[ 1/2 → 7/12 | b ]",
                "[ 7/12 → 2/3 | c ]",
                "[ 2/3 → 3/4 | a ]",
                "[ 3/4 → 5/6 | d ]",
                "[ 5/6 → 11/12 | b ]",
                "[ 11/12 → 1/1 | e ]",
            ),
        ) { stepalt(listOf(listOf(a, b), listOf(c, d, e))) },
        case(
            "stepalt.emptygroup", 1, "0",
            """stepalt(["a","b"], [])""",
            emptyList(),
        ) { stepalt(listOf(listOf(a, b), emptyList<Any?>())) },
        case(
            "stepalt.nosteps", 1, "1",
            """stepalt(seq("a","b").setSteps(undefined), "c")""",
            listOf(
                "[ 0/1 → 1/1 | c ]",
            ),
        ) { stepalt(listOf(ab.setSteps(null), c)) },
        case(
            "stepalt.none", 1, "0",
            """stepalt()""",
            emptyList(),
        ) { stepalt(emptyList()) },
        case(
            "arrange.spec", 6, "3",
            """arrange([2, "a"], [1, "b"])""",
            listOf(
                "[ 0/1 → 1/1 | a ]",
                "[ 1/1 → 2/1 | a ]",
                "[ 2/1 → 3/1 | b ]",
                "[ 3/1 → 4/1 | a ]",
                "[ 4/1 → 5/1 | a ]",
                "[ 5/1 → 6/1 | b ]",
            ),
        ) { arrange(listOf(listOf(2.0, a), listOf(1.0, b))) },
        case(
            "arrange.seq", 3, "3",
            """arrange([2, "a b"], [1, "c d e"])""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 1/2 → 1/1 | b ]",
                "[ 1/1 → 3/2 | a ]",
                "[ 3/2 → 2/1 | b ]",
                "[ 2/1 → 7/3 | c ]",
                "[ 7/3 → 8/3 | d ]",
                "[ 8/3 → 3/1 | e ]",
            ),
        ) { arrange(listOf(listOf(2.0, ab), listOf(1.0, cde))) },
        case(
            "arrange.fraction", 4, "2",
            """arrange([1.5, "a"], [0.5, "b b"])""",
            listOf(
                "[ 0/1 → 1/1 | a ]",
                "[ (1/1 → 3/2) ⇝ 2/1 | a ]",
                "[ 3/2 → 2/1 | b ]",
                "[ 3/2 ⇜ (2/1 → 5/2) | a ]",
                "[ 5/2 → 7/2 | a ]",
                "[ 7/2 → 4/1 | b ]",
            ),
        ) { arrange(listOf(listOf(1.5, a), listOf(0.5, fastcat(b, b)))) },
        case(
            "arrange.one", 6, "3",
            """arrange([3, "<a b c>"])""",
            listOf(
                "[ 0/1 → 1/1 | a ]",
                "[ 1/1 → 2/1 | b ]",
                "[ 2/1 → 3/1 | c ]",
                "[ 3/1 → 4/1 | a ]",
                "[ 4/1 → 5/1 | b ]",
                "[ 5/1 → 6/1 | c ]",
            ),
        ) { arrange(listOf(listOf(3.0, slowcat(a, b, c)))) },
        case(
            "arrange.none", 1, "1",
            """arrange()""",
            emptyList(),
        ) { arrange(emptyList()) },
        case(
            "seqPLoop.overlap", 6, null,
            """seqPLoop([0, 2, "a"], [1, 3, "b"])""",
            listOf(
                "[ 0/1 → 1/1 | a ]",
                "[ 1/1 → 2/1 | a ]",
                "[ 1/1 → 2/1 | b ]",
                "[ 2/1 → 3/1 | b ]",
                "[ 3/1 → 4/1 | a ]",
                "[ 4/1 → 5/1 | a ]",
                "[ 4/1 → 5/1 | b ]",
                "[ 5/1 → 6/1 | b ]",
            ),
        ) { seqPLoop(listOf(listOf(0.0, 2.0, a), listOf(1.0, 3.0, b))) },
        case(
            "seqPLoop.pairs", 6, null,
            """seqPLoop([2, "a"], [3, "b"])""",
            listOf(
                "[ 0/1 → 1/1 | a ]",
                "[ 1/1 → 2/1 | a ]",
                "[ 2/1 → 3/1 | b ]",
                "[ 3/1 → 4/1 | a ]",
                "[ 4/1 → 5/1 | a ]",
                "[ 5/1 → 6/1 | b ]",
            ),
        ) { seqPLoop(listOf(listOf(2.0, a), listOf(3.0, b))) },
    )

    /** The cases that go through `zoom` or `repeatCycles`. */
    private val withTimeFunctions: List<Any> = listOf(
        case(
            "take.2", 1, "2",
            """seq("a","b","c","d").take(2)""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 1/2 → 1/1 | b ]",
            ),
        ) { abcd._take(2.0) },
        case(
            "take.-1", 1, "1",
            """seq("a","b","c","d").take(-1)""",
            listOf(
                "[ 0/1 → 1/1 | d ]",
            ),
        ) { abcd._take(-1.0) },
        case(
            "take.-3", 1, "3",
            """seq("a","b","c","d").take(-3)""",
            listOf(
                "[ 0/1 → 1/3 | b ]",
                "[ 1/3 → 2/3 | c ]",
                "[ 2/3 → 1/1 | d ]",
            ),
        ) { abcd._take(-3.0) },
        case(
            "take.10", 1, "4",
            """seq("a","b","c","d").take(10)""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { abcd._take(10.0) },
        case(
            "take.0", 1, "0",
            """seq("a","b","c","d").take(0)""",
            emptyList(),
        ) { abcd._take(0.0) },
        case(
            "take.1.5", 1, "3/2",
            """seq("a","b","c","d").take(1.5)""",
            listOf(
                "[ 0/1 → 2/3 | a ]",
                "[ (2/3 → 1/1) ⇝ 4/3 | b ]",
            ),
        ) { abcd._take(1.5) },
        case(
            "take.patterned", 1, "3",
            """seq("a","b","c","d").take("1 2")""",
            listOf(
                "[ 0/1 → 1/3 | a ]",
                "[ 1/3 → 2/3 | a ]",
                "[ 2/3 → 1/1 | b ]",
            ),
        ) { abcd.callMethod("take", fastcat(1.0, 2.0)) },
        case(
            "take.patterned.neg", 2, "6",
            """seq("a","b","c","d").take("-1 -2 -3")""",
            listOf(
                "[ 0/1 → 1/6 | d ]",
                "[ 1/6 → 1/3 | c ]",
                "[ 1/3 → 1/2 | d ]",
                "[ 1/2 → 2/3 | b ]",
                "[ 2/3 → 5/6 | c ]",
                "[ 5/6 → 1/1 | d ]",
                "[ 1/1 → 7/6 | d ]",
                "[ 7/6 → 4/3 | c ]",
                "[ 4/3 → 3/2 | d ]",
                "[ 3/2 → 5/3 | b ]",
                "[ 5/3 → 11/6 | c ]",
                "[ 11/6 → 2/1 | d ]",
            ),
        ) { abcd.callMethod("take", fastcat(-1.0, -2.0, -3.0)) },
        case(
            "take.cycles", 2, "1",
            """seq("a","b","c","d").take("<1 2>")""",
            listOf(
                "[ 0/1 → 1/1 | a ]",
                "[ 1/1 → 2/1 | a ]",
            ),
        ) { abcd.callMethod("take", slowcat(1.0, 2.0)) },
        case(
            "take.varying", 2, "1",
            """cat(seq("a","b"), seq("c","d","e")).take(1)""",
            listOf(
                "[ (0/1 → 1/1) ⇝ 3/1 | a ]",
                "[ (1/1 → 2/1) ⇝ 3/1 | c ]",
            ),
        ) { slowcat(ab, cde).callMethod("take", 1.0) },
        case(
            "take.nosteps", 1, "0",
            """seq("a","b","c","d").setSteps(undefined).take(2)""",
            emptyList(),
        ) { abcd.setSteps(null)._take(2.0) },
        case(
            "drop.1", 1, "3",
            """seq("a","b","c","d").drop(1)""",
            listOf(
                "[ 0/1 → 1/3 | b ]",
                "[ 1/3 → 2/3 | c ]",
                "[ 2/3 → 1/1 | d ]",
            ),
        ) { abcd._drop(1.0) },
        case(
            "drop.-1", 1, "3",
            """seq("a","b","c","d").drop(-1)""",
            listOf(
                "[ 0/1 → 1/3 | a ]",
                "[ 1/3 → 2/3 | b ]",
                "[ 2/3 → 1/1 | c ]",
            ),
        ) { abcd._drop(-1.0) },
        case(
            "drop.0", 1, "4",
            """seq("a","b","c","d").drop(0)""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { abcd._drop(0.0) },
        case(
            "drop.4", 1, "0",
            """seq("a","b","c","d").drop(4)""",
            emptyList(),
        ) { abcd._drop(4.0) },
        case(
            "drop.10", 1, "4",
            """seq("a","b","c","d").drop(10)""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | c ]",
                "[ 3/4 → 1/1 | d ]",
            ),
        ) { abcd._drop(10.0) },
        case(
            "drop.patterned", 1, "10",
            """seq("a","b","c","d").drop("0 1 2 3")""",
            listOf(
                "[ 0/1 → 1/10 | a ]",
                "[ 1/10 → 1/5 | b ]",
                "[ 1/5 → 3/10 | c ]",
                "[ 3/10 → 2/5 | d ]",
                "[ 2/5 → 1/2 | b ]",
                "[ 1/2 → 3/5 | c ]",
                "[ 3/5 → 7/10 | d ]",
                "[ 7/10 → 4/5 | c ]",
                "[ 4/5 → 9/10 | d ]",
                "[ 9/10 → 1/1 | d ]",
            ),
        ) { abcd.callMethod("drop", fastcat(0.0, 1.0, 2.0, 3.0)) },
        case(
            "drop.nosteps", 1, "0",
            """seq("a","b","c","d").setSteps(undefined).drop(1)""",
            emptyList(),
        ) { abcd.setSteps(null)._drop(1.0) },
        case(
            "replicate.2", 2, "4",
            """seq("a","b").replicate(2)""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | a ]",
                "[ 3/4 → 1/1 | b ]",
                "[ 1/1 → 5/4 | a ]",
                "[ 5/4 → 3/2 | b ]",
                "[ 3/2 → 7/4 | a ]",
                "[ 7/4 → 2/1 | b ]",
            ),
        ) { ab.callMethod("replicate", 2.0) },
        case(
            "replicate.cat", 4, "2",
            """cat("a","b").replicate(2)""",
            listOf(
                "[ 0/1 → 1/2 | a ]",
                "[ 1/2 → 1/1 | a ]",
                "[ 1/1 → 3/2 | b ]",
                "[ 3/2 → 2/1 | b ]",
                "[ 2/1 → 5/2 | a ]",
                "[ 5/2 → 3/1 | a ]",
                "[ 3/1 → 7/2 | b ]",
                "[ 7/2 → 4/1 | b ]",
            ),
        ) { slowcat(a, b).callMethod("replicate", 2.0) },
        case(
            "replicate.patterned", 2, "4",
            """seq("a","b").replicate("<2 1>")""",
            listOf(
                "[ 0/1 → 1/4 | a ]",
                "[ 1/4 → 1/2 | b ]",
                "[ 1/2 → 3/4 | a ]",
                "[ 3/4 → 1/1 | b ]",
                "[ 1/1 → 5/4 | a ]",
                "[ 5/4 → 3/2 | b ]",
                "[ 3/2 → 7/4 | a ]",
                "[ 7/4 → 2/1 | b ]",
            ),
        ) { ab.callMethod("replicate", slowcat(2.0, 1.0)) },
        list(
            "shrinklist.1",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "3" to listOf("[ 0/1 → 1/3 | b ]", "[ 1/3 → 2/3 | c ]", "[ 2/3 → 1/1 | d ]"),
                "2" to listOf("[ 0/1 → 1/2 | c ]", "[ 1/2 → 1/1 | d ]"),
                "1" to listOf("[ 0/1 → 1/1 | d ]"),
            ),
        ) { abcd._shrinklist(1.0) },
        list(
            "shrinklist.-1",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "3" to listOf("[ 0/1 → 1/3 | a ]", "[ 1/3 → 2/3 | b ]", "[ 2/3 → 1/1 | c ]"),
                "2" to listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]"),
                "1" to listOf("[ 0/1 → 1/1 | a ]"),
            ),
        ) { abcd._shrinklist(-1.0) },
        list(
            "shrinklist.2",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "2" to listOf("[ 0/1 → 1/2 | c ]", "[ 1/2 → 1/1 | d ]"),
                "0" to listOf(),
            ),
        ) { abcd._shrinklist(2.0) },
        list(
            "shrinklist.pair",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "3" to listOf("[ 0/1 → 1/3 | b ]", "[ 1/3 → 2/3 | c ]", "[ 2/3 → 1/1 | d ]"),
            ),
        ) { abcd._shrinklist(listOf(1.0, 2.0)) },
        list(
            "shrinklist.negpair",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "3" to listOf("[ 0/1 → 1/3 | a ]", "[ 1/3 → 2/3 | b ]", "[ 2/3 → 1/1 | c ]"),
                "2" to listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]"),
            ),
        ) { abcd._shrinklist(listOf(-1.0, 3.0)) },
        list(
            "shrinklist.timeszero",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
            ),
        ) { abcd._shrinklist(listOf(1.0, 0.0)) },
        list(
            "shrinklist.0",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
            ),
        ) { abcd._shrinklist(0.0) },
        list(
            "shrinklist.fraction",
            listOf(
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
                "7/2" to listOf("[ -1/7 ⇜ (0/1 → 1/7) | a ]", "[ 1/7 → 3/7 | b ]", "[ 3/7 → 5/7 | c ]", "[ 5/7 → 1/1 | d ]"),
                "3" to listOf("[ 0/1 → 1/3 | b ]", "[ 1/3 → 2/3 | c ]", "[ 2/3 → 1/1 | d ]"),
                "5/2" to listOf("[ -1/5 ⇜ (0/1 → 1/5) | b ]", "[ 1/5 → 3/5 | c ]", "[ 3/5 → 1/1 | d ]"),
            ),
        ) { abcd._shrinklist(0.5) },
        list(
            "growlist.1",
            listOf(
                "1" to listOf("[ 0/1 → 1/1 | d ]"),
                "2" to listOf("[ 0/1 → 1/2 | c ]", "[ 1/2 → 1/1 | d ]"),
                "3" to listOf("[ 0/1 → 1/3 | b ]", "[ 1/3 → 2/3 | c ]", "[ 2/3 → 1/1 | d ]"),
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
            ),
        ) { abcd._growlist(1.0) },
        list(
            "growlist.-1",
            listOf(
                "1" to listOf("[ 0/1 → 1/1 | a ]"),
                "2" to listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]"),
                "3" to listOf("[ 0/1 → 1/3 | a ]", "[ 1/3 → 2/3 | b ]", "[ 2/3 → 1/1 | c ]"),
                "4" to listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
            ),
        ) { abcd._growlist(-1.0) },
        list(
            "shrinklist.global",
            listOf(
                "3" to listOf("[ 0/1 → 1/3 | a ]", "[ 1/3 → 2/3 | b ]", "[ 2/3 → 1/1 | c ]"),
                "2" to listOf("[ 0/1 → 1/2 | b ]", "[ 1/2 → 1/1 | c ]"),
                "1" to listOf("[ 0/1 → 1/1 | c ]"),
            ),
        ) { shrinklist(1.0, abc) },
        case(
            "shrink.1", 1, "10",
            """seq("a","b","c","d").shrink(1)""",
            listOf(
                "[ 0/1 → 1/10 | a ]",
                "[ 1/10 → 1/5 | b ]",
                "[ 1/5 → 3/10 | c ]",
                "[ 3/10 → 2/5 | d ]",
                "[ 2/5 → 1/2 | b ]",
                "[ 1/2 → 3/5 | c ]",
                "[ 3/5 → 7/10 | d ]",
                "[ 7/10 → 4/5 | c ]",
                "[ 4/5 → 9/10 | d ]",
                "[ 9/10 → 1/1 | d ]",
            ),
        ) { abcd._shrink(1.0) },
        case(
            "shrink.-1", 1, "10",
            """seq("a","b","c","d").shrink(-1)""",
            listOf(
                "[ 0/1 → 1/10 | a ]",
                "[ 1/10 → 1/5 | b ]",
                "[ 1/5 → 3/10 | c ]",
                "[ 3/10 → 2/5 | d ]",
                "[ 2/5 → 1/2 | a ]",
                "[ 1/2 → 3/5 | b ]",
                "[ 3/5 → 7/10 | c ]",
                "[ 7/10 → 4/5 | a ]",
                "[ 4/5 → 9/10 | b ]",
                "[ 9/10 → 1/1 | a ]",
            ),
        ) { abcd._shrink(-1.0) },
        case(
            "shrink.2", 1, "6",
            """seq("a","b","c","d").shrink(2)""",
            listOf(
                "[ 0/1 → 1/6 | a ]",
                "[ 1/6 → 1/3 | b ]",
                "[ 1/3 → 1/2 | c ]",
                "[ 1/2 → 2/3 | d ]",
                "[ 2/3 → 5/6 | c ]",
                "[ 5/6 → 1/1 | d ]",
            ),
        ) { abcd._shrink(2.0) },
        case(
            "shrink.pair", 1, "7",
            """seq("a","b","c","d").shrink([1, 2])""",
            listOf(
                "[ 0/1 → 1/7 | a ]",
                "[ 1/7 → 2/7 | b ]",
                "[ 2/7 → 3/7 | c ]",
                "[ 3/7 → 4/7 | d ]",
                "[ 4/7 → 5/7 | b ]",
                "[ 5/7 → 6/7 | c ]",
                "[ 6/7 → 1/1 | d ]",
            ),
        ) { abcd.callMethod("shrink", listOf(1.0, 2.0)) },
        case(
            "shrink.patterned", 1, "20",
            """seq("a","b","c","d").shrink("1 -1")""",
            listOf(
                "[ 0/1 → 1/20 | a ]",
                "[ 1/20 → 1/10 | b ]",
                "[ 1/10 → 3/20 | c ]",
                "[ 3/20 → 1/5 | d ]",
                "[ 1/5 → 1/4 | b ]",
                "[ 1/4 → 3/10 | c ]",
                "[ 3/10 → 7/20 | d ]",
                "[ 7/20 → 2/5 | c ]",
                "[ 2/5 → 9/20 | d ]",
                "[ 9/20 → 1/2 | d ]",
                "[ 1/2 → 11/20 | a ]",
                "[ 11/20 → 3/5 | b ]",
                "[ 3/5 → 13/20 | c ]",
                "[ 13/20 → 7/10 | d ]",
                "[ 7/10 → 3/4 | a ]",
                "[ 3/4 → 4/5 | b ]",
                "[ 4/5 → 17/20 | c ]",
                "[ 17/20 → 9/10 | a ]",
                "[ 9/10 → 19/20 | b ]",
                "[ 19/20 → 1/1 | a ]",
            ),
        ) { abcd.callMethod("shrink", fastcat(1.0, -1.0)) },
        case(
            "shrink.nosteps", 1, "0",
            """seq("a","b","c","d").setSteps(undefined).shrink(1)""",
            emptyList(),
        ) { abcd.setSteps(null)._shrink(1.0) },
        case(
            "grow.1", 1, "10",
            """seq("a","b","c","d").grow(1)""",
            listOf(
                "[ 0/1 → 1/10 | a ]",
                "[ 1/10 → 1/5 | a ]",
                "[ 1/5 → 3/10 | b ]",
                "[ 3/10 → 2/5 | a ]",
                "[ 2/5 → 1/2 | b ]",
                "[ 1/2 → 3/5 | c ]",
                "[ 3/5 → 7/10 | a ]",
                "[ 7/10 → 4/5 | b ]",
                "[ 4/5 → 9/10 | c ]",
                "[ 9/10 → 1/1 | d ]",
            ),
        ) { abcd._grow(1.0) },
        case(
            "grow.-1", 1, "10",
            """seq("a","b","c","d").grow(-1)""",
            listOf(
                "[ 0/1 → 1/10 | d ]",
                "[ 1/10 → 1/5 | c ]",
                "[ 1/5 → 3/10 | d ]",
                "[ 3/10 → 2/5 | b ]",
                "[ 2/5 → 1/2 | c ]",
                "[ 1/2 → 3/5 | d ]",
                "[ 3/5 → 7/10 | a ]",
                "[ 7/10 → 4/5 | b ]",
                "[ 4/5 → 9/10 | c ]",
                "[ 9/10 → 1/1 | d ]",
            ),
        ) { abcd._grow(-1.0) },
        case(
            "grow.pair", 1, "13",
            """seq("a","b","c","d").grow([1, 2])""",
            listOf(
                "[ 0/1 → 1/13 | a ]",
                "[ 1/13 → 2/13 | b ]",
                "[ (2/13 → 5/26) ⇝ 3/13 | c ]",
                "[ 5/26 → 7/26 | a ]",
                "[ 7/26 → 9/26 | b ]",
                "[ 9/26 → 11/26 | c ]",
                "[ 11/26 → 1/2 | a ]",
                "[ 1/2 → 15/26 | b ]",
                "[ 15/26 → 17/26 | c ]",
                "[ (17/26 → 9/13) ⇝ 19/26 | d ]",
                "[ 9/13 → 10/13 | a ]",
                "[ 10/13 → 11/13 | b ]",
                "[ 11/13 → 12/13 | c ]",
                "[ 12/13 → 1/1 | d ]",
            ),
        ) { abcd.callMethod("grow", listOf(1.0, 2.0)) },
        case(
            "grow.patterned", 1, "20",
            """seq("a","b","c","d").grow("1 -1")""",
            listOf(
                "[ 0/1 → 1/20 | a ]",
                "[ 1/20 → 1/10 | a ]",
                "[ 1/10 → 3/20 | b ]",
                "[ 3/20 → 1/5 | a ]",
                "[ 1/5 → 1/4 | b ]",
                "[ 1/4 → 3/10 | c ]",
                "[ 3/10 → 7/20 | a ]",
                "[ 7/20 → 2/5 | b ]",
                "[ 2/5 → 9/20 | c ]",
                "[ 9/20 → 1/2 | d ]",
                "[ 1/2 → 11/20 | d ]",
                "[ 11/20 → 3/5 | c ]",
                "[ 3/5 → 13/20 | d ]",
                "[ 13/20 → 7/10 | b ]",
                "[ 7/10 → 3/4 | c ]",
                "[ 3/4 → 4/5 | d ]",
                "[ 4/5 → 17/20 | a ]",
                "[ 17/20 → 9/10 | b ]",
                "[ 9/10 → 19/20 | c ]",
                "[ 19/20 → 1/1 | d ]",
            ),
        ) { abcd.callMethod("grow", fastcat(1.0, -1.0)) },
        case(
            "grow.nosteps", 1, "0",
            """seq("a","b","c","d").setSteps(undefined).grow(1)""",
            emptyList(),
        ) { abcd.setSteps(null)._grow(1.0) },
    )

    @Test
    fun theFunctionsDoWhatStrudelDoes() = check(independent, programs = false)

    @Test
    fun theFunctionsDoWhatStrudelDoesWhenAProgramCallsThem() = check(independent, programs = true)

    @Test
    fun theFunctionsThatZoomAndRepeatDoWhatStrudelDoes() {
        requireTimeFunctions()
        check(withTimeFunctions, programs = false)
    }

    @Test
    fun theFunctionsThatZoomAndRepeatDoWhatStrudelDoesWhenAProgramCallsThem() {
        requireTimeFunctions()
        check(withTimeFunctions, programs = true)
    }

    // ---- registration -------------------------------------------------------------------------------------------------

    @Test
    fun theNamesAreMethodsAndGlobalsOrGlobalsOnlyAsInStrudel() {
        val both = listOf(
            "pace", "steps", "take", "s_add", "drop", "s_sub", "replicate", "contract", "s_contract", "shrink", "s_taper", "grow",
            "tour", "s_tour", "stackLeft", "stackRight", "stackCenter", "stackCentre", "stackRepeat",
        )
        for (name in both) assertTrue(assertNotNull(Registry.lookup(name), name).hasMethod, "$name is a method too")
        val globalOnly = listOf(
            "polymeter", "pm", "s_polymeter", "_polymeterListSteps", "stepalt", "s_alt", "arrange", "seqPLoop", "zip", "s_zip", "stackBy",
            "calculateSteps",
        )
        for (name in globalOnly) assertFalse(assertNotNull(Registry.lookup(name), name).hasMethod, "$name is not a method")
    }

    @Test
    fun theMethodsOfAProgramsPatternsAreThoseOfStrudel() {
        val world = ParityWorld.plain()
        for (name in listOf("shrinklist", "s_taperlist", "growlist", "pace", "steps", "take", "tour", "stackLeft", "stackCentre")) {
            assertEquals("function", world.run("typeof seq('a').$name"), name)
        }
        // There is no `Pattern.prototype.polymeter`, `zip` or `stepalt`: only the global functions exist.
        for (name in listOf("polymeter", "pm", "s_polymeter", "zip", "s_zip", "stepalt", "s_alt", "arrange", "seqPLoop", "stackBy", "stepcat")) {
            assertEquals("undefined", world.run("typeof seq('a').$name"), name)
            assertEquals("function", world.run("typeof $name"), name)
        }
    }

    @Test
    fun stepsIsPaceWithoutAPlainMethodBecauseThatIsTheStepsOfAPattern() {
        assertTrue(Registry.lookup("pace")!!.hasPlain)
        assertFalse(Registry.lookup("steps")!!.hasPlain)
        val world = ParityWorld.plain()
        assertEquals("function", world.run("typeof seq('a')._pace"))
        assertEquals("object", world.run("typeof seq('a')._steps"))
        assertEquals(true, world.run("seq('a', 'b', 'c').hasSteps"))
    }

    // ---- the same function under its other names and forms ----------------------------------------------------------------

    private fun program(code: String, cycles: Int = 2): List<String> = evaluateForQuery(code).hapLines(cycles)

    private val abcdProgram = """seq("a","b","c","d")"""

    private fun assertFormsAgree(names: List<String>) {
        for (name in names) {
            val method = program("$abcdProgram.$name(2)")
            assertEquals(method, program("$name(2, $abcdProgram)"), "$name(2, pat)")
            assertEquals(method, program("$name(2)($abcdProgram)"), "$name(2)(pat)")
        }
    }

    private fun assertAliasesAgree(pairs: List<Pair<String, String>>) {
        for ((name, alias) in pairs) {
            assertEquals(program("$abcdProgram.$name(2)"), program("$abcdProgram.$alias(2)"), "$abcdProgram.$alias(2)")
            assertEquals(program("$name(2, $abcdProgram)"), program("$alias(2, $abcdProgram)"), "$alias(2, pat)")
        }
    }

    @Test
    fun aMethodAGlobalAndACurriedGlobalAreTheSameFunction() = assertFormsAgree(listOf("pace", "contract", "expand", "extend"))

    @Test
    fun aMethodAGlobalAndACurriedGlobalAreTheSameFunctionForTheOnesThatZoomAndRepeat() {
        requireTimeFunctions()
        assertFormsAgree(listOf("take", "drop", "replicate", "shrink", "grow"))
    }

    @Test
    fun theAliasesAreTheSameFunctions() {
        assertAliasesAgree(listOf("pace" to "steps", "contract" to "s_contract", "expand" to "s_expand", "extend" to "s_extend"))
        val ab = """seq("a","b")"""
        val cde = """seq("c","d","e")"""
        val globals = listOf(
            listOf("polymeter", "pm", "s_polymeter") to "$ab, $cde",
            listOf("stepalt", "s_alt") to """["a", "b"], "c"""",
            listOf("zip", "s_zip") to "$ab, $cde",
            listOf("stepcat", "timecat", "timeCat", "s_cat") to "$ab, $cde",
            listOf("stackCenter", "stackCentre") to "$ab, $cde",
            listOf("tour", "s_tour") to "$ab, $cde",
        )
        for ((names, args) in globals) {
            val expected = program("${names[0]}($args)")
            assertTrue(expected.isNotEmpty(), names[0])
            for (alias in names.drop(1)) assertEquals(expected, program("$alias($args)"), alias)
        }
        assertEquals(program("""seq("a","b").stackCenter(seq("c","d","e"))"""), program("""seq("a","b").stackCentre(seq("c","d","e"))"""))
        assertEquals(program("""seq("a","b").tour(seq("c","d","e"))"""), program("""seq("a","b").s_tour(seq("c","d","e"))"""))
    }

    @Test
    fun theAliasesOfTheFunctionsThatZoomAreTheSameFunctions() {
        requireTimeFunctions()
        assertAliasesAgree(listOf("take" to "s_add", "drop" to "s_sub", "shrink" to "s_taper"))
    }

    @Test
    fun theListFunctionsAnswerArraysOfPatterns() {
        requireTimeFunctions()
        val world = ParityWorld.plain()
        val abcd = "seq('a','b','c','d')"
        assertEquals(4.0, world.run("$abcd.shrinklist(1).length"))
        assertEquals(4.0, world.run("shrinklist(1, $abcd).length"))
        assertEquals(4.0, world.run("$abcd.s_taperlist(1).length"))
        assertEquals(4.0, world.run("s_taperlist(1, $abcd).length"))
        assertEquals(4.0, world.run("$abcd.growlist(1).length"))
        assertEquals(4.0, world.run("growlist(1, $abcd).length"))
    }

    // ---- what goes wrong ----------------------------------------------------------------------------------------------

    private fun failure(code: String): String? = assertFailsWith<CosicError>(code) { evaluateForQuery(code) }.message

    @Test
    fun theErrorsAreThoseOfStrudel() {
        val nothingToRead = "Cannot read properties of undefined"
        val iterable = "is not iterable (cannot read property Symbol(Symbol.iterator))"
        val cases = listOf(
            """stackLeft(seq("a","b"), sine)""" to "$nothingToRead (reading 'eq')",
            """stackLeft(sine, seq("a","b"))""" to "$nothingToRead (reading 'maximum')",
            """stackRight(sine, seq("a","b"))""" to "$nothingToRead (reading 'maximum')",
            """stackCenter(seq("a","b"), sine)""" to "$nothingToRead (reading 'eq')",
            """stackBy("left")""" to "$nothingToRead (reading 'maximum')",
            """stackBy("left", sine, seq("c","d"))""" to "$nothingToRead (reading 'maximum')",
            """stackRepeat(sine, seq("c","d"))""" to "$nothingToRead (reading 'maximum')",
            """stackBy('left', seq("a","b"), seq("c","d"))""" to "by.inhabit is not a function",
            "polymeter(null)" to "Cannot read properties of null (reading 'hasSteps')",
            """polymeter(seq("a"), undefined)""" to "$nothingToRead (reading 'hasSteps')",
            "zip(undefined)" to "$nothingToRead (reading 'hasSteps')",
            """arrange(["x"])""" to "$nothingToRead (reading 'fast')",
            "arrange([2, 'a'])" to "section.fast is not a function",
            "arrange('xy')" to "section.fast is not a function",
            "arrange(undefined)" to "undefined $iterable",
            "arrange(null)" to "object null $iterable",
            "arrange(5)" to "number 5 $iterable",
            "arrange(true)" to "boolean true $iterable",
            "arrange({})" to "object $iterable",
            """arrange(seq("a"))""" to "object $iterable",
            "arrange(() => 1)" to "function $iterable",
            "seqPLoop()" to "Division by Zero",
            """seqPLoop([0, 0, "a"])""" to "Division by Zero",
            """seqPLoop(["a"])""" to "Invalid argument",
            "seqPLoop(5)" to "number 5 $iterable",
            """seqPLoop(seq("a"))""" to "object $iterable",
            "seqPLoop(null)" to "Cannot read properties of null (reading 'length')",
            "seqPLoop(undefined)" to "$nothingToRead (reading 'length')",
            "tour('a', seq(\"b\"))" to "pat.tour is not a function",
            "tour(undefined)" to "$nothingToRead (reading 'tour')",
            "shrinklist(1, 'x')" to "pat.shrinklist is not a function",
            "growlist(1)" to "$nothingToRead (reading 'growlist')",
            """seq("a","b").s_zip(seq("c"))""" to "seq(...).s_zip is not a function",
            """seq("a","b").polymeter(seq("c"))""" to "seq(...).polymeter is not a function",
            """seq("a","b").stepalt(seq("c"))""" to "seq(...).stepalt is not a function",
        )
        val wrong = cases.mapNotNull { (code, message) ->
            val actual = try {
                evaluateForQuery(code)
                "no error"
            } catch (failure: CosicError) {
                failure.message
            }
            if (actual == message) null else "$code: expected '$message', got '$actual'"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun aPatternWithoutStepsCannotBePaddedButAloneItIsTheResult() {
        val nosteps = ab.setSteps(null)
        assertEquals("Cannot read properties of undefined (reading 'eq')", assertFailsWith<CosicError> { stackLeft(listOf(ab, nosteps)) }.message)
        assertEquals("Cannot read properties of undefined (reading 'maximum')", assertFailsWith<CosicError> { stackLeft(listOf(nosteps, ab)) }.message)
        assertSame(nosteps, stackLeft(listOf(nosteps)))
        assertSame(silence, stackLeft(emptyList()))
    }

    // ---- calculateSteps -------------------------------------------------------------------------------------------------

    @Test
    fun calculateStepsSwitchesTheStepsOffAndOn() {
        try {
            assertEquals(Undefined, Registry.call("calculateSteps", listOf(false)))
            assertFalse(Steps.enabled)
            assertNull(stack(ab, c).steps, "stack leaves its steps undefined")
            assertEquals(r(2), ab._fast(2).steps, "what a pattern had it keeps")
            assertEquals(
                "Cannot read properties of undefined (reading 'sub')",
                assertFailsWith<CosicError> { stackLeft(listOf(ab, c)) }.message,
            )
            assertEquals(Undefined, Registry.call("calculateSteps", listOf(1.0)))
            assertTrue(Steps.enabled)
            assertEquals(r(2), stack(ab, c).steps)
            Registry.call("calculateSteps", listOf(0.0))
            assertFalse(Steps.enabled, "0 is falsy")
            Registry.call("calculateSteps", emptyList<Any?>())
            assertFalse(Steps.enabled, "undefined is falsy")
        } finally {
            Steps.enabled = true
        }
    }

    // ---- the limits ---------------------------------------------------------------------------------------------------

    @Test
    fun aCountThatWouldBuildMoreThanTheLimitIsTooDense() {
        fun items(n: Int): List<Any?> = List(n) { a }
        // 101 * 103 * 107 repeats before every list has come round together
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { stepalt(listOf(items(101), items(103), items(107))) }.message)
        assertEquals(r(2), stepalt(listOf(items(2))).steps)
    }

    @Test
    fun aShrinkThatWouldNeverEndIsTooDense() {
        requireTimeFunctions()
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { abcd._shrinklist(listOf(0.0001, 1e9)) }.message)
        assertEquals(5, abcd._shrinklist(listOf(1.0, 1e9)).size, "from 0 to 1 in quarters, the last being nothing")
    }
}
