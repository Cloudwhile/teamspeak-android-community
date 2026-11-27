package com.cloudwhile.tscc.data.backup

import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.VoiceMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupPasswordRequiredException :
    IllegalArgumentException("TSCC configuration backup is encrypted and requires a password")

class BackupInvalidPasswordException :
    IllegalArgumentException("Incorrect backup password or corrupted encrypted payload")

class BackupFormatException(message: String) : IllegalArgumentException(message)

@Serializable
data class TsccBackupIdentity(
    val name: String,
    val uniqueId: String,
    val publicKeyBase64: String,
    val iniPayload: String,
    val securityLevel: Int,
    val isDefault: Boolean = false,
    val createdAt: Long = 0L
)

@Serializable
data class TsccBackupBookmark(
    val label: String,
    val address: String,
    val port: Int = 9987,
    val nickname: String,
    val serverPassword: String = "",
    val defaultChannel: String = "",
    val channelPassword: String = "",
    val identityUniqueId: String? = null,
    val lastConnectedAt: Long = 0L
)

@Serializable
data class TsccBackupSettings(
    val defaultNickname: String = "TSCC_Player",
    val voiceModeCode: Int = VoiceMode.VAD.code,
    val vadThresholdDb: Float = -42f,
    val antiPlosiveEnabled: Boolean = true,
    val soundCuesEnabled: Boolean = true,
    val hardwareAecEnabled: Boolean = true,
    val hardwareNsEnabled: Boolean = true,
    val hardwareAgcEnabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val darkTheme: Boolean = true,
    val appLanguageCode: String = AppLanguage.SYSTEM.code,
    val autoCheckUpdates: Boolean = true
)

@Serializable
data class TsccBackupUserAudioPref(
    val clientUid: String,
    val nicknameHint: String = "",
    val volumePercent: Int = 100,
    val localMuted: Boolean = false,
    val updatedAt: Long = 0L
)

@Serializable
data class TsccConfigPayload(
    val identities: List<TsccBackupIdentity> = emptyList(),
    val bookmarks: List<TsccBackupBookmark> = emptyList(),
    val settings: TsccBackupSettings? = null,
    val userAudioPrefs: List<TsccBackupUserAudioPref> = emptyList()
)

@Serializable
data class TsccBackupEnvelope(
    val schema: String = TsccConfigBackupCodec.SCHEMA_ID,
    val version: Int = TsccConfigBackupCodec.CURRENT_VERSION,
    val exportedAtMs: Long = 0L,
    val appVersion: String = "",
    val encrypted: Boolean = false,
    val kdf: String? = null,
    val iterations: Int? = null,
    val salt: String? = null,
    val iv: String? = null,
    val ciphertext: String? = null,
    val data: TsccConfigPayload? = null
)

data class TsccConfigImportSummary(
    val identitiesCount: Int,
    val bookmarksCount: Int,
    val settingsRestored: Boolean,
    val audioPrefsCount: Int
)

object TsccConfigBackupCodec {

    const val SCHEMA_ID = "tscc-config"
    const val CURRENT_VERSION = 1
    private const val KDF_NAME = "PBKDF2-HMAC-SHA256"
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val DEFAULT_ITERATIONS = 120_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 1_000_000
    private const val SALT_BYTES = 16
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val compactJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun inspectEnvelope(rawJson: String): Result<TsccBackupEnvelope> = runCatching {
        val clean = rawJson.trim().removePrefix("\uFEFF")
        if (clean.isEmpty()) {
            throw BackupFormatException("Empty configuration file")
        }
        val env = try {
            compactJson.decodeFromString<TsccBackupEnvelope>(clean)
        } catch (_: Exception) {
            throw BackupFormatException("Invalid JSON configuration format")
        }
        if (env.schema != SCHEMA_ID) {
            throw BackupFormatException("Unsupported backup schema: ${env.schema}")
        }
        if (env.version < 1 || env.version > CURRENT_VERSION) {
            throw BackupFormatException("Unsupported backup version: ${env.version}")
        }
        env
    }

