package com.octopusman.cosic.audio

import android.app.ActivityManager
import android.content.Context
import androidx.core.net.toUri
import com.octopusman.cosic.BuildConfig
import cosic.SampleData
import cosic.SampleStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val MAX_REDIRECTS = 5
private const val NETWORK_THREADS = 4
private const val DECODE_THREADS = 2
private const val MAP_CACHE_TEMP = ".tmp"
private const val MEGA = 1024L * 1024

/** The decoded samples the store keeps in memory, in floats: 35 % of the app's heap, between 16 and 64 million (see R10 of the plan). */
internal fun decodedBudgetFloats(memoryClassMegabytes: Int): Long =
    (memoryClassMegabytes * MEGA / 4 * 35 / 100).coerceIn(16 * MEGA, 64 * MEGA)

/** What [HttpSampleStore] needs to know; the defaults are the limits of the app. */
internal class StoreConfig(
    /** Where the sample maps (JSON) are kept, with the ETag they came with. */
    val mapCacheDir: File,
    /** Where the downloaded audio files are kept. */
    val sampleCacheDir: File,
    val userAgent: String,
    /** The most floats of decoded audio the store keeps in memory; the sample used least recently goes first. */
    val decodedBudgetFloats: Long,
    val diskCapBytes: Long = DISK_CACHE_CAP_BYTES,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 20_000,
    /** A map that is cached already is only checked for news: it must not hold up the start of the app on a bad network. */
    val revalidateConnectTimeoutMs: Int = 4_000,
    val revalidateReadTimeoutMs: Int = 8_000,
    val maxTextBytes: Long = 20 * MEGA,
    val maxFileBytes: Long = 256 * MEGA,
    /** A file that could not be loaded is not asked for again by [HttpSampleStore.sample] for this long. */
    val failureMemoryMs: Long = 30_000,
    val awaitTimeoutMs: Long = 60_000,
    /** A clock that only goes forward, for the failure memory. */
    val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Wall-clock time, for the use marks of the disk cache. */
    val wallMillis: () -> Long = System::currentTimeMillis,
)

