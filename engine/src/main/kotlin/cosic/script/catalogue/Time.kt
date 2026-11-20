package cosic.script.catalogue

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern._apply
import cosic.pattern._bite
import cosic.pattern._brak
import cosic.pattern._bypass
import cosic.pattern._chunk
import cosic.pattern._chunkBack
import cosic.pattern._chunkBackInto
import cosic.pattern._chunkInto
import cosic.pattern._cpm
import cosic.pattern._echo
import cosic.pattern._echoWith
import cosic.pattern._fastChunk
import cosic.pattern._filter
import cosic.pattern._filterWhen
import cosic.pattern._firstOf
import cosic.pattern._hurry
import cosic.pattern._inside
import cosic.pattern._invert
import cosic.pattern._isobite
import cosic.pattern._iter
import cosic.pattern._iterBack
import cosic.pattern._jux
import cosic.pattern._juxBy
import cosic.pattern._juxFlip
import cosic.pattern._juxFlipBy
import cosic.pattern._lastOf
import cosic.pattern._linger
import cosic.pattern._off
import cosic.pattern._outside
import cosic.pattern._palindrome
import cosic.pattern._ply
import cosic.pattern._plyForEach
import cosic.pattern._plyWith
import cosic.pattern._press
import cosic.pattern._pressBy
import cosic.pattern._repeatCycles
import cosic.pattern._rev
import cosic.pattern._revv
import cosic.pattern._ribbon
import cosic.pattern._segment
import cosic.pattern._stut
import cosic.pattern._swing
import cosic.pattern._swingBy
import cosic.pattern._when
import cosic.pattern._within
import cosic.pattern._zoom
import cosic.pattern._zoomArc
import cosic.pattern.applyN
import cosic.pattern.curry
import cosic.pattern.into
import cosic.pattern.layer
import cosic.pattern.reg1
import cosic.pattern.reg2
import cosic.pattern.reg3
import cosic.pattern.reg4
import cosic.pattern.reify
import cosic.pattern.silence
import cosic.pattern.superimpose
import cosic.pattern.tag
import cosic.pattern.unjoin

/**
 * The time and structure functions of pattern.mjs (CORE task T1): `ply`, `inside`, `every`, `zoom`, `rev`, `jux`, `echo`,
 * `iter`, `chunk` and the others, with the names and aliases `register` gives them there and the flags that decide how
 * they handle their arguments (see [Registry.register]). `fast`, `slow`, `early`, `late`, `compress`, `fastGap` and
 * `focus` are the kernel's (Kernel.kt). A later registration of a name replaces an earlier one, as it does in Strudel.
 */
