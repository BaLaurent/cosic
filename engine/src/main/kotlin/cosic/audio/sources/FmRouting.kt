package cosic.audio.sources

/**
 * One connection of a voice's FM graph: the output of operator [source] (1 to 8), times [amount] and times the frequency of that operator, is added to
 * the frequency of [target], which is the carrier (0) or another operator (1 to 8). [control] is the name of the control that asked for it.
 */
internal class FmEdge(val source: Int, val target: Int, val control: String, val amount: Double)

/**
 * The FM matrix of superdough (`applyFM`, packages/superdough/helpers.mjs:429-502): which controls make which connections, and how the controls of one
 * operator are named.
 *
 * Operator `i` is the one whose controls end in `i` (`fmh3`, `fmwave3`, `fmattack3`), except operator 1, whose controls have no suffix (`fmh`, `fmwave`,
 * `fmattack`). The amount of the connection from operator `i` into target `j` is the control `fmi<i><j>` (`fmi12`: operator 1 into operator 2; `fmi20`:
 * operator 2 into the carrier), except for the eight connections "operator `i` into the one before it" (operator 1 into the carrier, 2 into 1, and so on),
 * which have the short names `fmi`, `fmi2`, `fmi3` ... `fmi8` that `fm`, `fm2`, ... write. Eight operators can connect to the carrier and to each of the
 * eight: 72 connections, 8 with a short name and 64 with a matrix name. The language accepts 81 matrix names (`fmi00` to `fmi88`), but the 17 that
 * `applyFM` never looks at (`fmi00` to `fmi08`, which have no source, and `fmi10`, `fmi21`, ... `fmi87`, whose connections have a short name) do nothing.
 */
internal object FmRouting {
    /** Operators superdough can create (`for (let i = 1; i <= 8; i++)`). */
    const val OPERATORS = 8

    /** The suffix of the controls of operator [index]: empty for the first operator, its number for the others. */
    fun suffix(index: Int): String = if (index == 1) "" else index.toString()

    /** The control holding the amount of the connection from operator [source] (1 to 8) into [target] (0, the carrier, to 8). */
    fun controlName(source: Int, target: Int): String {
        require(source in 1..OPERATORS) { "operator $source" }
        require(target in 0..OPERATORS) { "target $target" }
        return if (source == target + 1) "fmi" + suffix(source) else "fmi$source$target"
    }

    /**
     * The connections [controls] ask for, in the order `applyFM` makes them (source 1 to 8 in the outer loop, target 0 to 8 in the inner one): those whose
     * control holds a number other than 0 (`if (!amt) continue`). A value that is not a number (NaN, text that is no number) is not an amount.
     */
    fun edges(controls: SourceControls): List<FmEdge> {
        val edges = ArrayList<FmEdge>()
        for (source in 1..OPERATORS) {
            for (target in 0..OPERATORS) {
                val control = controlName(source, target)
                val amount = controls.number(control) ?: continue
                if (amount == 0.0) continue
                edges.add(FmEdge(source, target, control, amount))
            }
        }
        return edges
    }
}
