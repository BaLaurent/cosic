package cosic.kabel

import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToString

private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' // JavaScript's `\b` is ASCII

/** The id every voice's gate gets: the number in the original is random per event, and nothing but `includes('strudel-gate')` ever looks at it. */
internal const val GATE_CALL = "cc('strudel-gate-0')"

/**
 * What `superdough()` does to the source of a worklet before it compiles it (superdough.mjs:640-643), as text:
 *
 *     fx.workletSrc
 *       .replace(/\bpat\[(\d+)\]/g, (_, i) => fx.workletInputs[i])
 *       .replaceAll('sFreq', getFrequencyFromValue(value))
 *       .replaceAll('sGate', `cc('strudel-gate-${chainID}')`)
 *
 * so a `pat[i]` becomes whatever `String(workletInputs[i])` is (an index that is not there, or not canonical as `pat[01]`, `undefined`), then every
 * `sFreq` (also inside what was just inserted) the frequency [freq] as JavaScript prints it, then every `sGate` a gate.
 */
internal fun substituteWorklet(src: String, inputs: List<Any?>, freq: Double): String {
    val sb = StringBuilder(src.length + 32)
    var i = 0
    while (i < src.length) {
        if (src.startsWith("pat[", i) && (i == 0 || !isWordChar(src[i - 1]))) {
            var j = i + 4
            while (j < src.length && src[j] in '0'..'9') j++
            if (j > i + 4 && j < src.length && src[j] == ']') {
                val digits = src.substring(i + 4, j)
                val index = if (digits == "0" || digits[0] != '0') digits.toIntOrNull() else null // `array["01"]` is undefined
                sb.append(jsToString(if (index != null && index < inputs.size) inputs[index] else Undefined))
                i = j + 1
                continue
            }
        }
        sb.append(src[i])
        i++
    }
    return sb.toString().replace("sFreq", jsNumberToString(freq)).replace("sGate", GATE_CALL)
}
