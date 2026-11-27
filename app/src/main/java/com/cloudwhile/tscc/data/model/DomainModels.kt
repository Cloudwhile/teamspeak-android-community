package com.cloudwhile.tscc.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

@Serializable
enum class VoiceMode(val code: Int, val label: String, val labelEn: String) {
    VAD(0, "语音感应 (VAD)", "Voice Activity (VAD)"),
    PTT(1, "按住说话 (PTT)", "Push-To-Talk (PTT)"),
    CONTINUOUS(2, "常开麦", "Continuous");

    fun localizedLabel(isEn: Boolean): String = if (isEn) labelEn else label

    companion object {
        fun fromCode(code: Int): VoiceMode = entries.firstOrNull { it.code == code } ?: VAD
    }
}

enum class AppLanguage(val code: String, val displayLabel: String, val displayLabelEn: String) {
    SYSTEM("system", "跟随系统", "System"),
    ZH_CN("zh", "简体中文", "简体中文"),
    EN("en", "English", "English");

    fun localizedLabel(isEn: Boolean): String = if (isEn) displayLabelEn else displayLabel

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

enum class AudioRoute(val label: String, val labelEn: String) {
    SPEAKER("扬声器外放", "Speaker"),
    EARPIECE("手机听筒", "Earpiece"),
    WIRED_HEADSET("有线耳机", "Wired"),
    BLUETOOTH("蓝牙耳机", "Bluetooth");

    fun localizedLabel(isEn: Boolean): String = if (isEn) labelEn else label
}

enum class ChannelLifecycle(val code: Int) {
    TEMPORARY(0),
    SEMI_PERMANENT(1),
    PERMANENT(2)
}

enum class PasswordEditMode(val code: Int) {
    KEEP(0),
    SET(1),
    CLEAR(2)
}

enum class ToastTone {
    SUCCESS,
    WARNING,
    ERROR
}

enum class ToastAction {
    OPEN_PRIVILEGE_KEY,
    EXIT_AFK,
    REQUEST_TALK_POWER,
    UNMUTE_MIC,
    GRANT_MIC_PERMISSION
}

data class UiToastMessage(
    val id: Long = System.nanoTime(),
    val text: String,
    val tone: ToastTone = ToastTone.SUCCESS,
    val action: ToastAction? = null
)

data class ChannelPasswordPrompt(
    val channelId: Long,
    val channelName: String,
    val errorHint: String? = null
)

data class LastConnectionRequest(
    val bookmarkId: Long? = null,
    val label: String = "",
    val address: String,
    val port: Int = 9987,
    val nickname: String,
    val serverPassword: String = "",
    val defaultChannel: String = "",
    val channelPassword: String = "",
    val identityId: Long? = null,
    val privilegeKey: String? = null
)

@Serializable
data class ChannelNode(
    val id: Long,
    val parentId: Long = 0L,
    val order: Long = 0L,
    val name: String,
    val topic: String = "",
    val description: String = "",
    val hasPassword: Boolean = false,
    val isPermanent: Boolean = true,
    val isSemiPermanent: Boolean = false,
    val isDefault: Boolean = false,
    val maxClients: Int = -1,
    val neededTalkPower: Int = 0,
    val codec: Int = 4, // 4 = Opus Voice, 5 = Opus Music
    val codecQuality: Int = 6,
    val iconId: Long = 0L
) {
    fun lifecycle(): ChannelLifecycle = when {
        isPermanent -> ChannelLifecycle.PERMANENT
        isSemiPermanent -> ChannelLifecycle.SEMI_PERMANENT
        else -> ChannelLifecycle.TEMPORARY
    }
}

/**
 * Returns the set of channel IDs that cannot be selected as the parent of [editingChannelId]
 * (itself plus all of its nested descendants) to prevent cyclic parent references.
 */
fun computeExcludedParentChannelIds(
    editingChannelId: Long?,
    allChannels: List<ChannelNode>
): Set<Long> {
    if (editingChannelId == null || editingChannelId <= 0L) return emptySet()
    val byParent = allChannels.groupBy { it.parentId }
    val excluded = mutableSetOf<Long>()
    fun dfs(cid: Long) {
        if (!excluded.add(cid)) return
        for (child in byParent[cid].orEmpty()) {
            dfs(child.id)
        }
    }
    dfs(editingChannelId)
    return excluded
}

@Serializable
data class OnlineClient(
    val id: Int,
    val channelId: Long,
    val uid: String = "",
    val nickname: String,
    val isTalking: Boolean = false,
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false,
    val inputHardware: Boolean = true,
    val outputHardware: Boolean = true,
    val isAway: Boolean = false,
    val awayMessage: String = "",
    val isChannelCommander: Boolean = false,
    val talkPower: Int = 0,
    val talkPowerGranted: Boolean = false,
    val serverGroups: List<Long> = emptyList(),
    val channelGroup: Long = 0L,
    val iconId: Long = 0L,
    val avatarFlag: String = "",
    val clientType: Int = 0, // 0 = Normal voice client, 1 = ServerQuery
    val isStreaming: Boolean = false,
    val streamId: String = "",
    val streamName: String = "",
    val streamType: Int = 0, // 1 = Camera, 2 = Screen, 3 = Window
    val streamAudio: Boolean = false,
    val streamBitrate: Int = 0,
    val streamViewers: Int = 0
)

enum class LiveStreamSourceType(val tsType: Int) {
    CAMERA(1),
    SCREEN(2)
}

enum class LiveWatchStatus {
    IDLE,
    CONNECTING,
    PLAYING,
    ERROR
}

data class WatchingStreamState(
    val status: LiveWatchStatus = LiveWatchStatus.IDLE,
    val streamerClientId: Int = 0,
    val streamerName: String = "",
    val streamId: String = "",
    val streamTitle: String = "",
    val streamType: Int = 2,
    val resolutionText: String = "",
    val isMinimized: Boolean = false,
    val isFullscreen: Boolean = false,
    val streamAudioMuted: Boolean = false,
    val errorMessage: String? = null
)

data class BroadcastingStreamState(
    val isBroadcasting: Boolean = false,
    val isStarting: Boolean = false,
    val sourceType: LiveStreamSourceType = LiveStreamSourceType.SCREEN,
    val streamId: String = "",
    val streamTitle: String = "",
    val bitrateKbps: Int = 3500,
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val viewerCount: Int = 0,
    val isFrontCamera: Boolean = true
)

@Serializable
data class Ts6StreamSignal(
    val eventType: String = "",
    val clientId: Int = 0,
    val streamId: String = "",
    val name: String = "",
    val streamType: Int = 0,
    val accessibility: Int = 1,
    val mode: Int = 1,
    val bitrate: Int = 0,
    val viewerLimit: Int = 0,
    val audio: Boolean = false,
    val viewerCount: Int = 0,
    val reason: Int = 0,
    val isRemove: Boolean = false,
    val decision: Boolean = false,
    val message: String = "",
    val offer: String = "",
    val json: String = ""
)

@Serializable
enum class ChatTarget(val tsMode: Int) {
    CLIENT(1),
    CHANNEL(2),
    SERVER(3);

