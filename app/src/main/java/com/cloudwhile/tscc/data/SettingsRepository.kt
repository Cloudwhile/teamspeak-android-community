package com.cloudwhile.tscc.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.VoiceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "tscc_settings")

data class AppSettings(
    val defaultNickname: String = "TSCC_Player",
    val voiceMode: VoiceMode = VoiceMode.VAD,
    val vadThresholdDb: Float = -42f,
    val antiPlosiveEnabled: Boolean = true,
    val soundCuesEnabled: Boolean = true,
    val hardwareAecEnabled: Boolean = true,
    val hardwareNsEnabled: Boolean = true,
    val hardwareAgcEnabled: Boolean = true,
    val overlayEnabled: Boolean = false,
    val darkTheme: Boolean = true,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val autoCheckUpdates: Boolean = true,
    val skippedUpdateVersion: String = "",
    val onboardingCompleted: Boolean = true,
    val isLoaded: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Pin ONBOARDING_COMPLETED explicitly on first launch so updating nickname or voice mode
        // mid-onboarding never flips onboardingCompleted to true prematurely.
        initScope.launch {
            context.dataStore.edit { prefs ->
                if (prefs[Keys.ONBOARDING_COMPLETED] == null) {
                    val hasExistingUserPrefs =
                        prefs[Keys.DEFAULT_NICKNAME] != null || prefs[Keys.VOICE_MODE] != null
                    prefs[Keys.ONBOARDING_COMPLETED] = hasExistingUserPrefs
                }
            }
        }
    }

    private object Keys {
        val DEFAULT_NICKNAME = stringPreferencesKey("default_nickname")
        val VOICE_MODE = intPreferencesKey("voice_mode")
        val VAD_THRESHOLD_DB = floatPreferencesKey("vad_threshold_db")
        val ANTI_PLOSIVE = booleanPreferencesKey("anti_plosive_dsp")
        val SOUND_CUES = booleanPreferencesKey("sound_cues_enabled")
        val HW_AEC = booleanPreferencesKey("hw_aec")
        val HW_NS = booleanPreferencesKey("hw_ns")
        val HW_AGC = booleanPreferencesKey("hw_agc")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val SKIPPED_UPDATE_VERSION = stringPreferencesKey("skipped_update_version")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val explicitOnboarding = prefs[Keys.ONBOARDING_COMPLETED]
        val hasExistingUserPrefs = prefs[Keys.DEFAULT_NICKNAME] != null || prefs[Keys.VOICE_MODE] != null
        AppSettings(
            defaultNickname = prefs[Keys.DEFAULT_NICKNAME] ?: "TSCC_Player",
            voiceMode = VoiceMode.fromCode(prefs[Keys.VOICE_MODE] ?: VoiceMode.VAD.code),
            vadThresholdDb = prefs[Keys.VAD_THRESHOLD_DB] ?: -42f,
            antiPlosiveEnabled = prefs[Keys.ANTI_PLOSIVE] ?: true,
            soundCuesEnabled = prefs[Keys.SOUND_CUES] ?: true,
            hardwareAecEnabled = prefs[Keys.HW_AEC] ?: true,
            hardwareNsEnabled = prefs[Keys.HW_NS] ?: true,
            hardwareAgcEnabled = prefs[Keys.HW_AGC] ?: true,
            overlayEnabled = prefs[Keys.OVERLAY_ENABLED] ?: false,
            darkTheme = prefs[Keys.DARK_THEME] ?: true,
            appLanguage = AppLanguage.fromCode(prefs[Keys.APP_LANGUAGE]),
            autoCheckUpdates = prefs[Keys.AUTO_CHECK_UPDATES] ?: true,
            skippedUpdateVersion = prefs[Keys.SKIPPED_UPDATE_VERSION].orEmpty(),
            onboardingCompleted = explicitOnboarding ?: hasExistingUserPrefs,
            isLoaded = true
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.preserveImplicitOnboardingState() {
        if (this[Keys.ONBOARDING_COMPLETED] == null) {
            this[Keys.ONBOARDING_COMPLETED] =
                this[Keys.DEFAULT_NICKNAME] != null || this[Keys.VOICE_MODE] != null
        }
    }

    suspend fun setDefaultNickname(name: String) {
        context.dataStore.edit {
            it.preserveImplicitOnboardingState()
            it[Keys.DEFAULT_NICKNAME] = name.trim().ifBlank { "TSCC_Player" }
        }
    }

    suspend fun setVoiceMode(mode: VoiceMode) {
        context.dataStore.edit {
            it.preserveImplicitOnboardingState()
            it[Keys.VOICE_MODE] = mode.code
        }
    }

    suspend fun setVadThresholdDb(db: Float) {
        context.dataStore.edit { it[Keys.VAD_THRESHOLD_DB] = db.coerceIn(-75f, -5f) }
    }

    suspend fun setAntiPlosiveEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ANTI_PLOSIVE] = enabled }
    }

    suspend fun setSoundCuesEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SOUND_CUES] = enabled }
    }

    suspend fun setHardwareAudioProcessing(aec: Boolean, ns: Boolean, agc: Boolean) {
        context.dataStore.edit {
            it[Keys.HW_AEC] = aec
            it[Keys.HW_NS] = ns
            it[Keys.HW_AGC] = agc
        }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OVERLAY_ENABLED] = enabled }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.dataStore.edit { it[Keys.DARK_THEME] = dark }
    }

    suspend fun setAppLanguage(language: AppLanguage) {
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = language.code }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun setSkippedUpdateVersion(version: String) {
        context.dataStore.edit { it[Keys.SKIPPED_UPDATE_VERSION] = version.trim() }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun clearAllSettings() {
        context.dataStore.edit {
            it.clear()
            it[Keys.ONBOARDING_COMPLETED] = false
        }
    }

    suspend fun restoreAllSettings(
        defaultNickname: String,
        voiceMode: VoiceMode,
        vadThresholdDb: Float,
        antiPlosiveEnabled: Boolean,
        soundCuesEnabled: Boolean,
        aecEnabled: Boolean,
        nsEnabled: Boolean,
        agcEnabled: Boolean,
        overlayEnabled: Boolean,
        darkTheme: Boolean,
        appLanguage: AppLanguage,
        autoCheckUpdates: Boolean
    ) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DEFAULT_NICKNAME] = defaultNickname.trim().ifBlank { "TSCC_Player" }
            prefs[Keys.VOICE_MODE] = voiceMode.code
            prefs[Keys.VAD_THRESHOLD_DB] = vadThresholdDb.coerceIn(-75f, -5f)
            prefs[Keys.ANTI_PLOSIVE] = antiPlosiveEnabled
            prefs[Keys.SOUND_CUES] = soundCuesEnabled
            prefs[Keys.HW_AEC] = aecEnabled
            prefs[Keys.HW_NS] = nsEnabled
            prefs[Keys.HW_AGC] = agcEnabled
            prefs[Keys.OVERLAY_ENABLED] = overlayEnabled
            prefs[Keys.DARK_THEME] = darkTheme
            prefs[Keys.APP_LANGUAGE] = appLanguage.code
            prefs[Keys.AUTO_CHECK_UPDATES] = autoCheckUpdates
            prefs[Keys.ONBOARDING_COMPLETED] = true
        }
    }
}
