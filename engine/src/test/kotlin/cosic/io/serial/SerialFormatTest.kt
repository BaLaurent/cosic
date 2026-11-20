package cosic.io.serial

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SerialFormatTest {
    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    private val plain = SerialRoute()

    @Test
    fun crcIsCcittFalse() {
        assertEquals(0x29B1, crc16CcittFalse("123456789"))
        assertEquals(0, crc16CcittFalse(""))
        assertEquals(0xB915, crc16CcittFalse("A"))
    }

    @Test
    fun anActionIsAFunctionCall() {
        val map = linkedMapOf<String, Any?>("action" to "move", "x" to 1.0, "y" to 2.0)
        assertEquals("move(x:1,y:2)", text(serialBytes(map, plain)))
        assertEquals("m(x:1,y:2)", text(serialBytes(map, SerialRoute(singleCharIds = true))))
    }

    @Test
    fun theActionCanComeAnywhereInTheMap() {
        val map = linkedMapOf<String, Any?>("x" to 1.0, "action" to "go", "y" to "up")
        assertEquals("go(x:1,y:up)", text(serialBytes(map, plain)))
        assertEquals("g(x:1,y:up)", text(serialBytes(map, SerialRoute(singleCharIds = true))))
        assertEquals("go()", text(serialBytes(linkedMapOf<String, Any?>("action" to "go"), plain)))
    }

    @Test
    fun aMapWithoutAnActionIsKeyValuePairsWithNoSeparator() {
        assertEquals("a:1b:2", text(serialBytes(linkedMapOf<String, Any?>("a" to 1.0, "b" to 2.0), plain)))
        // single character ids only apply to the action form
        assertEquals("ab:1", text(serialBytes(linkedMapOf<String, Any?>("ab" to 1.0), SerialRoute(singleCharIds = true))))
    }

    @Test
    fun valuesAreWrittenAsJavaScriptWritesThem() {
        assertEquals("x(v:1e+21)", text(serialBytes(linkedMapOf<String, Any?>("action" to "x", "v" to 1e21), plain)))
        assertEquals("x(v:0.30000000000000004,w:true,u:1,2)", text(serialBytes(linkedMapOf<String, Any?>("action" to "x", "v" to 0.1 + 0.2, "w" to true, "u" to listOf(1.0, 2.0)), plain)))
        assertEquals("x(v:null,w:undefined,o:[object Object])", text(serialBytes(linkedMapOf<String, Any?>("action" to "x", "v" to null, "w" to Undefined, "o" to linkedMapOf<String, Any?>()), plain)))
    }

    @Test
    fun anythingElseIsItsString() {
        assertEquals("hello", text(serialBytes("hello", plain)))
        assertEquals("5", text(serialBytes(5.0, plain)))
        assertEquals("true", text(serialBytes(true, plain)))
        assertEquals("0:a1:b", text(serialBytes(listOf("a", "b"), plain)))
        assertEquals(0, serialBytes(Undefined, plain).size)
    }

    @Test
    fun nullIsATypeError() {
        assertFailsWith<CosicError> { serialBytes(null, plain) }
    }

    @Test
    fun theRoutingKeysOfTheEngineAreNotWritten() {
        val map = linkedMapOf<String, Any?>("s" to "bd", "_cosic_engine" to "dough", "n" to 1.0)
        assertEquals("s:bdn:1", text(serialBytes(map, plain)))
    }

    @Test
    fun theCrcTrailerFollowsAnActionMessage() {
        val bytes = serialBytes(linkedMapOf<String, Any?>("action" to "a"), SerialRoute(sendCrc = true))
        val message = "a()".toByteArray()
        val crc = crc16CcittFalse("a()")
        assertEquals(message.size + 4, bytes.size)
        assertContentEquals(message, bytes.copyOfRange(0, message.size))
        assertContentEquals(byteArrayOf(0x7C, (crc shr 8).toByte(), crc.toByte(), 0x3B), bytes.copyOfRange(message.size, bytes.size))
    }

    @Test
    fun theCrcIsOnlyMadeForTheActionForm() {
        assertEquals("a:1", text(serialBytes(linkedMapOf<String, Any?>("a" to 1.0), SerialRoute(sendCrc = true))))
        assertEquals("hi", text(serialBytes("hi", SerialRoute(sendCrc = true))))
    }

    @Test
    fun aCrcThatIsZeroAddsNothing() {
        val value = (0..999_999).first { crc16CcittFalse("a(k:$it)") == 0 } // about one in 65536, so one exists
        val bytes = serialBytes(linkedMapOf<String, Any?>("action" to "a", "k" to value.toDouble()), SerialRoute(sendCrc = true))
        assertEquals("a(k:$value)", text(bytes))
    }

    @Test
    fun textIsUtf8AndALoneSurrogateIsTheReplacementCharacter() {
        assertContentEquals("é€😀".toByteArray(Charsets.UTF_8), serialBytes("é€😀", plain))
        assertContentEquals(byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte()), serialBytes("\uD800", plain))
    }

    @Test
    fun singleCharIdsNeedAStringAction() {
        assertFailsWith<CosicError> { serialBytes(linkedMapOf<String, Any?>("action" to 1.0), SerialRoute(singleCharIds = true)) }
    }
}
