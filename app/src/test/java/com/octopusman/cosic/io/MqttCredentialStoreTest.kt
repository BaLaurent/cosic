package com.octopusman.cosic.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The store of broker passwords and the queue of password questions, without the Android Keystore and the screen. */
class MqttCredentialStoreTest {
    /** Reverses the bytes and appends a marker: not secure, but it shows that what is stored is not the password and that opening checks the marker. */
    private class FakeCipher : SecretCipher {
        override fun seal(plain: ByteArray) = plain.reversedArray() + byteArrayOf(0x7E)

        override fun open(blob: ByteArray): ByteArray? = if (blob.isNotEmpty() && blob.last() == 0x7E.toByte()) blob.copyOf(blob.size - 1).reversedArray() else null
    }

    private class MapStore : StringStore {
        val map = HashMap<String, String>()

        override fun get(key: String) = map[key]

        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }
    }

    @Test
    fun aSavedPasswordComesBackAndIsNotStoredAsText() {
        val backing = MapStore()
        val store = MqttCredentialStore(backing, FakeCipher())
        assertNull(store.load("wss://h/", "me"))
        store.save("wss://h/", "me", "hunter2")
        assertEquals("hunter2", store.load("wss://h/", "me"))
        assertTrue(backing.map.values.none { "hunter2" in it })
        assertTrue(backing.map.keys.none { "h" == it || "me" in it }, "entries are named by a hash")
    }

    @Test
    fun accountsAreKeptApart() {
        val store = MqttCredentialStore(MapStore(), FakeCipher())
        store.save("wss://h/", "me", "a")
        store.save("wss://h/", "you", "b")
        store.save("wss://g/", "me", "c")
        assertEquals(listOf("a", "b", "c"), listOf(store.load("wss://h/", "me"), store.load("wss://h/", "you"), store.load("wss://g/", "me")))
        assertNotEquals(MqttCredentialStore.entry("a", "bc"), MqttCredentialStore.entry("ab", "c"))
    }

    @Test
    fun clearForgets() {
        val store = MqttCredentialStore(MapStore(), FakeCipher())
        store.save("h", "me", "x")
        store.clear("h", "me")
        assertNull(store.load("h", "me"))
    }

    @Test
    fun aBlobThatCannotBeOpenedReadsAsNotThere() {
        val backing = MapStore()
        val store = MqttCredentialStore(backing, FakeCipher())
        backing.put(MqttCredentialStore.entry("h", "me"), "!!not base64!!")
        assertNull(store.load("h", "me"))
        backing.put(MqttCredentialStore.entry("h", "me"), java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        assertNull(store.load("h", "me"))
    }

    @Test
    fun questionsAreShownOneAtATimeAndEachAnsweredOnce() {
        val prompt = MqttPasswordPrompt()
        val answers = ArrayList<String?>()
        prompt.request("h", "a") { answers.add("a:$it") }
        prompt.request("h", "b") { answers.add("b:$it") }
        assertEquals("a", prompt.pending.value?.user)
        val first = prompt.pending.value!!
        first.answer("pw")
        first.answer("again")
        assertEquals(listOf<String?>("a:pw"), answers)
        assertEquals("b", prompt.pending.value?.user)
        prompt.pending.value!!.answer(null)
        assertEquals(listOf<String?>("a:pw", "b:null"), answers)
        assertNull(prompt.pending.value)
    }

    @Test
    fun cancelAllAnswersWithNone() {
        val prompt = MqttPasswordPrompt()
        val answers = ArrayList<String?>()
        prompt.request("h", "a") { answers.add(it) }
        prompt.request("h", "b") { answers.add(it) }
        prompt.cancelAll()
        assertEquals(listOf<String?>(null, null), answers)
        assertFalse(prompt.pending.value != null)
    }
}
