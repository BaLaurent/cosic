package cosic.mini

import cosic.pattern.Pattern
import cosic.pattern.callMethod
import cosic.pattern.compact
import cosic.pattern.lines
import cosic.pattern.pure
import cosic.pattern._fast
import cosic.script.catalogue.Catalogue

/** The values of the haps of the first cycle, in time order (`firstCycleValues` of Strudel's tests). */
internal fun Pattern.firstValues(): List<Any?> = sortHapsByPart().firstCycle().map { it.value }

/** `value: begin - end` for the haps of the first cycle, in time order (`showFirstCycle` of Strudel's tests). */
internal fun Pattern.showFirstCycle(): List<String> =
    sortHapsByPart().firstCycle().map { h -> "${h.value}: ${h.whole!!.begin.compact()} - ${h.whole!!.end.compact()}" }

/** `minV` of Strudel's mini tests: the values of the first cycle of the notation [src]. */
internal fun minV(src: String): List<Any?> = mini(src).firstValues()

/** `minS` of Strudel's mini tests: the haps of the first cycle of the notation [src] as text. */
internal fun minS(src: String): List<String> = mini(src).showFirstCycle()

/**
 * Probes of functions that mini-notation calls and other tasks own, answering whether the registered one is the one of
 * Strudel. A test of what the notation makes of such a function is skipped while it is not (the function's own tests are
 * what prove it).
 */
internal object StrudelRegistry {
    init {
        Catalogue.installAll()
    }

    /** `euclid` and `euclidRot` do what Strudel's do: rotate right, invert for a negative number of pulses, legato for a negative number of steps. */
    val euclid: Boolean by lazy {
        try {
            minS("a(3,8,2)") == listOf("a: 0 - 1/8", "a: 1/4 - 3/8", "a: 5/8 - 3/4") &&
                minS("a(-3,8)") == listOf("a: 1/8 - 1/4", "a: 1/4 - 3/8", "a: 1/2 - 5/8", "a: 5/8 - 3/4", "a: 7/8 - 1") &&
                minS("a(3,-8)") == listOf("a: 0 - 3/8", "a: 3/8 - 3/4", "a: 3/4 - 1")
        } catch (e: Exception) {
            false
        }
    }

    /** `degradeBy` is `_degradeByWith(rand, x)`, the stream `?` draws on with its seed at 0. */
    val degradeBy: Boolean by lazy {
        try {
            val registered = pure("a")._fast(8.0).callMethod("degradeBy", 0.5).lines(0, 8)
            registered == mini("a*8?").lines(0, 8)
        } catch (e: Exception) {
            false
        }
    }
}
