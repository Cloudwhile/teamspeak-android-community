package com.cloudwhile.tscc.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudwhile.tscc.TsccApp
import com.cloudwhile.tscc.data.AppSettings
import com.cloudwhile.tscc.data.db.BookmarkEntity
import com.cloudwhile.tscc.data.db.IdentityEntity
import com.cloudwhile.tscc.data.db.UserAudioPrefEntity
import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.AudioRoute
import com.cloudwhile.tscc.data.model.BroadcastingStreamState
import com.cloudwhile.tscc.data.model.ChannelPasswordPrompt
import com.cloudwhile.tscc.data.model.ChatMessage
import com.cloudwhile.tscc.data.model.ChatTarget
import com.cloudwhile.tscc.data.model.ConnectionStatus
import com.cloudwhile.tscc.data.model.LastConnectionRequest
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.PokeAlert
import com.cloudwhile.tscc.data.model.ServerSnapshot
import com.cloudwhile.tscc.data.model.ToastTone
import com.cloudwhile.tscc.data.model.UiToastMessage
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.data.model.WatchingStreamState
import com.cloudwhile.tscc.data.update.UpdateUiState
import com.cloudwhile.tscc.data.util.Ts3ServerUri
import com.cloudwhile.tscc.data.util.Ts3UriParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TsccApp
    val repository = app.tsccRepository
    val updateManager = app.appUpdateManager
    private val settingsRepo = app.settingsRepository

    val updateState: StateFlow<UpdateUiState> = updateManager.state

    val bookmarks: StateFlow<List<BookmarkEntity>> = repository.bookmarksFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val identities: StateFlow<List<IdentityEntity>> = repository.identitiesFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    val userAudioPrefs: StateFlow<Map<String, UserAudioPrefEntity>> = repository.userAudioPrefsFlow
    val settings: StateFlow<AppSettings> = repository.appSettings
    val hasMicPermission: StateFlow<Boolean> = repository.hasMicPermission
    val availableAudioRoutes: StateFlow<List<AudioRoute>> = repository.availableAudioRoutes
    val activeAudioRoute: StateFlow<AudioRoute> = repository.activeAudioRoute

    val connectionStatus: StateFlow<ConnectionStatus> = repository.connectionStatus
    val statusMessage: StateFlow<String?> = repository.statusMessage
    val activeBookmarkId: StateFlow<Long?> = repository.activeBookmarkId
    val lastConnectionRequest: StateFlow<LastConnectionRequest?> = repository.lastConnectionRequest
    val manualDisconnectSeq: StateFlow<Long> = repository.manualDisconnectSeq
    val passwordPrompt: StateFlow<ChannelPasswordPrompt?> = repository.passwordPrompt
    val toastEvents: SharedFlow<UiToastMessage> = repository.toastEvents

    val serverSnapshot: StateFlow<ServerSnapshot> = repository.serverSnapshot
    val chatMessages: StateFlow<List<ChatMessage>> = repository.chatMessages
    val unreadChatCounts: StateFlow<Map<ChatTarget, Int>> = repository.unreadChatCounts
    val pokeQueue: StateFlow<List<PokeAlert>> = repository.pokeQueue
    val speakingClientIds: StateFlow<Set<Int>> = repository.speakingClientIds
    val inputLevelDb: StateFlow<Float> = repository.inputLevelDb
    val isVoiceTransmitting: StateFlow<Boolean> = repository.isVoiceTransmitting
    val isSelfMicMuted: StateFlow<Boolean> = repository.isSelfMicMuted
    val isSelfOutputMuted: StateFlow<Boolean> = repository.isSelfOutputMuted
    val isSelfAway: StateFlow<Boolean> = repository.isSelfAway
    val isPttPressed: StateFlow<Boolean> = repository.isPttPressed
    val isLoopbackTesting: StateFlow<Boolean> = repository.isLoopbackTesting
    val securityUpgradeProgress: StateFlow<Pair<Int, Long>?> = repository.securityUpgradeProgress
    val iconCachePaths: StateFlow<Map<String, String>> = repository.iconCachePaths

    val watchingStreamState: StateFlow<WatchingStreamState> = repository.liveStreamManager.watchingState
    val remoteVideoTrack: StateFlow<VideoTrack?> = repository.liveStreamManager.remoteVideoTrack
    val broadcastingStreamState: StateFlow<BroadcastingStreamState> = repository.liveStreamManager.broadcastingState

    fun eglContext(): EglBase.Context = repository.liveStreamManager.ensureEglContext()

    private val _collapsedChannelIds = MutableStateFlow<Set<Long>>(emptySet())
    val collapsedChannelIds: StateFlow<Set<Long>> = _collapsedChannelIds.asStateFlow()

    private val _selectedBookmarkId = MutableStateFlow<Long?>(null)
    val selectedBookmarkId: StateFlow<Long?> = _selectedBookmarkId.asStateFlow()

    private val _activeDmPeer = MutableStateFlow<OnlineClient?>(null)
    val activeDmPeer: StateFlow<OnlineClient?> = _activeDmPeer.asStateFlow()

    private val _pendingDeepLink = MutableStateFlow<Ts3ServerUri?>(null)
    val pendingDeepLink: StateFlow<Ts3ServerUri?> = _pendingDeepLink.asStateFlow()

    private val _micPermissionRequestSeq = MutableStateFlow(0L)
    val micPermissionRequestSeq: StateFlow<Long> = _micPermissionRequestSeq.asStateFlow()

    fun refreshMicPermission(): Boolean = repository.refreshMicPermission()

    fun requestMicPermissionFlow() {
        if (!repository.refreshMicPermission()) {
            _micPermissionRequestSeq.update { it + 1L }
        }
    }

    fun showToast(msg: String, tone: ToastTone = ToastTone.SUCCESS) {
        repository.emitToast(msg, tone)
    }

    fun handleIncomingDeepLink(rawUri: String) {
        val parsed = Ts3UriParser.parse(rawUri)
        if (parsed != null) {
            _pendingDeepLink.value = parsed
        } else {
            val en = repository.isEnglish()
            showToast(
                if (en) "Unrecognized server link: $rawUri" else "无法识别的服务器链接: $rawUri",
                ToastTone.ERROR
            )
        }
    }

    fun consumePendingDeepLink() {
        _pendingDeepLink.value = null
    }

    fun toggleChannelCollapsed(channelId: Long) {
        _collapsedChannelIds.update { set ->
            if (channelId in set) set - channelId else set + channelId
        }
    }

    fun openDirectMessageWith(client: OnlineClient) {
        _activeDmPeer.value = client
    }

    // --- Bookmarks ---

    fun selectBookmark(id: Long) {
        _selectedBookmarkId.value = id
    }

    fun saveBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            val insertedId = repository.upsertBookmark(bookmark)
            val effectiveId = if (bookmark.id != 0L) bookmark.id else insertedId
            _selectedBookmarkId.value = effectiveId
            val en = repository.isEnglish()
            showToast(if (en) "Saved bookmark \"${bookmark.label}\"" else "书签「${bookmark.label}」已保存")
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmark)
            if (_selectedBookmarkId.value == bookmark.id) {
                _selectedBookmarkId.value = null
            }
            val en = repository.isEnglish()
            showToast(if (en) "Deleted bookmark \"${bookmark.label}\"" else "已删除书签「${bookmark.label}」")
        }
    }

    fun connectBookmark(bookmark: BookmarkEntity) {
        repository.connectToBookmark(bookmark)
    }

    // --- Identities ---

    fun createIdentity(name: String) {
        viewModelScope.launch {
            val en = repository.isEnglish()
            repository.createNewIdentity(name)
                .onSuccess {
                    showToast(
                        if (en) {
                            "Created identity \"${it.name}\" (Lv.${it.securityLevel})"
                        } else {
                            "已创建身份「${it.name}」(Lv.${it.securityLevel})"
                        }
                    )
                }
                .onFailure {
                    showToast(
                        if (en) "Failed to create identity: ${it.message}" else "创建身份失败: ${it.message}",
                        ToastTone.ERROR
                    )
                }
        }
    }

    fun importIdentity(name: String, rawIniOrKey: String, makeDefault: Boolean = false) {
        viewModelScope.launch {
            val en = repository.isEnglish()
            repository.importIdentityIni(name, rawIniOrKey, makeDefault = makeDefault)
                .onSuccess {
                    showToast(
                        if (en) {
                            "Imported identity \"${it.name}\" (Lv.${it.securityLevel})"
                        } else {
                            "成功导入身份「${it.name}」(Lv.${it.securityLevel})"
                        }
                    )
                }
                .onFailure {
                    showToast(
                        if (en) "Import failed: ${it.message}" else "导入失败: ${it.message}",
                        ToastTone.ERROR
                    )
                }
        }
    }

    fun upgradeIdentity(identity: IdentityEntity, targetLevel: Int) {
        viewModelScope.launch {
            val en = repository.isEnglish()
            repository.upgradeIdentitySecurity(identity, targetLevel)
                .onSuccess { outcome ->
                    when {
                        !outcome.cancelled -> showToast(
                            if (en) {
                                "Security level upgraded to Lv.${outcome.entity.securityLevel}"
                            } else {
                                "安全等级已提升至 Lv.${outcome.entity.securityLevel}"
                            }
                        )
                        outcome.levelIncreased -> showToast(
                            if (en) {
                                "Paused upgrade — saved progress at Lv.${outcome.entity.securityLevel}"
                            } else {
                                "已停止计算，进度已保存至 Lv.${outcome.entity.securityLevel}"
                            },
                            ToastTone.WARNING
                        )
                        else -> showToast(
                            if (en) "Security upgrade cancelled" else "已取消安全等级提升",
                            ToastTone.WARNING
                        )
                    }
                }
                .onFailure {
                    showToast(
                        if (en) "Security upgrade failed: ${it.message}" else "安全等级提升失败: ${it.message}",
                        ToastTone.ERROR
                    )
                }
        }
    }

    fun cancelIdentityUpgrade() {
        repository.cancelSecurityUpgrade()
    }

    fun setDefaultIdentity(id: Long) {
        viewModelScope.launch {
            repository.setDefaultIdentity(id)
        }
    }

    fun deleteIdentity(identity: IdentityEntity) {
        viewModelScope.launch {
            repository.deleteIdentity(identity)
            val en = repository.isEnglish()
            showToast(if (en) "Deleted identity \"${identity.name}\"" else "已删除身份「${identity.name}」")
        }
    }

    // --- Settings, DSP, Language & Full Data Reset ---

    fun updateDefaultNickname(nickname: String) {
        viewModelScope.launch { settingsRepo.setDefaultNickname(nickname) }
    }

    fun updateVoiceMode(mode: VoiceMode) {
        viewModelScope.launch { settingsRepo.setVoiceMode(mode) }
    }

    fun updateVadThreshold(db: Float) {
        viewModelScope.launch { settingsRepo.setVadThresholdDb(db) }
    }

    fun updateAntiPlosive(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAntiPlosiveEnabled(enabled) }
    }

    fun updateSoundCues(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSoundCuesEnabled(enabled) }
    }

    fun updateHardwareAudio(aec: Boolean, ns: Boolean, agc: Boolean) {
        viewModelScope.launch { settingsRepo.setHardwareAudioProcessing(aec, ns, agc) }
    }

    fun updateOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setOverlayEnabled(enabled) }
    }

    fun updateDarkTheme(dark: Boolean) {
        viewModelScope.launch { settingsRepo.setDarkTheme(dark) }
    }

    fun updateAppLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepo.setAppLanguage(language) }
    }

    fun updateAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoCheckUpdates(enabled) }
    }

    fun completeOnboarding() {
        viewModelScope.launch { settingsRepo.setOnboardingCompleted(true) }
    }

    fun checkForUpdates(manual: Boolean) {
        val curSettings = settings.value
        val en = repository.isEnglish()
        updateManager.checkForUpdates(
            manual = manual,
            skippedVersion = curSettings.skippedUpdateVersion,
            isEnglish = en
        ) { msg, isError ->
            showToast(msg, if (isError) ToastTone.ERROR else ToastTone.SUCCESS)
        }
    }

    fun skipAvailableUpdate() {
        val ver = updateState.value.latestRelease?.versionName ?: return
        viewModelScope.launch {
            settingsRepo.setSkippedUpdateVersion(ver)
            updateManager.dismissDialog()
            val en = repository.isEnglish()
            showToast(if (en) "Skipped update v$ver" else "已忽略 v$ver 更新提醒")
        }
    }

    fun dismissUpdateDialog() {
        updateManager.dismissDialog()
    }

    fun cancelUpdateDownload() {
        updateManager.cancelDownload()
    }

    fun clearAllAppData() {
        viewModelScope.launch {
            val en = repository.isEnglish()
            repository.clearAllAppData()
                .onSuccess {
                    _selectedBookmarkId.value = null
                    _collapsedChannelIds.value = emptySet()
                    _activeDmPeer.value = null
                    showToast(
                        if (en) {
                            "Cleared all app data and restored defaults"
                        } else {
                            "已清除应用全部数据并恢复初始状态"
                        }
                    )
                }
                .onFailure {
                    showToast(
                        if (en) "Failed to clear data: ${it.message}" else "清除数据失败: ${it.message}",
                        ToastTone.ERROR
                    )
                }
        }
    }

    fun selectAudioRoute(route: AudioRoute) {
        repository.selectAudioRoute(route)
    }
}
