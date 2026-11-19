package com.octopusman.cosic.library

private const val MINUTE_MS = 60_000L
private const val HOUR_MINUTES = 60L
private const val DAY_MINUTES = 24 * HOUR_MINUTES
private const val DATE_AFTER_DAYS = 30L

/** The first line of [code] that is neither blank nor a `//` comment, trimmed; empty when there is none. */
fun firstCodeLine(code: String): String =
    code.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("//") }.orEmpty()

/** How long ago something happened, in the unit that suits it. */
sealed interface Age {
    data object JustNow : Age

    data class Minutes(val value: Int) : Age

    data class Hours(val value: Int) : Age

    data class Days(val value: Int) : Age

    /** Too long ago to count: show the date of [epochMillis]. */
    data class OnDate(val epochMillis: Long) : Age
}

/** The [Age] of [updatedAt] at [now]; a time in the future (a clock that was set back) counts as just now. */
fun ageOf(updatedAt: Long, now: Long): Age {
    val minutes = (now - updatedAt) / MINUTE_MS
    return when {
        minutes < 1 -> Age.JustNow
        minutes < HOUR_MINUTES -> Age.Minutes(minutes.toInt())
        minutes < DAY_MINUTES -> Age.Hours((minutes / HOUR_MINUTES).toInt())
        minutes < DATE_AFTER_DAYS * DAY_MINUTES -> Age.Days((minutes / DAY_MINUTES).toInt())
        else -> Age.OnDate(updatedAt)
    }
}
