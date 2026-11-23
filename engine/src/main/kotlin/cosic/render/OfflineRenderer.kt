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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlin.math.abs

private const val BLOCK_FRAMES = 256
private const val WAV_HEADER_BYTES = 44
private const val WAV_CHUNK_SAMPLES = 8192
/** The most sample bytes a WAV file can hold: its sizes are 32 bits, and the one of the RIFF chunk also counts the 36 bytes that come before the samples. */
private const val MAX_WAV_DATA_BYTES = 0xFFFF_FFFFL - (WAV_HEADER_BYTES - 8)
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

/** Plays programs into memory, or into a WAV file, instead of to a speaker, in the same blocks the app's audio thread uses. */
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
     * The samples are collected in memory, 8 bytes a frame; [renderWav] writes them to a file as they are rendered instead.
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
        var main = FloatArray(0)
        var mainFloats = 0
        var tail = FloatArray(0)
        var tailFloats = 0
        exportBlocks(
            code, beginCycle, endCycle, sampleRate, library, progress, cancelled,
            start = { frames ->
                require(frames in 1..Int.MAX_VALUE / 2 - TAIL_SECONDS * sampleRate) { "${endCycle - beginCycle} cycles are too long to render in memory" }
                main = FloatArray(frames.toInt() * 2)
            },
            sink = { block, frames ->
                val floats = frames * 2
                if (mainFloats < main.size) {
                    block.copyInto(main, mainFloats, 0, floats)
                    mainFloats += floats
                } else {
                    // the release tail: its length is not known in advance, so it grows; the cycles were allocated once, at their size
                    if (tail.size < tailFloats + floats) tail = tail.copyOf(maxOf(tail.size * 2, tailFloats + floats))
                    block.copyInto(tail, tailFloats, 0, floats)
                    tailFloats += floats
                }
            },
        )
        val out = main.copyOf(main.size + tailFloats)
        tail.copyInto(out, main.size, 0, tailFloats)
        return out
    }

    /**
     * [render] (cycles [beginCycle] to [endCycle], the same blocks, the same release tail) written to [file] as a 16-bit stereo PCM WAV file while it renders, instead of collected in memory:
     * it takes the same few blocks of memory however long the export is, where the array of [render] takes 8 bytes a frame (1.4 GB for an hour at 48 kHz). The file holds exactly the bytes
     * [writeWav] writes for the array [render] returns. Its header is written last, once the length is known, so a render that fails or is cancelled leaves a file that is not a WAV file: the caller
     * deletes it. A range whose file would be longer than the 32-bit sizes of the format allow (about 6 hours at 48 kHz) is refused before anything is rendered. [progress] and [cancelled] are
     * those of [render]. Returns the number of frames written, the cycles and the tail.
     */
    fun renderWav(
        file: File,
        code: String,
        beginCycle: Double,
        endCycle: Double,
        sampleRate: Int = 48000,
        library: SoundLibrary = emptyLibrary(),
        progress: ((Double) -> Unit)? = null,
        cancelled: () -> Boolean = { false },
    ): Long {
        var dataBytes = 0L
        val encoded = ByteBuffer.allocate(TAIL_BLOCK_FRAMES * 4).order(ByteOrder.LITTLE_ENDIAN)
        file.outputStream().buffered().use { stream ->
            stream.write(ByteArray(WAV_HEADER_BYTES)) // the sizes are not known yet: the header is written over these zeros at the end
            exportBlocks(
                code, beginCycle, endCycle, sampleRate, library, progress, cancelled,
                start = { frames ->
                    // the tail is TAIL_SECONDS at the most, and it ends on a whole block, which can take it one block over
                    require((frames + TAIL_SECONDS.toLong() * sampleRate + TAIL_BLOCK_FRAMES) * 4 <= MAX_WAV_DATA_BYTES) { "${endCycle - beginCycle} cycles are too long for a WAV file" }
                },
                sink = { block, frames ->
                    val samples = frames * 2
                    for (i in 0 until samples) encoded.putShort(i * 2, pcm16(block[i]))
                    stream.write(encoded.array(), 0, samples * 2)
                    dataBytes += samples * 2L
                },
            )
        }
        RandomAccessFile(file, "rw").use { it.write(wavHeader(dataBytes, sampleRate)) }
        return dataBytes / 4
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

    /**
     * What [render] and [renderWav] share (the export of Strudel's `renderPatternAudio`): evaluates [code], waits for its sounds, plays cycles [beginCycle] to [endCycle] from the first frame and hands
     * every block of the result, in order, to [sink]: first the cycles, in blocks of [BLOCK_FRAMES] frames, then the release tail, in blocks of [TAIL_BLOCK_FRAMES]. [start] is told how many frames the
     * cycles come to (without the tail) before anything is rendered, and may refuse them by throwing. [sink] gets a block and how many of its frames count; the block is reused, so what is kept of it is
     * copied. [start], [sink] and [progress] are called from the calling thread.
     */
    private fun exportBlocks(
        code: String,
        beginCycle: Double,
        endCycle: Double,
        sampleRate: Int,
        library: SoundLibrary,
        progress: ((Double) -> Unit)?,
        cancelled: () -> Boolean,
        start: (mainFrames: Long) -> Unit,
        sink: (block: FloatArray, frames: Int) -> Unit,
    ) {
        require(beginCycle >= 0.0 && beginCycle.isFinite()) { "the start cycle must be zero or a positive number" }
        require(endCycle > beginCycle && endCycle.isFinite()) { "the end cycle must be after the start cycle" }
        val engine = CosicEngine(sampleRate, library, IrExecutor.INLINE)
        val cps = evaluated(engine, code)
        library.awaitReady(LIBRARY_READY_MILLIS)
        awaitSounds(engine, beginCycle, endCycle, cancelled)
        engine.playFrom(beginCycle)

        val frames = Math.round((endCycle - beginCycle) / cps * sampleRate)
        start(frames)
        val block = FloatArray(BLOCK_FRAMES * 2)
        var done = 0L
        while (done < frames) {
            if (cancelled()) throw CancellationException("export cancelled")
            val n = minOf(BLOCK_FRAMES.toLong(), frames - done).toInt()
            engine.render(block, n)
            sink(block, n)
            done += n
            progress?.invoke(done.toDouble() / frames)
        }
        engine.halt()

        // the release tail: blocks until the first silent one, 10 seconds at the most
        val maxTail = TAIL_SECONDS * sampleRate
        var tailFrames = 0
        val tailBlock = FloatArray(TAIL_BLOCK_FRAMES * 2)
        while (tailFrames < maxTail) {
            if (cancelled()) throw CancellationException("export cancelled")
            engine.render(tailBlock, TAIL_BLOCK_FRAMES)
            if (isSilent(tailBlock)) break
            sink(tailBlock, TAIL_BLOCK_FRAMES)
            tailFrames += TAIL_BLOCK_FRAMES
        }
        progress?.invoke(1.0)
    }

    /** Whether [block] is below the half step of 16 bits, so that a WAV file would hold zeros. */
    private fun isSilent(block: FloatArray): Boolean {
        for (v in block) if (abs(v) >= SILENCE) return false
        return true
    }

    /** [v] as the signed 16-bit sample of a WAV file: clipped to -1..1, scaled to 32767 and rounded (NaN is 0). */
    private fun pcm16(v: Float): Short = Math.round(v.coerceIn(-1f, 1f) * 32767f).toShort()

    /** The 44 bytes that begin a 16-bit stereo PCM WAV file of [dataBytes] bytes of samples; the two sizes it holds are the low 32 bits of the real ones, as a RIFF file cannot say more. */
    private fun wavHeader(dataBytes: Long, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt((WAV_HEADER_BYTES - 8 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header.putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes.toInt())
        return header.array()
    }

    /** Writes [interleaved] stereo samples as a 16-bit PCM WAV file; values outside -1..1 are clipped. */
    fun writeWav(file: File, interleaved: FloatArray, sampleRate: Int) {
        file.outputStream().use { writeWav(it, interleaved, sampleRate) }
    }

    /** [writeWav] to a stream, which it flushes but leaves open (the app writes to the document the user chose). */
    fun writeWav(output: OutputStream, interleaved: FloatArray, sampleRate: Int) {
        val stream = output.buffered()
        stream.write(wavHeader(interleaved.size * 2L, sampleRate))

        val chunk = ByteBuffer.allocate(WAV_CHUNK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < interleaved.size) {
            chunk.clear()
            val end = minOf(i + WAV_CHUNK_SAMPLES, interleaved.size)
            while (i < end) chunk.putShort(pcm16(interleaved[i++]))
            stream.write(chunk.array(), 0, chunk.position())
        }
        stream.flush()
    }
}
