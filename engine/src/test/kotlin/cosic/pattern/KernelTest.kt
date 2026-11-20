package cosic.pattern

import cosic.script.catalogue.registerKernel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The kernel functions and their catalogue entries against the hap strings Strudel prints (produced by running the pinned `@strudel/core`). */
class KernelTest {
    init {
        registerKernel()
    }

    private val a = pure("a")
    private val abc = fastcat("a", "b", "c")
    private val abcd = fastcat("a", "b", "c", "d")
    private val ab = fastcat("a", "b")

    @Test
    fun fast() {
        assertEquals(2, a.callMethod("fast", 2.0).firstLines().size)
        assertEquals(
            listOf("[ (0/1 → 1/2) ⇝ 1/1 | a ]", "[ 1/2 → 3/4 | a ]", "[ 3/4 → 1/1 | a ]"),
            a.callMethod("fast", fastcat(1.0, 4.0)).firstLines(),
        )
        assertEquals(
            listOf(
                "[ 0/1 → 2/9 | a ]", "[ 2/9 → 4/9 | b ]", "[ (4/9 → 1/2) ⇝ 2/3 | c ]",
                "[ 1/2 → 2/3 | a ]", "[ 2/3 → 5/6 | b ]", "[ 5/6 → 1/1 | c ]",
            ),
            abc.callMethod("fast", fastcat(1.5, 2.0)).firstLines(),
        )
        assertEquals(abc.callMethod("fast", 1.5, 2.0).firstLines(), abc.callMethod("fast", fastcat(1.5, 2.0)).firstLines())
    }

    @Test
    fun fastByZeroOrANegativeNumberHasNoHaps() {
        assertEquals(emptyList(), a.callMethod("fast", 0.0).firstLines())
        assertEquals(emptyList(), a.callMethod("slow", 0.0).firstLines())
        assertEquals(emptyList(), a.callMethod("fast", -1.0).firstLines())
        assertEquals(emptyList(), abc._fast(-2.0).firstLines())
    }

    @Test
    fun fastSupportsZeroWidthQueries() {
        assertEquals(listOf("[ (1/2 → 1/2) ⇝ 1/1 | a ]"), a._fast(2.0).queryArc(r(1, 2), r(1, 2)).map { it.show(true) })
    }

