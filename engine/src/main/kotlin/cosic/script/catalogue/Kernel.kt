package cosic.script.catalogue

import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern.TimeSpan
import cosic.pattern.Undefined
import cosic.pattern._compress
import cosic.pattern._compressSpan
import cosic.pattern._early
import cosic.pattern._expand
import cosic.pattern._extend
import cosic.pattern._fast
import cosic.pattern._fastGap
import cosic.pattern._focus
import cosic.pattern._focusSpan
import cosic.pattern._late
import cosic.pattern._slow
import cosic.pattern.cat
import cosic.pattern.fastcat
import cosic.pattern.gap
import cosic.pattern.isPattern
import cosic.pattern.nothing
import cosic.pattern.patternOf
import cosic.pattern.pure
import cosic.pattern.reg2
import cosic.pattern.reg3
import cosic.pattern.reify
import cosic.pattern.sequenceP
import cosic.pattern.silence
import cosic.pattern.slowcatPrime
import cosic.pattern.stack
import cosic.pattern.stepReg2
import cosic.pattern.stepcat

/**
 * The constructors and kernel functions of pattern.mjs: what every other function is built from. A later registration
 * of the same name (the full time and structure functions) replaces these.
 */
internal fun registerKernel() {
    // constructors
    Registry.constant(listOf("silence"), silence)
    Registry.constant(listOf("nothing"), nothing)
    Registry.function(listOf("pure")) { a -> pure(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("gap")) { a -> gap(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("isPattern")) { a -> isPattern(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("reify")) { a -> reify(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("sequenceP")) { a -> sequenceP((a.getOrElse(0) { emptyList<Any?>() } as List<*>).map { patternOf(it) }) }

    Registry.variadic(listOf("stack")) { a -> stack(a) }
    Registry.variadic(listOf("polyrhythm", "pr"), method = false) { a -> stack(a) }
    Registry.variadic(listOf("slowcat", "cat")) { a -> cat(a) }
    Registry.function(listOf("slowcatPrime")) { a -> slowcatPrime(a) }
    Registry.variadic(listOf("fastcat", "sequence", "seq")) { a -> fastcat(a) }
    Registry.function(listOf("stepcat", "timecat", "timeCat", "s_cat")) { a -> stepcat(a) }

    // time
    Registry.reg2("fast", keep = true) { factor, pat -> pat._fast(factor) }
    Registry.reg2("slow", "sparsity") { factor, pat -> pat._slow(factor) }
    Registry.reg2("early", keep = true) { offset, pat -> pat._early(offset) }
    Registry.reg2("late", keep = true) { offset, pat -> pat._late(offset) }
    Registry.reg3("compress") { b, e, pat -> pat._compress(b, e) }
    Registry.reg2("compressSpan", "compressspan") { span, pat -> pat._compressSpan(span.asSpan()) }
    Registry.reg2("fastGap", "fastgap") { factor, pat -> pat._fastGap(factor) }
    Registry.reg3("focus") { b, e, pat -> pat._focus(b, e) }
    Registry.reg2("focusSpan", "focusspan") { span, pat -> pat._focusSpan(span.asSpan()) }

    // steps
    Registry.stepReg2("expand", "s_expand") { factor, pat -> pat._expand(factor) }
    Registry.stepReg2("extend", "s_extend") { factor, pat -> pat._extend(factor) }
}

private fun Any?.asSpan(): TimeSpan = this as? TimeSpan ?: throw cosic.pattern.CosicError("expected a time span, got ${cosic.pattern.stringifyValues(this, true)}")
