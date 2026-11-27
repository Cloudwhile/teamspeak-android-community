package com.cloudwhile.tscc.data.backup

import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.VoiceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsccConfigBackupTest {

    private fun samplePayload() = TsccConfigPayload(
        identities = listOf(
            TsccBackupIdentity(
                name = "主力身份",
                uniqueId = "AbCdEfGhIjKlMnOpQrStUvWxYz0=",
                publicKeyBase64 = "ω+pubKeyBase64==",
                iniPayload = "[Identity]\nid=Main\nidentity=\"999V...\"\n",
                securityLevel = 24,
                isDefault = true,
                createdAt = 1_700_000_000_000L
            )
        ),
        bookmarks = listOf(
            TsccBackupBookmark(
                label = "开黑语音服",
                address = "ts.clan.example",
                port = 9988,
                nickname = "Cloudwhile",
                serverPassword = "srvSecretPassword",
                defaultChannel = "Lobby/Squad 1",
                channelPassword = "chSecretPassword",
                identityUniqueId = "AbCdEfGhIjKlMnOpQrStUvWxYz0=",
                lastConnectedAt = 1_700_000_123_000L
            )
        ),
        settings = TsccBackupSettings(
            defaultNickname = "Cloudwhile",
            voiceModeCode = VoiceMode.PTT.code,
            vadThresholdDb = -38.5f,
            antiPlosiveEnabled = true,
            soundCuesEnabled = false,
            hardwareAecEnabled = true,
            hardwareNsEnabled = true,
            hardwareAgcEnabled = false,
            overlayEnabled = true,
            darkTheme = false,
            appLanguageCode = AppLanguage.ZH_CN.code,
            autoCheckUpdates = true
        ),
        userAudioPrefs = listOf(
            TsccBackupUserAudioPref(
                clientUid = "PeerUid123=",
                nicknameHint = "Teammate",
                volumePercent = 145,
                localMuted = false,
                updatedAt = 1_700_000_555_000L
            )
        )
    )

    @Test
    fun `plain json backup round-trips identities bookmarks and preferences`() {
        val payload = samplePayload()
        val rawJson = TsccConfigBackupCodec.encode(
            payload = payload,
            password = "",
            appVersion = "0.1.0-alpha.2",
            exportedAtMs = 1_700_001_000_000L
        )
        assertFalse(TsccConfigBackupCodec.isEncrypted(rawJson))

        val decoded = TsccConfigBackupCodec.decode(rawJson).getOrThrow()
        assertEquals(payload, decoded)
    }

    @Test
    fun `password-encrypted aes-256-gcm backup hides secrets and rejects wrong password`() {
        val payload = samplePayload()
        val encryptedJson = TsccConfigBackupCodec.encode(
            payload = payload,
            password = "Strong-Passphrase#2026",
            appVersion = "0.1.0-alpha.2",
            exportedAtMs = 1_700_002_000_000L
        )

        assertTrue(TsccConfigBackupCodec.isEncrypted(encryptedJson))
        assertFalse(encryptedJson.contains("srvSecretPassword"))
        assertFalse(encryptedJson.contains("999V..."))
        assertFalse(encryptedJson.contains("ts.clan.example"))

        val missingPwdErr = TsccConfigBackupCodec.decode(encryptedJson, password = "").exceptionOrNull()
        assertTrue(missingPwdErr is BackupPasswordRequiredException)

        val wrongPwdErr = TsccConfigBackupCodec.decode(encryptedJson, password = "WrongPassword").exceptionOrNull()
        assertTrue(wrongPwdErr is BackupInvalidPasswordException)

        val decrypted = TsccConfigBackupCodec.decode(
            encryptedJson,
            password = "Strong-Passphrase#2026"
        ).getOrThrow()
        assertEquals(payload, decrypted)
    }

    @Test
    fun `rejects invalid schema or malformed json cleanly`() {
        val badSchema = """{"schema":"other-app","version":1,"encrypted":false}"""
        val err = TsccConfigBackupCodec.decode(badSchema).exceptionOrNull()
        assertTrue(err is BackupFormatException)
    }
}
