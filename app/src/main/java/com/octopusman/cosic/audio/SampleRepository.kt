package com.octopusman.cosic.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.core.net.toUri
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cosic.CosicEngine
import cosic.SampleBank
import cosic.SoundEntry
import cosic.SoundLibrary
import cosic.soundfont.SoundfontBank
import cosic.WaveTableBank
import cosic.sound.RegistryLibrary
import cosic.sound.registryKey
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CosicSamples"
private val IMPORTED_TREES = stringSetPreferencesKey("imported_trees")

/** A folder that takes longer to index than this after the default sounds are loaded does not hold up the first program: its sounds appear when it is done. */
private const val IMPORT_WAIT_MS = 3_000L

/** How often the lists of sounds look at the library for news; they only do while somebody shows them. */
private const val REVISION_POLL_MS = 500L

/** Where a sound comes from in the lists: the default library, or the user (an imported folder, `samples()` in a program). */
enum class BankKind { DEFAULT, USER }

/**
 * A sample bank as listed in the Sounds tab. [pitched] banks are played with notes (one recording per note), [count] is how many files `name:n`
 * can pick from, [tag] and [prebake] are what the map it came from said (see `SampleMapOptions`), [treeUri] is the imported folder a user
 * bank was made from, [wavetable] says it is a `wt_*` bank.
 */
data class BankSummary(
    val name: String,
    val count: Int,
    val pitched: Boolean,
    val kind: BankKind,
    val tag: String?,
    val prebake: Boolean,
    val treeUri: Uri?,
    val wavetable: Boolean = false,
) {
    /**
     * The drum machine this is a sound of: a bank of the default library that is tagged as a drum machine and called `<machine>_<sound>`
     * (`RolandTR909_bd`, `mridangam_ka`) is `s("<sound>").bank("<machine>")`; null for any other bank.
     */
    val kit: String?
        get() {
            if (kind != BankKind.DEFAULT || tag != DRUM_MACHINES_TAG) return null
            val underscore = name.indexOf('_')
            return if (underscore in 1 until name.length - 1) name.substring(0, underscore) else null
        }
}

/** The sample banks in [entries] (the sounds of the library that are made of files), by name. A user bank is listed under its registry key. */
internal fun bankSummaries(entries: List<SoundEntry>, treeOf: (registryKey: String) -> Uri?): List<BankSummary> =
    entries.mapNotNull { entry ->
        val kind = if (entry.prebake) BankKind.DEFAULT else BankKind.USER
        val key = registryKey(entry.name)
        val name = if (entry.prebake) entry.name else key
        val tree = if (entry.prebake) null else treeOf(key)
        when (entry) {
            is SampleBank -> BankSummary(name, entry.count, entry.pitched, kind, entry.tag, entry.prebake, tree)
            is WaveTableBank -> BankSummary(name, entry.groups.sumOf { it.urls.size }, true, kind, entry.tag, entry.prebake, tree, wavetable = true)
            is SoundfontBank -> BankSummary(name, entry.fonts.size, true, kind, entry.tag, entry.prebake, tree)
            else -> null
        }
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.name }))

/** How loading the default sounds went: [failed] are the packs (see [DefaultLibrary]) that could not be loaded. */
private class DefaultsState(val loading: Boolean, val failed: List<String>)

/**
 * What the Sounds tab says about loading: the default sounds are being loaded ([loading]) or some of them could not be ([failed], the
 * packs by name: the network is down, most likely), and [downloading] sample files are being fetched or decoded right now.
 */
data class SoundsStatus(val loading: Boolean, val failed: List<String>, val downloading: Int)

/**
 * The sounds of the app, as the library the engine plays from: the default sounds, loaded from the network at start (see [DefaultLibrary]),
 * and the folders the user imported. Everything the engine asks of a [SoundLibrary] is answered by the [RegistryLibrary] this holds;
 * lookups of samples never block (see [HttpSampleStore]).
 *
 * An imported folder is Strudel's: every folder of audio files in it, at any depth, is a bank named after the folder, and replaces a
 * bank of that name, `bd` of the default sounds included. The folders are kept in the settings, and indexed again at every start.
 */
