package cosic.soundfont

import cosic.SampleData
import cosic.SampleStore
import cosic.pattern.logger
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** A recorded font file of the soundfont data of Strudel (`engine/src/test/resources/soundfont/<name>.js`), or one the oracle wrote. */
internal fun fontFixture(name: String): String =
    FontFixtures::class.java.getResourceAsStream("/soundfont/$name.js")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("missing font fixture $name")

private object FontFixtures

/**
 * A [SampleStore] that serves font files: [texts] by file name (`0160_Chaos_sf2_file`), or [fallback] for any other; a file that is neither is a 404. It records the
 * urls fetched ([fetched]) and the zones decoded ([decoded], by the hint the loader gives them: `<font>#<zone index>`), and decodes a zone to a 0.1 s constant sample of
 * its own (kept in [datas], so that a test can tell which zone a voice plays) at [rate], or what [decoder] makes of the bytes.
 */
internal class FontStore(
    private val texts: Map<String, String> = emptyMap(),
    private val fallback: String? = null,
    val rate: Int = 44_100,
    private val decoder: ((ByteArray) -> SampleData)? = null,
) : SampleStore {
    val fetched = CopyOnWriteArrayList<String>()
    val decoded = CopyOnWriteArrayList<String>()
    val decodedBytes = ConcurrentHashMap<String, ByteArray>()
    val datas = ConcurrentHashMap<String, SampleData>()

    /** Hints of zones whose decode fails. */
    @Volatile
    var failDecode: Set<String> = emptySet()

    /** Fails every fetch until set to false. */
    @Volatile
    var offline = false

    /** Sleeps this long in every fetch and decode, to make jobs overlap. */
    @Volatile
    var latencyMillis = 0L

    override fun fetchText(url: String): String {
        fetched += url
        if (latencyMillis > 0) Thread.sleep(latencyMillis)
        if (offline) throw IOException("offline: $url")
        val name = url.substringAfterLast('/').removeSuffix(".js")
        return texts[name] ?: fallback ?: throw IOException("404 $url")
    }

    override fun sample(url: String): SampleData? = null

    override fun awaitSample(url: String): SampleData = throw IOException("no sample at $url")

    override fun decode(bytes: ByteArray, nameHint: String): SampleData {
        decoded += nameHint
        decodedBytes[nameHint] = bytes
        if (latencyMillis > 0) Thread.sleep(latencyMillis)
        if (nameHint in failDecode) throw IOException("cannot decode $nameHint")
        return datas.getOrPut(nameHint) { decoder?.invoke(bytes) ?: SampleData(FloatArray(4_410) { 0.5f }, rate) }
    }
}

/** An executor that keeps its jobs until [runAll]: a font that is "loading". */
internal class QueuedExecutor : Executor {
    private val jobs = ArrayDeque<Runnable>()

    val pending: Int get() = synchronized(jobs) { jobs.size }

    override fun execute(command: Runnable) {
        synchronized(jobs) { jobs.addLast(command) }
    }

    /** Runs the jobs, those they start included, and returns how many ran. */
    fun runAll(): Int {
        var count = 0
        while (true) {
            val job = synchronized(jobs) { jobs.removeFirstOrNull() } ?: return count
            job.run()
            count++
        }
    }
}

/** Runs [block] with the pattern logger collecting what is logged as `type: message`; the previous logger is back afterwards. */
internal inline fun <T> collectingLog(block: (log: MutableList<String>) -> T): T {
    val old = logger
    val log = CopyOnWriteArrayList<String>()
    logger = { message, type, _ -> log += "${type ?: "log"}: $message" }
    try {
        return block(log)
    } finally {
        logger = old
    }
}
