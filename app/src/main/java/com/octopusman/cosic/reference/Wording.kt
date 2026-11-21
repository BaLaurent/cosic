package com.octopusman.cosic.reference

private val MORE_INFO = Regex("""\s*more info at\s+https?://\S*strudel\S*""", RegexOption.IGNORE_CASE)
private val ADDRESS = Regex("""https?://\S*strudel\S*""", RegexOption.IGNORE_CASE)

/**
 * [this], a text of the documentation, in the words the app uses for itself. The documentation (the cards and the reference) is the JSDoc of the environment the app
 * is modelled on, which names itself in a few places; the app never shows that name. Where it appears the app's own name stands in its place, and an address of that
 * environment's web site, which leads nowhere useful in the app, is left out together with the "more info at" that introduces it.
 */
internal fun String.inAppWords(): String {
    if (!contains("strudel", ignoreCase = true)) return this
    return replace(MORE_INFO, "").replace(ADDRESS, "").replace("Strudel", "Cosic")
}
