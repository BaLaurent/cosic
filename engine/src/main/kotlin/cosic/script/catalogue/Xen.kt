package cosic.script.catalogue

import cosic.pattern.Registered
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern.Xen
import cosic.pattern.edoScale
import cosic.pattern.ftranspose
import cosic.pattern.patternOf
import cosic.pattern.reg2
import cosic.pattern.tune
import cosic.pattern.tuning
import cosic.pattern.withBase
import cosic.pattern.xen

/**
 * @strudel/xen and @strudel/edo: `xen`, `withBase`, `ftranspose` (`ftrans`, `fTrans`, `fTranspose`), `tune`, `edoScale` and the
 * function `edo`. `tuning` is a method only, as in Strudel, where it is registered but not exported.
 */
internal fun registerXen() {
    Registry.function(listOf("edo")) { a -> Xen.edo(a.getOrElse(0) { Undefined }) }
    // `xen` is not patterned: its argument is queried for each hap with the span of the hap
    Registry.reg2("xen", patternify = false) { scales, pat -> pat.xen(patternOf(scales)) }
    Registry.reg2("withBase") { b, pat -> pat.withBase(b) }
    Registry.reg2("ftrans", "fTrans", "ftranspose", "fTranspose") { amt, pat -> pat.ftranspose(amt) }
    Registry.reg2("tune") { scale, pat -> pat.tune(scale) }
    Registry.reg2("edoScale", keep = true) { definition, pat -> pat.edoScale(definition) }

    val tuning = Registered(listOf("tuning"), 2, true, false, { it.innerJoin() }, { a -> patternOf(a[1]).tuning(a[0]) })
    catalogue("Xen") {
        method(listOf("tuning"), 1) { ctx -> tuning.method(ctx.bridge.receiverOf(ctx.self, "tuning"), ctx.args.indices.map { ctx.kernelArg(it) }) }
        method(listOf("_tuning"), 2) { ctx -> tuning.plain(ctx.bridge.receiverOf(ctx.self, "_tuning"), ctx.args.indices.map { ctx.kernelArg(it) }) }
    }
}
