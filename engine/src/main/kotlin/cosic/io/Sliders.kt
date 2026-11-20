package cosic.io

import cosic.SliderConfig
import cosic.pattern.jsStringToNumber
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor

/**
 * The values of the sliders of the program being played, by the id the transpiler gave them (`slider_<offset of the first argument>`).
 * `sliderWithID` stores the value written in the code when the program is evaluated and reads the slider's value whenever its pattern is
 * queried; the editor sets it when the user moves the slider (`sliderValues` of slider.mjs). Safe to use from any thread.
 */
class SliderBank {
    private val values = ConcurrentHashMap<String, Any>()

    fun set(id: String, value: Any) {
        values[id] = value
    }

    fun get(id: String): Any? = values[id]
}

/** The value written in the code, when the first argument of `slider(...)` is a number literal (a signed one counts); else null. */
val SliderConfig.initial: Double?
    get() {
        val text = value ?: return null
        // `Number("-0x10")` is NaN, but `-0x10` is a number in code
        val negative = text.startsWith("-")
        val unsigned = if (negative || text.startsWith("+")) text.substring(1) else text
        val number = jsStringToNumber(unsigned)
        return if (number.isNaN()) null else if (negative) -number else number
    }

/**
 * How a slider's value is written into the code. The browser's `<input type=range>` snaps the value to `min + k·step` and the page
 * writes what the input reports as text (slider.mjs:25-54); this does the same, so the number in the code is the one the slider plays.
 */
object SliderCodec {
    private const val MAX_DECIMALS = 12

    /** The step a slider without one has (`(max - min) / 1000`, slider.mjs:30). */
    fun stepOf(min: Double, max: Double, step: Double?): Double {
        val given = step?.let { abs(it) }
        return if (given != null && given > 0.0 && given.isFinite()) given else abs(max - min) / 1000.0
    }

    /** [value] snapped to the grid of [step] from the smaller bound, clamped to the bounds, as the text that replaces the literal. */
    fun format(value: Double, min: Double, max: Double, step: Double?): String {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val grid = stepOf(lo, hi, step)
        val snapped = if (value.isNaN()) lo else if (grid > 0.0) lo + floor((value - lo) / grid + 0.5) * grid else lo
        val clamped = snapped.coerceIn(lo, hi)
        val decimals = minOf(maxOf(decimals(grid), decimals(lo)), MAX_DECIMALS)
        val text = BigDecimal(clamped).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    /** The number of digits after the decimal point of the shortest text of [x]. */
    private fun decimals(x: Double): Int {
        if (!x.isFinite()) return 0
        return BigDecimal(x.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
    }
}
