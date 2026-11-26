package com.cloudwhile.tscc.data.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM encryption for sensitive local database columns
 * (P-256 TeamSpeak identity `.ini` private keys and saved server/channel passwords).
 *
 * Transparently migrates legacy plaintext values (anything without [PREFIX]) on read/write
 * and gracefully falls back in non-Android JVM unit test environments.
 */
object SecretCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "tscc_db_master_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    internal const val PREFIX = "enc:v1:"

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return@runCatching existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        keyGenerator.generateKey()
    }.getOrNull()

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty() || plainText.startsWith(PREFIX)) return plainText
        val key = getOrCreateSecretKey() ?: return plainText
        return encryptWithKey(plainText, key)
    }

    fun decrypt(storedText: String): String {
        if (storedText.isEmpty() || !storedText.startsWith(PREFIX)) return storedText
        val key = getOrCreateSecretKey() ?: return ""
        return decryptWithKey(storedText, key)
    }

    internal fun encryptWithKey(plainText: String, key: SecretKey): String {
        if (plainText.isEmpty() || plainText.startsWith(PREFIX)) return plainText
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            PREFIX + Base64.getEncoder().encodeToString(combined)
        }.getOrDefault(plainText)
    }

    internal fun decryptWithKey(storedText: String, key: SecretKey): String {
        if (storedText.isEmpty() || !storedText.startsWith(PREFIX)) return storedText
        return runCatching {
            val rawPayload = storedText.removePrefix(PREFIX)
            val combined = Base64.getDecoder().decode(rawPayload)
            if (combined.size <= GCM_IV_BYTES) return@runCatching ""
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val cipherBytes = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
