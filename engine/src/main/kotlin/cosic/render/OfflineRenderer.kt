package cosic.render

import cosic.CosicEngine
import cosic.EvalReport
import cosic.NoSampleStore
import cosic.PLAY_START_DELAY_SECONDS
import cosic.SoundLibrary
import cosic.sound.RegistryLibrary
import cosic.audio.IrExecutor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val BLOCK_FRAMES = 256
private const val WAV_HEADER_BYTES = 44
private const val WAV_CHUNK_SAMPLES = 8192

/**
 * A library without sample banks: programs can use the synths, the drum kit and the synthesized drum machines only. Every call makes a
 * new one, because a program may register sounds in it.
 */
internal fun emptyLibrary(): SoundLibrary = RegistryLibrary(NoSampleStore, CosicEngine.BUILTIN_SOUNDS)

/** Plays programs into memory instead of to a speaker, in the same blocks the app's audio thread uses. */
object OfflineRenderer {
    /**
     * Interleaved stereo float samples of [code] played for [cycles] cycles at its own tempo, after the 50 ms the
     * engine takes to start. Throws [IllegalStateException] with the message of the error when the code is rejected.
     */
    fun render(code: String, cycles: Double, sampleRate: Int = 48000, library: SoundLibrary = emptyLibrary()): FloatArray {
        require(cycles > 0.0 && cycles.isFinite()) { "cycles must be a positive number" }
        val engine = CosicEngine(sampleRate, library, IrExecutor.INLINE)
        val cps = when (val report = engine.evaluate(code)) {
            is EvalReport.Ok -> report.cps
            is EvalReport.Failed -> throw IllegalStateException(report.message)
        }
        engine.play()

        val frames = Math.round(PLAY_START_DELAY_SECONDS * sampleRate) + Math.round(cycles / cps * sampleRate)
        require(frames <= Int.MAX_VALUE / 2) { "$cycles cycles are too long to render in memory" }
        val out = FloatArray(frames.toInt() * 2)
        val block = FloatArray(BLOCK_FRAMES * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK_FRAMES, frames.toInt() - done)
            engine.render(block, n)
            block.copyInto(out, done * 2, 0, n * 2)
            done += n
        }
        return out
    }

    /** Writes [interleaved] stereo samples as a 16-bit PCM WAV file; values outside -1..1 are clipped. */
    fun writeWav(file: File, interleaved: FloatArray, sampleRate: Int) {
        val dataBytes = interleaved.size * 2
        file.outputStream().buffered().use { stream ->
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
        }
    }
}
