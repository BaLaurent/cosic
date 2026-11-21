package com.octopusman.cosic.share

import cosic.io.ShareLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class SharedImportTest {
    private val created = ArrayList<Pair<String, String>>()
    private val legacyAsked = ArrayList<String>()
    private var legacyAnswer: LegacyResult = LegacyResult.NotFound

    private val importer = SharedImporter(
        create = { name, code ->
            created.add(name to code)
            "project-${created.size}"
        },
        legacy = { id ->
            legacyAsked.add(id)
            legacyAnswer
        },
    )

    private fun import(text: String): ImportResult = runBlocking { importer.import(text) }

    @Test
    fun aLinkThatCarriesItsCodeMakesAProject() {
        val link = ShareLink.encode("s(\"bd sd\")")
        assertEquals(ImportResult.Created("project-1"), import(link))
        assertEquals(listOf(SharedImporter.DEFAULT_NAME to "s(\"bd sd\")"), created)
    }

    @Test
    fun theProjectIsNamedAfterTheTitleOfTheCode() {
        val code = "// @title Acid line\ns(\"bd\")"
        import(ShareLink.encode(code))
        assertEquals("Acid line", created.single().first)
    }

    @Test
    fun aBlankTitleIsNoTitle() {
        assertEquals(SharedImporter.DEFAULT_NAME, SharedImporter.nameOf("// @title    \ns(\"bd\")"))
        assertEquals(SharedImporter.DEFAULT_NAME, SharedImporter.nameOf("s(\"bd\")"))
    }

    @Test
    fun textThatIsNoAddressIsTakenAsCode() {
        assertEquals(ImportResult.Created("project-1"), import("s(\"hh*8\")"))
        assertEquals("s(\"hh*8\")", created.single().second)
    }

    @Test
    fun aShortLinkIsAskedForItsCode() {
        legacyAnswer = LegacyResult.Found("note(\"c3 e3\")")
        assertEquals(ImportResult.Created("project-1"), import("https://strudel.cc/?abc123DEF456"))
        assertEquals(listOf("abc123DEF456"), legacyAsked)
        assertEquals("note(\"c3 e3\")", created.single().second)
    }

    @Test
    fun aShortLinkWithNoCodeBehindItIsNotFound() {
        legacyAnswer = LegacyResult.NotFound
        assertEquals(ImportResult.NotFound, import("https://strudel.cc/?abc123DEF456"))
        assertEquals(emptyList(), created)
    }

    @Test
    fun aShortLinkThatCannotBeResolvedSaysWhy() {
        legacyAnswer = LegacyResult.Failed("HTTP 503")
        assertEquals(ImportResult.Failed("HTTP 503"), import("https://strudel.cc/?abc123DEF456"))
        assertEquals(emptyList(), created)
    }

    @Test
    fun aLinkWithABrokenCodeIsNoLink() {
        assertEquals(ImportResult.NotALink, import("https://strudel.cc/#%%%"))
        assertEquals(emptyList(), created)
    }

    @Test
    fun theTextOfAViewIntentIsItsAddress() {
        assertEquals("https://strudel.cc/#abc", sharedTextOf("android.intent.action.VIEW", "https://strudel.cc/#abc", null, null))
        assertNull(sharedTextOf("android.intent.action.VIEW", null, null, null))
    }

    @Test
    fun theTextOfASendIntentIsItsExtraText() {
        assertEquals("s(\"bd\")", sharedTextOf("android.intent.action.SEND", null, "text/plain", "s(\"bd\")"))
        assertEquals("s(\"bd\")", sharedTextOf("android.intent.action.SEND", null, null, "s(\"bd\")"))
    }

    @Test
    fun otherSharedTypesAndOtherIntentsCarryNoPattern() {
        assertNull(sharedTextOf("android.intent.action.SEND", null, "image/png", "s(\"bd\")"))
        assertNull(sharedTextOf("android.intent.action.MAIN", "https://strudel.cc/#abc", null, "x"))
        assertNull(sharedTextOf(null, null, null, null))
        assertNull(sharedTextOf("android.intent.action.SEND", null, "text/plain", "   "))
    }
}
