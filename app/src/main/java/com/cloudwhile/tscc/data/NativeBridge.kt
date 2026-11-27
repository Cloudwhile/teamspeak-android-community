package com.cloudwhile.tscc.data

import android.util.Log
import com.cloudwhile.tscc.data.model.CoreEventEnvelope
import com.cloudwhile.tscc.data.model.IdentityInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer

interface NativeEventCallback {
    fun onCoreEventJson(json: String)
}

object NativeBridge : NativeEventCallback {
    private const val TAG = "NativeBridge"

    val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val _events = MutableSharedFlow<CoreEventEnvelope>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<CoreEventEnvelope> = _events.asSharedFlow()

    @Volatile
    private var isEnglishLang: Boolean = false

    val isNativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("tscc_core")
            nativeInit(this)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load libtscc_core.so: ${t.message}")
            false
        }
    }

    fun ensureLoaded(): Boolean = isNativeLoaded

    override fun onCoreEventJson(json: String) {
        runCatching {
            val env = jsonParser.decodeFromString<CoreEventEnvelope>(json)
            _events.tryEmit(env)
        }.onFailure { err ->
            Log.w(TAG, "Failed to parse CoreEvent JSON: ${err.message} -> $json")
        }
    }

    fun setLanguage(isEnglish: Boolean) {
        isEnglishLang = isEnglish
        if (ensureLoaded()) {
            nativeSetLanguage(isEnglish)
        }
    }

    private fun nativeNotLoadedMessage(): String =
        if (isEnglishLang) "Native protocol library is not loaded" else "原生协议库尚未加载"

    fun generateIdentity(): Result<IdentityInfo> = runCatching {
        check(ensureLoaded()) { nativeNotLoadedMessage() }
        val raw = nativeGenerateIdentity()
        decodeResultJson(raw)
    }

    fun inspectIdentityIni(iniOrRawKey: String): Result<IdentityInfo> = runCatching {
        check(ensureLoaded()) { nativeNotLoadedMessage() }
        val raw = nativeInspectIdentityIni(iniOrRawKey)
        decodeResultJson(raw)
    }

    fun upgradeSecurityLevel(iniOrRawKey: String, targetLevel: Int): Result<IdentityInfo> = runCatching {
        check(ensureLoaded()) { nativeNotLoadedMessage() }
        val raw = nativeUpgradeSecurityLevel(iniOrRawKey, targetLevel)
        decodeResultJson(raw)
    }

    private inline fun <reified T> decodeResultJson(raw: String): T {
        val parsed = runCatching { jsonParser.parseToJsonElement(raw) }.getOrNull()
        val errMsg = (parsed as? JsonObject)?.get("error")
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.content
        if (errMsg != null) {
            throw IllegalArgumentException(errMsg.ifBlank { raw })
        }
        return jsonParser.decodeFromString(raw)
    }

    // --- JNI External Methods ---

    @JvmStatic
    external fun nativeInit(callbackTarget: NativeEventCallback)

    @JvmStatic
    external fun nativeSetLanguage(isEnglish: Boolean)

    @JvmStatic
    external fun nativeGenerateIdentity(): String

    @JvmStatic
    external fun nativeInspectIdentityIni(iniOrRawKey: String): String

    @JvmStatic
    external fun nativeUpgradeSecurityLevel(iniOrRawKey: String, targetLevel: Int): String

    @JvmStatic
    external fun nativeCancelSecurityUpgrade()

    @JvmStatic
    external fun nativeConnect(
        address: String,
        port: Int,
        nickname: String,
        identityIni: String,
        serverPassword: String,
        defaultChannel: String,
        channelPassword: String,
        dnsServersCsv: String
    ): Boolean

    @JvmStatic
    external fun nativeDisconnect(reasonMessage: String)

    @JvmStatic
    external fun nativeSwitchChannel(channelId: Long, password: String): Boolean

    @JvmStatic
    external fun nativeSendMessage(targetMode: Int, targetId: Long, message: String): Boolean

    @JvmStatic
    external fun nativeSetSelfMuteState(
        inputMuted: Boolean,
        outputMuted: Boolean,
        away: Boolean,
        awayMessage: String
    )

    @JvmStatic
    external fun nativeAttachAudioBuffers(
        captureDirectBuf: ByteBuffer,
        playbackDirectBuf: ByteBuffer
    )

    @JvmStatic
    external fun nativeOnCaptureFrameReady(): Boolean

    @JvmStatic
    external fun nativePullPlaybackFrame(): Int

    @JvmStatic
    external fun nativeConfigureVoice(
        mode: Int,
        vadThresholdDb: Float,
        pttActive: Boolean,
        loopbackEnabled: Boolean,
        antiPlosiveEnabled: Boolean,
        captureBlocked: Boolean
    )

    @JvmStatic
    external fun nativeSetUserAudio(
        clientUid: String,
        volumePercent: Int,
        muted: Boolean
    )

    @JvmStatic
    external fun nativeSendPoke(clientId: Int, message: String): Boolean

    @JvmStatic
    external fun nativeUsePrivilegeKey(token: String): Boolean

    @JvmStatic
    external fun nativeRequestTalkPower(request: Boolean, message: String): Boolean

    @JvmStatic
    external fun nativeCreateChannel(
        name: String,
        parentId: Long,
        password: String,
        topic: String,
        description: String,
        maxClients: Int,
        channelType: Int,
        isDefault: Boolean,
        opusMusic: Boolean,
        quality: Int,
        neededTalkPower: Int,
        autoJoin: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeEditChannel(
        channelId: Long,
        parentId: Long,
        name: String,
        passwordMode: Int,
        password: String,
        topic: String,
        description: String,
        maxClients: Int,
        channelType: Int,
        isDefault: Boolean,
        opusMusic: Boolean,
        quality: Int,
        neededTalkPower: Int
    ): Boolean

    @JvmStatic
    external fun nativeDeleteChannel(channelId: Long, force: Boolean): Boolean

    @JvmStatic
    external fun nativeRequestChannelDescription(channelId: Long): Boolean

    @JvmStatic
    external fun nativeMoveClient(clientId: Int, targetChannelId: Long, password: String): Boolean

    @JvmStatic
    external fun nativeKickClient(clientId: Int, fromServer: Boolean, reason: String): Boolean

    @JvmStatic
    external fun nativeBanClient(clientId: Int, durationSeconds: Long, reason: String): Boolean

    @JvmStatic
    external fun nativeRequestIconOrAvatar(
        channelId: Long,
        remotePath: String,
        fileKey: String,
        destFilePath: String
    ): Boolean

    @JvmStatic
    external fun nativeSetupStream(
        name: String,
        streamType: Int,
        bitrateKbps: Int,
        accessibility: Int,
        mode: Int,
        viewerLimit: Int,
        audio: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeStopStream(streamId: String): Boolean

    @JvmStatic
    external fun nativeRequestStreamInfo(clientId: Int): Boolean

    @JvmStatic
    external fun nativeJoinStreamRequest(
        streamId: String,
        streamerClientId: Int,
        message: String,
        isRemove: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeRespondJoinStreamRequest(
        streamId: String,
        viewerClientId: Int,
        decision: Boolean,
        offer: String,
        message: String
    ): Boolean

    @JvmStatic
    external fun nativeSendStreamSignaling(
        streamId: String,
        targetClientId: Int,
        json: String
    ): Boolean

    @JvmStatic
    external fun nativeRemoveClientFromStream(
        streamId: String,
        viewerClientId: Int,
        reason: Int
    ): Boolean
}
