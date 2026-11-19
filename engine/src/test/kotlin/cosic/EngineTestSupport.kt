package cosic

import cosic.audio.SR
import cosic.audio.constantSample

/**
 * A [SoundLibrary] with fixed banks. A sample that is not in [samples] stays "not decoded yet" (null) for ever,
 * and every request is recorded in [requested], so tests can see what the engine asked for.
 */
internal class FakeLibrary(
    private val banks: Map<String, BankInfo> = emptyMap(),
    private val samples: Map<Pair<String, Int>, SampleData> = emptyMap(),
) : SoundLibrary {
    val requested = ArrayList<Pair<String, Int>>()

    override fun bank(name: String): BankInfo? = banks[name]

    override fun sample(name: String, index: Int): SampleData? {
        requested += name to index
        return samples[name to index]
    }
}

/**
 * A library with one bank, `kit`, whose only sample (a short constant 0.5) is "not decoded yet" until the
 * [readyOnRequest]-th time it is asked for, like a file that is still being decoded. [requests] counts the asks.
 */
internal class SlowLibrary(private val readyOnRequest: Int) : SoundLibrary {
    private val data = constantSample(0.05, 0.5f)

    var requests = 0
        private set

    override fun bank(name: String): BankInfo? = if (name == "kit") BankInfo("kit", 1, null) else null

    override fun sample(name: String, index: Int): SampleData? = if (++requests >= readyOnRequest) data else null
}

/** A library with one fully decoded bank. [roots] makes it a pitched bank. */
internal fun libraryWithBank(name: String, samples: List<SampleData>, roots: IntArray? = null): FakeLibrary =
    FakeLibrary(
        mapOf(name to BankInfo(name, samples.size, roots)),
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
