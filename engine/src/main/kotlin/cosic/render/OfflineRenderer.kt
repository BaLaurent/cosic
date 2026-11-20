package cosic.render

import cosic.CosicEngine
import cosic.EvalReport
import cosic.NoSampleStore
import cosic.PLAY_START_DELAY_SECONDS
import cosic.SoundLibrary
import cosic.sound.RegistryLibrary
import cosic.audio.IrExecutor
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.abs

private const val BLOCK_FRAMES = 256
private const val WAV_HEADER_BYTES = 44
private const val WAV_CHUNK_SAMPLES = 8192
private const val LIBRARY_READY_MILLIS = 15_000L
private const val SOUNDS_WAIT_MILLIS = 60_000L
private const val SOUNDS_POLL_MILLIS = 50L
private const val TAIL_SECONDS = 10
private const val TAIL_BLOCK_FRAMES = 4096
private const val SILENCE = 0.5f / 32768f

/**
 * A library without sample banks: programs can use the synths, the drum kit and the synthesized drum machines only. Every call makes a
 * new one, because a program may register sounds in it.
 */
internal fun emptyLibrary(): SoundLibrary = RegistryLibrary(NoSampleStore, CosicEngine.BUILTIN_SOUNDS)

/** Plays programs into memory instead of to a speaker, in the same blocks the app's audio thread uses. */
object OfflineRenderer {
    /**
     * Interleaved stereo float samples of [code] played for [cycles] cycles at its own tempo, after the 50 ms the
     * engine takes to start, exactly that long (no release tail), with the samples the library has decoded by then. Throws
     * [IllegalStateException] with the message of the error when the code is rejected.
     */
    fun render(code: String, cycles: Double, sampleRate: Int = 48000, library: SoundLibrary = emptyLibrary()): FloatArray {
        require(cycles > 0.0 && cycles.isFinite()) { "cycles must be a positive number" }
        val engine = CosicEngine(sampleRate, library, IrExecutor.INLINE)
        val cps = evaluated(engine, code)
        engine.play()
        val frames = Math.round(PLAY_START_DELAY_SECONDS * sampleRate) + Math.round(cycles / cps * sampleRate)
        require(frames <= Int.MAX_VALUE / 2) { "$cycles cycles are too long to render in memory" }
        val out = FloatArray(frames.toInt() * 2)
        renderFrames(engine, out, frames.toInt(), null) { false }
        return out
    }

    /**
     * Audio export (`renderPatternAudio` of Strudel's webaudio package): cycles [beginCycle] to [endCycle] of [code] at its tempo, as interleaved stereo float
     * samples at [sampleRate]. Unlike the other overload the program starts at [beginCycle] itself, with no start delay (`CosicEngine.playFrom`), so the first
     * frame is the first frame of that cycle. Before it renders it waits (up to a minute) for every sample the cycles use to be decoded and fails with `Export failed: samples
     * still loading` if one is not; the sounds that are not in the library at all are skipped, as when playing. The events begin and end exactly on the cycles: after the last frame
     * of [endCycle] nothing new starts, and the voices and the delay and reverb tails still sounding go on being rendered (10 seconds at the most, until a block of 4096 frames is silent).
     *
     * [progress] is told the fraction (0 to 1) of the cycles rendered so far, from the calling thread. [cancelled] is asked about once a block and a `true` ends the
     * render with a [java.util.concurrent.CancellationException]. Throws [IllegalStateException] with the message of the error when the code is rejected.
     */
    fun render(
        code: String,
        beginCycle: Double,
        endCycle: Double,
        sampleRate: Int = 48000,
        library: SoundLibrary = emptyLibrary(),
        progress: ((Double) -> Unit)? = null,
        cancelled: () -> Boolean = { false },
    ): FloatArray {
        require(beginCycle >= 0.0 && beginCycle.isFinite()) { "the start cycle must be zero or a positive number" }
        require(endCycle > beginCycle && endCycle.isFinite()) { "the end cycle must be after the start cycle" }
        val engine = CosicEngine(sampleRate, library, IrExecutor.INLINE)
        val cps = evaluated(engine, code)
        library.awaitReady(LIBRARY_READY_MILLIS)
        awaitSounds(engine, beginCycle, endCycle, cancelled)
        engine.playFrom(beginCycle)

        val frames = Math.round((endCycle - beginCycle) / cps * sampleRate)
        require(frames in 1..Int.MAX_VALUE / 2 - TAIL_SECONDS * sampleRate) { "${endCycle - beginCycle} cycles are too long to render in memory" }
        val main = FloatArray(frames.toInt() * 2)
        renderFrames(engine, main, frames.toInt(), progress, cancelled)
        engine.halt()

        // the release tail: blocks until the first silent one, 10 seconds at the most
        val maxTail = TAIL_SECONDS * sampleRate
        var tail = FloatArray(0)
        var tailFrames = 0
        val block = FloatArray(TAIL_BLOCK_FRAMES * 2)
        while (tailFrames < maxTail) {
            if (cancelled()) throw CancellationException("export cancelled")
            engine.render(block, TAIL_BLOCK_FRAMES)
            if (isSilent(block)) break
            if (tail.size < (tailFrames + TAIL_BLOCK_FRAMES) * 2) tail = tail.copyOf(maxOf(tail.size * 2, (tailFrames + TAIL_BLOCK_FRAMES) * 2))
            block.copyInto(tail, tailFrames * 2)
            tailFrames += TAIL_BLOCK_FRAMES
        }
        progress?.invoke(1.0)
        val out = main.copyOf(main.size + tailFrames * 2)
        tail.copyInto(out, main.size, 0, tailFrames * 2)
        return out
    }

