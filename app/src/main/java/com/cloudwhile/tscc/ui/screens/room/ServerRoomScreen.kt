package com.cloudwhile.tscc.ui.screens.room

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cloudwhile.tscc.data.db.UserAudioPrefEntity
import com.cloudwhile.tscc.data.model.AudioRoute
import com.cloudwhile.tscc.data.model.BroadcastingStreamState
import com.cloudwhile.tscc.data.model.ChannelLifecycle
import com.cloudwhile.tscc.data.model.ChannelNode
import com.cloudwhile.tscc.data.model.ChannelPasswordPrompt
import com.cloudwhile.tscc.data.model.ChatMessage
import com.cloudwhile.tscc.data.model.ChatTarget
import com.cloudwhile.tscc.data.model.ConnectionStatus
import com.cloudwhile.tscc.data.model.LastConnectionRequest
import com.cloudwhile.tscc.data.model.LiveWatchStatus
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.PasswordEditMode
import com.cloudwhile.tscc.data.model.ServerSnapshot
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.data.model.WatchingStreamState
import com.cloudwhile.tscc.data.model.resolveBadges
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import com.cloudwhile.tscc.data.util.BBCodeParser
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppCircleIconButton
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppSecondaryPillButton
import com.cloudwhile.tscc.ui.components.AppSegmentedPills
import com.cloudwhile.tscc.ui.components.DashedHorizontalLine
import com.cloudwhile.tscc.ui.components.DashedVerticalLine
import com.cloudwhile.tscc.ui.components.PulsingEmeraldDot
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.theme.AppTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val SPACER_REGEX = Regex("^\\[([clr*]?spacer[^]]*)]\\s*(.*)$", RegexOption.IGNORE_CASE)

private sealed interface TreeRowItem {
    data class ChannelRow(
        val channel: ChannelNode,
        val depth: Int,
        val hasChildren: Boolean,
        val isCollapsed: Boolean,
        val occupantCount: Int
    ) : TreeRowItem

    data class ClientRow(
        val client: OnlineClient,
        val depth: Int
    ) : TreeRowItem
}

