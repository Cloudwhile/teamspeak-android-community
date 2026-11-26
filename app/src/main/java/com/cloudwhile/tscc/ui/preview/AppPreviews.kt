package com.cloudwhile.tscc.ui.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.cloudwhile.tscc.data.AppSettings
import com.cloudwhile.tscc.data.db.BookmarkEntity
import com.cloudwhile.tscc.data.db.IdentityEntity
import com.cloudwhile.tscc.data.model.AudioRoute
import com.cloudwhile.tscc.data.model.ChannelNode
import com.cloudwhile.tscc.data.model.ConnectionStatus
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.ServerSnapshot
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.ui.screens.bookmarks.BookmarksScreen
import com.cloudwhile.tscc.ui.screens.identities.IdentitiesScreen
import com.cloudwhile.tscc.ui.screens.room.ServerRoomScreen
import com.cloudwhile.tscc.ui.screens.settings.SettingsScreen
import com.cloudwhile.tscc.ui.theme.TsccTheme

@Preview(name = "Bookmarks Dark", showBackground = true, widthDp = 390, heightDp = 820)
@Composable
fun BookmarksScreenDarkPreview() {
    TsccTheme(darkTheme = true) {
        SampleBookmarksPreviewContent(darkTheme = true)
    }
}

@Preview(name = "Bookmarks Light", showBackground = true, widthDp = 390, heightDp = 820)
@Composable
fun BookmarksScreenLightPreview() {
    TsccTheme(darkTheme = false) {
        SampleBookmarksPreviewContent(darkTheme = false)
    }
}

@Composable
private fun SampleBookmarksPreviewContent(darkTheme: Boolean) {
    BookmarksScreen(
        bookmarks = listOf(
            BookmarkEntity(
                id = 1,
                label = "周末好友语音频道",
                address = "voice.studio.cn",
                port = 9987,
                nickname = "Lin_Studio",
                serverPassword = "secret",
                defaultChannel = "日常吹水房"
            ),
            BookmarkEntity(
                id = 2,
                label = "开源社区协作站",
                address = "203.0.113.88",
                port = 10010,
                nickname = "Dev_Zero"
            )
        ),
        identities = listOf(
            IdentityEntity(
                id = 1,
                name = "主力身份",
                uniqueId = "lks7QL5OVMKo4pZ79cEOI5r5oEA=",
                publicKeyBase64 = "",
                iniPayload = "",
                securityLevel = 25,
                isDefault = true
            )
        ),
        selectedBookmarkId = 1L,
        activeBookmarkId = 1L,
        connectionStatus = ConnectionStatus.CONNECTED,
        defaultNickname = "Lin_Studio",
        darkTheme = darkTheme,
        onSelectBookmark = {},
        onConnectBookmark = {},
        onSaveBookmark = {},
        onDeleteBookmark = {},
        onCopyMessage = {}
    )
}

