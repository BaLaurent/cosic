package com.octopusman.cosic.io

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cosic.io.mqtt.MqttCredentials
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens small secrets. [open] returns null for a blob that is not one of ours or whose key is gone. */
internal interface SecretCipher {
    fun seal(plain: ByteArray): ByteArray

    fun open(blob: ByteArray): ByteArray?
}

/** The few string entries the store keeps. */
internal interface StringStore {
    fun get(key: String): String?

    fun put(key: String, value: String)

    fun remove(key: String)
}

/**
 * The passwords of MQTT brokers the user typed once: sealed with a key of the Android Keystore (AES-GCM; the key never leaves the keystore),
 * one entry per host and user in private preferences. A password that cannot be opened (the key was lost with a restored backup) reads as
 * not there, and the user is asked again.
 */
internal class MqttCredentialStore(private val store: StringStore, private val cipher: SecretCipher) : MqttCredentials {
    override fun load(host: String, user: String): String? {
        val blob = store.get(entry(host, user)) ?: return null
        val bytes = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return null
        return cipher.open(bytes)?.decodeToString()
    }

    override fun save(host: String, user: String, password: String) {
        store.put(entry(host, user), Base64.getEncoder().encodeToString(cipher.seal(password.encodeToByteArray())))
    }

    override fun clear(host: String, user: String) = store.remove(entry(host, user))

    companion object {
        /** The name of an entry: a hash, so that the preferences do not list the brokers a user has. */
        fun entry(host: String, user: String): String =
            MessageDigest.getInstance("SHA-256").digest("$host\u0000$user".encodeToByteArray()).joinToString("") { "%02x".format(it) }

        fun create(context: Context): MqttCredentialStore =
            MqttCredentialStore(PrefsStringStore(context.getSharedPreferences("mqtt_credentials", Context.MODE_PRIVATE)), KeystoreCipher("cosic_mqtt_credentials"))
    }
}

internal class PrefsStringStore(private val prefs: SharedPreferences) : StringStore {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

/** AES-256-GCM with a random 12-byte IV in front of the sealed bytes, under the keystore key [alias] (made on first use). */
internal class KeystoreCipher(private val alias: String) : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun open(blob: ByteArray): ByteArray? {
        if (blob.size <= IV_BYTES) return null
        return runCatching {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
            c.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }.getOrNull()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