    fun isEncrypted(rawJson: String): Boolean =
        inspectEnvelope(rawJson).getOrNull()?.encrypted == true

    fun encode(
        payload: TsccConfigPayload,
        password: String = "",
        appVersion: String = "",
        exportedAtMs: Long = System.currentTimeMillis()
    ): String {
        if (password.isEmpty()) {
            val plainEnv = TsccBackupEnvelope(
                schema = SCHEMA_ID,
                version = CURRENT_VERSION,
                exportedAtMs = exportedAtMs,
                appVersion = appVersion,
                encrypted = false,
                data = payload
            )
            return prettyJson.encodeToString(plainEnv)
        }

        val rng = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val iv = ByteArray(GCM_IV_BYTES).also { rng.nextBytes(it) }
        val secretKey = deriveAesKey(password, salt, DEFAULT_ITERATIONS)

        val plainBytes = compactJson.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD("$SCHEMA_ID:v$CURRENT_VERSION".toByteArray(StandardCharsets.UTF_8))
        val cipherBytes = cipher.doFinal(plainBytes)

        val b64 = Base64.getEncoder()
        val encEnv = TsccBackupEnvelope(
            schema = SCHEMA_ID,
            version = CURRENT_VERSION,
            exportedAtMs = exportedAtMs,
            appVersion = appVersion,
            encrypted = true,
            kdf = KDF_NAME,
            iterations = DEFAULT_ITERATIONS,
            salt = b64.encodeToString(salt),
            iv = b64.encodeToString(iv),
            ciphertext = b64.encodeToString(cipherBytes),
            data = null
        )
        return prettyJson.encodeToString(encEnv)
    }

    fun decode(rawJson: String, password: String = ""): Result<TsccConfigPayload> =
        inspectEnvelope(rawJson).mapCatching { env ->
            if (!env.encrypted) {
                return@mapCatching env.data
                    ?: throw BackupFormatException("Backup file contains no configuration data")
            }
            if (password.isEmpty()) {
                throw BackupPasswordRequiredException()
            }
            val saltStr = env.salt?.takeIf { it.isNotBlank() }
                ?: throw BackupFormatException("Encrypted backup is missing KDF salt")
            val ivStr = env.iv?.takeIf { it.isNotBlank() }
                ?: throw BackupFormatException("Encrypted backup is missing GCM IV")
            val ctStr = env.ciphertext?.takeIf { it.isNotBlank() }
                ?: throw BackupFormatException("Encrypted backup is missing ciphertext")

            val b64 = Base64.getDecoder()
            val salt = try {
                b64.decode(saltStr)
            } catch (e: IllegalArgumentException) {
                throw BackupFormatException("Malformed Base64 salt")
            }
            val iv = try {
                b64.decode(ivStr)
            } catch (e: IllegalArgumentException) {
                throw BackupFormatException("Malformed Base64 IV")
            }
            val cipherBytes = try {
                b64.decode(ctStr)
            } catch (e: IllegalArgumentException) {
                throw BackupFormatException("Malformed Base64 ciphertext")
            }
            val iterations = (env.iterations ?: DEFAULT_ITERATIONS)
                .coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)

            val plainBytes = try {
                val secretKey = deriveAesKey(password, salt, iterations)
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.updateAAD("$SCHEMA_ID:v${env.version}".toByteArray(StandardCharsets.UTF_8))
                cipher.doFinal(cipherBytes)
            } catch (_: GeneralSecurityException) {
                throw BackupInvalidPasswordException()
            }

            try {
                compactJson.decodeFromString<TsccConfigPayload>(
                    String(plainBytes, StandardCharsets.UTF_8)
                )
            } catch (_: Exception) {
                throw BackupFormatException("Corrupted configuration payload")
            }
        }

    private fun deriveAesKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
