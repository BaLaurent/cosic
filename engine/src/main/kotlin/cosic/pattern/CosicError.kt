package cosic.pattern

import cosic.SourceRange

/**
 * The single error type for everything the user can get wrong in a program: syntax errors, unknown names,
 * bad arguments and values that cannot be used. [range] points at the offending code when it is known.
 */
internal class CosicError(override val message: String, val range: SourceRange? = null) :
    Exception(message, null, false, false) {

    /** This error, or a copy carrying [at] when it has no range yet. */
    fun withRange(at: SourceRange?): CosicError = if (range != null || at == null) this else CosicError(message, at)
}
