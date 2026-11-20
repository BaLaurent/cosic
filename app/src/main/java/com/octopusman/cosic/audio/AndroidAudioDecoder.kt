package com.octopusman.cosic.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import cosic.SampleData
import cosic.audio.WavDecoder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TIMEOUT_US = 10_000L
private const val INITIAL_CAPACITY = 1 shl 16

/** A decoder that has produced nothing for this long is stuck. */
private const val DECODE_TIMEOUT_NANOS = 60_000_000_000L

/** Decoded audio is held as floats; a file that would take more than this many (a few minutes of stereo) is refused instead of filling the heap. */
private const val MAX_DECODED_FLOATS = 96L * 1024 * 1024

/**
 * Decodes the audio files of the sound library to what the engine plays: interleaved float samples at the sample rate and with the
 * channels the file has, whole and without a downmix. A WAV file goes through the engine's reader (exact, and the same on every
 * device); everything else (MP3, Ogg, FLAC, AAC, Opus, a WAV of a kind the reader does not know) goes through the platform's
 * `MediaExtractor` and `MediaCodec`.
 *
 * Gapless: the format of the track goes to the codec as it is, with the encoder delay and padding the extractor read from the tag of
 * the file (`encoder-delay`, `encoder-padding`), and the codec takes them out of the decoded audio. An MP3 comes out as long as it was
 * before it was encoded, and starts where the music starts; this is what the browser's `decodeAudioData` gives too, and it is checked
 * against LAME files of both kinds by `AndroidAudioDecoderTest` (on a device).
 */
internal object AndroidAudioDecoder {
    /** Decodes [bytes], the whole content of a file; [nameHint] says which file in errors. Throws [IOException] when it cannot. */
    fun decode(bytes: ByteArray, nameHint: String): SampleData {
        if (isRiffWave(bytes)) {
            try {
                return WavDecoder.decode(bytes)
            } catch (_: IOException) {
                // A WAV of a kind the engine does not read (ADPCM, A-law, MP3 in a WAV): the platform may.
            }
        }
        return decodeWithCodec(bytes, nameHint)
    }

    /** `MediaFormat.getInteger(key, default)` needs API 29. */
    private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default

    private fun isRiffWave(b: ByteArray): Boolean =
        b.size >= 12 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'A'.code.toByte() && b[10] == 'V'.code.toByte() && b[11] == 'E'.code.toByte()

    private fun decodeWithCodec(bytes: ByteArray, nameHint: String): SampleData {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(ByteArraySource(bytes))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track in $nameHint")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return pump(extractor, codec, format, nameHint)
            } finally {
                codec.release()
            }
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            // MediaExtractor and MediaCodec report unreadable data as IllegalArgumentException, IllegalStateException, CodecException...
            throw IOException("cannot decode $nameHint: ${e.message}", e)
        } finally {
            extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat, nameHint: String): SampleData {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = inputFormat.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        var samples = FloatArray(INITIAL_CAPACITY)
        var size = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val deadline = System.nanoTime() + DECODE_TIMEOUT_NANOS
        while (!outputDone) {
            if (System.nanoTime() > deadline) throw IOException("timed out decoding $nameHint")
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val length = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                    if (length < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(index, 0, length, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                encoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            } else if (index >= 0) {
                if (channels !in 1..SampleData.MAX_SAMPLE_CHANNELS) throw IOException("$nameHint has $channels channels")
                val bytesPerSample = bytesPerSample(encoding, nameHint)
                val count = info.size / (bytesPerSample * channels) * channels
                if (size + count > MAX_DECODED_FLOATS) throw IOException("$nameHint is too long to decode")
                if (size + count > samples.size) samples = samples.copyOf(maxOf(2 * samples.size, size + count))
                val pcm = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                readSamples(pcm, info.offset, count, encoding, samples, size)
                size += count
                codec.releaseOutputBuffer(index, false)
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            }
        }
        if (size == 0) throw IOException("$nameHint holds no audio")
        return SampleData(samples.copyOf(size), sampleRate, channels)
    }

    private fun bytesPerSample(encoding: Int, nameHint: String): Int = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> throw IOException("$nameHint decodes to PCM encoding $encoding, which is not supported")
    }

    /** Converts [count] samples that start at byte [offset] of [pcm] to floats in [out] at [at]. */
    private fun readSamples(pcm: ByteBuffer, offset: Int, count: Int, encoding: Int, out: FloatArray, at: Int) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> for (i in 0 until count) out[at + i] = ((pcm.get(offset + i).toInt() and 0xFF) - 128) / 128f
            AudioFormat.ENCODING_PCM_16BIT -> for (i in 0 until count) out[at + i] = pcm.getShort(offset + 2 * i) / 32768f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> for (i in 0 until count) {
                val p = offset + 3 * i
                val v = (pcm.get(p).toInt() and 0xFF) or ((pcm.get(p + 1).toInt() and 0xFF) shl 8) or (pcm.get(p + 2).toInt() shl 16)
                out[at + i] = v / 8388608f
            }
            AudioFormat.ENCODING_PCM_32BIT -> for (i in 0 until count) out[at + i] = pcm.getInt(offset + 4 * i) / 2147483648f
            else -> for (i in 0 until count) out[at + i] = pcm.getFloat(offset + 4 * i).let { if (it.isFinite()) it else 0f }
        }
    }

    /** A file held in memory, for `MediaExtractor`. */
    private class ByteArraySource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() = Unit
    }
}