private fun storeConfig(context: Context): StoreConfig {
    val app = context.applicationContext
    val memoryClass = (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
    return StoreConfig(
        mapCacheDir = File(app.filesDir, "sample-maps"),
        sampleCacheDir = File(app.cacheDir, "samples"),
        userAgent = "Cosic/${BuildConfig.VERSION_NAME} (Android)",
        decodedBudgetFloats = decodedBudgetFloats(memoryClass),
    )
}

/**
 * The [SampleStore] of the app: it loads sample maps and audio files from the network (and `content:` documents of imported folders),
 * keeps what it loaded on disk, and hands out decoded samples without ever making the caller wait.
 *
 * - [fetchText] (blocking) reads a JSON map: a request with the ETag of the copy on disk, which a `304` keeps; when the network fails the
 *   copy is used, if there is one.
 * - [sample] (never blocks) returns the decoded sample, or null while it is being downloaded and decoded or after that failed; the first
 *   call starts the load, so asking once preloads. A file that failed is not asked for again for 30 seconds. Loads of files go through
 *   4 network threads and 2 decoding threads; downloads are kept in the disk cache (1 GiB, least recently used out first), decoded samples
 *   in an LRU of a budget of floats that depends on the memory the app is allowed.
 * - [awaitSample] is the same load, blocking (60 seconds at most) and throwing [IOException].
 *
 * URLs are those of the browser: see [normalizeUrl]. `https` always works; `http` only to local hosts ([isCleartextAllowed]). Redirects
 * are followed by hand (5 at most, `http` to `https` included), and checked like the first URL.
 */
internal class HttpSampleStore internal constructor(
    private val config: StoreConfig,
    private val decoder: (ByteArray, String) -> SampleData,
    private val openContent: (String) -> InputStream,
) : SampleStore {
    constructor(context: Context, decoder: AndroidAudioDecoder = AndroidAudioDecoder) : this(
        storeConfig(context),
        decoder::decode,
        { url -> context.applicationContext.contentResolver.openInputStream(url.toUri()) ?: throw IOException("cannot open $url") },
    )

    private class Decoded(val data: SampleData) {
        @Volatile
        var lastUse = 0L
    }

    private class Failure(val at: Long, val error: IOException)

    private class CachedMap(val etag: String?, val body: String)

    private val diskCache = SampleDiskCache(config.sampleCacheDir, config.diskCapBytes, config.wallMillis)
    private val networkPool = threadPool(NETWORK_THREADS, "sample-net")
    private val decodePool = threadPool(DECODE_THREADS, "sample-decode")

    private val decoded = ConcurrentHashMap<String, Decoded>()
    private val decodedFloats = AtomicLong()
    private val useClock = AtomicLong()
    private val inflight = ConcurrentHashMap<String, CompletableFuture<SampleData>>()
    private val failures = ConcurrentHashMap<String, Failure>()
    private val lock = Any() // starts and ends of loads, and what they change in the caches
    private val loadingCount = MutableStateFlow(0)

    /** How many files are being downloaded or decoded right now. */
    val loading: StateFlow<Int> = loadingCount.asStateFlow()

    // -- SampleStore ---------------------------------------------------------------------------------------------------------------

    override fun sample(url: String): SampleData? {
        decoded[url]?.let { return use(it).data }
        if (inflight.containsKey(url)) return null
        val failure = failures[url]
        if (failure != null && config.monotonicMillis() - failure.at < config.failureMemoryMs) return null
        start(url, retryFailed = false)
        return null
    }

    override fun awaitSample(url: String): SampleData {
        decoded[url]?.let { return use(it).data }
        val future = start(url, retryFailed = true) ?: throw IOException("cannot load $url")
        try {
            return future.get(config.awaitTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException("cannot load $url: ${e.cause?.message}", e.cause)
        } catch (e: TimeoutException) {
            throw IOException("timed out loading $url", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("interrupted while loading $url")
        }
    }

    override fun failureOf(url: String): String? {
        if (decoded.containsKey(url) || inflight.containsKey(url)) return null
        val failure = failures[url] ?: return null
        if (config.monotonicMillis() - failure.at >= config.failureMemoryMs) return null
        return failure.error.message ?: failure.error.toString()
    }

    override fun decode(bytes: ByteArray, nameHint: String): SampleData = decoder(bytes, nameHint)

    override fun fetchText(url: String): String {
        val target = normalizeUrl(url)
        requireAllowed(target)
        val file = File(config.mapCacheDir, sha1Hex(target.toString()))
        val cached = readMapCache(file)
        try {
            val connectMs = if (cached != null) config.revalidateConnectTimeoutMs else config.connectTimeoutMs
            val readMs = if (cached != null) config.revalidateReadTimeoutMs else config.readTimeoutMs
            return connect(target, cached?.etag, connectMs, readMs) { connection ->
                val status = connection.responseCode
                when {
                    status == HttpURLConnection.HTTP_NOT_MODIFIED && cached != null -> cached.body
                    status in 200..299 -> {
                        val body = connection.inputStream.use { readLimited(it, config.maxTextBytes, connection.contentLengthLong) }
                        val text = String(body, Charsets.UTF_8).removePrefix("\uFEFF")
                        writeMapCache(file, connection.getHeaderField("ETag"), text)
                        text
                    }
                    else -> throw IOException("HTTP $status for $target")
                }
            }
        } catch (e: IOException) {
            // The copy of an earlier run is good enough when the network is down, or the server has a bad day.
            if (cached != null) return cached.body
            throw e
        }
    }

    // -- What the app asks of it ---------------------------------------------------------------------------------------------------

    /** Waits until no file is being loaded; false when [timeoutMillis] passes first. */
    suspend fun awaitIdle(timeoutMillis: Long): Boolean = withTimeoutOrNull(timeoutMillis) { loadingCount.first { it == 0 } } != null

    /** Stops the threads; what is being loaded is dropped. For tests. */
    fun close() {
        networkPool.shutdownNow()
        decodePool.shutdownNow()
    }

    // -- Loading files -------------------------------------------------------------------------------------------------------------

    /**
     * The load of [url]: the one that is running, a new one, or null when the file failed lately and [retryFailed] is false. A file that
     * is decoded already completes at once.
     */
    private fun start(url: String, retryFailed: Boolean): CompletableFuture<SampleData>? {
        val future = CompletableFuture<SampleData>()
        synchronized(lock) {
            decoded[url]?.let { return CompletableFuture.completedFuture(use(it).data) }
            inflight[url]?.let { return it }
            val failure = failures[url]
            if (!retryFailed && failure != null && config.monotonicMillis() - failure.at < config.failureMemoryMs) return null
            inflight[url] = future
            loadingCount.value = inflight.size
        }
        future.whenComplete { data, error -> finish(url, data, error) }
        try {
            decodePool.execute { settle(url, future) { loadFile(url, future) } }
        } catch (e: RejectedExecutionException) {
            future.completeExceptionally(IOException("the sample store is closed"))
        }
        return future
    }

    /** First stage, on a decoding thread: a document is decoded, a file of the disk cache too, anything else is downloaded first. */
    private fun loadFile(url: String, future: CompletableFuture<SampleData>) {
        if (url.startsWith("content:")) {
            future.complete(decoder(openContent(url).use { it.readBytes() }, url))
            return
        }
        val target = normalizeUrl(url)
        requireAllowed(target)
        val key = sha1Hex(target.toString())
        val cached = diskCache.get(key)
        if (cached != null) {
            val data = try {
                decoder(cached.readBytes(), url)
            } catch (_: IOException) {
                diskCache.remove(key) // damaged, or gone since: load it again
                null
            }
            if (data != null) {
                future.complete(data)
                return
            }
        }
        try {
            networkPool.execute {
                settle(url, future) {
                    download(target, key)
                    decodePool.execute { settle(url, future) { future.complete(decoder(diskCache.get(key)?.readBytes() ?: throw IOException("lost $url"), url)) } }
                }
            }
        } catch (e: RejectedExecutionException) {
            future.completeExceptionally(IOException("the sample store is closed"))
        }
    }

    private fun download(target: URL, key: String) {
        connect(target, etag = null, config.connectTimeoutMs, config.readTimeoutMs) { connection ->
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("HTTP $status for $target")
            val declared = connection.contentLengthLong
            // The length of a compressed response says nothing about the bytes that come out of it.
            val checkLength = connection.contentEncoding.let { it == null || it.equals("identity", ignoreCase = true) }
            diskCache.put(key) { out -> connection.inputStream.use { copyLimited(it, out, config.maxFileBytes, declared, checkLength) } }
        }
    }

    /** Runs [block] and turns whatever it throws into the failure of [future]. */
    private inline fun settle(url: String, future: CompletableFuture<SampleData>, block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            future.completeExceptionally(e)
        } catch (e: OutOfMemoryError) {
            future.completeExceptionally(IOException("out of memory while loading $url"))
        } catch (e: RuntimeException) {
            future.completeExceptionally(IOException("cannot load $url: ${e.message}", e))
        }
    }

    private fun finish(url: String, data: SampleData?, error: Throwable?) {
        synchronized(lock) {
            if (data != null) {
                failures.remove(url)
                keep(url, data)
            } else {
                val cause = if (error is CompletionException) error.cause ?: error else error
                failures[url] = Failure(config.monotonicMillis(), cause as? IOException ?: IOException(cause?.message, cause))
            }
            inflight.remove(url)
            loadingCount.value = inflight.size
        }
    }

    private fun use(entry: Decoded): Decoded {
        entry.lastUse = useClock.incrementAndGet()
        return entry
    }

    /** Remembers [data]; when that outgrows the budget, the samples used least recently are forgotten (voices that play them keep their copy). */
    private fun keep(url: String, data: SampleData) {
        val previous = decoded.put(url, Decoded(data).also { it.lastUse = useClock.incrementAndGet() })
        decodedFloats.addAndGet(data.samples.size - (previous?.data?.samples?.size ?: 0).toLong())
        while (decodedFloats.get() > config.decodedBudgetFloats && decoded.size > 1) {
            val oldest = decoded.entries.minBy { it.value.lastUse }
            if (decoded.remove(oldest.key, oldest.value)) decodedFloats.addAndGet(-oldest.value.data.samples.size.toLong())
        }
    }

    // -- HTTP ----------------------------------------------------------------------------------------------------------------------

    /**
     * Makes the request for [start] (the ETag of a cached copy goes with it) and calls [handle] with the connection of the answer, which is
     * the last of the redirects; [handle] reads the body and closes its stream, so that the connection can be used again. 5 redirects at
     * most; every hop is checked like the first URL.
     */
    private fun <T> connect(start: URL, etag: String?, connectMs: Int, readMs: Int, handle: (HttpURLConnection) -> T): T {
        var url = start
        for (hop in 0..MAX_REDIRECTS) {
            requireAllowed(url)
            val connection = url.openConnection() as? HttpURLConnection ?: throw IOException("not an HTTP URL: $url")
            var handled = false
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = connectMs
                connection.readTimeout = readMs
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", config.userAgent)
                if (etag != null) connection.setRequestProperty("If-None-Match", etag)
                val status = connection.responseCode
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("redirect without a Location from $url")
                    url = resolveRedirect(url, location)
                    continue
                }
                val result = handle(connection)
                handled = true
                return result
            } finally {
                if (!handled) connection.disconnect()
            }
        }
        throw IOException("too many redirects from $start")
    }

    /** Reads [input] to its end; throws when it is longer than [limit] (or [declared] says so), or shorter than [declared] ([declared] < 0: unknown). */
    private fun readLimited(input: InputStream, limit: Long, declared: Long): ByteArray {
        if (declared > limit) throw IOException("response of $declared bytes is over the limit of $limit")
        val out = java.io.ByteArrayOutputStream(if (declared in 1..limit) declared.toInt() else 16 * 1024)
        copyLimited(input, out, limit, declared, checkLength = false)
        return out.toByteArray()
    }

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Long, declared: Long, checkLength: Boolean) {
        if (declared > limit) throw IOException("response of $declared bytes is over the limit of $limit")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("response is over the limit of $limit bytes")
            out.write(buffer, 0, n)
        }
        if (checkLength && declared >= 0 && total != declared) throw IOException("connection closed after $total of $declared bytes")
    }

    // -- Sample maps on disk -------------------------------------------------------------------------------------------------------

    private fun readMapCache(file: File): CachedMap? = try {
        if (!file.isFile) {
            null
        } else {
            // The first line is the ETag (empty when the server sent none), the rest the body.
            val text = file.readText(Charsets.UTF_8)
            val newline = text.indexOf('\n')
            if (newline < 0) null else CachedMap(text.substring(0, newline).ifEmpty { null }, text.substring(newline + 1))
        }
    } catch (_: IOException) {
        null
    }

    private fun writeMapCache(file: File, etag: String?, body: String) {
        // The cache is a convenience: when it cannot be written, the map is still good for this run.
        try {
            val dir = file.parentFile ?: return
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return
            val temp = File(dir, "${file.name}$MAP_CACHE_TEMP${System.nanoTime()}")
            temp.writeText((etag?.takeIf { '\n' !in it && '\r' !in it }.orEmpty()) + "\n" + body, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (_: IOException) {
        }
    }

    private companion object {
        private val threadNumber = AtomicInteger()

        fun threadPool(size: Int, name: String) = Executors.newFixedThreadPool(size) { task ->
            // Daemon threads at the lowest priority: loading sounds must never take the time of the audio thread, nor keep the process alive.
            Thread(task, "$name-${threadNumber.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }
}
