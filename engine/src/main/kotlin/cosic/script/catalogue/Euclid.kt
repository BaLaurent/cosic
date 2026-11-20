package cosic.script.catalogue

import cosic.pattern.Registry
import cosic.pattern.bjork
import cosic.pattern.bjorklund
import cosic.pattern.euclid
import cosic.pattern.euclidLegato
import cosic.pattern.euclidLegatoRot
import cosic.pattern.euclidRot
import cosic.pattern.euclidish
import cosic.pattern.jsToNumber
import cosic.pattern.reg2
import cosic.pattern.reg3
import cosic.pattern.reg4

/**
 * The Euclidean rhythms of euclid.mjs (see [cosic.pattern.euclidCore]): `euclid`, `euclidRot` (`euclidrot`), `euclidLegato`,
 * `euclidLegatoRot`, `euclidish` (`eish`) and `bjork` as functions of patterns, and `bjorklund`, the plain function that
 * gives the list of hits.
 */
internal fun registerEuclid() {
    Registry.reg3("euclid") { pulses, steps, pat -> pat.euclid(pulses, steps) }
    Registry.reg2("bjork") { euc, pat -> pat.bjork(euc) }
    Registry.reg4("euclidRot", "euclidrot") { pulses, steps, rotation, pat -> pat.euclidRot(pulses, steps, rotation) }
    Registry.reg3("euclidLegato") { pulses, steps, pat -> pat.euclidLegato(pulses, steps) }
    Registry.reg4("euclidLegatoRot") { pulses, steps, rotation, pat -> pat.euclidLegatoRot(pulses, steps, rotation) }
    Registry.reg4("euclidish", "eish") { pulses, steps, perc, pat -> pat.euclidish(pulses, steps, perc) }

    catalogue("Euclid") {
        // bjorklund(ons, steps): the list of 1 and 0 of a rhythm of `ons` hits in `steps` steps
        function(listOf("bjorklund"), 2) { ctx ->
            bjorklund(jsToNumber(ctx.kernelArg(0)), jsToNumber(ctx.kernelArg(1))).map { it.toDouble() }
        }
    }
}
