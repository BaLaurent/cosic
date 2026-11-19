package cosic.pattern

/**
 * The most steps, copies, slices or events per cycle a number in a program may ask for (`euclid(3, 8)`, `a!4`,
 * `run(16)`, `fast(4)`, ...). Larger values would only exhaust memory building more events than anybody could hear.
 */
internal const val MAX_COUNT = 10_000

/** What a program is told when it asks for more events than one stretch of playback can hold. */
internal const val TOO_DENSE = "Pattern too dense"

/** What a program is told when it nests, or calls itself, so deeply that the stack runs out. */
internal const val TOO_DEEP = "The program nests or calls itself too deeply"

/**
 * The most pieces a single query may cut spans and events into. [MAX_COUNT] limits one number at a time, but the
 * limits multiply: `ply(10000).ply(10000)` splits each event of a chunk into millions of pieces. The scheduler keeps
 * at most 2000 events of a chunk anyway, so a sensible program needs far fewer pieces than this.
 */
private const val MAX_QUERY_UNITS = 50_000L

private class Meter {
    var units = 0L
}

private val meters = ThreadLocal<Meter?>()

/**
 * Runs [block], which queries patterns, with a fresh budget of [MAX_QUERY_UNITS] pieces for the calling thread:
 * [chargeQuery] throws a [CosicError] once the query has used it up. Queries made outside of any budget are not limited.
 */
internal fun <T> withQueryBudget(block: () -> T): T {
    val outer = meters.get()
    meters.set(Meter())
    try {
        return block()
    } finally {
        meters.set(outer)
    }
}

/**
 * Counts [units] pieces (the cycles a span is cut into, the events an event is split into) against the budget of the
 * query running on this thread, if there is one. Call it before the pieces are built, so that a huge split is refused
 * instead of attempted.
 */
internal fun chargeQuery(units: Long) {
    val meter = meters.get() ?: return
    if (units > MAX_QUERY_UNITS - meter.units) throw CosicError(TOO_DENSE)
    meter.units += units
}

/**
 * Runs [block], reporting the two ways a program can exhaust the machine as [CosicError]s like any other mistake in
 * it: nesting too deep for the stack ([TOO_DEEP]), and more events than memory holds ([TOO_DENSE]), which is the last
 * resort behind the budget of [withQueryBudget].
 */
internal fun <T> reportingExhaustion(block: () -> T): T = try {
    block()
} catch (e: StackOverflowError) {
    throw CosicError(TOO_DEEP)
} catch (e: OutOfMemoryError) {
    throw CosicError(TOO_DENSE)
}