internal fun registerTime() {
    // repeating and layering in time
    Registry.reg2("ply") { factor, pat -> pat._ply(factor) }
    Registry.reg2("hurry") { r, pat -> pat._hurry(r) }
    Registry.reg3("inside") { factor, f, pat -> pat._inside(factor, f) }
    Registry.reg3("outside") { factor, f, pat -> pat._outside(factor, f) }
    Registry.reg3("lastOf") { n, func, pat -> pat._lastOf(n, func) }
    Registry.reg3("firstOf", "every") { n, func, pat -> pat._firstOf(n, func) }
    Registry.reg2("apply") { func, pat -> pat._apply(func) }
    Registry.reg3("applyN") { n, func, pat -> applyN(n, func, pat) }
    Registry.reg2("cpm") { cpm, pat -> pat._cpm(cpm) }
    Registry.reg3("when") { on, func, pat -> pat._when(on, func) }
    Registry.reg3("off") { time, func, pat -> pat._off(time, func) }
    Registry.reg1("brak") { pat -> pat._brak() }
    Registry.reg1("invert", "inv", keep = true) { pat -> pat._invert() }
    Registry.reg2("bypass", keep = true) { on, pat -> pat._bypass(on) }

    // zooming, slicing and sampling
    Registry.reg3("zoom") { s, e, pat -> pat._zoom(s, e) }
    Registry.reg2("zoomArc", "zoomarc") { span, pat -> pat._zoomArc(span) }
    Registry.reg3("bite", patternify = false) { npat, ipat, pat -> pat._bite(npat, ipat) }
    Registry.reg2("isobite", "cyclestruct", "cycleStruct", patternify = false) { structure, pat -> pat._isobite(structure) }
    Registry.reg2("linger", keep = true) { t, pat -> pat._linger(t) }
    Registry.reg2("segment", "seg") { rate, pat -> pat._segment(rate) }
    Registry.reg3("swingBy") { swing, n, pat -> pat._swingBy(swing, n) }
    Registry.reg2("swing") { n, pat -> pat._swing(n) }

    // reversing and pressing
    // `rev` is registered without patternify, so a call with more arguments than the pattern (`echoWith(3, 1/8, rev)` calls it with the
    // pattern and the number of the echo) reverses the first of them, where the other one-argument functions take the last
    Registry.register(listOf("rev"), 1, patternify = false, preserveSteps = true) { a -> (a[0] as Pattern)._rev() }
    Registry.reg1("revv") { pat -> pat._revv() }
    Registry.reg2("pressBy") { r, pat -> pat._pressBy(r) }
    Registry.reg1("press") { pat -> pat._press() }
    Registry.reg1("palindrome", keep = true) { pat -> pat._palindrome() }

    // stereo
    Registry.reg3("juxBy", "juxby") { by, func, pat -> pat._juxBy(by, func) }
    Registry.reg3("juxFlipBy", "juxflipby", "fluxBy", "fluxby") { by, func, pat -> pat._juxFlipBy(by, func) }
    Registry.reg2("jux") { func, pat -> pat._jux(func) }
    Registry.reg2("juxFlip", "juxflip", "flux") { func, pat -> pat._juxFlip(func) }

    // echoes and repeated events
    Registry.reg4("echoWith", "echowith", "stutWith", "stutwith") { times, time, func, pat -> pat._echoWith(times, time, func) }
    Registry.reg4("echo") { times, time, feedback, pat -> pat._echo(times, time, feedback) }
    Registry.reg4("stut") { times, feedback, time, pat -> pat._stut(times, feedback, time) }
    Registry.reg3("plyWith", "plywith") { factor, func, pat -> pat._plyWith(factor, func) }
    Registry.reg3("plyForEach", "plyforeach") { factor, func, pat -> pat._plyForEach(factor, func) }

    // cycles
    Registry.reg2("iter", keep = true) { times, pat -> pat._iter(times) }
    Registry.reg2("iterBack", "iterback", keep = true) { times, pat -> pat._iterBack(times) }
    Registry.reg2("repeatCycles", keep = true) { n, pat -> pat._repeatCycles(n) }
    Registry.reg3("chunk", "slowchunk", "slowChunk", keep = true) { n, func, pat -> pat._chunk(n, func) }
    Registry.reg3("chunkBack", "chunkback", keep = true) { n, func, pat -> pat._chunkBack(n, func) }
    Registry.reg3("fastchunk", "fastChunk", keep = true) { n, func, pat -> pat._fastChunk(n, func) }
    Registry.reg3("chunkinto", "chunkInto") { n, func, pat -> pat._chunkInto(n, func) }
    Registry.reg3("chunkbackinto", "chunkBackInto") { n, func, pat -> pat._chunkBackInto(n, func) }
    Registry.reg3("ribbon", "rib") { offset, cycles, pat -> pat._ribbon(offset, cycles) }

    // filtering
    Registry.reg2("filter") { test, pat -> pat._filter(test) }
    Registry.reg2("filterWhen") { test, pat -> pat._filterWhen(test) }
    Registry.reg4("within") { a, b, fn, pat -> pat._within(a, b, fn) }

    // `superimpose` is a method of patterns that lists its functions, and a global that takes them as an array and the pattern
    // last (`superimpose([f, g], pat)`, curried like the registered functions are)
    val superimposeGlobal = curry(2) { a -> reify(a[1]).superimpose(spread(a[0])) }
    Registry.dual(
        listOf("superimpose"),
        global = { args -> superimposeGlobal.call(args) },
        method = { pat, args -> pat.superimpose(args) },
    )

    // the methods of the class itself: they take their arguments as they come and have no `_name` or curried global
    catalogue("Time") {
        method(listOf("hush"), 0) { silence }
        method(listOf("tag"), 1) { ctx -> receiver(ctx, "tag").tag(ctx.kernelArg(0)) }
        method(listOf("layer"), 0) { ctx -> receiver(ctx, "layer").layer(ctx.args.map { ctx.bridge.toKernel(it) }) }
        method(listOf("unjoin"), 2) { ctx -> receiver(ctx, "unjoin").unjoin(ctx.kernelArg(0), ctx.kernelArg(1)) }
        method(listOf("into"), 2) { ctx -> receiver(ctx, "into").into(ctx.kernelArg(0), ctx.kernelArg(1)) }
    }
}

private fun receiver(ctx: CallContext, name: String): Pattern = ctx.bridge.receiverOf(ctx.self, name)

/** `...a`: the elements of an array; a call of a function with anything else spread into it is a `TypeError`. */
private fun spread(a: Any?): List<Any?> =
    (a as? List<*>)?.toList() ?: throw CosicError("Spread syntax requires ...iterable[Symbol.iterator] to be a function")
