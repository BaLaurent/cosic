package cosic.pattern

import java.util.concurrent.ConcurrentHashMap

/*
 * packages/core/impure.mjs of the pinned Strudel, "the file of shame for stateful, impure and otherwise illegal pattern methods":
 * `timeline`, and the functions that make it forget. Nothing else lives there.
 */

/**
 * What `timeline` remembers between queries (the module variable `timelines` of impure.mjs): the cycle each timeline number began at,
 * under the name JavaScript gives the number as a property of an object. Only queries of the scheduler write it, so a visualiser asking
 * the same pattern changes nothing. It lives as long as the process, like the module's, and `reset_state()` empties it.
 */
internal object Timelines {
    private val offsets = ConcurrentHashMap<String, Any>()

    fun reset() = offsets.clear()

    fun get(id: Any?): Any? = offsets[jsToString(id)]

    fun set(id: Any?, offset: Any) {
        offsets[jsToString(id)] = offset
    }

    fun forget(id: Any?) {
        offsets.remove(jsToString(id))
    }
}

/**
 * Plays the pattern on the timeline the pattern [tpat] (a number, usually) names (`timeline` of impure.mjs): the pattern is delayed so
 * that its cycle zero begins where the timeline began, which is where the event of [tpat] that first named it begins (or ends, when
 * the scheduler first sees that event after its midpoint, which cues the pattern up for the next cycle). Timeline 0 is the pattern as it is,
 * and a timeline stays at its offset while later evaluations keep naming it; naming `-n` after `n` starts timeline `n` over.
 *
 * It is registered without patternify, so [tpat] is a pattern and may change over time.
 */
internal fun Pattern._timeline(tpat: Any?): Pattern {
    val timePattern = reify(tpat)
    return Pattern({ state ->
        // is this called from the scheduler? (rather than from e.g. the visualiser)
        val scheduler = jsTruthy(state.controls["cyclist"])
        val result = ArrayList<Hap>()
        for (timehap in timePattern.query(state)) {
            val id = timehap.value
            val offset: Any = if (jsStrictEq(id, 0.0)) {
                0.0
            } else {
                Timelines.get(id) ?: timehap.wholeOrPart().let { arc ->
                    // sync to the end of the arc if it is first seen over halfway into its time: this cues the next timeline up while live coding
                    if (!scheduler || state.span.begin < arc.midpoint()) arc.begin else arc.end
                }
            }
            if (scheduler) {
                Timelines.set(id, offset)
                if (!jsStrictEq(id, 0.0)) Timelines.forget(-jsToNumber(id))
            }
            for (hap in callMethod("late", offset).query(state.setSpan(timehap.part))) {
                result.add(hap.setContext(hap.combineContext(timehap)))
            }
        }
        result
    }, steps)
}
