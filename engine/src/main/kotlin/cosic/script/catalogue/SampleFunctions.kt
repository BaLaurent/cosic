package cosic.script.catalogue

import cosic.pattern.Registry
import cosic.pattern.SampleFunctions
import cosic.pattern.reg1
import cosic.pattern.reg2
import cosic.pattern.reg3

/**
 * The functions that cut, loop and fit samples (pattern.mjs:3891-4089): `chop`, `striate`, `loopAt`, `loopAtCps`, `slice`,
 * `splice` and `fit`. `scrub`, `hurry` and the sample library functions (`samples`, `soundAlias`, ...) belong to other categories.
 * `slice` and `splice` take their arguments as they are (patterns), the others patternify them.
 */
internal fun registerSampleFunctions() {
    Registry.reg2("chop") { n, pat -> SampleFunctions.chop(n, pat) }
    Registry.reg2("striate") { n, pat -> SampleFunctions.striate(n, pat) }
    Registry.reg2("loopAt", "loopat") { factor, pat -> SampleFunctions.loopAt(factor, pat) }
    Registry.reg3("loopAtCps", "loopatcps") { factor, cps, pat -> SampleFunctions.loopAtCps(factor, pat, cps) }
    Registry.reg3("slice", patternify = false) { n, i, pat -> SampleFunctions.slice(n, i, pat) }
    Registry.reg3("splice", patternify = false) { n, i, pat -> SampleFunctions.splice(n, i, pat) }
    Registry.reg1("fit") { pat -> SampleFunctions.fit(pat) }
}
