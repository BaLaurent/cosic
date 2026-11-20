package cosic.sound

import cosic.BankGroup
import cosic.SampleBank
import cosic.SampleData
import cosic.SampleMapException
import cosic.SampleMapOptions
import cosic.SampleStore
import cosic.SoundEntry
import cosic.SoundLibrary
import cosic.WaveTableBank
import cosic.WavetableFrames
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where soundfont files are fetched from until `setSoundfontUrl` says otherwise (fontloader.mjs:13). */
internal const val DEFAULT_SOUNDFONT_URL = "https://felixroos.github.io/webaudiofontdata/sound"

/** Wavetables that stay cut into frames; the decoded originals are the [SampleStore]'s to keep or drop. */
private const val MAX_CACHED_TABLES = 64

/**
 * The one [SoundLibrary]: a [SoundRegistry] of the sounds `samples()`, the default library and imports registered, with the engine's own
 * [builtins] behind it, and a [SampleStore] that loads what the entries point at. Pure JVM; safe to call from any thread (the
 * registry is replaced under a lock, lookups are lock free).
 *
 * [ready] says whether the default sounds are installed already. A host that installs them in the background passes `false`,
 * and calls [markReady] when it is done, whether it worked or not: `awaitReady` (which program evaluation calls) waits for that.
 *
 * Besides the interface, the host registers and removes what it imports: [registerSampleSource] (a list of files, as the folder import
 * of Strudel does), [unregister].
 */
class RegistryLibrary(private val store: SampleStore, builtins: Set<String>, ready: Boolean = true) : SoundLibrary {
    private val registry = SoundRegistry(builtins)
    private val readiness = CountDownLatch(if (ready) 0 else 1)
    private val tables = ConcurrentHashMap<String, WavetableFrames>()

    /** Where soundfont files are fetched from, as `<url>/<font>.js`: `setSoundfontUrl`. */
    @Volatile
    internal var soundfontUrl: String = DEFAULT_SOUNDFONT_URL
        private set

    /** The store the library loads from: for the code that decodes things other than samples (soundfonts). */
    internal val sampleStore: SampleStore get() = store

    override fun sound(name: String): SoundEntry? = registry.lookup(name)

    override fun sample(bank: SampleBank, group: Int, index: Int): SampleData? {
        val url = bank.groups.getOrNull(group)?.urls?.getOrNull(index) ?: return null
        return store.sample(url)
    }

    override fun wavetable(bank: WaveTableBank, group: Int, index: Int): WavetableFrames? {
        val url = bank.groups.getOrNull(group)?.urls?.getOrNull(index) ?: return null
        val key = "$url,${bank.frameLen}"
        tables[key]?.let { return it }
        val data = store.sample(url) ?: return null
        if (tables.size >= MAX_CACHED_TABLES) tables.clear()
        return tables.computeIfAbsent(key) { cutFrames(data, bank.frameLen) }
    }

    override fun entries(): List<SoundEntry> = registry.entries()

    override val revision: Long get() = registry.revision

    override fun awaitReady(timeoutMillis: Long): Boolean = readiness.await(maxOf(timeoutMillis, 0L), TimeUnit.MILLISECONDS)

    /** The default sounds are installed, or will never be: [awaitReady] returns true from now on. */
    fun markReady() = readiness.countDown()

    override fun register(entry: SoundEntry) = registry.register(entry)

    /** Removes the sound registered as [name] (in any case); true when there was one. Names that were made from it with an alias stay. */
    fun unregister(name: String): Boolean = registry.remove(name)

    /**
     * `registerSampleSource(key, urls)` (sampler.mjs:386-393) for a flat list of files: a [SampleBank] of them under [key], or a
     * [WaveTableBank] when the key (as written) starts with `wt_`. Replaces what the key's sound was.
     */
    fun registerSampleSource(key: String, urls: List<String>, options: SampleMapOptions = SampleMapOptions()) {
        val groups = listOf(BankGroup(null, null, urls))
        register(
            if (key.startsWith("wt_")) WaveTableBank(key, groups, DEFAULT_FRAME_LENGTH, options.tag, options.prebake)
            else SampleBank(key, groups, options.tag, options.prebake),
        )
    }

    override fun loadSampleMap(url: String, baseUrl: String?, options: SampleMapOptions) {
        val resolved = SampleMaps.mapUrl(url)
        val json = try {
            LooseJson.parse(store.fetchText(resolved))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (json !is Map<*, *>) throw SampleMapException("error loading \"$resolved\"")
        @Suppress("UNCHECKED_CAST")
        val map = json as Map<String, Any?>
        val own = (map["_base"] as? String)?.takeIf { it.isNotEmpty() } ?: SampleMaps.baseUrlOf(resolved)
        loadSampleMap(map, baseUrl?.takeIf { it.isNotEmpty() } ?: own, options)
    }

    override fun loadSampleMap(map: Map<String, Any?>, baseUrl: String?, options: SampleMapOptions) {
        val entries = ArrayList<SoundEntry>(map.size)
        try {
            SampleMaps.processInto(map, baseUrl ?: (map["_base"] as? String).orEmpty(), options, entries)
        } finally {
            registry.registerAll(entries) // the banks before one that is wrong stay registered, as in Strudel
        }
    }

    override fun soundAlias(original: String, alias: String): Boolean {
        if (registry.alias(original, alias)) return true
        cosic.pattern.logger("soundAlias: original sound not found", "log", null)
        return false
    }

    override fun aliasBank(map: Map<String, Any?>) = registry.aliasBank(map)

    override fun aliasBank(url: String) {
        val json = try {
            LooseJson.parse(store.fetchText(url))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (json !is Map<*, *>) throw SampleMapException("error loading \"$url\"")
        @Suppress("UNCHECKED_CAST")
        registry.aliasBank(json as Map<String, Any?>)
    }

    override fun aliasBank(bank: String, alias: Any) = registry.aliasBank(mapOf(bank to alias))

    override fun duration(name: String, n: Double): Double {
        val entry = registry.exact(name) ?: throw IllegalArgumentException("sound $name not found! Is it loaded?")
        val bank = entry as? SampleBank ?: throw IllegalArgumentException("sound $name has no samples")
        val choice = commonSampleInfo(bank.groups, n, null, null)
        return store.awaitSample(bank.groups[choice.group].urls[choice.index]).durationSeconds
    }

    override fun irSample(name: String, i: Int): SampleData? {
        val bank = registry.lookup(name) as? SampleBank ?: return null
        val flat = bank.groups.flatMap { it.urls }
        // superdough.mjs:962-967: the index wraps by the number of files for a list, by the number of notes for a pitched map
        val modulus = if (bank.pitched) bank.groups.size else bank.groups.firstOrNull()?.urls?.size ?: 0
        if (i < 0 || modulus == 0) return null
        return flat.getOrNull(i % modulus)?.let(store::sample)
    }

    override fun setSoundfontUrl(url: String) {
        soundfontUrl = url
    }

    /** `getPayload` (wavetable.mjs:46-63): channel 0 of [data] cut into frames of [frameLen]. */
    private fun cutFrames(data: SampleData, frameLen: Int): WavetableFrames {
        val length = maxOf(frameLen, 1)
        val total = data.frames
        val channels = data.channels
        val first = FloatArray(total) { data.samples[it * channels] }
        val count = maxOf(1, total / length)
        val frames = List(count) { i ->
            val start = i * length
            first.copyOfRange(start, minOf(start + length, total))
        }
        return WavetableFrames(frames, frameLen, data.sampleRate)
    }
}
