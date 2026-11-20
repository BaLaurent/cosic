package com.octopusman.cosic.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cosic.SampleData
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The platform decoders as the library uses them. The test files are a second of a 440 Hz sine (made by ffmpeg's `sine` source), encoded as MP3 with LAME (stereo, 44.1 kHz), Vorbis (mono, 22.05 kHz) and FLAC (stereo, 48 kHz): decoded without gaps
 * they are exactly one second long and start in the phase of the sine, which tells whether the encoder delay was taken out.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAudioDecoderTest {
    private companion object {
        /** The peak of the sine in the files: 1/8 for the mono one, and ffmpeg's up-mix to stereo takes 3 dB off. */
        const val MONO_AMPLITUDE = 0.125
        val STEREO_AMPLITUDE = 0.125 / sqrt(2.0)
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    /** The root mean square of what [channel] of [data] differs by from the sine (frames [from] until [to]; all by default). */
    private fun rmsError(data: SampleData, channel: Int, from: Int = 0, to: Int = data.frames): Double {
        val amplitude = if (data.channels == 1) MONO_AMPLITUDE else STEREO_AMPLITUDE
        var sum = 0.0
        for (n in from until to) {
            val expected = amplitude * sin(2 * PI * 440.0 * n / data.sampleRate)
            val error = data.samples[n * data.channels + channel] - expected
            sum += error * error
        }
        return sqrt(sum / (to - from))
    }

    @Test
    fun anMp3IsDecodedWithoutItsEncoderDelayToTheLengthItWasEncodedWith() {
        val data = AndroidAudioDecoder.decode(asset("sine.mp3"), "sine.mp3")

        assertEquals(44_100, data.sampleRate)
        assertEquals(2, data.channels)
        assertEquals("frames", 44_100, data.frames)
        // starts in the phase of the sine, not 1105 samples late as a decoder that does not remove the delay does
        assertTrue("rms error ${rmsError(data, 0)}", rmsError(data, 0) < 0.004)
        assertTrue("rms error ${rmsError(data, 1)}", rmsError(data, 1) < 0.004)
    }

    @Test
    fun anMp3OfTheLameCommandLineWithItsUsualDelayOf576IsGaplessToo() {
        // the encoder of the piano samples: delay 576 and padding 1404 in its tag; the decoder's own delay (529) goes as well
        val data = AndroidAudioDecoder.decode(asset("sine-lame.mp3"), "sine-lame.mp3")

        assertEquals("frames", 44_100, data.frames)
        assertTrue("rms error ${rmsError(data, 0)}", rmsError(data, 0) < 0.004)
    }

    @Test
    fun aVorbisFileKeepsItsRateItsChannelsAndItsLength() {
        val data = AndroidAudioDecoder.decode(asset("sine.ogg"), "sine.ogg")

        assertEquals(22_050, data.sampleRate)
        assertEquals(1, data.channels)
        assertEquals("frames", 22_050, data.frames)
        assertTrue("rms error ${rmsError(data, 0)}", rmsError(data, 0) < 0.004)
    }

    @Test
    fun aFlacFileIsDecodedExactlyAndStaysStereo() {
        val data = AndroidAudioDecoder.decode(asset("sine.flac"), "sine.flac")

        assertEquals(48_000, data.sampleRate)
        assertEquals(2, data.channels)
        assertEquals("frames", 48_000, data.frames)
        assertTrue("rms error ${rmsError(data, 0)}", rmsError(data, 0) < 0.0005)
        assertTrue("rms error ${rmsError(data, 1)}", rmsError(data, 1) < 0.0005)
    }

    @Test
    fun aWavFileIsReadByTheEngineExactlyWhateverItsRateAndChannels() {
        val bytes = wav16(sampleRate = 8_000, channels = 2, frames = 100) { n, channel -> ((n * 100 + channel * 7) % 20_000 - 10_000).toShort() }

        val data = AndroidAudioDecoder.decode(bytes, "ramp.wav")

        assertEquals(8_000, data.sampleRate)
        assertEquals(2, data.channels)
        assertEquals(100, data.frames)
        assertEquals(((5 * 100 + 7) % 20_000 - 10_000) / 32768f, data.samples[5 * 2 + 1], 0f)
    }

    @Test
    fun dataThatIsNoAudioFileIsAnIoExceptionAndNothingElse() {
        for (bytes in listOf(ByteArray(0), ByteArray(100) { it.toByte() }, "RIFF....WAVEjunk".toByteArray(), "not audio at all".toByteArray())) {
            try {
                AndroidAudioDecoder.decode(bytes, "junk")
                throw AssertionError("decoded ${bytes.size} bytes of junk")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun anMp3CutShortIsDecodedAsFarAsItGoesOrRefusedWithAnIoException() {
        val whole = asset("sine.mp3")

        try {
            val data = AndroidAudioDecoder.decode(whole.copyOf(whole.size / 2), "cut.mp3")
            assertTrue("frames ${data.frames}", data.frames in 1..44_100)
        } catch (_: IOException) {
        }
    }

    private fun wav16(sampleRate: Int, channels: Int, frames: Int, sample: (frame: Int, channel: Int) -> Short): ByteArray {
        val data = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until frames) for (c in 0 until channels) data.putShort(sample(n, c))
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun short(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
        out.write("RIFF".toByteArray())
        int(36 + data.capacity())
        out.write("WAVEfmt ".toByteArray())
        int(16)
        short(1)
        short(channels)
        int(sampleRate)
        int(sampleRate * channels * 2)
        short(channels * 2)
        short(16)
        out.write("data".toByteArray())
        int(data.capacity())
        out.write(data.array())
        return out.toByteArray()
    }
}
