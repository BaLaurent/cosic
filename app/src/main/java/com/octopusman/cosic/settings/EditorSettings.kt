package com.octopusman.cosic.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cosic.io.Prebake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The settings of the code editor that the web REPL keeps in its settings panel (`settings.mjs`), in the app's preferences. The values are
 * mirrored in memory, so reading them never waits; a change shows at once and is written behind. [scope] runs on the main thread.
 *
 * - [highlight]: mark the steps that sound while the music plays. On, as in the REPL.
 * - [flash]: flash the code when it is evaluated. On, as in the REPL.
 * - [autocomplete]: suggest function names above the keyboard while typing. On (the REPL has it off; on a phone the names are what one cannot type).
 * - [docs]: the documentation card of a function: from a suggestion and from "Docs at cursor". On.
 * - [includePrebakeInShare]: a web link carries the prebake script in front of the code, as the REPL's Share button does. On.
 * - [prebake]: the script that runs before the first program of a session; [Prebake.DEFAULT] until the user edits it.
 */
class EditorSettings(private val prefs: DataStore<Preferences>, private val scope: CoroutineScope) {
    private val _highlight = MutableStateFlow(true)
    private val _flash = MutableStateFlow(true)
    private val _autocomplete = MutableStateFlow(true)
    private val _docs = MutableStateFlow(true)
    private val _includePrebakeInShare = MutableStateFlow(true)
    private val _prebake = MutableStateFlow(Prebake.DEFAULT)

    val highlight: StateFlow<Boolean> = _highlight.asStateFlow()
    val flash: StateFlow<Boolean> = _flash.asStateFlow()
    val autocomplete: StateFlow<Boolean> = _autocomplete.asStateFlow()
    val docs: StateFlow<Boolean> = _docs.asStateFlow()
    val includePrebakeInShare: StateFlow<Boolean> = _includePrebakeInShare.asStateFlow()
    val prebake: StateFlow<String> = _prebake.asStateFlow()

    // Writes of the prebake script that have not finished. While there are some, what the store reports is older than what was typed, and is not taken
    // (the script is typed key by key, and the store answers every write). Main thread only, like the collector below.
    private var prebakeWritesPending = 0

    init {
        prefs.data.onEach { p ->
            _highlight.value = p[HIGHLIGHT] ?: true
            _flash.value = p[FLASH] ?: true
            _autocomplete.value = p[AUTOCOMPLETE] ?: true
            _docs.value = p[DOCS] ?: true
            _includePrebakeInShare.value = p[INCLUDE_PREBAKE_IN_SHARE] ?: true
            if (prebakeWritesPending == 0) _prebake.value = p[PREBAKE] ?: Prebake.DEFAULT
        }.launchIn(scope)
    }

    fun setHighlight(on: Boolean) = set(_highlight, HIGHLIGHT, on)

    fun setFlash(on: Boolean) = set(_flash, FLASH, on)

    fun setAutocomplete(on: Boolean) = set(_autocomplete, AUTOCOMPLETE, on)

    fun setDocs(on: Boolean) = set(_docs, DOCS, on)

    fun setIncludePrebakeInShare(on: Boolean) = set(_includePrebakeInShare, INCLUDE_PREBAKE_IN_SHARE, on)

    /** Keeps [text] as the prebake script (an empty text is a script that does nothing). */
    fun setPrebake(text: String) {
        _prebake.value = text
        writePrebake { it[PREBAKE] = text }
    }

    /** Back to the script the app starts with. */
    fun resetPrebake() {
        _prebake.value = Prebake.DEFAULT
        writePrebake { it.remove(PREBAKE) }
    }

    private fun writePrebake(change: (MutablePreferences) -> Unit) {
        prebakeWritesPending++
        scope.launch {
            try {
                prefs.edit(change)
            } finally {
                prebakeWritesPending--
            }
        }
    }

    private fun set(flow: MutableStateFlow<Boolean>, key: Preferences.Key<Boolean>, on: Boolean) {
        flow.value = on
        scope.launch { prefs.edit { it[key] = on } }
    }

    private companion object {
        val HIGHLIGHT = booleanPreferencesKey("editor_highlight")
        val FLASH = booleanPreferencesKey("editor_flash")
        val AUTOCOMPLETE = booleanPreferencesKey("editor_autocomplete")
        val DOCS = booleanPreferencesKey("editor_docs")
        val INCLUDE_PREBAKE_IN_SHARE = booleanPreferencesKey("editor_prebake_in_share")
        val PREBAKE = stringPreferencesKey("editor_prebake_script")
    }
}
