package com.cloudwhile.tscc.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.cloudwhile.tscc.BuildConfig
import com.cloudwhile.tscc.TsccApp
import com.cloudwhile.tscc.data.backup.TsccBackupBookmark
import com.cloudwhile.tscc.data.backup.TsccBackupIdentity
import com.cloudwhile.tscc.data.backup.TsccBackupSettings
import com.cloudwhile.tscc.data.backup.TsccBackupUserAudioPref
import com.cloudwhile.tscc.data.backup.TsccConfigBackupCodec
import com.cloudwhile.tscc.data.backup.TsccConfigImportSummary
import com.cloudwhile.tscc.data.backup.TsccConfigPayload
import com.cloudwhile.tscc.data.db.AppDatabase
import com.cloudwhile.tscc.data.db.BookmarkEntity
import com.cloudwhile.tscc.data.db.IdentityEntity
import com.cloudwhile.tscc.data.db.UserAudioPrefEntity
import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.AudioRoute
import com.cloudwhile.tscc.data.model.ChannelLifecycle
import com.cloudwhile.tscc.data.model.ChannelNode
import com.cloudwhile.tscc.data.model.ChannelPasswordPrompt
import com.cloudwhile.tscc.data.model.ChatMessage
import com.cloudwhile.tscc.data.model.ChatTarget
import com.cloudwhile.tscc.data.model.ConnectionStatus
import com.cloudwhile.tscc.data.model.CoreEventEnvelope
import com.cloudwhile.tscc.data.model.LastConnectionRequest
import com.cloudwhile.tscc.data.model.PasswordEditMode
import com.cloudwhile.tscc.data.model.PokeAlert
import com.cloudwhile.tscc.data.model.ServerSnapshot
import com.cloudwhile.tscc.data.model.ToastAction
import com.cloudwhile.tscc.data.model.ToastTone
import com.cloudwhile.tscc.data.model.UiToastMessage
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.data.util.SecretCrypto
import com.cloudwhile.tscc.data.util.TsIconCodec
import com.cloudwhile.tscc.service.AudioRouteController
import com.cloudwhile.tscc.service.SoundCuePlayer
import com.cloudwhile.tscc.service.SoundCueType
import com.cloudwhile.tscc.service.Ts6LiveStreamManager
import com.cloudwhile.tscc.service.VoiceCallService
import com.cloudwhile.tscc.ui.i18n.resolveAppStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private fun BookmarkEntity.encrypted(): BookmarkEntity = copy(
    serverPassword = SecretCrypto.encrypt(serverPassword),
    channelPassword = SecretCrypto.encrypt(channelPassword)
)

private fun BookmarkEntity.decrypted(): BookmarkEntity = copy(
    serverPassword = SecretCrypto.decrypt(serverPassword),
    channelPassword = SecretCrypto.decrypt(channelPassword)
)

private fun IdentityEntity.encrypted(): IdentityEntity = copy(
    iniPayload = SecretCrypto.encrypt(iniPayload)
)

private fun IdentityEntity.decrypted(): IdentityEntity = copy(
    iniPayload = SecretCrypto.decrypt(iniPayload)
)

class TsccRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val soundCuePlayer = SoundCuePlayer(context)
    val routeController = AudioRouteController(context)
    val liveStreamManager = Ts6LiveStreamManager(context) { msg, isErr ->
        emitToast(msg, if (isErr) ToastTone.ERROR else ToastTone.WARNING)
    }

    val bookmarksFlow: Flow<List<BookmarkEntity>> = db.bookmarkDao().observeAll().map { list ->
        list.map { it.decrypted() }
    }
    val identitiesFlow: Flow<List<IdentityEntity>> = db.identityDao().observeAll().map { list ->
        list.map { it.decrypted() }
    }
    val userAudioPrefsFlow: StateFlow<Map<String, UserAudioPrefEntity>> =
        db.userAudioPrefDao().observeAll()
            .let { flow ->
                val mapped = MutableStateFlow<Map<String, UserAudioPrefEntity>>(emptyMap())
                repoScope.launch {
                    flow.collect { list ->
                        mapped.value = list.associateBy { it.clientUid }
                    }
                }
                mapped.asStateFlow()
            }

    val appSettings: StateFlow<AppSettings> = settingsRepository.settingsFlow.stateIn(
        repoScope,
        SharingStarted.Eagerly,
        AppSettings()
    )

    val availableAudioRoutes: StateFlow<List<AudioRoute>> = routeController.availableRoutes
    val activeAudioRoute: StateFlow<AudioRoute> = routeController.activeRoute

    private val _hasMicPermission = MutableStateFlow(checkSystemMicPermission())
    val hasMicPermission: StateFlow<Boolean> = _hasMicPermission.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _activeBookmarkId = MutableStateFlow<Long?>(null)
    val activeBookmarkId: StateFlow<Long?> = _activeBookmarkId.asStateFlow()

    private val _lastConnectionRequest = MutableStateFlow<LastConnectionRequest?>(null)
    val lastConnectionRequest: StateFlow<LastConnectionRequest?> = _lastConnectionRequest.asStateFlow()

    private val _wasManualDisconnect = MutableStateFlow(true)
    val wasManualDisconnect: StateFlow<Boolean> = _wasManualDisconnect.asStateFlow()

    private val _manualDisconnectSeq = MutableStateFlow(0L)
    val manualDisconnectSeq: StateFlow<Long> = _manualDisconnectSeq.asStateFlow()

    private val _passwordPrompt = MutableStateFlow<ChannelPasswordPrompt?>(null)
    val passwordPrompt: StateFlow<ChannelPasswordPrompt?> = _passwordPrompt.asStateFlow()

    private val _toastEvents = MutableSharedFlow<UiToastMessage>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val toastEvents: SharedFlow<UiToastMessage> = _toastEvents.asSharedFlow()

    private val _serverSnapshot = MutableStateFlow(ServerSnapshot())
    val serverSnapshot: StateFlow<ServerSnapshot> = _serverSnapshot.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _unreadChatCounts = MutableStateFlow<Map<ChatTarget, Int>>(emptyMap())
    val unreadChatCounts: StateFlow<Map<ChatTarget, Int>> = _unreadChatCounts.asStateFlow()

    private val _activeChatTarget = MutableStateFlow<ChatTarget?>(null)
    val activeChatTarget: StateFlow<ChatTarget?> = _activeChatTarget.asStateFlow()

    private val _pokeQueue = MutableStateFlow<List<PokeAlert>>(emptyList())
    val pokeQueue: StateFlow<List<PokeAlert>> = _pokeQueue.asStateFlow()

    private val _speakingClientIds = MutableStateFlow<Set<Int>>(emptySet())
    val speakingClientIds: StateFlow<Set<Int>> = _speakingClientIds.asStateFlow()

    private val _inputLevelDb = MutableStateFlow(-90f)
    val inputLevelDb: StateFlow<Float> = _inputLevelDb.asStateFlow()

    private val _isVoiceTransmitting = MutableStateFlow(false)
    val isVoiceTransmitting: StateFlow<Boolean> = _isVoiceTransmitting.asStateFlow()

    private val _isSelfMicMuted = MutableStateFlow(false)
    val isSelfMicMuted: StateFlow<Boolean> = _isSelfMicMuted.asStateFlow()

    private val _isSelfOutputMuted = MutableStateFlow(false)
    val isSelfOutputMuted: StateFlow<Boolean> = _isSelfOutputMuted.asStateFlow()

    private val _isSelfAway = MutableStateFlow(false)
    val isSelfAway: StateFlow<Boolean> = _isSelfAway.asStateFlow()

    private val _isPttPressed = MutableStateFlow(false)
    val isPttPressed: StateFlow<Boolean> = _isPttPressed.asStateFlow()

    private val _isLoopbackTesting = MutableStateFlow(false)
    val isLoopbackTesting: StateFlow<Boolean> = _isLoopbackTesting.asStateFlow()

    private val _securityUpgradeProgress = MutableStateFlow<Pair<Int, Long>?>(null)
    val securityUpgradeProgress: StateFlow<Pair<Int, Long>?> = _securityUpgradeProgress.asStateFlow()

    private val _iconCachePaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val iconCachePaths: StateFlow<Map<String, String>> = _iconCachePaths.asStateFlow()

    private val requestedIconKeys = ConcurrentHashMap.newKeySet<String>()
    private val iconDownloadLimiter = Semaphore(2)
    private val sessionChannelPasswords = ConcurrentHashMap<Long, String>()
    private var pendingCreatedChannelPassword: Pair<String, String>? = null
    private var pendingAutoPrivilegeKey: String? = null
    private var micMutedBeforeDeafen: Boolean = false
    private var currentAwayMessage: String = ""
    private var blockedSpeechScore: Int = 0
    private var lastBlockedSpeechHintMs: Long = 0L

    init {
        repoScope.launch {
            NativeBridge.ensureLoaded()
            syncLanguageToNative(appSettings.value)
            ensureDefaultIdentityExists()
        }
        repoScope.launch {
            appSettings.collect { settings ->
                syncLanguageToNative(settings)
                syncVoiceConfigToNative(settings)
            }
        }
        repoScope.launch {
            NativeBridge.events.collect { env ->
                handleCoreEvent(env)
            }
        }
    }

    fun isEnglish(): Boolean = resolveAppStrings(appSettings.value.appLanguage).isEnglish

    private fun syncLanguageToNative(settings: AppSettings) {
        val en = resolveAppStrings(settings.appLanguage).isEnglish
        liveStreamManager.isEnglish = en
        NativeBridge.setLanguage(en)
        (context.applicationContext as? TsccApp)?.syncNotificationChannels(settings.appLanguage)
        repoScope.launch {
            syncBuiltinDefaultIdentityName(en)
        }
    }

    private suspend fun syncBuiltinDefaultIdentityName(isEnglish: Boolean) {
        val def = db.identityDao().getDefault()?.decrypted() ?: return
        val wantName = if (isEnglish) "Default Identity" else "默认身份"
        if ((def.name == "默认身份" || def.name == "Default Identity") && def.name != wantName) {
            db.identityDao().update(def.copy(name = wantName).encrypted())
        }
    }

    private fun checkSystemMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Re-evaluates [Manifest.permission.RECORD_AUDIO] and synchronizes native voice state
     * and active foreground service capture pipeline immediately.
     */
    fun refreshMicPermission(): Boolean {
        val prev = _hasMicPermission.value
        val granted = checkSystemMicPermission()
        _hasMicPermission.value = granted
        if (!granted) {
            _isVoiceTransmitting.value = false
            _isPttPressed.value = false
            _inputLevelDb.value = -90f
            if (_isLoopbackTesting.value) {
                setLoopbackTesting(false)
            }
        }
        if (prev != granted) {
            syncVoiceConfigToNative(appSettings.value)
            if (_connectionStatus.value == ConnectionStatus.CONNECTED ||
                _connectionStatus.value == ConnectionStatus.CONNECTING ||
                _connectionStatus.value == ConnectionStatus.RECONNECTING
            ) {
                pushSelfMuteState()
                if (granted) {
                    val svcIntent = Intent(context, VoiceCallService::class.java).apply {
                        action = VoiceCallService.ACTION_REFRESH_MIC_PERMISSION
                    }
                    runCatching { ContextCompat.startForegroundService(context, svcIntent) }
                }
            }
        }
        return granted
    }

    fun selectAudioRoute(route: AudioRoute) {
        routeController.selectRoute(route)
    }

    fun setActiveChatTarget(target: ChatTarget?) {
        _activeChatTarget.value = target
        if (target != null) {
            clearUnreadChat(target)
        }
    }

    fun emitToast(
        text: String,
        tone: ToastTone = ToastTone.SUCCESS,
        action: ToastAction? = null
    ) {
        if (text.isBlank()) return
        _toastEvents.tryEmit(UiToastMessage(text = text, tone = tone, action = action))
    }

    fun dismissPasswordPrompt() {
        _passwordPrompt.value = null
    }

    private suspend fun ensureDefaultIdentityExists() {
        val existing = db.identityDao().getAll()
        if (existing.isNotEmpty()) return
        if (!NativeBridge.ensureLoaded()) return

        NativeBridge.generateIdentity().onSuccess { info ->
            val defaultName = if (isEnglish()) "Default Identity" else "默认身份"
            db.identityDao().insert(
                IdentityEntity(
                    name = defaultName,
                    uniqueId = info.uniqueId,
                    publicKeyBase64 = info.publicKeyBase64,
                    iniPayload = info.iniContent,
                    securityLevel = info.securityLevel,
                    isDefault = true
                ).encrypted()
            )
        }
    }

    private fun handleCoreEvent(env: CoreEventEnvelope) {
        when (env.kind) {
            "connection_state" -> {
                val prevStatus = _connectionStatus.value
                val nextStatus = when (env.status?.uppercase(Locale.ROOT)) {
                    "CONNECTING" -> ConnectionStatus.CONNECTING
                    "CONNECTED" -> ConnectionStatus.CONNECTED
                    "RECONNECTING" -> ConnectionStatus.RECONNECTING
                    "ERROR" -> ConnectionStatus.ERROR
                    else -> ConnectionStatus.DISCONNECTED
                }
                _connectionStatus.value = nextStatus
                if (!env.reason.isNullOrBlank()) {
                    _statusMessage.value = env.reason
                }
                if (nextStatus == ConnectionStatus.CONNECTED) {
                    _securityUpgradeProgress.value = null
                    applyStoredUserAudioPrefsToNative()
                    if (!_hasMicPermission.value || _isSelfMicMuted.value || _isSelfOutputMuted.value) {
                        pushSelfMuteState()
                    }
                    if (prevStatus != ConnectionStatus.CONNECTED) {
                        playCue(SoundCueType.CONNECTED, withHaptic = true)
                    }
                    pendingAutoPrivilegeKey?.takeIf { it.isNotBlank() }?.let { token ->
                        pendingAutoPrivilegeKey = null
                        usePrivilegeKey(token)
                    }
                } else if (nextStatus == ConnectionStatus.DISCONNECTED || nextStatus == ConnectionStatus.ERROR) {
                    _securityUpgradeProgress.value = null
                    _speakingClientIds.value = emptySet()
                    _isVoiceTransmitting.value = false
                    _isSelfAway.value = false
                    currentAwayMessage = ""
                    blockedSpeechScore = 0
                    lastBlockedSpeechHintMs = 0L
                    requestedIconKeys.clear()
                    liveStreamManager.onServerDisconnected()
                    if (nextStatus == ConnectionStatus.ERROR) {
                        _wasManualDisconnect.value = false
                        _activeBookmarkId.value = null
                        soundCuePlayer.vibrateError()
                        env.reason?.takeIf { it.isNotBlank() }?.let { errReason ->
                            emitToast(errReason, ToastTone.ERROR)
                        }
                        stopVoiceService()
                    } else if (prevStatus == ConnectionStatus.CONNECTED ||
                        prevStatus == ConnectionStatus.RECONNECTING ||
                        prevStatus == ConnectionStatus.CONNECTING
                    ) {
                        if (!_wasManualDisconnect.value) {
                            if (prevStatus == ConnectionStatus.CONNECTED || prevStatus == ConnectionStatus.RECONNECTING) {
                                playCue(SoundCueType.DISCONNECTED, withHaptic = true)
                            }
                            stopVoiceService()
                        }
                        _activeBookmarkId.value = null
                    }
                }
            }

            "snapshot" -> {
                env.snapshot?.let { snap ->
                    val prevChannelId = _serverSnapshot.value.myChannelId
                    _serverSnapshot.value = snap

                    // Seed password for newly created encrypted channel if pending
                    pendingCreatedChannelPassword?.let { (createdName, createdPwd) ->
                        val createdNode = snap.channels.firstOrNull { it.name == createdName && it.hasPassword }
                        if (createdNode != null) {
                            sessionChannelPasswords[createdNode.id] = createdPwd
                            pendingCreatedChannelPassword = null
                        }
                    }

                    snap.clients.firstOrNull { it.id == snap.myClientId }?.let { me ->
                        if (_isSelfAway.value != me.isAway) {
                            _isSelfAway.value = me.isAway
                        }
                        if (me.isAway && me.awayMessage.isNotEmpty()) {
                            currentAwayMessage = me.awayMessage
                        }
                    }
                    if (prevChannelId != snap.myChannelId) {
                        blockedSpeechScore = 0
                        lastBlockedSpeechHintMs = 0L
                    }
                    val selfBlocked = isVoiceCaptureBlocked(snap)
                    if (selfBlocked) {
                        _isVoiceTransmitting.value = false
                    }
                    // Preserve live telemetry speakers; only prune offline/disconnected client IDs
                    val onlineIds = snap.clients.mapTo(HashSet()) { it.id }
                    _speakingClientIds.update { prev ->
                        prev.filterTo(LinkedHashSet()) { cid ->
                            cid in onlineIds && (cid != snap.myClientId || !selfBlocked)
                        }
                    }
                    syncVoiceConfigToNative(appSettings.value)
                    triggerAutoIconDownloads(snap)
                }
            }

            "room_cue" -> {
                when (env.cue) {
                    "self_switch" -> playCue(SoundCueType.SWITCH_CHANNEL, withHaptic = true)
                    "peer_join" -> playCue(SoundCueType.PEER_JOIN, withHaptic = false)
                    "peer_leave" -> playCue(SoundCueType.PEER_LEAVE, withHaptic = false)
                }
            }

            "identity_upgraded" -> {
                val uid = env.uniqueId
                val updatedIni = env.updatedIni
                val newLevel = env.securityLevel
                if (!uid.isNullOrBlank() && !updatedIni.isNullOrBlank() && newLevel != null) {
                    repoScope.launch {
                        val match = db.identityDao().getAll()
                            .map { it.decrypted() }
                            .firstOrNull { it.uniqueId == uid }
                        if (match != null && newLevel > match.securityLevel) {
                            db.identityDao().update(
                                match.copy(
                                    iniPayload = updatedIni,
                                    securityLevel = newLevel
                                ).encrypted()
                            )
                        }
                    }
                }
            }

            "command_result" -> {
                val ok = env.commandOk ?: false
                val msg = env.reason.orEmpty()
                if (ok) {
                    if (msg.isNotBlank()) {
                        emitToast(msg, ToastTone.SUCCESS)
                    }
                } else {
                    soundCuePlayer.vibrateError()
                    val act = env.action.orEmpty()
                    if (act == "开启 TS6 直播" || act == "Start TS6 Live Stream") {
                        liveStreamManager.stopBroadcast()
                    } else if (act == "请求观看直播" || act == "Watch stream") {
                        liveStreamManager.onWatchCommandError(msg)
                    }
                    val isPerm = env.isPermissionError == true
                    val targetChId = env.targetChannelId
                    // TS3 Error 0x030d (781): ChannelInvalidPassword -> evict stale session password & re-prompt
                    if (env.errorCode == 0x030d && targetChId != null && targetChId > 0L) {
                        sessionChannelPasswords.remove(targetChId)
                        val chName = _serverSnapshot.value.channels
                            .firstOrNull { it.id == targetChId }
                            ?.name
                            .orEmpty()
                        _passwordPrompt.value = ChannelPasswordPrompt(
                            channelId = targetChId,
                            channelName = chName.ifBlank { "#$targetChId" },
                            errorHint = msg.ifBlank {
                                if (isEnglish()) "Incorrect channel password" else "频道密码不正确，请重新输入"
                            }
                        )
                    }
                    if (msg.isNotBlank()) {
                        emitToast(
                            text = msg,
                            tone = ToastTone.ERROR,
                            action = if (isPerm) ToastAction.OPEN_PRIVILEGE_KEY else null
                        )
                    }
                }
            }

            "voice_telemetry" -> {
                val selfBlocked = isVoiceCaptureBlocked()
                env.inputDb?.let { dbVal ->
                    _inputLevelDb.value = if (_hasMicPermission.value) dbVal else -90f
                    if (_hasMicPermission.value) {
                        evaluateBlockedSpeechReminder(dbVal)
                    }
                }
                env.voiceTransmitting?.let { tx ->
                    _isVoiceTransmitting.value = if (selfBlocked) false else tx
                }
                env.speakingIds?.let { ids ->
                    val myId = _serverSnapshot.value.myClientId
                    _speakingClientIds.value = if (selfBlocked) {
                        ids.filterNot { it == myId }.toSet()
                    } else {
                        ids.toSet()
                    }
                }
            }

            "chat" -> {
                env.chat?.let { msg ->
                    _chatMessages.update { (it + msg).takeLast(300) }
                    val myId = _serverSnapshot.value.myClientId
                    val isOwnMsg = myId > 0 && msg.senderId == myId
                    val isActivelyViewing = _activeChatTarget.value == msg.target
                    if (!msg.isSystem && !isOwnMsg && !isActivelyViewing) {
                        _unreadChatCounts.update { counts ->
                            counts + (msg.target to ((counts[msg.target] ?: 0) + 1))
                        }
                    }
                }
            }

            "poke" -> {
                env.poke?.let { poke ->
                    _pokeQueue.update { it + poke }
                }
            }

            "security_progress" -> {
                val lvl = env.securityLevel ?: 0
                val offset = env.keyOffset ?: 0L
                _securityUpgradeProgress.value = lvl to offset
            }

            "file_downloaded" -> {
                val key = env.fileKey
                val path = env.localPath
                if (!key.isNullOrBlank() && !path.isNullOrBlank()) {
                    repoScope.launch {
                        if (key.startsWith("icon_")) {
                            TsIconCodec.normalizeIconFileInPlace(File(path))
                        }
                        _iconCachePaths.update { it + (key to path) }
                    }
                }
            }

            "ts6_stream" -> {
                env.streamSignal?.let { sig ->
                    liveStreamManager.onTs6StreamSignal(sig, _serverSnapshot.value.myClientId)
                }
            }

            "error" -> {
                if (!env.reason.isNullOrBlank()) {
                    _statusMessage.value = env.reason
                    soundCuePlayer.vibrateError()
                    emitToast(env.reason, ToastTone.ERROR)
                }
            }
        }
    }

    private fun playCue(type: SoundCueType, withHaptic: Boolean) {
        val settings = appSettings.value
        soundCuePlayer.playCue(
            type = type,
            soundEnabled = settings.soundCuesEnabled,
            outputDeafened = _isSelfOutputMuted.value,
            withHaptic = withHaptic
        )
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun clearUnreadChat(target: ChatTarget) {
        _unreadChatCounts.update { it + (target to 0) }
    }

    fun dismissPoke(pokeId: String) {
        _pokeQueue.update { list -> list.filterNot { it.id == pokeId } }
    }

    // --- Identities ---

    suspend fun createNewIdentity(name: String): Result<IdentityEntity> = withContext(Dispatchers.IO) {
        NativeBridge.generateIdentity().map { info ->
            val hasAny = db.identityDao().getAll().isNotEmpty()
            val fallbackName = if (isEnglish()) "New Identity" else "新身份"
            val plainEntity = IdentityEntity(
                name = name.trim().ifBlank { fallbackName },
                uniqueId = info.uniqueId,
                publicKeyBase64 = info.publicKeyBase64,
                iniPayload = info.iniContent,
                securityLevel = info.securityLevel,
                isDefault = !hasAny
            )
            val id = db.identityDao().insert(plainEntity.encrypted())
            plainEntity.copy(id = id)
        }
    }

    suspend fun importIdentityIni(
        name: String,
        rawIniOrKey: String,
        makeDefault: Boolean = false
    ): Result<IdentityEntity> =
        withContext(Dispatchers.IO) {
            NativeBridge.inspectIdentityIni(rawIniOrKey).map { info ->
                val hasAny = db.identityDao().getAll().isNotEmpty()
                val shouldBeDefault = !hasAny || makeDefault
                val fallbackName = if (isEnglish()) {
                    "Imported (${info.uniqueId.take(6)})"
                } else {
                    "导入身份 (${info.uniqueId.take(6)})"
                }
                val plainEntity = IdentityEntity(
                    name = name.trim().ifBlank { fallbackName },
                    uniqueId = info.uniqueId,
                    publicKeyBase64 = info.publicKeyBase64,
                    iniPayload = info.iniContent,
                    securityLevel = info.securityLevel,
                    isDefault = shouldBeDefault
                )
                val id = db.identityDao().insert(plainEntity.encrypted())
                if (makeDefault && hasAny) {
                    db.identityDao().setDefaultIdentity(id)
                }
                plainEntity.copy(id = id, isDefault = shouldBeDefault)
            }
        }

    data class IdentityUpgradeOutcome(
        val entity: IdentityEntity,
        val cancelled: Boolean,
        val levelIncreased: Boolean
    )

    suspend fun upgradeIdentitySecurity(
        identity: IdentityEntity,
        targetLevel: Int
    ): Result<IdentityUpgradeOutcome> =
        withContext(Dispatchers.IO) {
            _securityUpgradeProgress.value = identity.securityLevel to 0L
            try {
                val plainIni = SecretCrypto.decrypt(identity.iniPayload)
                NativeBridge.upgradeSecurityLevel(plainIni, targetLevel).map { updated ->
                    val improved = updated.securityLevel > identity.securityLevel
                    val nextPlain = if (improved) {
                        identity.copy(
                            iniPayload = updated.iniContent,
                            securityLevel = updated.securityLevel
                        )
                    } else {
                        identity.copy(iniPayload = plainIni)
                    }
                    if (improved) {
                        db.identityDao().update(nextPlain.encrypted())
                    }
                    IdentityUpgradeOutcome(
                        entity = nextPlain,
                        cancelled = updated.cancelled,
                        levelIncreased = improved
                    )
                }
            } finally {
                _securityUpgradeProgress.value = null
            }
        }

    fun cancelSecurityUpgrade() {
        if (NativeBridge.ensureLoaded()) {
            NativeBridge.nativeCancelSecurityUpgrade()
        }
    }

    suspend fun setDefaultIdentity(id: Long) {
        db.identityDao().setDefaultIdentity(id)
    }

    suspend fun deleteIdentity(identity: IdentityEntity) {
        db.identityDao().delete(identity)
        if (identity.isDefault) {
            db.identityDao().getAll().firstOrNull()?.let { first ->
                db.identityDao().setDefaultIdentity(first.id)
            }
        }
    }

    // --- Bookmarks ---

    suspend fun upsertBookmark(bookmark: BookmarkEntity): Long =
        db.bookmarkDao().upsert(bookmark.encrypted())

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = db.bookmarkDao().delete(bookmark)

    // --- Server Connection & Reconnect ---

    fun connectToBookmark(bookmark: BookmarkEntity) {
        val plainBm = bookmark.decrypted()
        if (_activeBookmarkId.value == plainBm.id &&
            (_connectionStatus.value == ConnectionStatus.CONNECTED ||
                _connectionStatus.value == ConnectionStatus.CONNECTING)
        ) {
            return
        }
        repoScope.launch {
            db.bookmarkDao().markConnected(plainBm.id)
            connectInternal(
                LastConnectionRequest(
                    bookmarkId = plainBm.id,
                    label = plainBm.label,
                    address = plainBm.address,
                    port = plainBm.port,
                    nickname = plainBm.nickname,
                    serverPassword = plainBm.serverPassword,
                    defaultChannel = plainBm.defaultChannel,
                    channelPassword = plainBm.channelPassword,
                    identityId = plainBm.identityId,
                    privilegeKey = null
                )
            )
        }
    }

    fun connectDirect(
        address: String,
        port: Int = 9987,
        nickname: String,
        serverPassword: String = "",
        defaultChannel: String = "",
        channelPassword: String = "",
        identityId: Long? = null,
        privilegeKey: String? = null
    ) {
        repoScope.launch {
            connectInternal(
                LastConnectionRequest(
                    bookmarkId = null,
                    label = address,
                    address = address,
                    port = port,
                    nickname = nickname,
                    serverPassword = serverPassword,
                    defaultChannel = defaultChannel,
                    channelPassword = channelPassword,
                    identityId = identityId,
                    privilegeKey = privilegeKey
                )
            )
        }
    }

    fun reconnectLast(overrideServerPassword: String? = null) {
        val last = _lastConnectionRequest.value ?: return
        repoScope.launch {
            val effectiveReq = if (overrideServerPassword != null) {
                if (last.bookmarkId != null) {
                    db.bookmarkDao().getById(last.bookmarkId)?.decrypted()?.let { bm ->
                        db.bookmarkDao().update(bm.copy(serverPassword = overrideServerPassword).encrypted())
                    }
                }
                last.copy(serverPassword = overrideServerPassword)
            } else {
                last
            }
            connectInternal(effectiveReq)
        }
    }

    private suspend fun connectInternal(req: LastConnectionRequest) {
        val en = isEnglish()
        if (!NativeBridge.ensureLoaded()) {
            _connectionStatus.value = ConnectionStatus.ERROR
            _statusMessage.value = if (en) {
                "Failed to load native TS protocol engine (libtscc_core.so)"
            } else {
                "未能加载原生 TS 协议引擎 (libtscc_core.so)"
            }
            return
        }

        refreshMicPermission()
        syncLanguageToNative(appSettings.value)
        ensureDefaultIdentityExists()
        val identity = ((req.identityId?.let { db.identityDao().getById(it) })
            ?: db.identityDao().getDefault()
            ?: db.identityDao().getAll().firstOrNull())
            ?.decrypted()

        if (identity == null) {
            _connectionStatus.value = ConnectionStatus.ERROR
            _statusMessage.value = if (en) {
                "No identity key found. Please generate one in Settings first."
            } else {
                "未找到可用身份密钥，请先在设置中生成"
            }
            return
        }

        _wasManualDisconnect.value = false
        _lastConnectionRequest.value = req
        _activeBookmarkId.value = req.bookmarkId
        _statusMessage.value = null
        _passwordPrompt.value = null
        sessionChannelPasswords.clear()
        pendingCreatedChannelPassword = null
        _isLoopbackTesting.value = false
        _isSelfAway.value = false
        currentAwayMessage = ""
        blockedSpeechScore = 0
        lastBlockedSpeechHintMs = 0L

        _chatMessages.value = listOf(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                target = ChatTarget.SERVER,
                senderName = if (en) "System" else "系统",
                content = if (en) {
                    "Connecting to [b]${req.address}:${req.port}[/b] (Security Lv.${identity.securityLevel})..."
                } else {
                    "正在连接 [b]${req.address}:${req.port}[/b]（安全等级 Lv.${identity.securityLevel}）..."
                },
                timestampMs = System.currentTimeMillis(),
                isSystem = true
            )
        )
        _unreadChatCounts.value = emptyMap()
        _connectionStatus.value = ConnectionStatus.CONNECTING
        pendingAutoPrivilegeKey = req.privilegeKey

        val dnsCsv = getActiveSystemDnsCsv()
        syncVoiceConfigToNative(appSettings.value)

        val started = NativeBridge.nativeConnect(
            address = req.address.trim(),
            port = req.port,
            nickname = req.nickname.trim().ifBlank { appSettings.value.defaultNickname },
            identityIni = identity.iniPayload,
            serverPassword = req.serverPassword,
            defaultChannel = req.defaultChannel,
            channelPassword = req.channelPassword,
            dnsServersCsv = dnsCsv
        )

        if (started) {
            val svcIntent = Intent(context, VoiceCallService::class.java).apply {
                action = VoiceCallService.ACTION_START_CALL
            }
            runCatching {
                ContextCompat.startForegroundService(context, svcIntent)
            }
        } else {
            _activeBookmarkId.value = null
            _connectionStatus.value = ConnectionStatus.ERROR
            _statusMessage.value = if (en) "Failed to dispatch connection request" else "启动连接请求失败"
        }
    }

    fun disconnect(reason: String = if (isEnglish()) "Disconnected" else "主动断开连接", manual: Boolean = true) {
        val wasActive = _connectionStatus.value != ConnectionStatus.DISCONNECTED
        _wasManualDisconnect.value = manual
        _activeBookmarkId.value = null
        _passwordPrompt.value = null
        _securityUpgradeProgress.value = null
        sessionChannelPasswords.clear()
        pendingCreatedChannelPassword = null
        liveStreamManager.onServerDisconnected()

        if (NativeBridge.ensureLoaded()) {
            NativeBridge.nativeDisconnect(reason)
        }
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _serverSnapshot.value = ServerSnapshot()
        _speakingClientIds.value = emptySet()
        _isVoiceTransmitting.value = false
        _isSelfAway.value = false
        currentAwayMessage = ""
        blockedSpeechScore = 0
        lastBlockedSpeechHintMs = 0L

        if (wasActive) {
            playCue(SoundCueType.DISCONNECTED, withHaptic = true)
        }
        if (manual) {
            _manualDisconnectSeq.update { it + 1L }
        }
        stopVoiceService()
    }

    /**
     * Synchronously flushes the encrypted `clientdisconnect` UDP packet to the OS socket and waits
     * briefly (up to [timeoutMs]) for the server ACK before process teardown when the user swipes
     * TSCC away from Recent Tasks, preventing ghost sessions stuck until UDP timeout.
     */
    fun disconnectForAppTermination(
        reason: String = if (isEnglish()) "App closed" else "清理后台退出",
        timeoutMs: Long = 350L
    ) {
        val wasConnectedOrConnecting = _connectionStatus.value != ConnectionStatus.DISCONNECTED
        val wasLoopback = _isLoopbackTesting.value
        if (!wasConnectedOrConnecting && !wasLoopback) return

        _wasManualDisconnect.value = true
        _activeBookmarkId.value = null
        _passwordPrompt.value = null
        _securityUpgradeProgress.value = null
        _isLoopbackTesting.value = false
        sessionChannelPasswords.clear()
        pendingCreatedChannelPassword = null
        liveStreamManager.onServerDisconnected()

        if (wasConnectedOrConnecting && NativeBridge.isNativeLoaded) {
            NativeBridge.nativeCancelSecurityUpgrade()
            NativeBridge.nativeDisconnectBlocking(reason, timeoutMs)
        }

        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _serverSnapshot.value = ServerSnapshot()
        _speakingClientIds.value = emptySet()
        _isVoiceTransmitting.value = false
        _isSelfAway.value = false
        currentAwayMessage = ""
        blockedSpeechScore = 0
        lastBlockedSpeechHintMs = 0L
    }

    private fun stopVoiceService() {
        val stopIntent = Intent(context, VoiceCallService::class.java).apply {
            action = VoiceCallService.ACTION_STOP_CALL
        }
        runCatching { context.startService(stopIntent) }
            .onFailure { runCatching { context.stopService(Intent(context, VoiceCallService::class.java)) } }
    }

    // --- Channel Joining & Session Password Cache ---

    fun requestJoinChannel(channel: ChannelNode, explicitPassword: String? = null) {
        if (channel.id == _serverSnapshot.value.myChannelId) return

        if (explicitPassword != null) {
            if (explicitPassword.isNotEmpty()) {
                sessionChannelPasswords[channel.id] = explicitPassword
            }
            _passwordPrompt.value = null
            switchChannel(channel.id, explicitPassword)
            return
        }

        if (!channel.hasPassword) {
            switchChannel(channel.id, "")
            return
        }

        val cachedPwd = sessionChannelPasswords[channel.id]
        if (!cachedPwd.isNullOrEmpty()) {
            switchChannel(channel.id, cachedPwd)
        } else {
            _passwordPrompt.value = ChannelPasswordPrompt(
                channelId = channel.id,
                channelName = channel.name,
                errorHint = null
            )
        }
    }

    fun switchChannel(channelId: Long, password: String = "") {
        if (!NativeBridge.ensureLoaded()) return
        if (password.isNotEmpty()) {
            sessionChannelPasswords[channelId] = password
        }
        repoScope.launch {
            NativeBridge.nativeSwitchChannel(channelId, password)
        }
    }

    fun sendChatMessage(target: ChatTarget, targetId: Long, message: String) {
        val clean = message.trim()
        if (clean.isEmpty() || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeSendMessage(target.tsMode, targetId, clean)
        }
    }

    // --- Voice State & Per-User Mixer ---

    fun toggleSelfMicMute() {
        if (!refreshMicPermission()) return
        val next = !_isSelfMicMuted.value
        _isSelfMicMuted.value = next
        if (!next && _isSelfOutputMuted.value) {
            _isSelfOutputMuted.value = false
            liveStreamManager.setGlobalOutputDeafened(false)
        }
        blockedSpeechScore = 0
        pushSelfMuteState()
        syncVoiceConfigToNative(appSettings.value)
    }

    fun toggleSelfOutputDeafen() {
        val next = !_isSelfOutputMuted.value
        _isSelfOutputMuted.value = next
        liveStreamManager.setGlobalOutputDeafened(next)
        if (next) {
            micMutedBeforeDeafen = _isSelfMicMuted.value
            _isSelfMicMuted.value = true
        } else {
            _isSelfMicMuted.value = micMutedBeforeDeafen
        }
        blockedSpeechScore = 0
        pushSelfMuteState()
        syncVoiceConfigToNative(appSettings.value)
    }

    fun setSelfAway(away: Boolean, awayMessage: String = "") {
        _isSelfAway.value = away
        currentAwayMessage = if (away) awayMessage else ""
        blockedSpeechScore = 0
        if (!away) {
            lastBlockedSpeechHintMs = 0L
        } else {
            _isVoiceTransmitting.value = false
        }
        pushSelfMuteState()
        syncVoiceConfigToNative(appSettings.value)
    }

    private fun pushSelfMuteState() {
        if (NativeBridge.ensureLoaded()) {
            val effectiveMicMuted = _isSelfMicMuted.value || !_hasMicPermission.value
            NativeBridge.nativeSetSelfMuteState(
                effectiveMicMuted,
                _isSelfOutputMuted.value,
                _isSelfAway.value,
                if (_isSelfAway.value) currentAwayMessage else ""
            )
        }
    }

    fun setPttPressed(pressed: Boolean) {
        val allowed = pressed && _hasMicPermission.value
        if (_isPttPressed.value == allowed) return
        _isPttPressed.value = allowed
        syncVoiceConfigToNative(appSettings.value)
    }

    fun setLoopbackTesting(enabled: Boolean) {
        if (enabled && !refreshMicPermission()) {
            _isLoopbackTesting.value = false
            return
        }
        if (_isLoopbackTesting.value == enabled) return
        _isLoopbackTesting.value = enabled
        syncVoiceConfigToNative(appSettings.value)
        val svcIntent = Intent(context, VoiceCallService::class.java).apply {
            action = if (enabled) {
                VoiceCallService.ACTION_START_LOOPBACK
            } else if (_connectionStatus.value == ConnectionStatus.DISCONNECTED) {
                VoiceCallService.ACTION_STOP_CALL
            } else {
                VoiceCallService.ACTION_START_CALL
            }
        }
        runCatching {
            if (enabled || _connectionStatus.value != ConnectionStatus.DISCONNECTED) {
                ContextCompat.startForegroundService(context, svcIntent)
            } else {
                context.startService(svcIntent)
            }
        }
    }

    fun isSelfTalkPowerRestricted(snap: ServerSnapshot = _serverSnapshot.value): Boolean {
        val me = snap.clients.firstOrNull { it.id == snap.myClientId } ?: return false
        val ch = snap.channels.firstOrNull { it.id == snap.myChannelId } ?: return false
        return ch.neededTalkPower > 0 && me.talkPower < ch.neededTalkPower && !me.talkPowerGranted
    }

    fun isEffectiveSelfAway(snap: ServerSnapshot = _serverSnapshot.value): Boolean {
        val me = snap.clients.firstOrNull { it.id == snap.myClientId }
        return _isSelfAway.value || me?.isAway == true
    }

    private fun isVoiceCaptureBlocked(snap: ServerSnapshot = _serverSnapshot.value): Boolean {
        if (!_hasMicPermission.value) return true
        return !_isLoopbackTesting.value && (
            _isSelfMicMuted.value ||
                _isSelfOutputMuted.value ||
                isEffectiveSelfAway(snap) ||
                isSelfTalkPowerRestricted(snap)
            )
    }

    private fun evaluateBlockedSpeechReminder(inputDb: Float) {
        if (_connectionStatus.value != ConnectionStatus.CONNECTED ||
            _isLoopbackTesting.value ||
            !_hasMicPermission.value ||
            _isSelfOutputMuted.value
        ) {
            blockedSpeechScore = 0
            return
        }
        val snap = _serverSnapshot.value
        val micMuted = _isSelfMicMuted.value
        val away = isEffectiveSelfAway(snap)
        val talkRestricted = isSelfTalkPowerRestricted(snap)
        if (!micMuted && !away && !talkRestricted) {
            blockedSpeechScore = 0
            return
        }

        val speechGateDb = maxOf(appSettings.value.vadThresholdDb, -46f)
        if (inputDb >= speechGateDb) {
            blockedSpeechScore = (blockedSpeechScore + 2).coerceAtMost(30)
        } else {
            blockedSpeechScore = (blockedSpeechScore - 1).coerceAtLeast(0)
        }

        if (blockedSpeechScore >= 18) {
            val now = System.currentTimeMillis()
            if (now - lastBlockedSpeechHintMs >= 25_000L) {
                blockedSpeechScore = 0
                lastBlockedSpeechHintMs = now
                val isEn = isEnglish()
                when {
                    micMuted -> {
                        emitToast(
                            text = if (isEn) {
                                "Speech detected while your microphone is muted"
                            } else {
                                "检测到麦克风输入：当前处于闭麦状态，队友听不到你的声音"
                            },
                            tone = ToastTone.WARNING,
                            action = ToastAction.UNMUTE_MIC
                        )
                    }
                    away -> {
                        emitToast(
                            text = if (isEn) {
                                "Mic input detected while Away (AFK) — others cannot hear you"
                            } else {
                                "检测到麦克风持续输入：当前处于离开 (AFK) 状态，队友听不到你的声音"
                            },
                            tone = ToastTone.WARNING,
                            action = ToastAction.EXIT_AFK
                        )
                    }
                    talkRestricted -> {
                        val needed = snap.channels.firstOrNull { it.id == snap.myChannelId }?.neededTalkPower ?: 0
                        val have = snap.clients.firstOrNull { it.id == snap.myClientId }?.talkPower ?: 0
                        emitToast(
                            text = if (isEn) {
                                "Mic input detected while muted in channel (Talk Power $have/$needed)"
                            } else {
                                "检测到麦克风持续输入：当前处于禁言状态（需发言权 ≥ $needed，当前 $have）"
                            },
                            tone = ToastTone.WARNING,
                            action = ToastAction.REQUEST_TALK_POWER
                        )
                    }
                }
            }
        }
    }

    private fun syncVoiceConfigToNative(settings: AppSettings) {
        if (!NativeBridge.ensureLoaded()) return
        val captureBlocked = isVoiceCaptureBlocked()
        if (captureBlocked) {
            _isVoiceTransmitting.value = false
        }
        val effectiveMode = if (captureBlocked) {
            VoiceMode.PTT.code
        } else {
            settings.voiceMode.code
        }
        val effectivePtt = !captureBlocked && _isPttPressed.value
        NativeBridge.nativeConfigureVoice(
            mode = effectiveMode,
            vadThresholdDb = settings.vadThresholdDb,
            pttActive = effectivePtt,
            loopbackEnabled = _isLoopbackTesting.value && _hasMicPermission.value,
            antiPlosiveEnabled = settings.antiPlosiveEnabled,
            captureBlocked = captureBlocked
        )
    }

    fun setUserVolumeAndMute(clientUid: String, nickname: String, volumePercent: Int, localMuted: Boolean) {
        if (clientUid.isBlank()) return
        val clamped = volumePercent.coerceIn(0, 200)
        repoScope.launch {
            db.userAudioPrefDao().upsert(
                UserAudioPrefEntity(
                    clientUid = clientUid,
                    nicknameHint = nickname,
                    volumePercent = clamped,
                    localMuted = localMuted
                )
            )
            if (NativeBridge.ensureLoaded()) {
                NativeBridge.nativeSetUserAudio(clientUid, clamped, localMuted)
            }
        }
    }

    private fun applyStoredUserAudioPrefsToNative() {
        if (!NativeBridge.ensureLoaded()) return
        repoScope.launch {
            val prefs = userAudioPrefsFlow.value.values
            for (p in prefs) {
                NativeBridge.nativeSetUserAudio(p.clientUid, p.volumePercent, p.localMuted)
            }
        }
    }

    // --- Full Channel Management, Moderation & Privilege Tokens ---

    fun sendPoke(clientId: Int, message: String) {
        if (!NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeSendPoke(clientId, message.trim())
        }
    }

    fun usePrivilegeKey(token: String) {
        val clean = token.trim()
        if (clean.isEmpty() || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeUsePrivilegeKey(clean)
        }
    }

    fun requestTalkPower(message: String = "") {
        if (!NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeRequestTalkPower(true, message.trim())
        }
    }

    fun createChannel(
        name: String,
        parentId: Long = 0L,
        password: String = "",
        topic: String = "",
        description: String = "",
        maxClients: Int = -1,
        lifecycle: ChannelLifecycle = ChannelLifecycle.TEMPORARY,
        isDefault: Boolean = false,
        opusMusic: Boolean = false,
        quality: Int = 6,
        neededTalkPower: Int = 0,
        autoJoin: Boolean = true
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty() || !NativeBridge.ensureLoaded()) return
        if (password.isNotEmpty()) {
            pendingCreatedChannelPassword = cleanName to password
        }
        repoScope.launch {
            NativeBridge.nativeCreateChannel(
                name = cleanName,
                parentId = parentId.coerceAtLeast(0L),
                password = password,
                topic = topic.trim(),
                description = description.trim(),
                maxClients = maxClients,
                channelType = if (isDefault) ChannelLifecycle.PERMANENT.code else lifecycle.code,
                isDefault = isDefault,
                opusMusic = opusMusic,
                quality = quality.coerceIn(1, 10),
                neededTalkPower = neededTalkPower.coerceAtLeast(0),
                autoJoin = autoJoin
            )
        }
    }

    fun editChannel(
        channelId: Long,
        parentId: Long,
        name: String,
        passwordMode: PasswordEditMode,
        password: String = "",
        topic: String = "",
        description: String = "",
        maxClients: Int = -1,
        lifecycle: ChannelLifecycle = ChannelLifecycle.PERMANENT,
        isDefault: Boolean = false,
        opusMusic: Boolean = false,
        quality: Int = 6,
        neededTalkPower: Int = 0
    ) {
        val cleanName = name.trim()
        if (channelId <= 0L || cleanName.isEmpty() || !NativeBridge.ensureLoaded()) return
        if (passwordMode == PasswordEditMode.SET && password.isNotEmpty()) {
            sessionChannelPasswords[channelId] = password
        } else if (passwordMode == PasswordEditMode.CLEAR) {
            sessionChannelPasswords.remove(channelId)
        }
        repoScope.launch {
            NativeBridge.nativeEditChannel(
                channelId = channelId,
                parentId = parentId,
                name = cleanName,
                passwordMode = passwordMode.code,
                password = password,
                topic = topic.trim(),
                description = description.trim(),
                maxClients = maxClients,
                channelType = if (isDefault) ChannelLifecycle.PERMANENT.code else lifecycle.code,
                isDefault = isDefault,
                opusMusic = opusMusic,
                quality = quality.coerceIn(1, 10),
                neededTalkPower = neededTalkPower.coerceAtLeast(0)
            )
        }
    }

    fun deleteChannel(channelId: Long, force: Boolean) {
        if (channelId <= 0L || !NativeBridge.ensureLoaded()) return
        sessionChannelPasswords.remove(channelId)
        repoScope.launch {
            NativeBridge.nativeDeleteChannel(channelId, force)
        }
    }

    fun requestChannelDescription(channelId: Long) {
        if (channelId <= 0L || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeRequestChannelDescription(channelId)
        }
    }

    fun moveClientToChannel(clientId: Int, targetChannelId: Long, password: String = "") {
        if (!NativeBridge.ensureLoaded()) return
        val effectivePwd = password.ifEmpty { sessionChannelPasswords[targetChannelId].orEmpty() }
        repoScope.launch {
            NativeBridge.nativeMoveClient(clientId, targetChannelId, effectivePwd)
        }
    }

    fun kickClient(clientId: Int, fromServer: Boolean, reason: String) {
        if (!NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeKickClient(clientId, fromServer, reason.trim())
        }
    }

    fun banClient(clientId: Int, durationSeconds: Long, reason: String) {
        if (!NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeBanClient(clientId, durationSeconds, reason.trim())
        }
    }

    fun requestServerVariables() {
        if (!NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeRequestServerVariables()
        }
    }

    fun requestClientDetails(clientId: Int) {
        if (clientId <= 0 || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeRequestClientDetails(clientId)
        }
    }

    fun updateSelfServerNickname(nickname: String) {
        val clean = nickname.trim()
        if (clean.isEmpty() || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeUpdateSelfProfile(
                updateNickname = true,
                nickname = clean,
                updateCommander = false,
                isChannelCommander = false
            )
        }
    }

    fun setSelfChannelCommander(enabled: Boolean) {
        if (!NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeUpdateSelfProfile(
                updateNickname = false,
                nickname = "",
                updateCommander = true,
                isChannelCommander = enabled
            )
        }
    }

    fun editClientDescription(clientId: Int, description: String) {
        if (clientId <= 0 || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeEditClientProperties(
                clientId = clientId,
                updateDescription = true,
                description = description.trim(),
                updateTalkPowerGranted = false,
                talkPowerGranted = false
            )
        }
    }

    fun setClientTalkPowerGranted(clientId: Int, granted: Boolean) {
        if (clientId <= 0 || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeEditClientProperties(
                clientId = clientId,
                updateDescription = false,
                description = "",
                updateTalkPowerGranted = true,
                talkPowerGranted = granted
            )
        }
    }

    fun setClientServerGroup(clientId: Int, serverGroupId: Long, add: Boolean) {
        if (clientId <= 0 || serverGroupId <= 0L || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeSetClientServerGroup(clientId, serverGroupId, add)
        }
    }

    fun setClientChannelGroup(clientId: Int, channelId: Long, channelGroupId: Long) {
        if (clientId <= 0 || channelId <= 0L || channelGroupId <= 0L || !NativeBridge.ensureLoaded()) return
        repoScope.launch {
            NativeBridge.nativeSetClientChannelGroup(clientId, channelId, channelGroupId)
        }
    }

    // --- TSCC Configuration Backup & Restore ---

    suspend fun exportTsccConfig(
        includeIdentities: Boolean = true,
        includeBookmarks: Boolean = true,
        includeSettings: Boolean = true,
        password: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(includeIdentities || includeBookmarks || includeSettings) {
                if (isEnglish()) {
                    "Select at least one section to export"
                } else {
                    "请至少勾选一项要导出的配置内容"
                }
            }

            val allIdentities = db.identityDao().getAll().map { it.decrypted() }
            val idToUidMap = allIdentities.associate { it.id to it.uniqueId }

            val exportedIdentities = if (includeIdentities) {
                allIdentities.map { id ->
                    TsccBackupIdentity(
                        name = id.name,
                        uniqueId = id.uniqueId,
                        publicKeyBase64 = id.publicKeyBase64,
                        iniPayload = id.iniPayload,
                        securityLevel = id.securityLevel,
                        isDefault = id.isDefault,
                        createdAt = id.createdAt
                    )
                }
            } else {
                emptyList()
            }

            val exportedBookmarks = if (includeBookmarks) {
                db.bookmarkDao().getAll().map { it.decrypted() }.map { bm ->
                    TsccBackupBookmark(
                        label = bm.label,
                        address = bm.address,
                        port = bm.port,
                        nickname = bm.nickname,
                        serverPassword = bm.serverPassword,
                        defaultChannel = bm.defaultChannel,
                        channelPassword = bm.channelPassword,
                        identityUniqueId = bm.identityId?.let { idToUidMap[it] },
                        lastConnectedAt = bm.lastConnectedAt
                    )
                }
            } else {
                emptyList()
            }

            val curSettings = appSettings.value
            val exportedSettings = if (includeSettings) {
                TsccBackupSettings(
                    defaultNickname = curSettings.defaultNickname,
                    voiceModeCode = curSettings.voiceMode.code,
                    vadThresholdDb = curSettings.vadThresholdDb,
                    antiPlosiveEnabled = curSettings.antiPlosiveEnabled,
                    soundCuesEnabled = curSettings.soundCuesEnabled,
                    hardwareAecEnabled = curSettings.hardwareAecEnabled,
                    hardwareNsEnabled = curSettings.hardwareNsEnabled,
                    hardwareAgcEnabled = curSettings.hardwareAgcEnabled,
                    overlayEnabled = curSettings.overlayEnabled,
                    darkTheme = curSettings.darkTheme,
                    appLanguageCode = curSettings.appLanguage.code,
                    autoCheckUpdates = curSettings.autoCheckUpdates
                )
            } else {
                null
            }

            val exportedAudioPrefs = if (includeSettings) {
                db.userAudioPrefDao().getAll().map { pref ->
                    TsccBackupUserAudioPref(
                        clientUid = pref.clientUid,
                        nicknameHint = pref.nicknameHint,
                        volumePercent = pref.volumePercent,
                        localMuted = pref.localMuted,
                        updatedAt = pref.updatedAt
                    )
                }
            } else {
                emptyList()
            }

            TsccConfigBackupCodec.encode(
                payload = TsccConfigPayload(
                    identities = exportedIdentities,
                    bookmarks = exportedBookmarks,
                    settings = exportedSettings,
                    userAudioPrefs = exportedAudioPrefs
                ),
                password = password,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }

    suspend fun importTsccConfig(
        rawJson: String,
        password: String = ""
    ): Result<TsccConfigImportSummary> = withContext(Dispatchers.IO) {
        TsccConfigBackupCodec.decode(rawJson, password).mapCatching { payload ->
            val nativeReady = NativeBridge.ensureLoaded()
            val existingIdentities = db.identityDao().getAll().map { it.decrypted() }.toMutableList()
            val uidToLocalId = existingIdentities.associate { it.uniqueId to it.id }.toMutableMap()

            var importedIdentitiesCount = 0
            var preferredDefaultUid: String? = null

            for (item in payload.identities) {
                if (item.iniPayload.isBlank()) continue
                val verified = if (nativeReady) {
                    NativeBridge.inspectIdentityIni(item.iniPayload).getOrNull() ?: continue
                } else {
                    null
                }
                val uid = verified?.uniqueId ?: item.uniqueId.trim()
                val pubKey = verified?.publicKeyBase64 ?: item.publicKeyBase64.trim()
                val iniContent = verified?.iniContent ?: item.iniPayload
                val secLevel = verified?.securityLevel ?: item.securityLevel
                if (uid.isEmpty() || pubKey.isEmpty()) continue

                if (item.isDefault && preferredDefaultUid == null) {
                    preferredDefaultUid = uid
                }

                val existingIdx = existingIdentities.indexOfFirst { it.uniqueId == uid }
                if (existingIdx >= 0) {
                    val cur = existingIdentities[existingIdx]
                    val shouldUpgradeIni = secLevel > cur.securityLevel
                    val isPlaceholderName = cur.name == "默认身份" || cur.name == "Default Identity"
                    val resolvedName = if (isPlaceholderName && item.name.isNotBlank()) {
                        item.name.trim()
                    } else {
                        cur.name
                    }
                    if (shouldUpgradeIni || resolvedName != cur.name) {
                        val updatedPlain = cur.copy(
                            name = resolvedName,
                            iniPayload = if (shouldUpgradeIni) iniContent else cur.iniPayload,
                            securityLevel = maxOf(cur.securityLevel, secLevel)
                        )
                        db.identityDao().update(updatedPlain.encrypted())
                        existingIdentities[existingIdx] = updatedPlain
                    }
                    uidToLocalId[uid] = cur.id
                    importedIdentitiesCount++
                } else {
                    val fallbackName = if (isEnglish()) {
                        "Imported (${uid.take(6)})"
                    } else {
                        "导入身份 (${uid.take(6)})"
                    }
                    val plainEntity = IdentityEntity(
                        name = item.name.trim().ifBlank { fallbackName },
                        uniqueId = uid,
                        publicKeyBase64 = pubKey,
                        iniPayload = iniContent,
                        securityLevel = secLevel,
                        isDefault = existingIdentities.isEmpty(),
                        createdAt = if (item.createdAt > 0L) item.createdAt else System.currentTimeMillis()
                    )
                    val insertedId = db.identityDao().insert(plainEntity.encrypted())
                    val stored = plainEntity.copy(id = insertedId)
                    existingIdentities.add(stored)
                    uidToLocalId[uid] = insertedId
                    importedIdentitiesCount++
                }
            }

            preferredDefaultUid?.let { defUid ->
                uidToLocalId[defUid]?.let { targetId ->
                    db.identityDao().setDefaultIdentity(targetId)
                }
            }

            val existingBookmarks = db.bookmarkDao().getAll().map { it.decrypted() }.toMutableList()
            var importedBookmarksCount = 0
            for (bm in payload.bookmarks) {
                val host = bm.address.trim()
                if (host.isEmpty()) continue
                val port = bm.port.takeIf { it in 1..65535 } ?: 9987
                val label = bm.label.trim().ifBlank { if (port == 9987) host else "$host:$port" }
                val nick = bm.nickname.trim().ifBlank {
                    payload.settings?.defaultNickname?.trim()?.takeIf { it.isNotEmpty() }
                        ?: appSettings.value.defaultNickname
                }
                val resolvedIdentityId = bm.identityUniqueId?.let { uidToLocalId[it] }

                val dupIdx = existingBookmarks.indexOfFirst {
                    it.address.equals(host, ignoreCase = true) &&
                        it.port == port &&
                        it.label == label &&
                        it.defaultChannel == bm.defaultChannel
                }
                if (dupIdx >= 0) {
                    val cur = existingBookmarks[dupIdx]
                    val merged = cur.copy(
                        nickname = nick,
                        serverPassword = bm.serverPassword.ifEmpty { cur.serverPassword },
                        defaultChannel = bm.defaultChannel.ifEmpty { cur.defaultChannel },
                        channelPassword = bm.channelPassword.ifEmpty { cur.channelPassword },
                        identityId = resolvedIdentityId ?: cur.identityId,
                        lastConnectedAt = maxOf(cur.lastConnectedAt, bm.lastConnectedAt)
                    )
                    db.bookmarkDao().update(merged.encrypted())
                    existingBookmarks[dupIdx] = merged
                } else {
                    val newPlain = BookmarkEntity(
                        label = label,
                        address = host,
                        port = port,
                        nickname = nick,
                        serverPassword = bm.serverPassword,
                        defaultChannel = bm.defaultChannel,
                        channelPassword = bm.channelPassword,
                        identityId = resolvedIdentityId,
                        lastConnectedAt = bm.lastConnectedAt
                    )
                    val newId = db.bookmarkDao().upsert(newPlain.encrypted())
                    existingBookmarks.add(newPlain.copy(id = newId))
                }
                importedBookmarksCount++
            }

            var settingsRestored = false
            payload.settings?.let { s ->
                val canOverlay = s.overlayEnabled && Settings.canDrawOverlays(context)
                settingsRepository.restoreAllSettings(
                    defaultNickname = s.defaultNickname,
                    voiceMode = VoiceMode.fromCode(s.voiceModeCode),
                    vadThresholdDb = s.vadThresholdDb,
                    antiPlosiveEnabled = s.antiPlosiveEnabled,
                    soundCuesEnabled = s.soundCuesEnabled,
                    aecEnabled = s.hardwareAecEnabled,
                    nsEnabled = s.hardwareNsEnabled,
                    agcEnabled = s.hardwareAgcEnabled,
                    overlayEnabled = canOverlay,
                    darkTheme = s.darkTheme,
                    appLanguage = AppLanguage.fromCode(s.appLanguageCode),
                    autoCheckUpdates = s.autoCheckUpdates
                )
                settingsRestored = true
            }

            var audioPrefsCount = 0
            for (pref in payload.userAudioPrefs) {
                val uid = pref.clientUid.trim()
                if (uid.isEmpty()) continue
                db.userAudioPrefDao().upsert(
                    UserAudioPrefEntity(
                        clientUid = uid,
                        nicknameHint = pref.nicknameHint,
                        volumePercent = pref.volumePercent.coerceIn(0, 200),
                        localMuted = pref.localMuted,
                        updatedAt = if (pref.updatedAt > 0L) pref.updatedAt else System.currentTimeMillis()
                    )
                )
                audioPrefsCount++
            }
            if (audioPrefsCount > 0 && _connectionStatus.value == ConnectionStatus.CONNECTED) {
                applyStoredUserAudioPrefsToNative()
            }

            TsccConfigImportSummary(
                identitiesCount = importedIdentitiesCount,
                bookmarksCount = importedBookmarksCount,
                settingsRestored = settingsRestored,
                audioPrefsCount = audioPrefsCount
            )
        }
    }

    // --- Full Application Reset ---

    suspend fun clearAllAppData(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (_isLoopbackTesting.value) {
                setLoopbackTesting(false)
            }
            if (_connectionStatus.value != ConnectionStatus.DISCONNECTED) {
                disconnect(if (isEnglish()) "Clear app data" else "清除应用数据", manual = true)
            }
            cancelSecurityUpgrade()

            db.clearAllTables()
            settingsRepository.clearAllSettings()

            File(context.cacheDir, "ts_icons").deleteRecursively()
            requestedIconKeys.clear()
            sessionChannelPasswords.clear()
            pendingCreatedChannelPassword = null
            _iconCachePaths.value = emptyMap()
            _chatMessages.value = emptyList()
            _unreadChatCounts.value = emptyMap()
            _pokeQueue.value = emptyList()
            _lastConnectionRequest.value = null
            _activeBookmarkId.value = null
            _statusMessage.value = null

            ensureDefaultIdentityExists()
        }
    }

    private fun triggerAutoIconDownloads(snap: ServerSnapshot) {
        val iconDir = File(context.cacheDir, "ts_icons").apply { mkdirs() }
        val pendingJobs = mutableListOf<Triple<String, String, String>>()
        val iconIds = buildSet {
            if (snap.serverIconId != 0L) add(snap.serverIconId)
            snap.channels.forEach { if (it.iconId != 0L) add(it.iconId) }
            snap.clients.forEach { if (it.iconId != 0L) add(it.iconId) }
            snap.serverGroups.forEach { if (it.iconId != 0L) add(it.iconId) }
            snap.channelGroups.forEach { if (it.iconId != 0L) add(it.iconId) }
        }
        for (rawIconId in iconIds) {
            val unsignedId = rawIconId and 0xFFFFFFFFL
            if (unsignedId <= 1000L) continue
            val key = "icon_$unsignedId"
            val dest = File(iconDir, "$key.png")
            if (dest.exists() && dest.length() > 0) {
                TsIconCodec.normalizeIconFileInPlace(dest)
                if (!_iconCachePaths.value.containsKey(key)) {
                    _iconCachePaths.update { it + (key to dest.absolutePath) }
                }
            } else if (requestedIconKeys.add(key)) {
                pendingJobs.add(Triple("/icon_$unsignedId", key, dest.absolutePath))
            }
        }

        for (c in snap.clients) {
            if (c.avatarFlag.isNotBlank() && c.uid.isNotBlank()) {
                val avatarName = uidToAvatarFileName(c.uid) ?: continue
                val key = "avatar_${c.uid}"
                val dest = File(iconDir, "${avatarName}_${c.avatarFlag.take(8)}.img")
                if (dest.exists() && dest.length() > 0) {
                    if (!_iconCachePaths.value.containsKey(key)) {
                        _iconCachePaths.update { it + (key to dest.absolutePath) }
                    }
                } else if (requestedIconKeys.add(key)) {
                    pendingJobs.add(Triple("/$avatarName", key, dest.absolutePath))
                }
            }
        }

        if (pendingJobs.isNotEmpty()) {
            repoScope.launch {
                for ((remotePath, key, destPath) in pendingJobs) {
                    if (_connectionStatus.value != ConnectionStatus.CONNECTED) break
                    iconDownloadLimiter.withPermit {
                        NativeBridge.nativeRequestIconOrAvatar(
                            channelId = 0L,
                            remotePath = remotePath,
                            fileKey = key,
                            destFilePath = destPath
                        )
                        delay(85L)
                    }
                }
            }
        }
    }

    private fun uidToAvatarFileName(uidBase64: String): String? = runCatching {
        val bytes = android.util.Base64.decode(uidBase64, android.util.Base64.DEFAULT)
        if (bytes.isEmpty()) return null
        val sb = StringBuilder("avatar_")
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(('a'.code + (v ushr 4)).toChar())
            sb.append(('a'.code + (v and 0x0F)).toChar())
        }
        sb.toString()
    }.getOrNull()

    private fun getActiveSystemDnsCsv(): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ""
        val net = cm.activeNetwork ?: return ""
        val lp = cm.getLinkProperties(net) ?: return ""
        lp.dnsServers.mapNotNull { it.hostAddress }.joinToString(",")
    }.getOrDefault("")
}
