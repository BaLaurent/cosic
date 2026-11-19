package com.octopusman.cosic.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import cosic.SampleData
import java.io.IOException
import java.nio.ByteOrder

private const val TIMEOUT_US = 10_000L
private const val INITIAL_CAPACITY = 1 shl 16

/** Decodes the first audio track of a file to mono float PCM at the file's own sample rate. */
internal object AudioDecoder {
    /** [maxSeconds] truncates the result; null keeps everything. */
    fun decode(fd: AssetFileDescriptor, maxSeconds: Int? = null): SampleData =
        decode(maxSeconds) { setDataSource(fd) }

    fun decode(context: Context, uri: Uri, maxSeconds: Int? = null): SampleData =
        decode(maxSeconds) { setDataSource(context, uri, null) }

    private fun decode(maxSeconds: Int?, open: MediaExtractor.() -> Unit): SampleData {
        val extractor = MediaExtractor()
        try {
            extractor.open()
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return pump(extractor, codec, format, maxSeconds)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun pump(extractor: MediaExtractor, codec: MediaCodec, inputFormat: MediaFormat, maxSeconds: Int?): SampleData {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var samples = FloatArray(INITIAL_CAPACITY)
        var size = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
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
            } else if (index >= 0) {
                val maxFrames = maxSeconds?.let { it * sampleRate } ?: Int.MAX_VALUE
                val pcm = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
                val frames = minOf(info.size / (2 * channels), maxFrames - size)
                if (size + frames > samples.size) samples = samples.copyOf(maxOf(2 * samples.size, size + frames))
                repeat(frames) { frame ->
                    var sum = 0
                    repeat(channels) { channel -> sum += pcm.getShort(info.offset + 2 * (frame * channels + channel)) }
                    samples[size++] = sum / (32768f * channels)
                }
                codec.releaseOutputBuffer(index, false)
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || size >= maxFrames
            }
        }
        return SampleData(samples.copyOf(size), sampleRate)
    }
}