    companion object {
        fun fromTsMode(mode: Int): ChatTarget = entries.firstOrNull { it.tsMode == mode } ?: SERVER
    }
}

@Serializable
data class ChatMessage(
    val id: String,
    val target: ChatTarget,
    val peerClientId: Int? = null,
    val peerUid: String? = null,
    val peerName: String = "",
    val senderId: Int = 0,
    val senderName: String,
    val senderUid: String = "",
    val content: String,
    val timestampMs: Long,
    val isSystem: Boolean = false
)

@Serializable
data class PokeAlert(
    val id: String,
    val fromClientId: Int,
    val fromName: String,
    val fromUid: String = "",
    val message: String,
    val timestampMs: Long
)

@Serializable
data class ServerSnapshot(
    val sessionId: Long = 0L,
    val serverName: String = "",
    val serverAddress: String = "",
    val welcomeMessage: String = "",
    val hostMessage: String = "",
    val platform: String = "",
    val version: String = "",
    val maxClients: Int = 32,
    val myClientId: Int = 0,
    val myChannelId: Long = 0L,
    val serverIconId: Long = 0L,
    val pingMs: Int = 0,
    val packetLossPct: Float = 0f,
    val channels: List<ChannelNode> = emptyList(),
    val clients: List<OnlineClient> = emptyList()
)

@Serializable
data class IdentityInfo(
    val iniContent: String,
    val publicKeyBase64: String,
    val uniqueId: String,
    val securityLevel: Int,
    val keyOffset: Long,
    val cancelled: Boolean = false
)

@Serializable
data class CoreEventEnvelope(
    val kind: String,
    val status: String? = null,
    val reason: String? = null,
    val snapshot: ServerSnapshot? = null,
    val chat: ChatMessage? = null,
    val poke: PokeAlert? = null,
    @SerialName("speaking_ids") val speakingIds: List<Int>? = null,
    @SerialName("input_db") val inputDb: Float? = null,
    @SerialName("voice_transmitting") val voiceTransmitting: Boolean? = null,
    @SerialName("security_level") val securityLevel: Int? = null,
    @SerialName("key_offset") val keyOffset: Long? = null,
    @SerialName("updated_ini") val updatedIni: String? = null,
    @SerialName("unique_id") val uniqueId: String? = null,
    @SerialName("file_key") val fileKey: String? = null,
    @SerialName("local_path") val localPath: String? = null,
    val action: String? = null,
    @SerialName("command_ok") val commandOk: Boolean? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    @SerialName("missing_perm") val missingPerm: String? = null,
    @SerialName("is_permission_error") val isPermissionError: Boolean? = null,
    @SerialName("target_channel_id") val targetChannelId: Long? = null,
    val cue: String? = null,
    @SerialName("stream_signal") val streamSignal: Ts6StreamSignal? = null
)
