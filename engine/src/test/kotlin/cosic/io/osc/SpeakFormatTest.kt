package cosic.io.osc

import cosic.io.speakText
import cosic.pattern.Undefined
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeakFormatTest {
    @Test
    fun valuesAreSaidAsJavaScriptWritesThem() {
        assertEquals("hello", speakText("hello"))
        assertEquals("3", speakText(3.0))
        assertEquals("0.30000000000000004", speakText(0.1 + 0.2))
        assertEquals("-1.5", speakText(-1.5))
        assertEquals("1e+21", speakText(1e21))
        assertEquals("true", speakText(true))
        assertEquals("null", speakText(null))
        assertEquals("undefined", speakText(Undefined))
        assertEquals("[object Object]", speakText(mapOf("s" to "bd")))
        assertEquals("1,a,,2", speakText(listOf(1.0, "a", null, 2.0)))
        assertEquals("1,2,3", speakText(listOf(1.0, listOf(2.0, 3.0))))
    }
}
