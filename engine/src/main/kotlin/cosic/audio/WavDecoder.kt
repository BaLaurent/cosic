package cosic.audio

import cosic.SampleData
import java.io.IOException

/**
 * Reads WAV files exactly: the samples come back at the rate and with the channels the file has, as interleaved floats, with no
 * resampling and no downmix (what `AudioContext.decodeAudioData` would do to the rate, the engine does when it plays).
 *
 * Understood: PCM of 8 bits (unsigned), 16, 24 and 32 bits (signed), IEEE float of 32 and 64 bits, also inside a
 * WAVE_FORMAT_EXTENSIBLE header, 1 to [SampleData.MAX_SAMPLE_CHANNELS] channels. Chunks are walked with their padding byte; a `data`
 * chunk whose size is 0, `0xFFFFFFFF` or larger than the file reaches to the end of the file (streaming recorders write such headers).
 * Anything else (ADPCM, A-law, MP3 in a WAV) throws [IOException], so that a caller with a general decoder can fall back to it.
 */
object WavDecoder {
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    private class Format(val tag: Int, val channels: Int, val sampleRate: Long, val bytesPerSample: Int)

    /** Decodes [bytes], the whole content of a WAV file. Throws [IOException] for anything that is not one of the supported kinds. */
    fun decode(bytes: ByteArray): SampleData {
        if (bytes.size < 12 || !hasTag(bytes, 0, "RIFF") || !hasTag(bytes, 8, "WAVE")) throw IOException("not a WAV file")
        var format: Format? = null
        var dataStart = -1
        var dataLength = 0
        var position = 12L
        while (position + 8 <= bytes.size) {
            val at = position.toInt()
            val size = u32(bytes, at + 4)
            val body = at + 8
            val available = bytes.size - body
            if (hasTag(bytes, at, "fmt ")) {
                format = parseFormat(bytes, body, minOf(size, available.toLong()).toInt())
            } else if (hasTag(bytes, at, "data")) {
                dataStart = body
                val toEnd = size == 0L || size == 0xFFFFFFFFL || size > available
                dataLength = if (toEnd) available else size.toInt()
                if (toEnd || format != null) break
            }
            position = body + size + (size and 1L)
        }
        val f = format ?: throw IOException("WAV file without a fmt chunk")
        if (dataStart < 0) throw IOException("WAV file without a data chunk")
        val frames = dataLength / (f.channels * f.bytesPerSample)
        val samples = FloatArray(frames * f.channels)
        when {
            f.tag == FORMAT_FLOAT && f.bytesPerSample == 4 ->
                for (i in samples.indices) samples[i] = finite(Float.fromBits(i32(bytes, dataStart + i * 4)))
            f.tag == FORMAT_FLOAT ->
                for (i in samples.indices) samples[i] = finite(Double.fromBits(i64(bytes, dataStart + i * 8)).toFloat())
            f.bytesPerSample == 1 ->
                for (i in samples.indices) samples[i] = ((bytes[dataStart + i].toInt() and 0xFF) - 128) / 128f
            f.bytesPerSample == 2 ->
                for (i in samples.indices) {
                    val at = dataStart + i * 2
                    samples[i] = ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)) / 32768f
                }
            f.bytesPerSample == 3 ->
                for (i in samples.indices) {
                    val at = dataStart + i * 3
                    val v = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() shl 16)
                    samples[i] = v / 8388608f
                }
            else ->
                for (i in samples.indices) samples[i] = i32(bytes, dataStart + i * 4) / 2147483648f
        }
        return SampleData(samples, f.sampleRate.toInt(), f.channels)
    }

    private fun parseFormat(b: ByteArray, at: Int, size: Int): Format {
        if (size < 16) throw IOException("WAV fmt chunk too short")
        var tag = u16(b, at)
        val channels = u16(b, at + 2)
        val sampleRate = u32(b, at + 4)
        val blockAlign = u16(b, at + 12)
        val bits = u16(b, at + 14)
        if (tag == FORMAT_EXTENSIBLE) {
            if (size < 26) throw IOException("WAV extensible fmt chunk too short")
            tag = u16(b, at + 24) // the first two bytes of the sub-format GUID are the format tag
        }
        if (tag != FORMAT_PCM && tag != FORMAT_FLOAT) throw IOException("unsupported WAV format tag $tag")
        if (channels !in 1..SampleData.MAX_SAMPLE_CHANNELS) throw IOException("unsupported WAV channel count $channels")
        if (sampleRate !in 1..Int.MAX_VALUE) throw IOException("bad WAV sample rate $sampleRate")
        val container = if (blockAlign > 0 && blockAlign % channels == 0) blockAlign / channels else (bits + 7) / 8
        val supported = if (tag == FORMAT_FLOAT) container == 4 || container == 8 else container in 1..4
        if (!supported) throw IOException("unsupported WAV sample size: $bits bits in $container bytes")
        return Format(tag, channels, sampleRate, container)
    }

    private fun finite(v: Float): Float = if (v.isFinite()) v else 0f

    private fun hasTag(b: ByteArray, at: Int, tag: String): Boolean {
        if (at + 4 > b.size) return false
        for (i in 0 until 4) if (b[at + i].toInt() != tag[i].code) return false
        return true
    }

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun i32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

    private fun u32(b: ByteArray, at: Int): Long = i32(b, at).toLong() and 0xFFFFFFFFL

    private fun i64(b: ByteArray, at: Int): Long = (u32(b, at + 4) shl 32) or u32(b, at)
}
