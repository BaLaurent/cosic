package cosic.parity

import cosic.pattern.jsToFixed

/*
 * The float rounding of test/tunes.test.mjs (Strudel at 84ef32eec8074f90c6690ab8d6a6c204f508b182, lines 8-18). The hap
 * strings themselves are printed by the engine (`Hap.show`); only the rounding that tune and extra snapshots are
 * compared with lives here, in the test source set, because the engine has no use for it.
 */

private val FLOAT_TEXT = Regex("-?\\d+\\.\\d+")
private val TRAILING_ZEROS = Regex("\\.?0+\\z")
private val DANGLING_DOT = Regex("\\.\\z")

/**
 * `roundFloatStrings` of test/tunes.test.mjs: every decimal number in [input] is rounded to [precision] digits with
 * `toFixed`, then loses trailing zeros and a dangling dot. Tunes and extra snippets are compared in this form,
 * examples are not.
 */
internal fun roundFloatStrings(input: String, precision: Int = 12): String =
    FLOAT_TEXT.replace(input) { match ->
        jsToFixed(match.value.toDouble(), precision)
            .replaceFirst(TRAILING_ZEROS, "")
            .replaceFirst(DANGLING_DOT, "")
    }
