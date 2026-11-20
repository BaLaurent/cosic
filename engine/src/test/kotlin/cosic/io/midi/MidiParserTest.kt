package cosic.io.midi

import kotlin.test.Test
import kotlin.test.assertEquals

class MidiParserTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun parse(vararg chunks: String): List<String> {
        val parser = MidiStreamParser()
        val out = ArrayList<String>()
        for (chunk in chunks) {
            val data = chunk.trim().split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            parser.feed(data, 0, data.size) { out.add(hex(it)) }
        }
        return out
    }

    @Test
    fun twoNotesInOneBufferShareTheStatusByte() {
        assertEquals(listOf("90 3C 40", "90 3E 40"), parse("90 3C 40 3E 40"))
    }

    @Test
    fun aMessageMayArriveInPieces() {
        assertEquals(listOf("90 3C 40"), parse("90", "3C", "40"))
        assertEquals(listOf("90 3C 40", "90 3E 41"), parse("90 3C", "40 3E", "41"))
    }

    @Test
    fun aRealTimeByteInsideAMessageIsReportedAtOnce() {
        assertEquals(listOf("F8", "90 3C 40"), parse("90 3C F8 40"))
        assertEquals(listOf("F8", "FE", "B0 07 7F"), parse("B0 F8 07 FE 7F"))
    }

    @Test
    fun programAndChannelPressureHaveOneDataByte() {
        assertEquals(listOf("C0 05", "C0 06"), parse("C0 05 06"))
        assertEquals(listOf("D1 40"), parse("D1 40"))
        assertEquals(listOf("E0 00 40"), parse("E0 00 40"))
    }

    @Test
    fun systemExclusiveIsReportedWhole() {
        assertEquals(listOf("F0 7D 01 F7"), parse("F0 7D 01 F7"))
        assertEquals(listOf("F0 7D 01 02 F7"), parse("F0 7D", "01", "02 F7"))
    }

    @Test
    fun aRealTimeByteInsideSystemExclusiveDoesNotBreakIt() {
        assertEquals(listOf("F8", "F0 7D 01 F7"), parse("F0 7D F8 01 F7"))
    }

    @Test
    fun systemExclusiveThatIsCutShortByAStatusByteIsDropped() {
        assertEquals(listOf("90 3C 40"), parse("F0 7D 01 90 3C 40"))
        assertEquals(emptyList(), parse("F7"))
    }

    @Test
    fun systemExclusiveLongerThanTheLimitIsDropped() {
        val parser = MidiStreamParser(maxSysexBytes = 4)
        val out = ArrayList<String>()
        val data = byteArrayOf(0xF0.toByte(), 1, 2, 3, 4, 5, 6, 0xF7.toByte(), 0x90.toByte(), 60, 64)
        parser.feed(data, 0, data.size) { out.add(hex(it)) }
        assertEquals(listOf("90 3C 40"), out)
    }

    @Test
    fun systemCommonMessagesAreWholeAndCancelRunningStatus() {
        assertEquals(listOf("F1 22", "F2 01 02", "F3 05", "F6"), parse("F1 22 F2 01 02 F3 05 F6"))
        assertEquals(listOf("90 3C 40", "F6"), parse("90 3C 40 F6 3E 40"))
    }

    @Test
    fun dataThatBelongsToNoMessageIsIgnored() {
        assertEquals(listOf("90 3C 40"), parse("01 02 90 3C 40"))
    }

    @Test
    fun aStatusByteReplacesAMessageThatWasNotFinished() {
        assertEquals(listOf("80 3C 00"), parse("90 3C 80 3C 00"))
    }

    @Test
    fun aSliceOfTheBufferIsRead() {
        val parser = MidiStreamParser()
        val out = ArrayList<String>()
        val data = byteArrayOf(0, 0, 0x90.toByte(), 60, 64, 0x55)
        parser.feed(data, 2, 3) { out.add(hex(it)) }
        assertEquals(listOf("90 3C 40"), out)
    }

    @Test
    fun resetForgetsAHalfReceivedMessage() {
        val parser = MidiStreamParser()
        val out = ArrayList<String>()
        parser.feed(byteArrayOf(0x90.toByte(), 60), 0, 2) { out.add(hex(it)) }
        parser.reset()
        parser.feed(byteArrayOf(64), 0, 1) { out.add(hex(it)) }
        assertEquals(emptyList(), out)
    }
}
