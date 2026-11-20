package cosic

import cosic.audio.SR
import cosic.audio.constantSample
import cosic.pattern.midi2note
import cosic.sound.RegistryLibrary
import java.io.IOException

/** A bank for [FakeLibrary]: [count] files, or one file per root note of [roots] (a pitched bank). */
internal class TestBank(val count: Int, val roots: IntArray? = null)

/** The entry a [TestBank] stands for; its files are `<name>/<flat index>`, the urls a [FakeStore] knows. */
internal fun testBankEntry(name: String, bank: TestBank): SampleBank {
    val roots = bank.roots
    val groups = if (roots == null) {
        listOf(BankGroup(null, null, List(bank.count) { "$name/$it" }))
    } else {
        roots.mapIndexed { i, root -> BankGroup(midi2note(root.toDouble()), root.toDouble(), listOf("$name/$i")) }
    }
    return SampleBank(name, groups, null, false)
}

/**
 * A [SampleStore] over a table of decoded samples by (bank, flat index). A sample that is not in [samples] stays "not decoded yet" (null)
 * for ever, and every request is recorded in [requested], so tests can see what the engine asked for.
 */
internal open class FakeStore(private val samples: Map<Pair<String, Int>, SampleData> = emptyMap()) : SampleStore {
    val requested = ArrayList<Pair<String, Int>>()

    private fun keyOf(url: String): Pair<String, Int>? {
        val at = url.lastIndexOf('/')
        return if (at < 0) null else url.substring(0, at) to (url.substring(at + 1).toIntOrNull() ?: return null)
    }

    override fun sample(url: String): SampleData? {
        val key = keyOf(url) ?: return null
        requested += key
        return samples[key]
    }

    override fun fetchText(url: String): String = throw IOException("no network in tests: $url")

    override fun awaitSample(url: String): SampleData = sample(url) ?: throw IOException("no sample at $url")

    override fun decode(bytes: ByteArray, nameHint: String): SampleData = throw IOException("no decoder in tests")
}

/**
 * A [SoundLibrary] with fixed banks (registered in a [RegistryLibrary], so lookups behave like the real one's). A sample that is not in
 * [samples] stays "not decoded yet" for ever, and every request is recorded in [requested].
 */
internal class FakeLibrary private constructor(private val store: FakeStore, private val library: RegistryLibrary) : SoundLibrary by library {
    constructor(banks: Map<String, TestBank> = emptyMap(), samples: Map<Pair<String, Int>, SampleData> = emptyMap()) :
        this(FakeStore(samples), banks)

    private constructor(store: FakeStore, banks: Map<String, TestBank>) :
        this(store, RegistryLibrary(store, emptySet()).also { library -> for ((name, bank) in banks) library.register(testBankEntry(name, bank)) })

    val requested: List<Pair<String, Int>> get() = store.requested
}

/**
 * A library with one bank, `kit`, whose only sample (a short constant 0.5) is "not decoded yet" until the
 * [readyOnRequest]-th time it is asked for, like a file that is still being decoded. [requests] counts the asks.
 */
internal class SlowLibrary private constructor(private val store: SlowStore, library: RegistryLibrary) : SoundLibrary by library {
    constructor(readyOnRequest: Int) : this(SlowStore(readyOnRequest))

    private constructor(store: SlowStore) : this(store, RegistryLibrary(store, emptySet()).also { it.register(testBankEntry("kit", TestBank(1))) })

    val requests: Int get() = store.requests
}

private class SlowStore(private val readyOnRequest: Int) : FakeStore() {
    private val data = constantSample(0.05, 0.5f)

    var requests = 0
        private set

    override fun sample(url: String): SampleData? = if (++requests >= readyOnRequest) data else null
}

/** A library with one fully decoded bank. [roots] makes it a pitched bank. */
internal fun libraryWithBank(name: String, samples: List<SampleData>, roots: IntArray? = null): FakeLibrary =
    FakeLibrary(
        mapOf(name to TestBank(samples.size, roots)),
        samples.withIndex().associate { (name to it.index) to it.value },
    )

/** A fresh engine at the standard test rate. */
internal fun engineOf(library: SoundLibrary = FakeLibrary()): CosicEngine = CosicEngine(SR, library)

/** Evaluates [code], failing the test with the report when it is not accepted. */
internal fun CosicEngine.evaluateOk(code: String): EvalReport.Ok =
    when (val report = evaluate(code)) {
        is EvalReport.Ok -> report
        is EvalReport.Failed -> error("evaluate failed: $report")
    }

/**
 * Renders [frames] frames in blocks of [block] (like the app's audio thread) and returns the interleaved stereo
 * output. [afterBlock] sees the engine after every block, for tests that follow the snapshot over time.
 */
internal fun CosicEngine.renderFrames(frames: Int, block: Int = 256, afterBlock: (CosicEngine) -> Unit = {}): FloatArray {
    val out = FloatArray(frames * 2)
    val buffer = FloatArray(block * 2)
    var done = 0
    while (done < frames) {
        val n = minOf(block, frames - done)
        render(buffer, n)
        buffer.copyInto(out, done * 2, 0, n * 2)
        done += n
        afterBlock(this)
    }
    return out
}

/** The start frames of every highlight the engine publishes while [frames] frames are rendered, in order. */
internal fun CosicEngine.highlightStarts(frames: Int): List<Long> {
    val seen = LinkedHashSet<Long>()
    renderFrames(frames) { engine -> engine.snapshot.highlights.forEach { seen += it.startFrame } }
    return seen.sorted()
}
