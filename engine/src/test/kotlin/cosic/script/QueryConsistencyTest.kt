package cosic.script

import cosic.pattern.ControlMap
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.r
import cosic.pattern.span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The engine plays a pattern by asking for it in small chunks of time and keeping the events that start inside
 * each chunk. That only works if the chunks add up to what one big query returns, whatever the pattern.
 */
class QueryConsistencyTest {
    private val programs = listOf(
        "s(\"bd sd\")",
        "s(\"bd*3 [sd cp]\")",
        "s(\"bd(3,8)\")",
        "s(\"bd(3,8,2)\")",
        "s(\"<a b c>*2\")",
        "s(\"{a b c}%4\")",
        "s(\"a!2 b _ c@2\")",
        "s(\"a/2 b\")",
        "s(\"a? b?0.3 c*2?\")",
        "s(\"a | b | c\")",
        "s(\"[a|b] c\")",
        "s(\"a b c d\").rev()",
        "s(\"a b c d\").fast(1.5)",
        "s(\"a b\").slow(3)",
        "s(\"a b c\").iter(3)",
        "s(\"a b c\").palindrome()",
        "s(\"a b c d\").ply(3)",
        "s(\"a b\").off(0.25, x => x.speed(2))",
        "s(\"a b c d\").every(3, fast(2))",
        "s(\"a b c d\").chunk(4, x => x.speed(2))",
        "s(\"hh*8\").swingBy(1/3, 4)",
        "s(\"hh*16\").degradeBy(0.4)",
        "s(\"hh*8\").sometimes(x => x.speed(2))",
        "s(\"bd sd\").someCycles(fast(2))",
        "s(\"bd\").euclid(5, 8).late(0.1)",
        "n(\"0 1 2 3\").inside(2, rev)",
        "cat(s(\"a b\"), s(\"c\")).outside(2, rev)",
        "s(\"a b c\").jux(rev)",
        "s(\"a b\").chop(3)",
        "s(\"a b\").striate(3)",
        "s(\"a b\").hurry(\"<1 2>\")",
        "s(\"a b\").fast(\"<1 2 3>\")",
        "s(\"a b c\").struct(\"x ~ x x\")",
        "s(\"hh*8\").mask(\"1 0 1 1\")",
        "note(saw.range(48, 60)).segment(5)",
        "s(\"a b c\").early(0.3).late(0.1)",
        "stack(s(\"a*2\"), s(\"b*3\"), s(\"c\"))",
        "arrange([2, s(\"a b\")], [1, s(\"c*3\")])",
        "s(\"a b\").layer(rev, x => x.fast(2))",
        "\"0 2 4\".scale(\"C:major\")",
        "n(\"0 1 2\").add(\"<0 3>\")",
        "s(\"a b c\").every(\"<2 3>\", rev).late(\"<0 0.25>\")",
        "s(\"a*4\").gain(sine).pan(\"0 1\")",
        "\$: s(\"bd*2\")\n\$: s(\"hh*3\").degrade()",
    )

    private fun key(hap: Hap<ControlMap>): String {
        val whole = checkNotNull(hap.whole)
        return "${renderValue(hap.value)}@${whole.begin}-${whole.end}"
    }

    private fun fullOnsets(pattern: Pattern<ControlMap>): List<String> =
        pattern.query(span(r(0), r(4))).filter { it.hasOnset() }.map(::key).sorted()

    private fun chunkedOnsets(pattern: Pattern<ControlMap>): List<String> {
        val onsets = ArrayList<String>()
        for (i in 0L until 64L) {
            val begin = r(i, 16)
            val end = r(i + 1, 16)
            for (hap in pattern.query(span(begin, end))) {
                val start = hap.whole?.begin ?: continue
                if (hap.hasOnset() && start >= begin && start < end) onsets.add(key(hap))
            }
        }
        return onsets.sorted()
    }

    @Test
    fun chunkedQueriesFindTheSameOnsetsAsOneBigQuery() {
        for (code in programs) {
            val pattern = evaluateProgram(code).pattern
            val expected = fullOnsets(pattern)
            assertEquals(expected, chunkedOnsets(pattern), "chunked onsets differ for: $code")
        }
    }

    @Test
    fun theProgramsActuallyPlaySomething() {
        for (code in programs) {
            assertTrue(fullOnsets(evaluateProgram(code).pattern).isNotEmpty(), "silent over four cycles: $code")
        }
    }

    @Test
    fun cycleByCycleQueriesFindTheSameOnsetsAsOneBigQuery() {
        for (code in programs) {
            val pattern = evaluateProgram(code).pattern
            val perCycle = (0L until 4L).flatMap { c ->
                pattern.query(span(r(c), r(c + 1))).filter { it.hasOnset() }.map(::key)
            }.sorted()
            assertEquals(fullOnsets(pattern), perCycle, "per-cycle onsets differ for: $code")
        }
    }
}