@Preview(name = "Server Room Dark", showBackground = true, widthDp = 390, heightDp = 820)
@Composable
fun ServerRoomScreenPreview() {
    val sampleSnap = ServerSnapshot(
        sessionId = 1,
        serverName = "North Studio · 语音空间",
        myClientId = 10,
        myChannelId = 2,
        maxClients = 64,
        pingMs = 24,
        packetLossPct = 0.0f,
        channels = listOf(
            ChannelNode(id = 1, parentId = 0, order = 0, name = "公共接待大厅", isDefault = true),
            ChannelNode(
                id = 2,
                parentId = 0,
                order = 1,
                name = "设计评审室",
                topic = "每周五 20:00 评审",
                hasPassword = true,
                maxClients = 8,
                neededTalkPower = 50
            ),
            ChannelNode(id = 3, parentId = 2, order = 0, name = "分组讨论 A", isPermanent = false),
            ChannelNode(id = 4, parentId = 0, order = 2, name = "24H 轻音乐电台", codec = 5)
        ),
        clients = listOf(
            OnlineClient(id = 10, channelId = 2, uid = "me", nickname = "Lin_Studio", talkPower = 25),
            OnlineClient(id = 11, channelId = 2, uid = "u2", nickname = "阿泽", isTalking = true, talkPower = 50),
            OnlineClient(id = 12, channelId = 2, uid = "u3", nickname = "Echo", inputMuted = true),
            OnlineClient(id = 13, channelId = 4, uid = "u4", nickname = "MusicBot", isTalking = true)
        )
    )

    TsccTheme(darkTheme = true) {
        ServerRoomScreen(
            status = ConnectionStatus.CONNECTED,
            statusMessage = null,
            snapshot = sampleSnap,
            speakingIds = setOf(11, 13),
            isVoiceTransmitting = false,
            isMicMuted = false,
            isOutputMuted = false,
            isPttPressed = false,
            voiceMode = VoiceMode.PTT,
            overlayEnabled = true,
            collapsedChannelIds = emptySet(),
            userAudioPrefs = emptyMap(),
            iconCachePaths = emptyMap(),
            chatMessages = emptyList(),
            unreadChatCounts = emptyMap(),
            activeDmPeer = null,
            availableRoutes = listOf(AudioRoute.BLUETOOTH, AudioRoute.SPEAKER),
            activeRoute = AudioRoute.BLUETOOTH,
            onToggleChannelCollapsed = {},
            onRequestJoinChannel = { _, _ -> },
            onToggleMicMute = {},
            onToggleOutputDeafen = {},
            onSetPttPressed = {},
            onSelectAudioRoute = {},
            onToggleOverlay = {},
            onDisconnect = {},
            onSendChatMessage = { _, _, _ -> },
            onClearUnreadChat = {},
            onOpenDmWith = {},
            onUpdateUserVolume = { _, _, _, _ -> },
            onSendPoke = { _, _ -> },
            onUsePrivilegeKey = {},
            onCreateChannel = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
            onMoveClientToChannel = { _, _ -> },
            onKickClient = { _, _, _ -> },
            onBanClient = { _, _, _ -> },
            onOpenDeepLink = {}
        )
    }
}

@Preview(name = "Identities Subscreen", showBackground = true, widthDp = 390, heightDp = 820)
@Composable
fun IdentitiesScreenPreview() {
    TsccTheme(darkTheme = true) {
        IdentitiesScreen(
            identities = listOf(
                IdentityEntity(
                    id = 1,
                    name = "PC 迁移主身份",
                    uniqueId = "lks7QL5OVMKo4pZ79cEOI5r5oEA=",
                    publicKeyBase64 = "",
                    iniPayload = "",
                    securityLevel = 26,
                    isDefault = true
                )
            ),
            upgradeProgress = 18 to 1_450_000L,
            onCreateIdentity = {},
            onImportIdentity = { _, _ -> },
            onUpgradeIdentity = { _, _ -> },
            onCancelUpgrade = {},
            onSetDefault = {},
            onDeleteIdentity = {},
            onShowMessage = {},
            onNavigateBack = {}
        )
    }
}

@Preview(name = "Unified Settings Hub", showBackground = true, widthDp = 390, heightDp = 820)
@Composable
fun SettingsScreenPreview() {
    TsccTheme(darkTheme = true) {
        SettingsScreen(
            settings = AppSettings(voiceMode = VoiceMode.VAD, vadThresholdDb = -40f),
            identities = listOf(
                IdentityEntity(
                    id = 1,
                    name = "主力身份",
                    uniqueId = "lks7QL5OVMKo4pZ79cEOI5r5oEA=",
                    publicKeyBase64 = "",
                    iniPayload = "",
                    securityLevel = 25,
                    isDefault = true
                )
            ),
            upgradeProgress = null,
            inputLevelDb = -24f,
            isVoiceTransmitting = true,
            isLoopbackTesting = false,
            onUpdateNickname = {},
            onSelectVoiceMode = {},
            onUpdateVadThreshold = {},
            onToggleLoopback = {},
            onUpdateAntiPlosive = {},
            onUpdateSoundCues = {},
            onUpdateHardwareAudio = { _, _, _ -> },
            onUpdateOverlayEnabled = {},
            onUpdateDarkTheme = {},
            onUpdateLanguage = {},
            onCreateIdentity = {},
            onImportIdentity = { _, _ -> },
            onUpgradeIdentity = { _, _ -> },
            onCancelUpgrade = {},
            onSetDefaultIdentity = {},
            onDeleteIdentity = {},
            onClearAllAppData = {},
            onShowMessage = {}
        )
    }
}
