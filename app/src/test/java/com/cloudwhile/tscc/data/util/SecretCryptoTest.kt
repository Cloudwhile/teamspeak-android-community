package com.cloudwhile.tscc.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator

class SecretCryptoTest {

    @Test
    fun `encryptWithKey and decryptWithKey roundtrip identity ini and passwords`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val rawIni = "[Identity]\nid=Default\nidentity=\"9283V+secretP256KeyPayload==\"\nnickname=Commander"

        val encrypted = SecretCrypto.encryptWithKey(rawIni, key)
        assertTrue(encrypted.startsWith(SecretCrypto.PREFIX))
        assertNotEquals(rawIni, encrypted)

        // Idempotent when already encrypted
        assertEquals(encrypted, SecretCrypto.encryptWithKey(encrypted, key))

        //Randomized IV produces distinct ciphertexts for identical plaintexts
        val encryptedSecond = SecretCrypto.encryptWithKey(rawIni, key)
        assertNotEquals(encrypted, encryptedSecond)

        assertEquals(rawIni, SecretCrypto.decryptWithKey(encrypted, key))
        assertEquals(rawIni, SecretCrypto.decryptWithKey(encryptedSecond, key))
    }

    @Test
    fun `legacy plaintext migration and tampered payload rejection`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val legacyPlain = "legacy_server_password_123"

        // Plaintext without prefix is returned intact for transparent migration
        assertEquals(legacyPlain, SecretCrypto.decryptWithKey(legacyPlain, key))
        assertEquals("", SecretCrypto.encryptWithKey("", key))
        assertEquals("", SecretCrypto.decryptWithKey("", key))

        // Tampered GCM tag or wrong key returns empty string instead of crashing
        val cipherText = SecretCrypto.encryptWithKey("top_secret", key)
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals("", SecretCrypto.decryptWithKey(cipherText, wrongKey))
        assertEquals("", SecretCrypto.decryptWithKey(SecretCrypto.PREFIX + "bad_base64!!", key))
    }
}