    /** The tempo of [code] after [engine] has accepted it. */
    private fun evaluated(engine: CosicEngine, code: String): Double = when (val report = engine.evaluate(code)) {
        is EvalReport.Ok -> report.cps
        is EvalReport.Failed -> throw IllegalStateException(report.message)
    }

    /** Looks at the events of the cycles every 50 ms until none has a sound that is being decoded, for a minute at the most. */
    private fun awaitSounds(engine: CosicEngine, beginCycle: Double, endCycle: Double, cancelled: () -> Boolean) {
        val deadline = System.nanoTime() + SOUNDS_WAIT_MILLIS * 1_000_000
        while (engine.soundsLoading(beginCycle, endCycle) > 0) {
            if (cancelled()) throw CancellationException("export cancelled")
            check(System.nanoTime() < deadline) { "Export failed: samples still loading" }
            Thread.sleep(SOUNDS_POLL_MILLIS)
        }
    }

    /** Renders [frames] frames into [out] in the blocks the app's audio thread uses. */
    private fun renderFrames(engine: CosicEngine, out: FloatArray, frames: Int, progress: ((Double) -> Unit)?, cancelled: () -> Boolean) {
        val block = FloatArray(BLOCK_FRAMES * 2)
        var done = 0
        while (done < frames) {
            if (cancelled()) throw CancellationException("export cancelled")
            val n = minOf(BLOCK_FRAMES, frames - done)
            engine.render(block, n)
            block.copyInto(out, done * 2, 0, n * 2)
            done += n
            progress?.invoke(done.toDouble() / frames)
        }
    }

    /** Whether [block] is below the half step of 16 bits, so that a WAV file would hold zeros. */
    private fun isSilent(block: FloatArray): Boolean {
        for (v in block) if (abs(v) >= SILENCE) return false
        return true
    }

    /** Writes [interleaved] stereo samples as a 16-bit PCM WAV file; values outside -1..1 are clipped. */
    fun writeWav(file: File, interleaved: FloatArray, sampleRate: Int) {
        file.outputStream().use { writeWav(it, interleaved, sampleRate) }
    }

    /** [writeWav] to a stream, which it flushes but leaves open (the app writes to the document the user chose). */
    fun writeWav(output: OutputStream, interleaved: FloatArray, sampleRate: Int) {
        val dataBytes = interleaved.size * 2
        val stream = output.buffered()
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(WAV_HEADER_BYTES - 8 + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        stream.write(header.array())

        val chunk = ByteBuffer.allocate(WAV_CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < interleaved.size) {
            chunk.clear()
            val end = minOf(i + WAV_CHUNK_SAMPLES, interleaved.size)
            while (i < end) chunk.putShort(Math.round(interleaved[i++].coerceIn(-1f, 1f) * 32767f).toShort())
            stream.write(chunk.array(), 0, chunk.position())
        }
        stream.flush()
    }
}
