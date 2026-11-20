package com.octopusman.cosic.audio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.documentfile.provider.DocumentFile
import cosic.BankInfo
import cosic.CosicEngine
import cosic.SampleData
import cosic.SoundLibrary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "CosicSamples"
private const val INDEX_ASSET = "samples/index.json"
private const val MAX_IMPORT_FILES = 1000
private const val IMPORT_MAX_SECONDS = 20
private const val CACHE_BUDGET_FLOATS = 16L * 1024 * 1024
private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "ogg", "flac", "m4a", "aac", "opus")
private val IMPORTED_TREES = stringSetPreferencesKey("imported_trees")
private val json = Json { ignoreUnknownKeys = true }

@Serializable
private class SampleIndex(val banks: List<IndexBank>)

@Serializable
private class IndexBank(val name: String, val pitched: Boolean, val files: List<IndexFile>, val kit: String? = null)

@Serializable
private class IndexFile(val file: String, val root: Int? = null)

enum class BankOrigin { BUNDLED, IMPORTED }

/** How a bank is grouped wherever sounds are listed. */
enum class BankKind { INSTRUMENT, PERCUSSION, IMPORTED }

/**
 * A sample bank as listed in the Sounds tab; [treeUri] is the imported folder it came from. A bank named
 * `<kit>_<sound>` is a sound of the drum machine [kit], played with `s("<sound>").bank("<kit>")`.
 */
data class BankSummary(
    val name: String,
    val count: Int,
    val pitched: Boolean,
    val origin: BankOrigin,
    val treeUri: Uri?,
    val kit: String?,
) {
    val kind: BankKind
        get() = when {
            origin == BankOrigin.IMPORTED -> BankKind.IMPORTED
            pitched -> BankKind.INSTRUMENT
            else -> BankKind.PERCUSSION
        }
}

private sealed interface SampleSource {
    /** Sources with the same key share one decoded copy in the cache. */
    val key: String

    data class Asset(val path: String) : SampleSource {
        override val key = "asset:$path"
    }

    data class Document(val uri: Uri) : SampleSource {
        override val key = uri.toString()
    }
}

private class BankRecord(
    val info: BankInfo,
    val origin: BankOrigin,
    val treeUri: Uri?,
    val sources: List<SampleSource>,
    val kit: String?,
)

private class CacheEntry(val data: SampleData, @Volatile var lastAccess: Long)

/**
 * Bundled and imported sample banks for the engine. Lookups never block: a sample that is not
 * decoded yet is reported as missing and decoded in the background, so asking for it once also
 * preloads it.
 */
class SampleRepository(context: Context, private val prefs: DataStore<Preferences>) : SoundLibrary {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decoders = Dispatchers.IO.limitedParallelism(2)
    private val indexLock = Mutex()

    private val bundled: Map<String, BankRecord> by lazy { readBundledBanks() }

    // Immutable snapshot, replaced as a whole whenever the index changes.
    @Volatile
    private var records: Map<String, BankRecord> = emptyMap()
    private val _banks = MutableStateFlow<List<BankSummary>>(emptyList())

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheLock = Any()
    private val accessClock = AtomicLong()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val broken = ConcurrentHashMap.newKeySet<String>()

    val banks: StateFlow<List<BankSummary>> = _banks.asStateFlow()

    init {
        scope.launch {
            // Bundled banks are usable at once; scanning imported folders can take a while.
            indexLock.withLock { publish(bundled) }
            reindex()
        }
    }

    override fun bank(name: String): BankInfo? = records[name]?.info

    override fun sample(name: String, index: Int): SampleData? {
        val source = records[name]?.sources?.getOrNull(index) ?: return null
        cache[source.key]?.let {
            it.lastAccess = accessClock.incrementAndGet()
            return it.data
        }
        if (source.key !in broken && loading.add(source.key)) scope.launch(decoders) { load(source) }
        return null
    }

    /** Adds a folder picked with the system folder picker; its banks override bundled ones of the same name. */
    fun importTree(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        scope.launch {
            prefs.edit { it[IMPORTED_TREES] = it[IMPORTED_TREES].orEmpty() + uri.toString() }
            reindex()
        }
    }

    fun removeImported(uri: Uri) {
        try {
            appContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The grant is already gone, for example because the folder was deleted.
        }
        scope.launch {
            prefs.edit { it[IMPORTED_TREES] = it[IMPORTED_TREES].orEmpty() - uri.toString() }
            reindex()
        }
    }

