package cosic.pattern.legacy

import cosic.pattern.*

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** A continuous pattern: one hap without a whole whose value is `f(midpoint of the query)`. */
internal fun signal(f: (Rational) -> Any?): Pattern =
    Pattern { state -> listOf(Hap(null, state.span, f(state.span.midpoint()))) }

internal val sine: Pattern = signal { 0.5 + 0.5 * sin(2 * PI * it.toDouble()) }

internal val cosine: Pattern = sine.early(Rational(1, 4))

internal val saw: Pattern = signal { it.cyclePos().toDouble() }

internal val isaw: Pattern = signal { 1.0 - it.cyclePos().toDouble() }

internal val tri: Pattern = signal { 1.0 - abs(2.0 * it.cyclePos().toDouble() - 1.0) }

internal val square: Pattern = signal { if (it.cyclePos().toDouble() < 0.5) 0.0 else 1.0 }

// ---- deterministic randomness ---------------------------------------------------------

private const val GOLDEN_GAMMA = -7046029254386353131L // 0x9E3779B97F4A7C15
private val MIX_1 = 0xBF58476D1CE4E5B9uL.toLong()
private val MIX_2 = 0x94D049BB133111EBuL.toLong()
private const val TIME_RESOLUTION = 1048576.0 // 2^20 steps per cycle
private const val UNIT_53 = 1.0 / 9007199254740992.0 // 2^-53

private fun splitmix64(x: Long): Long {
    var z = x + GOLDEN_GAMMA
    z = (z xor (z ushr 30)) * MIX_1
    z = (z xor (z ushr 27)) * MIX_2
    return z xor (z ushr 31)
}

/**
 * A repeatable pseudo-random number in `[0, 1)` for time [t] and [seed]: the same inputs always give the
 * same number, so randomised patterns sound identical every time they are played.
 */
internal fun timeRand(t: Rational, seed: Int): Double {
    val x = Math.floor(t.toDouble() * TIME_RESOLUTION).toLong()
    val h = splitmix64(x xor (seed.toLong() * GOLDEN_GAMMA))
    return (h ushr 11).toDouble() * UNIT_53
}

internal val rand: Pattern = signal { timeRand(it, 0) }

/** Random whole numbers from 0 up to (not including) [n]. */
internal fun irand(n: Double): Pattern = rand.fmap { Math.floor((it as Double) * n) }

/** Smooth noise: interpolates between the random numbers of the surrounding cycle starts. */
internal val perlin: Pattern = signal { t ->
    val cycle = t.floor()
    val from = timeRand(Rational(cycle), 0)
    val to = timeRand(Rational(cycle + 1), 0)
    val x = t.cyclePos().toDouble()
    from + (x * x * x * (x * (x * 6.0 - 15.0) + 10.0)) * (to - from)
}

// ---- randomised operations ------------------------------------------------------------

private fun Hap.randomAt(seed: Int): Double = timeRand(wholeOrPart().begin, seed)

/** Drops each hap with probability [p]; the choice depends on the hap's start time and [seed] only. */
internal fun Pattern.degradeBy(p: Double, seed: Int = 0): Pattern = filterHaps { it.randomAt(seed) >= p }

/** Applies [f] to a random share [p] of the haps and leaves the rest as they are. */
internal fun Pattern.sometimesBy(p: Double, f: (Pattern) -> Pattern): Pattern =
    stack(degradeBy(p), f(filterHaps { it.randomAt(0) < p }))

/** Plays [applied] on a random share [p] of the cycles. */
internal fun Pattern.someCyclesBy(p: Double, applied: Pattern): Pattern =
    perCycle { c -> if (timeRand(Rational(c), 1) < p) applied else this }

/** Plays one of [pats] each cycle, chosen at random. */
internal fun randomChoice(pats: List<Pattern>, seed: Int): Pattern {
    if (pats.isEmpty()) return silence
    return perCycle { c -> pats[Math.floor(timeRand(Rational(c), seed) * pats.size).toInt().coerceIn(0, pats.size - 1)] }
}
