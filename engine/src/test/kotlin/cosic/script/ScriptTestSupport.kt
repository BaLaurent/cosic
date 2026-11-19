package cosic.script

import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.cycleSpan
import kotlin.test.assertFailsWith

/** Whole numbers print without a decimal point: `2`, but `0.5`. */
internal fun formatValue(value: Any): String = when (value) {
    is Double -> if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    else -> value.toString()
}

/** `gain=0.5 s=bd`: the entries of a control map sorted by key, or the plain value. */
internal fun renderValue(value: Any): String = when (value) {
    is Map<*, *> -> value.entries.sortedBy { it.key.toString() }.joinToString(" ") { "${it.key}=${formatValue(it.value!!)}" }
    else -> formatValue(value)
}

private fun Hap<Any>.render(): String {
    val w = whole?.let { "${it.begin}-${it.end}" } ?: "~"
    return "${renderValue(value)}@$w" + if (whole != part) "|${part.begin}-${part.end}" else ""
}

/** The haps of one cycle in time order, as `values@whole` strings (`|part` when the part is smaller). */
internal fun Pattern<Any>.renderCycle(cycle: Long = 0L): List<String> =
    query(cycleSpan(cycle))
        .sortedWith(compareBy<Hap<Any>>({ it.part.begin }, { it.part.end }, { renderValue(it.value) }))
        .map { it.render() }

internal fun ProgramResult.render(cycle: Long = 0L): List<String> = pattern.renderCycle(cycle)

/** Haps of one cycle of a program as `values@whole` strings. */
internal fun run(code: String, cycle: Long = 0L): List<String> = evaluateProgram(code).render(cycle)

/** The control maps of one cycle, in time order. */
internal fun values(code: String, cycle: Long = 0L): List<ControlMap> =
    evaluateProgram(code).pattern.query(cycleSpan(cycle)).sortedBy { it.part.begin }.map { it.value }

/** The evaluation error of a program. */
internal fun failure(code: String): CosicError = assertFailsWith<CosicError> { evaluateProgram(code) }

/** The error thrown while querying a program that evaluates fine. */
internal fun queryFailure(code: String, cycle: Long = 0L): CosicError {
    val program = evaluateProgram(code)
    return assertFailsWith<CosicError> { program.pattern.query(cycleSpan(cycle)) }
}
