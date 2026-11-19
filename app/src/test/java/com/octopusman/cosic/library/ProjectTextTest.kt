package com.octopusman.cosic.library

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectTextTest {
    @Test
    fun firstCodeLineSkipsCommentsAndBlankLines() {
        assertEquals("setcpm(30)", firstCodeLine("// New sketch\n\n  // more\n  setcpm(30)\n\$: s(\"bd\")"))
    }

    @Test
    fun firstCodeLineIsTrimmed() {
        assertEquals("s(\"bd\")", firstCodeLine("   s(\"bd\")   \n"))
    }

    @Test
    fun firstCodeLineIsEmptyWhenThereIsOnlyComments() {
        assertEquals("", firstCodeLine("// one\n// two\n"))
        assertEquals("", firstCodeLine(""))
    }

    @Test
    fun exampleTitleIsTheFirstLineWithoutTheSlashes() {
        assertEquals("First beat", exampleTitle("// First beat\nsetcpm(30)"))
        assertEquals("Tight", exampleTitle("//Tight  \n"))
    }

    private val minute = 60_000L
    private val now = 10_000_000_000L

    @Test
    fun ageIsJustNowUnderAMinuteAndForClocksThatRunBackwards() {
        assertEquals(Age.JustNow, ageOf(updatedAt = now - 59_999, now = now))
        assertEquals(Age.JustNow, ageOf(updatedAt = now + 5 * minute, now = now))
    }

    @Test
    fun ageCountsWholeMinutesHoursAndDays() {
        assertEquals(Age.Minutes(1), ageOf(now - minute, now))
        assertEquals(Age.Minutes(59), ageOf(now - 59 * minute - 30_000, now))
        assertEquals(Age.Hours(1), ageOf(now - 60 * minute, now))
        assertEquals(Age.Hours(23), ageOf(now - 24 * 60 * minute + 1, now))
        assertEquals(Age.Days(1), ageOf(now - 24 * 60 * minute, now))
        assertEquals(Age.Days(29), ageOf(now - 30 * 24 * 60 * minute + 1, now))
    }

    @Test
    fun ageIsADateFromThirtyDaysOn() {
        val updatedAt = now - 30 * 24 * 60 * minute
        assertEquals(Age.OnDate(updatedAt), ageOf(updatedAt, now))
    }
}
