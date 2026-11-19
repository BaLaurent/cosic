package com.octopusman.cosic.reference

import cosic.script.ReferenceEntry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReferenceSearchTest {
    private val entry = ReferenceEntry("seq", listOf("sequence", "fastcat"), "Constructors", "seq(...xs)", "Plays the items in turn.", "seq(1, 2)")

    @Test
    fun aBlankQueryMatchesEverything() {
        assertTrue(entry.matches(""))
        assertTrue(entry.matches("   "))
    }

    @Test
    fun theNameMatchesIgnoringCase() {
        assertTrue(entry.matches("SE"))
        assertTrue(entry.matches("eq"))
    }

    @Test
    fun anAliasMatches() {
        assertTrue(entry.matches("fastc"))
    }

    @Test
    fun surroundingSpacesAreIgnored() {
        assertTrue(entry.matches("  seq "))
    }

    @Test
    fun otherTextDoesNotMatch() {
        assertFalse(entry.matches("stack"))
        // Only names and aliases are searched, not the description.
        assertFalse(entry.matches("turn"))
    }
}