private enum class RoomPhase {
    DISCONNECTED,
    CONNECTING,
    ACTIVE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerRoomScreen(
    status: ConnectionStatus,
    statusMessage: String?,
    snapshot: ServerSnapshot,
    speakingIds: Set<Int>,
    hasMicPermission: Boolean = true,
    isVoiceTransmitting: Boolean,
    isMicMuted: Boolean,
    isOutputMuted: Boolean,
    isSelfAway: Boolean = false,
    isPttPressed: Boolean,
    voiceMode: VoiceMode,
    overlayEnabled: Boolean,
    collapsedChannelIds: Set<Long>,
    userAudioPrefs: Map<String, UserAudioPrefEntity>,
    iconCachePaths: Map<String, String>,
    chatMessages: List<ChatMessage>,
    unreadChatCounts: Map<ChatTarget, Int>,
    activeDmPeer: OnlineClient?,
    availableRoutes: List<AudioRoute>,
    activeRoute: AudioRoute,
    passwordPrompt: ChannelPasswordPrompt? = null,
    lastConnectionRequest: LastConnectionRequest? = null,
    openTokenDialogSeq: Long = 0L,
    watchingStreamState: WatchingStreamState = WatchingStreamState(),
    remoteVideoTrack: VideoTrack? = null,
    broadcastingStreamState: BroadcastingStreamState = BroadcastingStreamState(),
    eglContextProvider: () -> EglBase.Context? = { null },
    onRequestMicPermission: () -> Unit = {},
    onSetActiveChatTarget: (ChatTarget?) -> Unit = {},
    onWatchPeerStream: (OnlineClient) -> Unit = {},
    onStopWatchingStream: () -> Unit = {},
    onToggleWatchAudioMute: () -> Unit = {},
    onSetWatchMinimized: (Boolean) -> Unit = {},
    onSetWatchFullscreen: (Boolean) -> Unit = {},
    onStartScreenBroadcast: (Intent, String, Int, Int, Int, Int) -> Unit = { _, _, _, _, _, _ -> },
    onStartCameraBroadcast: (String, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
    onSwitchBroadcastCamera: () -> Unit = {},
    onStopBroadcast: () -> Unit = {},
    onDismissPasswordPrompt: () -> Unit = {},
    onToggleChannelCollapsed: (Long) -> Unit,
    onRequestJoinChannel: (channel: ChannelNode, explicitPassword: String?) -> Unit,
    onRequestChannelDescription: (Long) -> Unit = {},
    onToggleMicMute: () -> Unit,
    onToggleOutputDeafen: () -> Unit,
    onSetSelfAway: (Boolean, String) -> Unit = { _, _ -> },
    onSetPttPressed: (Boolean) -> Unit,
    onSelectAudioRoute: (AudioRoute) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
    onRetryConnect: () -> Unit = {},
    onRetryWithPassword: (String) -> Unit = {},
    onBackToBookmarks: () -> Unit = {},
    onSendChatMessage: (ChatTarget, Long, String) -> Unit,
    onClearUnreadChat: (ChatTarget) -> Unit,
    onOpenDmWith: (OnlineClient) -> Unit,
    onUpdateUserVolume: (uid: String, nickname: String, volumePct: Int, muted: Boolean) -> Unit,
    onSendPoke: (Int, String) -> Unit,
    onUsePrivilegeKey: (String) -> Unit,
    onRequestTalkPower: () -> Unit = {},
    onCreateChannel: (
        name: String,
        parentId: Long,
        password: String,
        topic: String,
        description: String,
        maxClients: Int,
        lifecycle: ChannelLifecycle,
        isDefault: Boolean,
        opusMusic: Boolean,
        quality: Int,
        neededTalkPower: Int,
        autoJoin: Boolean
    ) -> Unit,
    onEditChannel: (
        channelId: Long,
        parentId: Long,
        name: String,
        passwordMode: PasswordEditMode,
        password: String,
        topic: String,
        description: String,
        maxClients: Int,
        lifecycle: ChannelLifecycle,
        isDefault: Boolean,
        opusMusic: Boolean,
        quality: Int,
        neededTalkPower: Int
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onDeleteChannel: (channelId: Long, force: Boolean) -> Unit = { _, _ -> },
    onMoveClientToChannel: (clientId: Int, targetChannelId: Long) -> Unit,
    onKickClient: (Int, Boolean, String) -> Unit,
    onBanClient: (Int, Long, String) -> Unit,
    onRequestServerVariables: () -> Unit = {},
    onRequestClientDetails: (Int) -> Unit = {},
    onUpdateSelfServerNickname: (String) -> Unit = {},
    onSetSelfChannelCommander: (Boolean) -> Unit = {},
    onEditClientDescription: (Int, String) -> Unit = { _, _ -> },
    onSetClientTalkPowerGranted: (Int, Boolean) -> Unit = { _, _ -> },
    onSetClientServerGroup: (Int, Long, Boolean) -> Unit = { _, _, _ -> },
    onSetClientChannelGroup: (Int, Long, Long) -> Unit = { _, _, _ -> },
    onShowMessage: (String) -> Unit = {},
    onOpenDeepLink: (String) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val context = LocalContext.current

    var showServerInfoSheet by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showExitModal by remember { mutableStateOf(false) }
    var showQuickPasswordRetry by remember { mutableStateOf(false) }
    var showStartStreamSheet by remember { mutableStateOf(false) }

    data class PendingStreamCfg(val title: String, val w: Int, val h: Int, val fps: Int, val kbps: Int)
    var pendingScreenCfg by remember { mutableStateOf<PendingStreamCfg?>(null) }
    var pendingCameraCfg by remember { mutableStateOf<PendingStreamCfg?>(null) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val cfg = pendingScreenCfg
        pendingScreenCfg = null
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null && cfg != null) {
            onStartScreenBroadcast(data, cfg.title, cfg.w, cfg.h, cfg.fps, cfg.kbps)
        }
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cfg = pendingCameraCfg
        pendingCameraCfg = null
        if (granted && cfg != null) {
            onStartCameraBroadcast(cfg.title, cfg.w, cfg.h, cfg.fps, cfg.kbps)
        }
    }

    val overlayPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onToggleOverlay(Settings.canDrawOverlays(context))
    }

    // Channel management states
    var selectedChannelIdForSheet by remember { mutableStateOf<Long?>(null) }
    var channelFormState by remember { mutableStateOf<Pair<ChannelNode?, Long>?>(null) }
    var deleteTargetChannel by remember { mutableStateOf<ChannelNode?>(null) }

    // Client / Self management states
    var selectedClientForSheet by remember { mutableStateOf<OnlineClient?>(null) }
    var pokeTargetClient by remember { mutableStateOf<OnlineClient?>(null) }
    var kickOrBanTarget by remember { mutableStateOf<Pair<OnlineClient, Boolean>?>(null) }

    LaunchedEffect(openTokenDialogSeq) {
        if (openTokenDialogSeq > 0L) {
            showTokenDialog = true
        }
    }

    val phase = when {
        status == ConnectionStatus.DISCONNECTED || status == ConnectionStatus.ERROR -> RoomPhase.DISCONNECTED
        status == ConnectionStatus.CONNECTING && snapshot.channels.isEmpty() -> RoomPhase.CONNECTING
        else -> RoomPhase.ACTIVE
    }

    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            (fadeIn(tween(200)) + scaleIn(initialScale = 0.97f, animationSpec = tween(200)))
                .togetherWith(fadeOut(tween(150)))
        },
        label = "roomPhaseTransition",
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
    ) { targetPhase ->
        when (targetPhase) {
            RoomPhase.DISCONNECTED -> {
                DisconnectedRecoveryPanel(
                    isError = status == ConnectionStatus.ERROR,
                    message = statusMessage,
                    lastRequest = lastConnectionRequest,
                    onRetryNow = onRetryConnect,
                    onEditPasswordRetry = { showQuickPasswordRetry = true },
                    onBackToBookmarks = onBackToBookmarks
                )
            }

            RoomPhase.CONNECTING -> {
                ConnectingProgressPanel(
                    statusMessage = statusMessage,
                    onCancel = onDisconnect
                )
            }

            RoomPhase.ACTIVE -> {
                val flatTree = remember(snapshot.channels, snapshot.clients, collapsedChannelIds) {
                    buildChannelTreeRows(snapshot.channels, snapshot.clients, collapsedChannelIds)
                }
                val totalUnread = unreadChatCounts.values.sum()
                val onlineCount = snapshot.clients.count { it.clientType == 0 }
                val myClient = remember(snapshot.clients, snapshot.myClientId) {
                    snapshot.clients.firstOrNull { it.id == snapshot.myClientId }
                }
                val myChannel = remember(snapshot.channels, snapshot.myChannelId) {
                    snapshot.channels.firstOrNull { it.id == snapshot.myChannelId }
                }
                val talkPowerRestricted = myChannel != null &&
                    myChannel.neededTalkPower > 0 &&
                    myClient != null &&
                    myClient.talkPower < myChannel.neededTalkPower &&
                    !myClient.talkPowerGranted
                val isLegacyChannelCodec = myChannel != null && myChannel.codec in 0..3
                val effectiveAway = isSelfAway || myClient?.isAway == true
                val canSelfTransmit = hasMicPermission &&
                    !isMicMuted &&
                    !isOutputMuted &&
                    !effectiveAway &&
                    !talkPowerRestricted &&
                    !isLegacyChannelCodec

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.canvas)
                ) {
                    // Top Server Telemetry Header (tappable to view full Server Info)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)
                    ) {
                        val serverIconKey = "icon_${snapshot.serverIconId and 0xFFFFFFFFL}"
                        val serverIconFile = iconCachePaths[serverIconKey]

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .pressableScale(0.98f) { showServerInfoSheet = true }
                                .padding(vertical = 2.dp, horizontal = 2.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(colors.card)
                                    .border(1.dp, colors.border, CircleShape)
                            ) {
                                if (serverIconFile != null) {
                                    AsyncImage(
                                        model = File(serverIconFile),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Text(
                                        text = snapshot.serverName.ifBlank { "TS" }.take(1).uppercase(),
                                        color = colors.periwinkle,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 16.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = snapshot.serverName.ifBlank { strings.tabRoom },
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = if (strings.isEnglish) "Server Info" else "服务器详情",
                                        tint = colors.periwinkle.copy(alpha = 0.85f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    PulsingEmeraldDot(
                                        size = 7.dp,
                                        color = if (snapshot.pingMs < 95) colors.emerald else colors.marigold
                                    )
                                    val lossPct = (snapshot.packetLossPct * 100f).roundToInt()
                                    Text(
                                        text = if (strings.isEnglish) {
                                            "${snapshot.pingMs} ms · Loss $lossPct% · $onlineCount/${snapshot.maxClients}"
                                        } else {
                                            "${snapshot.pingMs} ms · 丢包 $lossPct% · $onlineCount/${snapshot.maxClients} 在线"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppCircleIconButton(
                                icon = Icons.Default.PictureInPictureAlt,
                                contentDescription = strings.floatingOverlayTitle,
                                active = overlayEnabled,
                                onClick = {
                                    val want = !overlayEnabled
                                    if (want && !Settings.canDrawOverlays(context)) {
                                        runCatching {
                                            overlayPermLauncher.launch(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    } else {
                                        onToggleOverlay(want)
                                    }
                                }
                            )

                            AppCircleIconButton(
                                icon = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = if (strings.isEnglish) "Chat" else "频道聊天",
                                badgeCount = totalUnread,
                                onClick = { showChatSheet = true }
                            )
                        }
                    }

                    // TS6 Live Stream Broadcast Banner & Active Video Stage
                    SelfBroadcastingBanner(
                        state = broadcastingStreamState,
                        isEnglish = strings.isEnglish,
                        onSwitchCamera = onSwitchBroadcastCamera,
                        onStopBroadcast = onStopBroadcast
                    )

                    if (watchingStreamState.status != LiveWatchStatus.IDLE) {
                        val activeEgl = remember(watchingStreamState.status) {
                            runCatching { eglContextProvider() }.getOrNull()
                        }
                        Ts6LiveStreamViewerStage(
                            state = watchingStreamState,
                            videoTrack = remoteVideoTrack,
                            eglContext = activeEgl,
                            isEnglish = strings.isEnglish,
                            onToggleMute = onToggleWatchAudioMute,
                            onSetMinimized = onSetWatchMinimized,
                            onSetFullscreen = onSetWatchFullscreen,
                            onRetry = {
                                val target = snapshot.clients.firstOrNull {
                                    it.id == watchingStreamState.streamerClientId
                                } ?: OnlineClient(
                                    id = watchingStreamState.streamerClientId,
                                    channelId = snapshot.myChannelId,
                                    nickname = watchingStreamState.streamerName,
                                    isStreaming = true,
                                    streamId = watchingStreamState.streamId,
                                    streamName = watchingStreamState.streamTitle,
                                    streamType = watchingStreamState.streamType
                                )
                                onWatchPeerStream(target)
                            },
                            onClose = onStopWatchingStream
                        )
                    }

                    val activeStreamers = remember(snapshot.clients) {
                        snapshot.clients.filter { it.clientType == 0 && it.isStreaming }
                    }
                    ActiveChannelStreamsStrip(
                        streamers = activeStreamers,
                        myClientId = snapshot.myClientId,
                        watchingStreamerId = if (watchingStreamState.status != LiveWatchStatus.IDLE) {
                            watchingStreamState.streamerClientId
                        } else 0,
                        isEnglish = strings.isEnglish,
                        onWatchPeer = onWatchPeerStream
                    )

                    // Channel Hierarchy & Quick Actions
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                RoomQuickActionTile(
                                    icon = Icons.Default.Add,
                                    title = strings.quickNewChannelTitle,
                                    subtitle = strings.quickNewChannelSub,
                                    accentBg = colors.periwinkleSoft,
                                    accentFg = colors.periwinkle,
                                    onClick = { channelFormState = null to 0L },
                                    modifier = Modifier.weight(1f)
                                )
                                RoomQuickActionTile(
                                    icon = Icons.Default.Key,
                                    title = strings.quickTokenTitle,
                                    subtitle = strings.quickTokenSub,
                                    accentBg = colors.marigoldSoft,
                                    accentFg = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                                    onClick = { showTokenDialog = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        items(
                            items = flatTree,
                            key = { row ->
                                when (row) {
                                    is TreeRowItem.ChannelRow -> "ch_${row.channel.id}"
                                    is TreeRowItem.ClientRow -> "cl_${row.client.id}"
                                }
                            }
                        ) { row ->
                            when (row) {
                                is TreeRowItem.ChannelRow -> {
                                    val isMyChannel = row.channel.id == snapshot.myChannelId
                                    val chIconPath = iconCachePaths["icon_${row.channel.iconId and 0xFFFFFFFFL}"]
                                    ChannelRowView(
                                        row = row,
                                        isMyChannel = isMyChannel,
                                        iconPath = chIconPath,
                                        onToggleCollapse = { onToggleChannelCollapsed(row.channel.id) },
                                        onJoin = {
                                            if (!isMyChannel) {
                                                onRequestJoinChannel(row.channel, null)
                                            }
                                        },
                                        onOpenManageSheet = {
                                            selectedChannelIdForSheet = row.channel.id
                                            onRequestChannelDescription(row.channel.id)
                                        }
                                    )
                                }

                                is TreeRowItem.ClientRow -> {
                                    val c = row.client
                                    val isMe = c.id == snapshot.myClientId
                                    val isSpeaking = if (isMe) {
                                        canSelfTransmit && (c.id in speakingIds || isVoiceTransmitting)
                                    } else {
                                        c.id in speakingIds
                                    }
                                    val userPref = userAudioPrefs[c.uid]
                                    val avatarPath = iconCachePaths["avatar_${c.uid}"]
                                    val resolvedBadges = remember(c, snapshot.serverGroups, snapshot.channelGroups) {
                                        c.resolveBadges(snapshot)
                                    }
                                    val (inlineIcons, overflowCount) = remember(resolvedBadges) {
                                        resolvedBadges.inlineIcons(maxVisible = 4)
                                    }
                                    ClientRowView(
                                        client = c,
                                        displayName = resolvedBadges.displayName,
                                        inlineIconIds = inlineIcons,
                                        overflowIconCount = overflowCount,
                                        iconCachePaths = iconCachePaths,
                                        depth = row.depth,
                                        isMe = isMe,
                                        isSpeaking = isSpeaking,
                                        localVolumePct = userPref?.volumePercent ?: 100,
                                        localMuted = userPref?.localMuted ?: false,
                                        avatarPath = avatarPath,
                                        onWatchLive = if (!isMe && c.isStreaming) {
                                            { onWatchPeerStream(c) }
                                        } else null,
                                        onOpenSheet = { selectedClientForSheet = c }
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Voice Control Dock (includes status banners when mic permission, codec, AFK or talk power is restricted)
                    VoiceDockBar(
                        voiceMode = voiceMode,
                        hasMicPermission = hasMicPermission,
                        isMicMuted = isMicMuted,
                        isOutputMuted = isOutputMuted,
                        isSelfAway = effectiveAway,
                        isLegacyChannelCodec = isLegacyChannelCodec,
                        isVoiceTransmitting = canSelfTransmit && isVoiceTransmitting,
                        isPttPressed = canSelfTransmit && isPttPressed,
                        isBroadcasting = broadcastingStreamState.isBroadcasting || broadcastingStreamState.isStarting,
                        moderatedTalkPowerInfo = if (talkPowerRestricted) {
                            myChannel.neededTalkPower to myClient.talkPower
                        } else null,
                        availableRoutes = availableRoutes,
                        activeRoute = activeRoute,
                        onRequestMicPermission = onRequestMicPermission,
                        onToggleMicMute = onToggleMicMute,
                        onToggleOutputDeafen = onToggleOutputDeafen,
                        onExitAfk = { onSetSelfAway(false, "") },
                        onRequestTalkPower = onRequestTalkPower,
                        onToggleLiveBroadcast = {
                            if (broadcastingStreamState.isBroadcasting || broadcastingStreamState.isStarting) {
                                onStopBroadcast()
                            } else {
                                showStartStreamSheet = true
                            }
                        },
                        onSetPttPressed = onSetPttPressed,
                        onSelectRoute = onSelectAudioRoute,
                        onOpenTokenDialog = { showTokenDialog = true },
                        onRequestExit = { showExitModal = true }
                    )
                }
            }
        }
    }

    // --- Bottom Sheets & Modals ---

    val activeSheetChannel = remember(selectedChannelIdForSheet, snapshot.channels) {
        selectedChannelIdForSheet?.let { id -> snapshot.channels.firstOrNull { it.id == id } }
    }
    if (activeSheetChannel != null) {
        val occupantCount = snapshot.clients.count { it.clientType == 0 && it.channelId == activeSheetChannel.id }
        val hasSubChannels = snapshot.channels.any { it.parentId == activeSheetChannel.id }
        ChannelActionBottomSheet(
            channel = activeSheetChannel,
            isMyChannel = activeSheetChannel.id == snapshot.myChannelId,
            occupantCount = occupantCount,
            onDismiss = { selectedChannelIdForSheet = null },
            onJoin = {
                selectedChannelIdForSheet = null
                onRequestJoinChannel(activeSheetChannel, null)
            },
            onCreateSubChannel = {
                val pid = activeSheetChannel.id
                selectedChannelIdForSheet = null
                channelFormState = null to pid
            },
            onEditChannel = {
                val target = activeSheetChannel
                selectedChannelIdForSheet = null
                channelFormState = target to target.parentId
            },
            onDeleteChannel = {
                val target = activeSheetChannel
                selectedChannelIdForSheet = null
                deleteTargetChannel = target
            },
            onClickUrl = { url ->
                if (url.startsWith("ts3server://", ignoreCase = true)) {
                    selectedChannelIdForSheet = null
                    onOpenDeepLink(url)
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            },
            hasChildren = hasSubChannels
        )
    }

    channelFormState?.let { (editingCh, defaultParentId) ->
        ChannelFormDialog(
            editingChannel = editingCh,
            initialParentId = defaultParentId,
            allChannels = snapshot.channels,
            onDismiss = { channelFormState = null },
            onCreate = onCreateChannel,
            onEdit = onEditChannel
        )
    }

    deleteTargetChannel?.let { targetCh ->
        val hasOccupants = snapshot.clients.any { it.clientType == 0 && it.channelId == targetCh.id }
        val hasSub = snapshot.channels.any { it.parentId == targetCh.id }
        DeleteChannelConfirmDialog(
            channel = targetCh,
            hasOccupantsOrChildren = hasOccupants || hasSub,
            onDismiss = { deleteTargetChannel = null },
            onConfirmDelete = { force -> onDeleteChannel(targetCh.id, force) }
        )
    }

    if (showExitModal) {
        val defaultChannel = snapshot.channels.firstOrNull { it.isDefault }
        LeaveOrDisconnectDialog(
            defaultChannel = defaultChannel,
            isInDefaultChannel = defaultChannel == null || snapshot.myChannelId == defaultChannel.id,
            onDismiss = { showExitModal = false },
            onReturnToDefaultLobby = { defCh -> onRequestJoinChannel(defCh, null) },
            onDisconnectServer = onDisconnect
        )
    }

    if (showQuickPasswordRetry && lastConnectionRequest != null) {
        QuickRetryPasswordDialog(
            serverLabel = lastConnectionRequest.label.ifBlank { lastConnectionRequest.address },
            initialPassword = lastConnectionRequest.serverPassword,
            onDismiss = { showQuickPasswordRetry = false },
            onConfirmRetry = onRetryWithPassword
        )
    }

    if (showChatSheet) {
        ChatBottomSheet(
            messages = chatMessages,
            allClients = snapshot.clients,
            myClientId = snapshot.myClientId,
            myChannelId = snapshot.myChannelId,
            activeDmPeer = activeDmPeer,
            onDismiss = { showChatSheet = false },
            onSelectTab = onClearUnreadChat,
            onSetActiveChatTarget = onSetActiveChatTarget,
            onSelectDmPeer = onOpenDmWith,
            onSend = onSendChatMessage,
            onClickUrl = { url ->
                if (url.startsWith("ts3server://", ignoreCase = true)) {
                    showChatSheet = false
                    onOpenDeepLink(url)
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }
        )
    }

    if (showServerInfoSheet) {
        ServerInfoBottomSheet(
            snapshot = snapshot,
            iconCachePaths = iconCachePaths,
            onRequestServerVariables = onRequestServerVariables,
            onCopyText = { label, value, toast ->
                copyPlainTextToClipboard(context, label, value)
                onShowMessage(toast)
            },
            onClickUrl = { url ->
                if (url.startsWith("ts3server://", ignoreCase = true) ||
                    url.startsWith("teamspeak://", ignoreCase = true)
                ) {
                    showServerInfoSheet = false
                    onOpenDeepLink(url)
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            },
            onDismiss = { showServerInfoSheet = false }
        )
    }

    selectedClientForSheet?.let { client ->
        val currentPref = userAudioPrefs[client.uid]
        MemberDetailAndActionSheet(
            initialClient = client,
            snapshot = snapshot,
            isSelfAway = isSelfAway,
            initialVolumePct = currentPref?.volumePercent ?: 100,
            initialLocalMuted = currentPref?.localMuted ?: false,
            iconCachePaths = iconCachePaths,
            onRequestClientDetails = onRequestClientDetails,
            onCopyText = { label, value, toast ->
                copyPlainTextToClipboard(context, label, value)
                onShowMessage(toast)
            },
            onClickUrl = { url ->
                if (url.startsWith("ts3server://", ignoreCase = true) ||
                    url.startsWith("teamspeak://", ignoreCase = true)
                ) {
                    selectedClientForSheet = null
                    onOpenDeepLink(url)
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            },
            onSetSelfAway = onSetSelfAway,
            onUpdateSelfServerNickname = onUpdateSelfServerNickname,
            onSetSelfChannelCommander = onSetSelfChannelCommander,
            onEditClientDescription = onEditClientDescription,
            onSetClientTalkPowerGranted = onSetClientTalkPowerGranted,
            onSetClientServerGroup = onSetClientServerGroup,
            onSetClientChannelGroup = onSetClientChannelGroup,
            onVolumeChanged = { vol, muted ->
                onUpdateUserVolume(client.uid, client.nickname, vol, muted)
            },
            onSendDm = {
                selectedClientForSheet = null
                onOpenDmWith(client)
                showChatSheet = true
            },
            onPoke = {
                selectedClientForSheet = null
                pokeTargetClient = client
            },
            onMoveToChannel = { targetCid ->
                selectedClientForSheet = null
                onMoveClientToChannel(client.id, targetCid)
            },
            onKick = {
                selectedClientForSheet = null
                kickOrBanTarget = client to false
            },
            onBan = {
                selectedClientForSheet = null
                kickOrBanTarget = client to true
            },
            onWatchLive = if (client.id != snapshot.myClientId && client.isStreaming) {
                {
                    selectedClientForSheet = null
                    onWatchPeerStream(client)
                }
            } else null,
            onDismiss = { selectedClientForSheet = null }
        )
    }

    if (showStartStreamSheet) {
        val meName = snapshot.clients.firstOrNull { it.id == snapshot.myClientId }?.nickname
            ?: strings.meBadge
        StartTs6BroadcastBottomSheet(
            defaultNickname = meName,
            isEnglish = strings.isEnglish,
            onDismiss = { showStartStreamSheet = false },
            onStartScreenShare = { title, w, h, fps, kbps ->
                showStartStreamSheet = false
                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                if (mpm != null) {
                    pendingScreenCfg = PendingStreamCfg(title, w, h, fps, kbps)
                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
            },
            onStartCameraShare = { title, w, h, fps, kbps ->
                showStartStreamSheet = false
                val hasCam = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCam) {
                    onStartCameraBroadcast(title, w, h, fps, kbps)
                } else {
                    pendingCameraCfg = PendingStreamCfg(title, w, h, fps, kbps)
                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        )
    }

    passwordPrompt?.let { prompt ->
        val targetNode = snapshot.channels.firstOrNull { it.id == prompt.channelId }
            ?: ChannelNode(id = prompt.channelId, name = prompt.channelName, hasPassword = true)
        ChannelPasswordDialog(
            channelName = prompt.channelName,
            errorHint = prompt.errorHint,
            onDismiss = onDismissPasswordPrompt,
            onConfirm = { pwd -> onRequestJoinChannel(targetNode, pwd) }
        )
    }

    if (showTokenDialog) {
        PrivilegeKeyDialog(
            onDismiss = { showTokenDialog = false },
            onSubmitToken = onUsePrivilegeKey
        )
    }

    pokeTargetClient?.let { target ->
        SendPokeDialog(
            targetClient = target,
            onDismiss = { pokeTargetClient = null },
            onConfirm = { msg -> onSendPoke(target.id, msg) }
        )
    }

    kickOrBanTarget?.let { (target, isBan) ->
        KickOrBanDialog(
            targetClient = target,
            isBan = isBan,
            onDismiss = { kickOrBanTarget = null },
            onKick = { fromSrv, reason -> onKickClient(target.id, fromSrv, reason) },
            onBan = { dur, reason -> onBanClient(target.id, dur, reason) }
        )
    }
}

@Composable
private fun ConnectingProgressPanel(
    statusMessage: String?,
    onCancel: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card)
                .border(1.dp, colors.border, RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 36.dp)
        ) {
            CircularProgressIndicator(
                color = colors.periwinkle,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = strings.connectingTitle,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            AppSecondaryPillButton(
                text = strings.cancelConnect,
                onClick = onCancel
            )
        }
    }
}

@Composable
private fun DisconnectedRecoveryPanel(
    isError: Boolean,
    message: String?,
    lastRequest: LastConnectionRequest?,
    onRetryNow: () -> Unit,
    onEditPasswordRetry: () -> Unit,
    onBackToBookmarks: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.card)
                .border(
                    width = 1.dp,
                    color = if (isError) colors.coral.copy(alpha = 0.45f) else colors.border,
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 24.dp, vertical = 34.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (isError) colors.coralSoft else colors.periwinkleSoft)
            ) {
                Icon(
                    imageVector = Icons.Default.Headset,
                    contentDescription = null,
                    tint = if (isError) colors.coral else colors.periwinkle,
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = if (isError) strings.connectionInterruptedTitle else strings.disconnectedTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                color = colors.textPrimary
            )

            Text(
                text = message?.takeIf { it.isNotBlank() } ?: strings.disconnectedIdleDesc,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) colors.coral else colors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (lastRequest != null) {
                AppPrimaryPillButton(
                    text = "${strings.retryConnectNow} · ${lastRequest.label.ifBlank { lastRequest.address }}",
                    leadingIcon = Icons.Default.Refresh,
                    onClick = onRetryNow,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isError) {
                    AppSecondaryPillButton(
                        text = strings.editPasswordAndRetry,
                        leadingIcon = Icons.Default.Lock,
                        onClick = onEditPasswordRetry,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                AppSecondaryPillButton(
                    text = strings.backToBookmarks,
                    onClick = onBackToBookmarks,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                AppPrimaryPillButton(
                    text = strings.backToBookmarks,
                    onClick = onBackToBookmarks,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun RoomQuickActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentBg: Color,
    accentFg: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .pressableScale(0.96f, onClick = onClick)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accentBg)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentFg,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRowView(
    row: TreeRowItem.ChannelRow,
    isMyChannel: Boolean,
    iconPath: String?,
    onToggleCollapse: () -> Unit,
    onJoin: () -> Unit,
    onOpenManageSheet: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val ch = row.channel

    val spacerMatch = remember(ch.name) {
        if (ch.parentId == 0L) SPACER_REGEX.find(ch.name) else null
    }

    if (spacerMatch != null && !isMyChannel && row.occupantCount == 0 && !row.hasChildren) {
        val rawBody = spacerMatch.groupValues[2].trim()
        val isLineOnly = rawBody.isEmpty() || rawBody.all { it == '-' || it == '=' || it == '_' || it == '—' || it == '*' }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpenManageSheet,
                    onLongClick = onOpenManageSheet
                )
                .padding(vertical = 6.dp, horizontal = 6.dp)
        ) {
            if (isLineOnly) {
                DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())
            } else {
                DashedHorizontalLine(color = colors.border, modifier = Modifier.weight(1f))
                Text(
                    text = rawBody,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                DashedHorizontalLine(color = colors.border, modifier = Modifier.weight(1f))
            }
        }
        return
    }

    val cleanTitle = remember(ch.name, spacerMatch) {
        spacerMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() } ?: ch.name
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (row.depth * 14).dp, top = if (row.depth == 0) 4.dp else 0.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isMyChannel) colors.periwinkleSoft else colors.card)
            .border(
                width = 1.dp,
                color = if (isMyChannel) colors.periwinkle.copy(alpha = 0.55f) else colors.border,
                shape = RoundedCornerShape(18.dp)
            )
            .combinedClickable(
                onClick = {
                    if (isMyChannel) onOpenManageSheet() else onJoin()
                },
                onLongClick = onOpenManageSheet
            )
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    ) {
        if (row.hasChildren) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(colors.cardElevated)
                    .clickable { onToggleCollapse() }
            ) {
                Icon(
                    imageVector = if (row.isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    contentDescription = if (strings.isEnglish) "Toggle Sub-channels" else "展开或折叠子频道",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(17.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isMyChannel) colors.periwinkle else colors.textTertiary)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanTitle,
                style = MaterialTheme.typography.titleSmall,
                color = if (isMyChannel) colors.periwinkle else colors.textPrimary,
                fontWeight = if (isMyChannel) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (ch.topic.isNotBlank()) {
                Text(
                    text = ch.topic,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isMyChannel) {
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.periwinkle)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = strings.currentChannelBadge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (!ch.isPermanent) {
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.cardElevated)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = if (ch.isSemiPermanent) strings.lifecycleSemi else strings.lifecycleTemp,
                    color = colors.textSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (ch.neededTalkPower > 0) {
            Icon(
                imageVector = Icons.Default.MicOff,
                contentDescription = null,
                tint = colors.marigold,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(13.dp)
            )
        }

        if (ch.codec == 5) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = colors.periwinkle,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(15.dp)
            )
        }

        if (ch.hasPassword) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = colors.marigold,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(14.dp)
            )
        }

        if (iconPath != null) {
            AsyncImage(
                model = File(iconPath),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        val countLabel = if (ch.maxClients > 0) {
            "${row.occupantCount}/${ch.maxClients}"
        } else if (row.occupantCount > 0) {
            "${row.occupantCount}"
        } else {
            ""
        }
        if (countLabel.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(colors.cardElevated)
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }

        // Trailing channel manage button ("⋯")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable { onOpenManageSheet() }
        ) {
            Icon(
                imageVector = Icons.Default.MoreHoriz,
                contentDescription = if (strings.isEnglish) "Channel Options" else "频道管理选项",
                tint = colors.textSecondary,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClientRowView(
    client: OnlineClient,
    displayName: String,
    inlineIconIds: List<Long>,
    overflowIconCount: Int,
    iconCachePaths: Map<String, String>,
    depth: Int,
    isMe: Boolean,
    isSpeaking: Boolean,
    localVolumePct: Int,
    localMuted: Boolean,
    avatarPath: String?,
    onWatchLive: (() -> Unit)? = null,
    onOpenSheet: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = ((depth * 14) + 18).dp)
    ) {
        DashedVerticalLine(
            color = if (isSpeaking) colors.emerald else colors.border,
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 2.dp)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(50))
                .background(if (isSpeaking) colors.emeraldSoft else Color.Transparent)
                .combinedClickable(
                    onClick = onOpenSheet,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onOpenSheet()
                    }
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.cardElevated)
                    .border(
                        width = if (isSpeaking) 2.dp else 1.dp,
                        color = when {
                            isSpeaking -> colors.emerald
                            client.isChannelCommander -> colors.marigold
                            else -> colors.border
                        },
                        shape = CircleShape
                    )
            ) {
                if (avatarPath != null) {
                    AsyncImage(
                        model = File(avatarPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = client.nickname.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSpeaking) colors.emerald else colors.textPrimary,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSpeaking || isMe) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    localMuted -> colors.textTertiary
                    isSpeaking -> colors.emerald
                    else -> colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            if (isMe) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.periwinkleSoft)
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = strings.meBadge,
                        color = colors.periwinkle,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (client.hasTalkPowerRequest) {
                Icon(
                    imageVector = Icons.Default.PanTool,
                    contentDescription = if (strings.isEnglish) "Requesting Talk Power" else "申请发言",
                    tint = colors.marigold,
                    modifier = Modifier
                        .padding(end = 5.dp)
                        .size(14.dp)
                )
            }

            if (client.isStreaming) {
                LiveBadgePill(
                    isEnglish = strings.isEnglish,
                    viewers = client.streamViewers,
                    onClick = onWatchLive,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            if (isSpeaking) {
                PulsingEmeraldDot(size = 6.dp, modifier = Modifier.padding(end = 4.dp))
            }

            if (localVolumePct != 100 && !localMuted) {
                Text(
                    text = "$localVolumePct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.periwinkle,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            if (client.isAway) {
                Text(
                    text = strings.awayBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.marigold,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }

            if (inlineIconIds.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.padding(end = 5.dp)
                ) {
                    inlineIconIds.forEach { iconId ->
                        TsIconBadge(
                            iconId = iconId,
                            iconCachePaths = iconCachePaths,
                            size = 15.dp
                        )
                    }
                    if (overflowIconCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.cardElevated)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "+$overflowIconCount",
                                color = colors.textSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (localMuted || client.outputMuted || !client.outputHardware) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = null,
                    tint = colors.coral,
                    modifier = Modifier.size(16.dp)
                )
            } else if (client.inputMuted || !client.inputHardware) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = null,
                    tint = colors.coral,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceDockBar(
    voiceMode: VoiceMode,
    hasMicPermission: Boolean = true,
    isMicMuted: Boolean,
    isOutputMuted: Boolean,
    isSelfAway: Boolean = false,
    isLegacyChannelCodec: Boolean = false,
    isVoiceTransmitting: Boolean,
    isPttPressed: Boolean,
    isBroadcasting: Boolean = false,
    moderatedTalkPowerInfo: Pair<Int, Int>?,
    availableRoutes: List<AudioRoute>,
    activeRoute: AudioRoute,
    onRequestMicPermission: () -> Unit = {},
    onToggleMicMute: () -> Unit,
    onToggleOutputDeafen: () -> Unit,
    onExitAfk: () -> Unit = {},
    onRequestTalkPower: () -> Unit = {},
    onToggleLiveBroadcast: () -> Unit = {},
    onSetPttPressed: (Boolean) -> Unit,
    onSelectRoute: (AudioRoute) -> Unit,
    onOpenTokenDialog: () -> Unit,
    onRequestExit: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val haptics = LocalHapticFeedback.current
    var routeMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!hasMicPermission) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.coralSoft)
                    .border(1.dp, colors.coral.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .clickable { onRequestMicPermission() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = null,
                    tint = colors.coral,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (strings.isEnglish) {
                        "Mic permission denied (Listen-only)"
                    } else {
                        "未授予麦克风权限（当前仅收听，麦克风不可用）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.coral,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.coral)
                        .clickable { onRequestMicPermission() }
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (strings.isEnglish) "Grant Mic" else "授权开麦",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (isLegacyChannelCodec) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.marigoldSoft)
                    .border(1.dp, colors.marigold.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = null,
                    tint = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (strings.isEnglish) {
                        "Channel uses legacy Speex/CELT codec — Opus required for voice"
                    } else {
                        "当前频道使用旧版 Speex/CELT 编码，仅支持 Opus 频道语音收发"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (isSelfAway) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.marigoldSoft)
                    .border(1.dp, colors.marigold.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = null,
                    tint = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = strings.afkChannelBanner,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.card)
                        .clickable { onExitAfk() }
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = strings.exitAfkAction,
                        color = colors.periwinkle,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Persistent Amber Warning Banner when in a moderated channel without sufficient Talk Power
        if (moderatedTalkPowerInfo != null) {
            val (needed, have) = moderatedTalkPowerInfo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.marigoldSoft)
                    .border(1.dp, colors.marigold.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MicOff,
                    contentDescription = null,
                    tint = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = strings.moderatedChannelBanner(needed, have),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.card)
                        .clickable { onRequestTalkPower() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = strings.requestTalkAction,
                        color = colors.periwinkle,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.card)
                        .clickable { onOpenTokenDialog() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = strings.enterTokenAction,
                        color = colors.periwinkle,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (voiceMode == VoiceMode.PTT) {
            val pttBlocked = !hasMicPermission ||
                isLegacyChannelCodec ||
                isMicMuted ||
                isOutputMuted ||
                isSelfAway ||
                moderatedTalkPowerInfo != null
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            !hasMicPermission -> colors.coralSoft
                            pttBlocked -> colors.cardElevated
                            isPttPressed -> colors.emerald
                            else -> colors.periwinkleSoft
                        }
                    )
                    .border(
                        width = 1.2.dp,
                        color = when {
                            !hasMicPermission -> colors.coral.copy(alpha = 0.55f)
                            pttBlocked -> colors.border
                            isPttPressed -> colors.emerald
                            else -> colors.periwinkle.copy(alpha = 0.55f)
                        },
                        shape = RoundedCornerShape(50)
                    )
                    .then(
                        if (!hasMicPermission) {
                            Modifier.clickable { onRequestMicPermission() }
                        } else {
                            Modifier.pointerInput(pttBlocked) {
                                if (pttBlocked) return@pointerInput
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSetPttPressed(true)
                                    try {
                                        waitForUpOrCancellation()
                                    } finally {
                                        onSetPttPressed(false)
                                    }
                                }
                            }
                        }
                    )
            ) {
                Text(
                    text = when {
                        !hasMicPermission -> if (strings.isEnglish) {
                            "Mic Permission Disabled · Tap to Grant"
                        } else {
                            "未开启麦克风权限 · 点击此处授权"
                        }
                        isLegacyChannelCodec -> if (strings.isEnglish) {
                            "Legacy Codec Unsupported"
                        } else {
                            "旧版频道编码不支持发声"
                        }
                        isMicMuted || isOutputMuted -> strings.pttMutedHint
                        isSelfAway -> strings.pttAfkHint
                        moderatedTalkPowerInfo != null -> strings.pttNoTalkPowerHint
                        isPttPressed -> strings.pttSpeaking
                        else -> strings.pttHoldToTalk
                    },
                    color = when {
                        !hasMicPermission -> colors.coral
                        pttBlocked -> colors.textSecondary
                        isPttPressed -> Color(0xFF06291D)
                        else -> colors.periwinkle
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val micOff = !hasMicPermission || isMicMuted
                DockCircleControl(
                    icon = if (micOff) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (strings.isEnglish) "Toggle Microphone" else "切换麦克风开关",
                    bg = when {
                        micOff -> colors.coralSoft
                        isVoiceTransmitting -> colors.emeraldSoft
                        else -> colors.cardElevated
                    },
                    tint = when {
                        micOff -> colors.coral
                        isVoiceTransmitting -> colors.emerald
                        else -> colors.textPrimary
                    },
                    onClick = {
                        if (hasMicPermission) onToggleMicMute() else onRequestMicPermission()
                    }
                )

                DockCircleControl(
                    icon = if (isOutputMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (strings.isEnglish) "Toggle Output Deafen" else "切换耳机静音",
                    bg = if (isOutputMuted) colors.coralSoft else colors.cardElevated,
                    tint = if (isOutputMuted) colors.coral else colors.textPrimary,
                    onClick = onToggleOutputDeafen
                )

                DockCircleControl(
                    icon = if (isBroadcasting) {
                        Icons.AutoMirrored.Filled.StopScreenShare
                    } else {
                        Icons.AutoMirrored.Filled.ScreenShare
                    },
                    contentDescription = if (strings.isEnglish) "Live Stream" else "屏幕或摄像头直播",
                    bg = if (isBroadcasting) Color(0x2AE11D48) else colors.cardElevated,
                    tint = if (isBroadcasting) Color(0xFFE11D48) else colors.textPrimary,
                    onClick = onToggleLiveBroadcast
                )

                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .pressableScale(0.95f) { routeMenuExpanded = true }
                            .clip(RoundedCornerShape(50))
                            .background(colors.cardElevated)
                            .border(1.dp, colors.border, RoundedCornerShape(50))
                            .padding(horizontal = 14.dp)
                    ) {
                        Icon(
                            imageVector = when (activeRoute) {
                                AudioRoute.BLUETOOTH -> Icons.Default.BluetoothAudio
                                AudioRoute.SPEAKER -> Icons.Default.Speaker
                                else -> Icons.Default.Headset
                            },
                            contentDescription = null,
                            tint = colors.periwinkle,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = activeRoute.localizedLabel(strings.isEnglish),
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    DropdownMenu(
                        expanded = routeMenuExpanded,
                        onDismissRequest = { routeMenuExpanded = false }
                    ) {
                        availableRoutes.forEach { r ->
                            DropdownMenuItem(
                                text = { Text(r.localizedLabel(strings.isEnglish)) },
                                onClick = {
                                    onSelectRoute(r)
                                    routeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .height(42.dp)
                    .pressableScale(0.95f, onClick = onRequestExit)
                    .clip(RoundedCornerShape(50))
                    .background(colors.coral)
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = strings.exitButton,
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = strings.exitButton,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun DockCircleControl(
    icon: ImageVector,
    contentDescription: String?,
    bg: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(42.dp)
            .pressableScale(0.92f, onClick = onClick)
            .clip(CircleShape)
            .background(bg)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelActionBottomSheet(
    channel: ChannelNode,
    isMyChannel: Boolean,
    occupantCount: Int,
    hasChildren: Boolean,
    onDismiss: () -> Unit,
    onJoin: () -> Unit,
    onCreateSubChannel: () -> Unit,
    onEditChannel: () -> Unit,
    onDeleteChannel: () -> Unit,
    onClickUrl: (String) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings

    val typeBadge = when {
        channel.isDefault -> if (strings.isEnglish) "Default Lobby" else "默认大厅"
        channel.isPermanent -> strings.lifecyclePerm
        channel.isSemiPermanent -> strings.lifecycleSemi
        else -> strings.lifecycleTemp
    }
    val codecBadge = when (channel.codec) {
        5 -> "${strings.opusMusicLabel} · Lv.${channel.codecQuality}"
        4 -> "${strings.opusVoiceLabel} · Lv.${channel.codecQuality}"
        else -> if (strings.isEnglish) "Legacy Codec (${channel.codec})" else "旧版编码 (${channel.codec})"
    }
    val maxBadge = if (channel.maxClients > 0) {
        "$occupantCount / ${channel.maxClients}"
    } else {
        if (strings.isEnglish) "$occupantCount online" else "在线 $occupantCount 人"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InfoPillChip(text = typeBadge, tint = colors.periwinkle, bg = colors.periwinkleSoft)
                    InfoPillChip(text = codecBadge, tint = colors.textSecondary, bg = colors.cardElevated)
                    InfoPillChip(text = maxBadge, tint = colors.textSecondary, bg = colors.cardElevated)
                    if (channel.neededTalkPower > 0) {
                        InfoPillChip(
                            text = if (strings.isEnglish) "Talk ≥ ${channel.neededTalkPower}" else "发言权 ≥ ${channel.neededTalkPower}",
                            tint = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                            bg = colors.marigoldSoft
                        )
                    }
                }
            }

            if (channel.topic.isNotBlank() || channel.description.isNotBlank()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.cardElevated)
                        .padding(14.dp)
                ) {
                    if (channel.topic.isNotBlank()) {
                        Text(
                            text = channel.topic,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary
                        )
                    }
                    if (channel.description.isNotBlank()) {
                        val annotated = remember(channel.description) {
                            BBCodeParser.parse(channel.description)
                        }
                        @Suppress("DEPRECATION")
                        ClickableText(
                            text = annotated,
                            style = TextStyle(
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            onClick = { offset ->
                                annotated.getStringAnnotations(BBCodeParser.URL_TAG, offset, offset)
                                    .firstOrNull()
                                    ?.let { onClickUrl(it.item) }
                            }
                        )
                    }
                }
            }

            if (!isMyChannel) {
                AppPrimaryPillButton(
                    text = strings.joinThisChannel,
                    onClick = onJoin,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppSecondaryPillButton(
                    text = strings.createSubChannelHere,
                    leadingIcon = Icons.Default.Add,
                    onClick = onCreateSubChannel,
                    modifier = Modifier.weight(1f)
                )
                AppSecondaryPillButton(
                    text = strings.edit,
                    leadingIcon = Icons.Default.Edit,
                    onClick = onEditChannel,
                    modifier = Modifier.weight(1f)
                )
            }

            AppSecondaryPillButton(
                text = if (channel.isDefault) {
                    if (strings.isEnglish) "Default Lobby Cannot Be Deleted" else "默认大厅不可删除"
                } else {
                    strings.deleteChannelTitle
                },
                leadingIcon = Icons.Default.DeleteOutline,
                enabled = !channel.isDefault,
                tint = colors.coral,
                onClick = onDeleteChannel,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun InfoPillChip(text: String, tint: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatBottomSheet(
    messages: List<ChatMessage>,
    allClients: List<OnlineClient>,
    myClientId: Int,
    myChannelId: Long,
    activeDmPeer: OnlineClient?,
    onDismiss: () -> Unit,
    onSelectTab: (ChatTarget) -> Unit,
    onSetActiveChatTarget: (ChatTarget?) -> Unit,
    onSelectDmPeer: (OnlineClient) -> Unit,
    onSend: (ChatTarget, Long, String) -> Unit,
    onClickUrl: (String) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    var selectedTab by remember(activeDmPeer?.uid, activeDmPeer?.id) {
        mutableStateOf(if (activeDmPeer != null) ChatTarget.CLIENT else ChatTarget.CHANNEL)
    }
    var draft by remember { mutableStateOf("") }
    val chatLocale = if (strings.isEnglish) Locale.US else Locale.SIMPLIFIED_CHINESE
    val timeFmt = remember(chatLocale) { SimpleDateFormat("HH:mm", chatLocale) }
    val listState = rememberLazyListState()

    val resolvedDmPeer = remember(activeDmPeer, allClients) {
        activeDmPeer?.let { peer ->
            (if (peer.uid.isNotBlank()) allClients.firstOrNull { it.uid == peer.uid } else null)
                ?: allClients.firstOrNull { it.id == peer.id }
                ?: peer
        }
    }

    LaunchedEffect(selectedTab) {
        onSetActiveChatTarget(selectedTab)
        onSelectTab(selectedTab)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSetActiveChatTarget(null)
        }
    }

    val filteredMessages = remember(messages, selectedTab, resolvedDmPeer) {
        messages.filter { msg ->
            when (selectedTab) {
                ChatTarget.SERVER -> msg.target == ChatTarget.SERVER
                ChatTarget.CHANNEL -> msg.target == ChatTarget.CHANNEL
                ChatTarget.CLIENT -> msg.target == ChatTarget.CLIENT && (
                    resolvedDmPeer == null ||
                        (resolvedDmPeer.uid.isNotBlank() && (msg.peerUid == resolvedDmPeer.uid || msg.senderUid == resolvedDmPeer.uid)) ||
                        msg.peerClientId == resolvedDmPeer.id ||
                        msg.senderId == resolvedDmPeer.id
                    )
            }
        }
    }

    LaunchedEffect(filteredMessages.size, selectedTab) {
        if (filteredMessages.isNotEmpty()) {
            listState.animateScrollToItem(filteredMessages.lastIndex)
        }
    }

    val canSendPrivate = selectedTab != ChatTarget.CLIENT || resolvedDmPeer != null
    val sendCurrentDraft = {
        if (draft.isNotBlank() && canSendPrivate) {
            val targetId = when (selectedTab) {
                ChatTarget.SERVER -> 0L
                ChatTarget.CHANNEL -> myChannelId
                ChatTarget.CLIENT -> (resolvedDmPeer?.id ?: 0).toLong()
            }
            onSend(selectedTab, targetId, draft)
            draft = ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(490.dp)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            val tabs = listOf(
                ChatTarget.CHANNEL to (if (strings.isEnglish) "Channel" else "当前频道"),
                ChatTarget.SERVER to (if (strings.isEnglish) "Server" else "服务器"),
                ChatTarget.CLIENT to (
                    resolvedDmPeer?.let { "${if (strings.isEnglish) "DM" else "私聊"}·${it.nickname}" }
                        ?: (if (strings.isEnglish) "Direct" else "私聊")
                    )
            )

            AppSegmentedPills(
                options = tabs,
                selected = selectedTab,
                onSelect = { target ->
                    selectedTab = target
                    onSelectTab(target)
                }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredMessages, key = { it.id }) { msg ->
                    val annotated = remember(msg.content) { BBCodeParser.parse(msg.content) }
                    val canSelectSenderForDm = msg.target == ChatTarget.CLIENT &&
                        !msg.isSystem &&
                        msg.senderId != 0 &&
                        msg.senderId != myClientId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.cardElevated)
                            .then(
                                if (canSelectSenderForDm) {
                                    Modifier.clickable {
                                        val peer = allClients.firstOrNull {
                                            (msg.senderUid.isNotBlank() && it.uid == msg.senderUid) ||
                                                it.id == msg.senderId
                                        } ?: OnlineClient(
                                            id = msg.senderId,
                                            channelId = myChannelId,
                                            uid = msg.senderUid,
                                            nickname = msg.senderName
                                        )
                                        onSelectDmPeer(peer)
                                    }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = msg.senderName,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (msg.isSystem) colors.marigold else colors.periwinkle,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeFmt.format(Date(msg.timestampMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary
                            )
                            if (canSelectSenderForDm && resolvedDmPeer == null) {
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = if (strings.isEnglish) "Tap to reply" else "点击回复",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.periwinkle,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        @Suppress("DEPRECATION")
                        ClickableText(
                            text = annotated,
                            style = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            onClick = { offset ->
                                val hitUrl = annotated.getStringAnnotations(BBCodeParser.URL_TAG, offset, offset)
                                    .firstOrNull()
                                if (hitUrl != null) {
                                    onClickUrl(hitUrl.item)
                                } else if (canSelectSenderForDm && resolvedDmPeer == null) {
                                    val peer = allClients.firstOrNull {
                                        (msg.senderUid.isNotBlank() && it.uid == msg.senderUid) ||
                                            it.id == msg.senderId
                                    } ?: OnlineClient(
                                        id = msg.senderId,
                                        channelId = myChannelId,
                                        uid = msg.senderUid,
                                        nickname = msg.senderName
                                    )
                                    onSelectDmPeer(peer)
                                }
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppCapsuleField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = canSendPrivate,
                    placeholder = if (canSendPrivate) {
                        if (strings.isEnglish) "Type a message..." else "输入消息..."
                    } else {
                        if (strings.isEnglish) "Tap a DM message or channel member to reply" else "轻点上方私聊消息或频道成员即可发起回复"
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendCurrentDraft() }),
                    modifier = Modifier.weight(1f)
                )

                AppCircleIconButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (strings.isEnglish) "Send" else "发送",
                    size = 44.dp,
                    active = draft.isNotBlank() && canSendPrivate,
                    onClick = sendCurrentDraft
                )
            }
        }
    }
}

private fun buildChannelTreeRows(
    channels: List<ChannelNode>,
    clients: List<OnlineClient>,
    collapsedIds: Set<Long>
): List<TreeRowItem> {
    val childrenByParent = channels.groupBy { it.parentId }
    val voiceClientsByChannel = clients
        .filter { it.clientType == 0 }
        .sortedWith(compareByDescending<OnlineClient> { it.talkPower }.thenBy { it.nickname.lowercase() })
        .groupBy { it.channelId }

    val result = mutableListOf<TreeRowItem>()

    fun sortSiblings(siblings: List<ChannelNode>): List<ChannelNode> {
        if (siblings.size <= 1) return siblings
        val byOrder = siblings.groupBy { it.order }
        val ordered = mutableListOf<ChannelNode>()
        val visited = mutableSetOf<Long>()

        fun walkChain(afterId: Long) {
            val matches = byOrder[afterId].orEmpty().sortedBy { it.id }
            for (m in matches) {
                if (visited.add(m.id)) {
                    ordered.add(m)
                    walkChain(m.id)
                }
            }
        }
        walkChain(0L)
        for (s in siblings.sortedBy { it.id }) {
            if (visited.add(s.id)) ordered.add(s)
        }
        return ordered
    }

    fun traverse(parentId: Long, depth: Int) {
        val siblings = sortSiblings(childrenByParent[parentId].orEmpty())
        for (ch in siblings) {
            val subChildren = childrenByParent[ch.id].orEmpty()
            val channelClients = voiceClientsByChannel[ch.id].orEmpty()
            val isCollapsed = ch.id in collapsedIds
            result.add(
                TreeRowItem.ChannelRow(
                    channel = ch,
                    depth = depth,
                    hasChildren = subChildren.isNotEmpty(),
                    isCollapsed = isCollapsed,
                    occupantCount = channelClients.size
                )
            )
            if (!isCollapsed) {
                for (cl in channelClients) {
                    result.add(TreeRowItem.ClientRow(cl, depth))
                }
                traverse(ch.id, depth + 1)
            }
        }
    }

    traverse(0L, 0)
    return result
}