class SampleRepository internal constructor(
    context: Context,
    private val prefs: DataStore<Preferences>,
    private val store: HttpSampleStore = HttpSampleStore(context),
    private val registry: RegistryLibrary = RegistryLibrary(store, CosicEngine.BUILTIN_SOUNDS, ready = false),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SoundLibrary by registry {
    constructor(context: Context, prefs: DataStore<Preferences>) : this(context, prefs, HttpSampleStore(context))

    private val appContext = context.applicationContext
    private val imported = ImportedBanks(registry)
    private val importLock = Mutex()
    private val defaults = MutableStateFlow(DefaultsState(loading = true, failed = emptyList()))
    private val retrying = AtomicBoolean()

    // The folders are indexed while the maps load, which takes longer.
    private val walked: Deferred<List<ImportedTree>> = scope.async { walkAll() }

    /** Ends when the default sounds are loaded (or failed to be) and the imported folders are in. */
    private val installed: Job = DefaultLibrary.install(registry, scope) { failed ->
        defaults.value = DefaultsState(loading = false, failed = failed)
        // Folders go on top of the default sounds, so that they win their names; the first program does not wait for a huge one.
        safely("importing folders") {
            val trees = withTimeoutOrNull(IMPORT_WAIT_MS) { walked.await() }
            if (trees != null) {
                importLock.withLock { imported.replaceAll(trees) }
            } else {
                scope.launch { safely("importing folders") { importLock.withLock { imported.replaceAll(walked.await()) } } }
            }
        }
    }

    /** The sample banks, sorted by name; the list follows the library, so it also shows what a program registers with `samples()`. */
    val banks: StateFlow<List<BankSummary>> = flow {
        var seen = -1L
        while (true) {
            val revision = registry.revision
            if (revision != seen) {
                seen = revision
                emit(bankSummaries(registry.entries()) { key -> imported.treeOf(key)?.toUri() })
            }
            delay(REVISION_POLL_MS)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether the default sounds are loaded, and how many files are being fetched. */
    val status: StateFlow<SoundsStatus> = combine(defaults, store.loading) { state, downloading ->
        SoundsStatus(state.loading, state.failed, downloading)
    }.stateIn(scope, SharingStarted.Eagerly, SoundsStatus(loading = true, failed = emptyList(), downloading = 0))

    /** Adds a folder picked with the system folder picker; its banks replace those of the same names, default ones included. */
    fun importTree(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scope.launch {
            safely("importing $uri") {
                prefs.edit { it[IMPORTED_TREES] = it[IMPORTED_TREES].orEmpty() + uri.toString() }
                reindex()
            }
        }
    }

    /** Takes an imported folder, and every sound made from it, away again; the sounds it replaced come back. */
    fun removeImported(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The grant is already gone, for example because the folder was deleted.
        }
        scope.launch {
            safely("removing $uri") {
                prefs.edit { it[IMPORTED_TREES] = it[IMPORTED_TREES].orEmpty() - uri.toString() }
                reindex()
            }
        }
    }

    /** Loads again the default sounds that could not be loaded (and the aliases), for when the network is back. */
    fun retryDefaults() {
        scope.launch {
            installed.join()
            if (!retrying.compareAndSet(false, true)) return@launch
            try {
                val previous = defaults.value.failed
                defaults.value = DefaultsState(loading = true, failed = previous)
                var failed = previous
                try {
                    failed = DefaultLibrary.load(registry, only = previous.toSet() - DefaultLibrary.ALIASES)
                } finally {
                    defaults.value = DefaultsState(loading = false, failed = failed)
                }
                // What was loaded now is registered over the imported banks of the same names: they go on top again.
                safely("importing folders") { importLock.withLock { imported.replaceAll(walkAll()) } }
            } finally {
                retrying.set(false)
            }
        }
    }

    /**
     * Waits for file [index] of group [group] of [bank] to be on the device and decoded, fetching it first if need be (up to a minute). It
     * never throws: a file that cannot be loaded is simply not there afterwards.
     */
    suspend fun load(bank: SampleBank, group: Int, index: Int) {
        val url = bank.groups.getOrNull(group)?.urls?.getOrNull(index) ?: return
        withContext(Dispatchers.IO) {
            try {
                store.awaitSample(url)
            } catch (_: IOException) {
            }
        }
    }

    /** Waits until no sample file is being fetched or decoded, at most [timeoutMillis]; true when that is so. */
    suspend fun awaitDownloads(timeoutMillis: Long): Boolean = store.awaitIdle(timeoutMillis)

    private suspend fun reindex() {
        installed.join() // folders go on top of the default sounds, however long those take
        importLock.withLock { imported.replaceAll(walkAll()) }
    }

    private suspend fun walkAll(): List<ImportedTree> {
        val trees = try {
            prefs.data.first()[IMPORTED_TREES].orEmpty()
        } catch (e: IOException) {
            Log.w(TAG, "cannot read the imported folders", e)
            return emptyList()
        }
        return trees.map { tree -> ImportedTree(tree, walkDocumentTree(appContext, tree.toUri())) }
    }

    /** Background work must never take the app down: what goes wrong is logged, and the sounds stay as they were. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }
}
