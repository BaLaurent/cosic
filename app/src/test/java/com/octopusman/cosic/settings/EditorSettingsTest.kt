package com.octopusman.cosic.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import cosic.io.Prebake
import java.io.File
import java.util.concurrent.Executors
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class EditorSettingsTest {
    // one thread plays the part of the main thread, which the settings expect
    private val executor = Executors.newSingleThreadExecutor()
    private val thread = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val directory: File = createTempDirectory("editor-settings").toFile()
    private val storeScope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        storeScope.cancel()
        executor.shutdownNow()
        directory.deleteRecursively()
    }

    /** A DataStore on the one file of this test (a file may have only one active DataStore, so a test makes one and shares it). */
    private fun store(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = storeScope) { File(directory, "prefs.preferences_pb") }

    private fun settings(store: DataStore<Preferences>) = EditorSettings(store, scope)

    private fun until(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    @Test
    fun theEditorStartsWithEverythingOnAndTheDefaultPrebakeScript() {
        val settings = settings(store())
        assertTrue(settings.highlight.value && settings.flash.value && settings.autocomplete.value && settings.docs.value && settings.includePrebakeInShare.value)
        assertEquals(Prebake.DEFAULT, settings.prebake.value)
    }

    @Test
    fun aSwitchShowsAtOnceAndIsKeptInTheStore() {
        val store = store()
        val first = settings(store)
        runBlocking(thread) {
            first.setHighlight(false)
            first.setAutocomplete(false)
        }
        assertFalse(first.highlight.value)
        assertFalse(first.autocomplete.value)
        // another reader of the store (the next start of the app, in effect) finds them
        val reader = settings(store)
        until("the switches to be stored") { !reader.highlight.value && !reader.autocomplete.value }
        assertTrue(reader.flash.value && reader.docs.value && reader.includePrebakeInShare.value)
    }

    @Test
    fun theScriptIsKeptAndResetBringsBackTheDefault() {
        val store = store()
        val first = settings(store)
        val observer = settings(store)
        runBlocking(thread) { first.setPrebake("const x = 1") }
        assertEquals("const x = 1", first.prebake.value)
        until("the script to be stored") { observer.prebake.value == "const x = 1" }

        runBlocking(thread) { first.resetPrebake() }
        assertEquals(Prebake.DEFAULT, first.prebake.value)
        until("the default to be stored") { observer.prebake.value == Prebake.DEFAULT }
    }

    @Test
    fun anEmptyScriptIsAScriptThatDoesNothing() {
        val store = store()
        val first = settings(store)
        val observer = settings(store)
        runBlocking(thread) { first.setPrebake("") }
        until("the empty script to be stored") { observer.prebake.value == "" }
    }

    @Test
    fun whatTheStoreAnswersToAnEarlierKeystrokeNeverTakesBackALaterOne() {
        val store = store()
        val settings = settings(store)
        val observer = settings(store)
        val seen = ArrayList<String>()
        runBlocking(thread) { settings.prebake.onEach { seen.add(it) }.launchIn(scope) }
        runBlocking(thread) {
            for (text in listOf("a", "ab", "abc", "abcd", "abcde")) settings.setPrebake(text)
        }
        // let the store answer every write
        until("the script to be stored") { observer.prebake.value == "abcde" }
        runBlocking(thread) { delay(100) }
        assertEquals("abcde", settings.prebake.value)
        // what was seen only ever grows: an older text is not seen again after a newer one
        val typed = runBlocking(thread) { seen.filter { it != Prebake.DEFAULT } }
        assertEquals(typed.sortedBy { it.length }, typed)
        assertEquals(typed.distinct(), typed)
    }
}