    private suspend fun reindex() = indexLock.withLock {
        val resolved = LinkedHashMap(bundled)
        // The stored set keeps import order, so a later import replaces banks of an earlier one.
        for (tree in prefs.data.first()[IMPORTED_TREES].orEmpty()) {
            for (bank in indexTree(Uri.parse(tree))) resolved[bank.info.name] = bank
        }
        publish(resolved)
    }

    private fun publish(banks: Map<String, BankRecord>) {
        records = banks
        _banks.value = banks.values.sortedBy { it.info.name }.map {
            BankSummary(it.info.name, it.info.count, it.info.rootNotes != null, it.origin, it.treeUri, it.kit)
        }
    }

    private fun readBundledBanks(): Map<String, BankRecord> {
        val text = appContext.assets.open(INDEX_ASSET).bufferedReader().use { it.readText() }
        return json.decodeFromString<SampleIndex>(text).banks.associate { bank ->
            val files = if (bank.pitched) bank.files.sortedBy { it.root } else bank.files
            val roots = if (bank.pitched) {
                IntArray(files.size) { requireNotNull(files[it].root) { "Pitched bank '${bank.name}' needs root notes" } }
            } else {
                null
            }
            bank.name to BankRecord(
                BankInfo(bank.name, files.size, roots),
                BankOrigin.BUNDLED,
                null,
                files.map { SampleSource.Asset("samples/${it.file}") },
                bank.kit,
            )
        }
    }

    /**
     * A tree is a bank of its own audio files plus one bank per direct subfolder that has audio files.
     * Those subfolders are also the sounds of a kit named after the tree (see [importedBanks]).
     */
    private fun indexTree(treeUri: Uri): List<BankRecord> {
        // A tree that is no longer accessible (folder removed, drive gone) simply drops out of the index.
        val root = DocumentFile.fromTreeUri(appContext, treeUri)?.takeIf { it.canRead() } ?: return emptyList()
        val banks = ArrayList<BankRecord>()
        var remaining = MAX_IMPORT_FILES

        fun addBanks(names: List<ImportedBank>, entries: List<Pair<String, DocumentFile>>) {
            val audio = entries.filter { isAudio(it.first) }.sortedBy { it.first.lowercase() }.take(remaining)
            if (names.isEmpty() || audio.isEmpty()) return
            // The banks of one folder share its files, which count against the limit once.
            remaining -= audio.size
            val sources = audio.map { SampleSource.Document(it.second.uri) }
            for (bank in names) {
                banks += BankRecord(BankInfo(bank.name, audio.size, null), BankOrigin.IMPORTED, treeUri, sources, bank.kit)
            }
        }

        // A kit's sound folders keep their own names only where no built-in sound has that name.
        val builtIn = { name: String -> name in CosicEngine.SYNTHS || name in CosicEngine.DRUMS || name in bundled }
        val rootEntries = root.entries()
        addBanks(importedBanks(root.name), rootEntries)
        for ((name, file) in rootEntries) {
            if (remaining <= 0) break
            if (!isAudio(name) && file.isDirectory) addBanks(importedBanks(file.name, parent = root.name, builtIn), file.entries())
        }
        return banks
    }

    private fun load(source: SampleSource) {
        try {
            val data = when (source) {
                is SampleSource.Asset -> appContext.assets.openFd(source.path).use { AudioDecoder.decode(it) }
                is SampleSource.Document -> AudioDecoder.decode(appContext, source.uri, IMPORT_MAX_SECONDS)
            }
            cacheSample(source.key, data)
        } catch (e: Exception) {
            Log.w(TAG, "cannot decode ${source.key}", e)
            broken.add(source.key)
        } finally {
            loading.remove(source.key)
        }
    }

    /** Evicts the least recently used samples once the cache outgrows its budget; playing voices keep their own array. */
    private fun cacheSample(key: String, data: SampleData) {
        synchronized(cacheLock) {
            cache[key] = CacheEntry(data, accessClock.incrementAndGet())
            var total = cache.values.sumOf { it.data.samples.size.toLong() }
            while (total > CACHE_BUDGET_FLOATS && cache.size > 1) {
                val oldest = cache.entries.minBy { it.value.lastAccess }
                cache.remove(oldest.key)
                total -= oldest.value.data.samples.size
            }
        }
    }
}

private fun isAudio(fileName: String) = fileName.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

private fun DocumentFile.entries(): List<Pair<String, DocumentFile>> = listFiles().map { (it.name ?: "") to it }
