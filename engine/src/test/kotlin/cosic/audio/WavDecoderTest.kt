package cosic.audio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavDecoderTest {
    private class Bytes {
        private val out = ByteArrayOutputStream()

        fun str(s: String) = apply { out.write(s.toByteArray(Charsets.US_ASCII)) }

        fun u16(v: Int) = apply { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        fun u32(v: Long) = apply { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }

        fun raw(b: ByteArray) = apply { out.write(b) }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun fmt(tag: Int, channels: Int, rate: Int, bits: Int, extensibleTag: Int? = null): ByteArray {
        val align = channels * ((bits + 7) / 8)
        val b = Bytes().str("fmt ")
        if (extensibleTag == null) {
            b.u32(16).u16(tag).u16(channels).u32(rate.toLong()).u32(rate.toLong() * align).u16(align).u16(bits)
        } else {
            b.u32(40).u16(0xFFFE).u16(channels).u32(rate.toLong()).u32(rate.toLong() * align).u16(align).u16(bits)
                .u16(22).u16(bits).u32(3).u16(extensibleTag).raw(ByteArray(14))
        }
        return b.toByteArray()
    }

    private fun chunk(id: String, payload: ByteArray, size: Long = payload.size.toLong()): ByteArray {
        val b = Bytes().str(id).u32(size).raw(payload)
        if (payload.size % 2 == 1) b.raw(byteArrayOf(0))
        return b.toByteArray()
    }

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val body = Bytes().str("WAVE")
        for (c in chunks) body.raw(c)
        val inner = body.toByteArray()
        return Bytes().str("RIFF").u32(inner.size.toLong()).raw(inner).toByteArray()
    }

    private fun fmtChunk(tag: Int, channels: Int, rate: Int, bits: Int, extensibleTag: Int? = null) =
        fmt(tag, channels, rate, bits, extensibleTag)

    private fun pcm16(vararg v: Int): ByteArray = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()

    @Test
    fun sixteenBitMonoIsScaledBy32768() {
        val sample = WavDecoder.decode(wav(fmtChunk(1, 1, 44_100, 16), chunk("data", pcm16(0, 16384, -16384, 32767, -32768))))
        assertEquals(44_100, sample.sampleRate)
        assertEquals(1, sample.channels)
        assertContentEquals(floatArrayOf(0f, 0.5f, -0.5f, 32767f / 32768f, -1f), sample.samples)
        assertEquals(0.99997f, sample.samples[3], 1e-5f)
    }

    @Test
    fun eightBitIsUnsignedAroundOneHundredTwentyEight() {
        val sample = WavDecoder.decode(wav(fmtChunk(1, 1, 8000, 8), chunk("data", byteArrayOf(0, 128.toByte(), 255.toByte(), 192.toByte()))))
        assertContentEquals(floatArrayOf(-1f, 0f, 127f / 128f, 0.5f), sample.samples)
        assertEquals(8000, sample.sampleRate)
    }

    @Test
    fun twentyFourAndThirtyTwoBitPcm() {
        // 0x400000 = 4194304 is half of 2^23; -8388608 is full negative
        val twentyFour = byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F)
        assertContentEquals(floatArrayOf(0.5f, -1f, 8388607f / 8388608f), WavDecoder.decode(wav(fmtChunk(1, 1, 48_000, 24), chunk("data", twentyFour))).samples)
        val thirtyTwo = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1 shl 30).putInt(Int.MIN_VALUE).array()
        assertContentEquals(floatArrayOf(0.5f, -1f), WavDecoder.decode(wav(fmtChunk(1, 1, 48_000, 32), chunk("data", thirtyTwo))).samples)
    }

    @Test
    fun floatingPointOfThirtyTwoAndSixtyFourBitsIsUsedAsItIs() {
        val f32 = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(-1.5f).putFloat(Float.NaN).array()
        val a = WavDecoder.decode(wav(fmtChunk(3, 1, 48_000, 32), chunk("data", f32))).samples
        assertEquals(0.25f, a[0])
        assertEquals(-1.5f, a[1], "float files may exceed 1")
        assertEquals(0f, a[2], "a NaN would poison every filter after it")
        val f64 = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(0.125).putDouble(-0.75).array()
        assertContentEquals(floatArrayOf(0.125f, -0.75f), WavDecoder.decode(wav(fmtChunk(3, 1, 48_000, 64), chunk("data", f64))).samples)
    }

    @Test
    fun channelsStayInterleavedAndTheFramesAreCounted() {
        val sample = WavDecoder.decode(wav(fmtChunk(1, 2, 22_050, 16), chunk("data", pcm16(16384, -16384, 8192, -8192, 0, 32767))))
        assertEquals(2, sample.channels)
        assertEquals(3, sample.frames)
        assertContentEquals(floatArrayOf(0.5f, -0.5f, 0.25f, -0.25f, 0f, 32767f / 32768f), sample.samples)
        assertEquals(3.0 / 22_050, sample.durationSeconds, 1e-12)
        // a partial frame at the end is dropped
        assertEquals(1, WavDecoder.decode(wav(fmtChunk(1, 2, 22_050, 16), chunk("data", pcm16(1, 2, 3)))).frames)
    }

    @Test
    fun chunksOfOddSizeAreFollowedByAPaddingByte() {
        val sample = WavDecoder.decode(
            wav(
                fmtChunk(1, 1, 8000, 16),
                chunk("LIST", byteArrayOf(1, 2, 3)),
                chunk("junk", ByteArray(5)),
                chunk("data", pcm16(16384)),
            ),
        )
        assertContentEquals(floatArrayOf(0.5f), sample.samples)
    }

    @Test
    fun anExtensibleHeaderIsReadByItsSubFormat() {
        val pcm = WavDecoder.decode(wav(fmtChunk(0xFFFE, 1, 48_000, 16, extensibleTag = 1), chunk("data", pcm16(16384))))
        assertContentEquals(floatArrayOf(0.5f), pcm.samples)
        val f32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.75f).array()
        val float = WavDecoder.decode(wav(fmtChunk(0xFFFE, 1, 48_000, 32, extensibleTag = 3), chunk("data", f32)))
        assertContentEquals(floatArrayOf(0.75f), float.samples)
        assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(0xFFFE, 1, 48_000, 16, extensibleTag = 2), chunk("data", pcm16(1)))) }
    }

    @Test
    fun aDataSizeOfZeroOrAllOnesOrTooBigReachesToTheEndOfTheFile() {
        for (size in listOf(0L, 0xFFFFFFFFL, 1_000_000L)) {
            val sample = WavDecoder.decode(wav(fmtChunk(1, 1, 8000, 16), chunk("data", pcm16(16384, -16384), size = size)))
            assertContentEquals(floatArrayOf(0.5f, -0.5f), sample.samples, "size $size")
        }
    }

    @Test
    fun theDataChunkMayComeBeforeTheFormat() {
        val sample = WavDecoder.decode(wav(chunk("data", pcm16(16384)), fmtChunk(1, 1, 8000, 16)))
        assertContentEquals(floatArrayOf(0.5f), sample.samples)
    }

    @Test
    fun anythingElseIsAnIOException() {
        assertFailsWith<IOException> { WavDecoder.decode(ByteArray(0)) }
        assertFailsWith<IOException> { WavDecoder.decode("RIFF....AVI ".toByteArray()) }
        assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(2, 1, 8000, 4), chunk("data", ByteArray(8)))) }
        assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(6, 1, 8000, 8), chunk("data", ByteArray(8)))) }
        assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(1, 9, 8000, 16), chunk("data", ByteArray(18)))) }
        assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(1, 0, 8000, 16), chunk("data", ByteArray(8)))) }
        assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(1, 1, 0, 16), chunk("data", ByteArray(8)))) }
        assertFailsWith<IOException> { WavDecoder.decode(wav(chunk("data", ByteArray(8)))) }
        assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(1, 1, 8000, 16))) }
        assertTrue(assertFailsWith<IOException> { WavDecoder.decode(wav(fmtChunk(2, 1, 8000, 4), chunk("data", ByteArray(8)))) }.message!!.contains("format tag 2"))
    }

    @Test
    fun anEmptyDataChunkIsAnEmptySample() {
        val sample = WavDecoder.decode(wav(fmtChunk(1, 1, 8000, 16), chunk("data", ByteArray(0), size = 0)))
        assertEquals(0, sample.frames)
    }
}