    @Test
    fun slow() {
        assertEquals(listOf("[ 0/1 → 2/1 | a ]"), a.callMethod("slow", 2.0).lines(0, 2))
        assertEquals(a.callMethod("slow", 2.0).firstLines(), a.callMethod("sparsity", 2.0).firstLines())
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 1/1 | b ]",
                "[ 1/1 → 5/4 | a ]", "[ 5/4 → 3/2 | b ]", "[ 3/2 → 2/1 | d ]",
            ),
            abcd.callMethod("slow", fastcat(1.0, 2.0)).lines(0, 2),
        )
    }

    @Test
    fun earlyAndLate() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | d ]", "[ 3/4 → 1/1 | a ]"),
            abcd.callMethod("early", fastcat(0.0, 0.25)).firstLines(),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | d ]", "[ 1/4 → 1/2 | a ]", "[ 1/2 → 3/4 | b ]", "[ 3/4 → 1/1 | c ]"),
            abcd.callMethod("late", 0.25).firstLines(),
        )
        assertEquals(
            listOf("[ -1/4 ⇜ (0/1 → 1/4) | a ]", "[ 1/4 → 3/4 | b ]", "[ (3/4 → 1/1) ⇝ 5/4 | a ]"),
            ab.callMethod("late", -0.25).firstLines(),
        )
        assertEquals(
            listOf("[ -1/2 ⇜ (0/1 → 1/2) | a ]", "[ 1/2 → 3/2 | a ]", "[ (3/2 → 2/1) ⇝ 5/2 | a ]"),
            a.callMethod("late", 0.5).lines(0, 2),
        )
    }

    @Test
    fun compress() {
        assertEquals(
            listOf("[ 1/4 → 1/2 | a ]", "[ 1/2 → 3/4 | b ]", "[ 5/4 → 3/2 | a ]", "[ 3/2 → 7/4 | b ]"),
            ab.callMethod("compress", 0.25, 0.75).lines(0, 2),
        )
        assertEquals(
            listOf("[ 1/4 → 1/2 | a ]", "[ 3/8 ⇜ (1/2 → 3/4) | b ]"),
            ab.callMethod("compress", fastcat(0.25, 0.0), 0.75).firstLines(),
        )
        assertEquals(
            listOf("[ (1/4 → 3/4) ⇝ 5/4 | a ]", "[ 3/4 ⇜ (5/4 → 7/4) | a ]"),
            a.callMethod("slow", 2.0).callMethod("compress", 0.25, 0.75).lines(0, 2),
        )
        assertEquals(emptyList(), a.callMethod("compress", 0.75, 0.25).firstLines())
        assertEquals(emptyList(), a.callMethod("compress", 0.5, 1.5).firstLines())
        assertEquals("Division by Zero", assertFailsWith<CosicError> { a._compress(0.5, 0.5) }.message)
        assertEquals(listOf("[ 1/4 → 1/2 | a ]"), a._compressSpan(TimeSpan(r(1, 4), r(1, 2))).firstLines())
        assertEquals(a.callMethod("compressSpan", TimeSpan(r(1, 4), r(1, 2))).firstLines(), a.callMethod("compressspan", TimeSpan(r(1, 4), r(1, 2))).firstLines())
    }

    @Test
    fun fastGap() {
        assertEquals(
            listOf("[ 0/1 → 1/6 | a ]", "[ 1/6 → 1/3 | b ]", "[ 1/3 → 1/2 | c ]", "[ 1/1 → 7/6 | a ]", "[ 7/6 → 4/3 | b ]", "[ 4/3 → 3/2 | c ]"),
            abc.callMethod("fastGap", 2.0).lines(0, 2),
        )
        assertEquals(listOf("[ 0/1 → 1/9 | a ]", "[ 1/9 → 2/9 | b ]", "[ 2/9 → 1/3 | c ]"), abc.callMethod("fastgap", 3.0).firstLines())
        assertEquals(listOf("[ (0/1 → 1/2) ⇝ 1/1 | a ]", "[ 1/2 ⇜ (1/1 → 3/2) | a ]"), a._slow(2.0)._fastGap(2.0).lines(0, 2))
        assertEquals(listOf("[ 0/1 → 2/9 | a ]", "[ 2/9 → 4/9 | b ]", "[ 4/9 → 2/3 | c ]"), abc._fastGap(1.5).firstLines())
    }

    @Test
    fun focus() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/8 | c ]", "[ 1/8 → 1/4 | d ]", "[ 1/4 → 3/8 | a ]", "[ 3/8 → 1/2 | b ]",
                "[ 1/2 → 5/8 | c ]", "[ 5/8 → 3/4 | d ]", "[ 3/4 → 7/8 | a ]", "[ 7/8 → 1/1 | b ]",
            ),
            abcd.callMethod("focus", 0.25, 0.75).firstLines(),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | b ]", "[ 1/4 → 1/2 | a ]", "[ 1/2 → 3/4 | b ]", "[ 3/4 → 1/1 | a ]"),
            ab.callMethod("focusSpan", TimeSpan(r(1, 4), r(3, 4))).firstLines(),
        )
        assertEquals(ab.callMethod("focusSpan", TimeSpan(r(1, 4), r(3, 4))).firstLines(), ab.callMethod("focusspan", TimeSpan(r(1, 4), r(3, 4))).firstLines())
    }

    @Test
    fun extendPlaysFasterAndKeepsTheStepsOfTheResult() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]", "[ 1/2 → 3/4 | a ]", "[ 3/4 → 1/1 | b ]", "[ 1/1 → 5/4 | a ]", "[ 5/4 → 3/2 | b ]", "[ 3/2 → 7/4 | a ]", "[ 7/4 → 2/1 | b ]"),
            ab.callMethod("extend", 2.0).lines(0, 2),
        )
    }

    @Test
    fun slowcatCountsCyclesPerPattern() {
        assertEquals(listOf("[ 0/1 → 1/1 | b ]"), slowcat("a", "b")._early(-1.0).firstLines())
        assertEquals(
            listOf("[ 0/1 → 1/1 | d ]", "[ 1/1 → 3/2 | a ]", "[ 3/2 → 2/1 | b ]", "[ 2/1 → 3/1 | c ]"),
            slowcat(ab, "c", "d")._late(7.0).lines(0, 3),
        )
        assertEquals(listOf("[ 0/1 → 1/1 | c ]"), slowcat("a", slowcat("b", "c"))._early(3.0).firstLines())
    }

    // ---- the catalogue entries -------------------------------------------------------------------------------------

    @Test
    fun constantsAndPlainFunctions() {
        assertSame(silence, Registry.constants()["silence"])
        assertSame(nothing, Registry.constants()["nothing"])
        assertEquals(listOf("[ 0/1 → 1/1 | 5 ]"), (Registry.call("pure", listOf(5.0)) as Pattern).firstLines())
        assertEquals(r(7), (Registry.call("gap", listOf(7.0)) as Pattern).steps)
        assertEquals(true, Registry.call("isPattern", listOf(a)))
        assertEquals(false, Registry.call("isPattern", listOf("a")))
        assertFalse(Registry.lookup("pure")!!.hasMethod)
        assertFailsWith<CosicError> { a.callMethod("pure", 1.0) }
    }

    @Test
    fun variadicConstructorsAreGlobalsAndMethodsWithTheReceiverFirst() {
        assertEquals(listOf("[ 0/1 → 1/1 | a ]", "[ 0/1 → 1/1 | b ]"), (Registry.call("stack", listOf(a, pure("b"))) as Pattern).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | a ]", "[ 0/1 → 1/1 | b ]"), a.callMethod("stack", pure("b")).firstLines())
        assertEquals(listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]"), a.callMethod("seq", pure("b")).firstLines())
        assertEquals(listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]"), a.callMethod("fastcat", "b").firstLines())
        assertEquals(listOf("[ 1/1 → 2/1 | b ]"), a.callMethod("cat", pure("b")).lines(1, 2))
        assertEquals(listOf("[ 1/1 → 2/1 | b ]"), a.callMethod("slowcat", pure("b")).lines(1, 2))
        for (name in listOf("polyrhythm", "pr")) {
            assertFalse(Registry.lookup(name)!!.hasMethod, name)
            assertEquals(2, (Registry.call(name, listOf(a, pure("b"))) as Pattern).firstLines().size)
        }
    }

    @Test
    fun everyNameOfTheKernelIsRegistered() {
        val names = Registry.names()
        val expected = listOf(
            "pure", "gap", "isPattern", "reify", "sequenceP", "stack", "polyrhythm", "pr", "slowcat", "cat", "slowcatPrime", "fastcat", "sequence",
            "seq", "stepcat", "timecat", "timeCat", "s_cat", "fast", "slow", "sparsity", "early", "late", "compress", "compressSpan", "compressspan",
            "fastGap", "fastgap", "focus", "focusSpan", "focusspan", "expand", "s_expand", "extend", "s_extend",
        )
        assertTrue(names.containsAll(expected), "missing: ${expected - names}")
    }

    @Test
    fun stepcatIsAGlobalOnly() {
        val p = Registry.call("timecat", listOf(listOf(3.0, pure("e3")), listOf(1.0, pure("g3")))) as Pattern
        assertEquals(listOf("[ 0/1 → 3/4 | e3 ]", "[ 3/4 → 1/1 | g3 ]"), p.firstLines())
        assertFalse(Registry.lookup("stepcat")!!.hasMethod)
    }
}
