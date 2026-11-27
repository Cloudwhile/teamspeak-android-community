package com.cloudwhile.tscc.ui.screens.room

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cloudwhile.tscc.BuildConfig
import com.cloudwhile.tscc.data.model.ChannelNode
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.ServerSnapshot
import com.cloudwhile.tscc.data.model.resolveBadges
import com.cloudwhile.tscc.data.util.BBCodeParser
import com.cloudwhile.tscc.data.util.TsccServerUri
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppModalDialog
import com.cloudwhile.tscc.ui.components.AppPillSwitch
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppSecondaryPillButton
import com.cloudwhile.tscc.ui.components.AppSegmentedPills
import com.cloudwhile.tscc.ui.components.DashedHorizontalLine
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.theme.AppTheme
import java.io.File
import kotlin.math.roundToInt

/**
 * Renders either a downloaded custom TeamSpeak icon (`icon_<unsignedId>`) or a crisp built-in
 * vector badge for standard TeamSpeak icon IDs (`100..600`) and pending/firewalled custom icons.
 */
@Composable
fun TsIconBadge(
    iconId: Long,
    iconCachePaths: Map<String, String>,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier
) {
    val unsignedId = iconId and 0xFFFFFFFFL
    if (unsignedId == 0L) return

    val colors = AppTheme.colors
    val cachedPath = iconCachePaths["icon_$unsignedId"]
    if (cachedPath != null) {
        AsyncImage(
            model = File(cachedPath),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(4.dp))
        )
        return
    }

    val (vec, bg, fg) = when (unsignedId) {
        300L -> Triple(Icons.Default.Shield, colors.coralSoft, colors.coral) // Server Admin
        100L -> Triple(
            Icons.Default.AdminPanelSettings,
            colors.marigoldSoft,
            if (colors.isDark) colors.marigold else colors.inkOnPastel
        ) // Channel Admin
        200L -> Triple(Icons.Default.VerifiedUser, colors.periwinkleSoft, colors.periwinkle) // Operator
        600L -> Triple(Icons.Default.RecordVoiceOver, colors.emeraldSoft, colors.emerald) // Voice / Talker
        400L, 500L -> Triple(Icons.Default.Person, colors.cardElevated, colors.textSecondary) // Normal / Guest
        else -> Triple(Icons.Default.Star, colors.periwinkleSoft, colors.periwinkle) // Custom icon pending/firewalled
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
    ) {
        Icon(
            imageVector = vec,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size((size.value * 0.72f).dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerInfoBottomSheet(
    snapshot: ServerSnapshot,
    iconCachePaths: Map<String, String>,
    onRequestServerVariables: () -> Unit,
    onCopyText: (label: String, value: String, toast: String) -> Unit,
    onClickUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val isEn = strings.isEnglish

    LaunchedEffect(snapshot.sessionId) {
        onRequestServerVariables()
    }

    val onlineCount = remember(snapshot.clients) {
        snapshot.clients.count { it.clientType == 0 }
    }
    val lossPct = (snapshot.packetLossPct * 100f).roundToInt()
    val serverIconPath = iconCachePaths["icon_${snapshot.serverIconId and 0xFFFFFFFFL}"]

    val encryptionLabel = when (snapshot.codecEncryptionMode) {
        1 -> if (isEn) "Globally Off" else "全局关闭"
        2 -> if (isEn) "Forced Encrypted" else "全局强制加密"
        else -> if (isEn) "Per-Channel" else "按频道配置"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (snapshot.hostbannerGfxUrl.isNotBlank()) {
                AsyncImage(
                    model = snapshot.hostbannerGfxUrl,
                    contentDescription = snapshot.serverName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp, max = 112.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.cardElevated)
                        .then(
                            if (snapshot.hostbannerUrl.isNotBlank()) {
                                Modifier.clickable { onClickUrl(snapshot.hostbannerUrl) }
                            } else Modifier
                        )
                )
            }

            // Server Identity Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.cardElevated)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                ) {
                    if (serverIconPath != null) {
                        AsyncImage(
                            model = File(serverIconPath),
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        Text(
                            text = snapshot.serverName.ifBlank { "TS" }.take(1).uppercase(),
                            color = colors.periwinkle,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = snapshot.serverName.ifBlank { strings.tabRoom },
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (snapshot.serverAddress.isNotBlank()) {
                        Text(
                            text = snapshot.serverAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.periwinkle,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Quick Copy Address & Invite Link Row
            if (snapshot.serverAddress.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppSecondaryPillButton(
                        text = if (isEn) "Copy Address" else "复制地址",
                        leadingIcon = Icons.Default.ContentCopy,
                        onClick = {
                            onCopyText(
                                "server_addr",
                                snapshot.serverAddress,
                                if (isEn) "Copied server address" else "已复制服务器地址"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    AppSecondaryPillButton(
                        text = if (isEn) "Share Link" else "复制邀请链接",
                        leadingIcon = Icons.Default.Share,
                        tint = colors.periwinkle,
                        onClick = {
                            val parts = snapshot.serverAddress.split(':')
                            val host = parts.firstOrNull().orEmpty()
                            val port = parts.getOrNull(1)?.toIntOrNull() ?: 9987
                            val invite = TsccServerUri(
                                host = host,
                                port = port,
                                label = snapshot.serverName
                            ).toUriString()
                            onCopyText(
                                "tscc_invite",
                                invite,
                                if (isEn) "Server invite link copied" else "已复制服务器邀请链接"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Telemetry & Capacity Metrics Grid
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.cardElevated)
                    .padding(14.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ServerMetricCell(
                        label = if (isEn) "Online / Slots" else "在线成员 / 槽位",
                        value = buildString {
                            append("$onlineCount / ${snapshot.maxClients}")
                            if (snapshot.reservedSlots > 0) {
                                append(if (isEn) " (+${snapshot.reservedSlots})" else " (含保留 ${snapshot.reservedSlots})")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ServerMetricCell(
                        label = if (isEn) "Ping & Loss" else "实时延迟与丢包",
                        value = "${snapshot.pingMs} ms · $lossPct%",
                        valueColor = if (snapshot.pingMs < 95 && lossPct == 0) colors.emerald else colors.marigold,
                        modifier = Modifier.weight(1f)
                    )
                }

                DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ServerMetricCell(
                        label = if (isEn) "Uptime" else "持续运行时长",
                        value = if (snapshot.uptimeSec > 0L) {
                            formatDurationHuman(snapshot.uptimeSec, isEn)
                        } else {
                            "—"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ServerMetricCell(
                        label = if (isEn) "Channels & Security" else "频道数 / 安全门槛",
                        value = strings.channelCountAndSecurity(
                            snapshot.channels.size,
                            snapshot.neededSecurityLevel.coerceAtLeast(8)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ServerMetricCell(
                        label = if (isEn) "Platform & Version" else "系统平台与服务端版本",
                        value = listOf(snapshot.platform, snapshot.version)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifBlank { "—" },
                        modifier = Modifier.weight(1f)
                    )
                    ServerMetricCell(
                        label = if (isEn) "Voice Encryption" else "语音加密策略",
                        value = encryptionLabel,
                        modifier = Modifier.weight(0.75f)
                    )
                }

                if (snapshot.serverUid.isNotBlank()) {
                    DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onCopyText(
                                    "server_uid",
                                    snapshot.serverUid,
                                    if (isEn) "Copied Server UID" else "已复制服务器 UID"
                                )
                            }
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.serverUidLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textTertiary
                            )
                            Text(
                                text = snapshot.serverUid,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = strings.copyServerUidDesc,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            if (snapshot.hostMessage.isNotBlank()) {
                RichServerTextCard(
                    title = if (isEn) "Host Announcement" else "服务器公告",
                    rawBbCode = snapshot.hostMessage,
                    onClickUrl = onClickUrl
                )
            }

            if (snapshot.welcomeMessage.isNotBlank()) {
                RichServerTextCard(
                    title = if (isEn) "Welcome Message" else "欢迎词",
                    rawBbCode = snapshot.welcomeMessage,
                    onClickUrl = onClickUrl
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ServerMetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null
) {
    val colors = AppTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = valueColor ?: colors.textPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RichServerTextCard(
    title: String,
    rawBbCode: String,
    onClickUrl: (String) -> Unit
) {
    val colors = AppTheme.colors
    val annotated = remember(rawBbCode) { BBCodeParser.parse(rawBbCode) }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.cardElevated)
            .padding(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MemberDetailAndActionSheet(
    initialClient: OnlineClient,
    snapshot: ServerSnapshot,
    isSelfAway: Boolean,
    initialVolumePct: Int,
    initialLocalMuted: Boolean,
    iconCachePaths: Map<String, String>,
    onRequestClientDetails: (Int) -> Unit,
    onCopyText: (label: String, value: String, toast: String) -> Unit,
    onClickUrl: (String) -> Unit,
    onSetSelfAway: (Boolean, String) -> Unit,
    onUpdateSelfServerNickname: (String) -> Unit,
    onSetSelfChannelCommander: (Boolean) -> Unit,
    onEditClientDescription: (Int, String) -> Unit,
    onSetClientTalkPowerGranted: (Int, Boolean) -> Unit,
    onSetClientServerGroup: (Int, Long, Boolean) -> Unit,
    onSetClientChannelGroup: (Int, Long, Long) -> Unit,
    onVolumeChanged: (Int, Boolean) -> Unit,
    onSendDm: () -> Unit,
    onPoke: () -> Unit,
    onMoveToChannel: (Long) -> Unit,
    onKick: () -> Unit,
    onBan: () -> Unit,
    onWatchLive: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val isEn = strings.isEnglish

    val client = remember(snapshot.clients, initialClient.id) {
        snapshot.clients.firstOrNull { it.id == initialClient.id } ?: initialClient
    }
    val isMe = client.id == snapshot.myClientId
    val badges = remember(client, snapshot.serverGroups, snapshot.channelGroups) {
        client.resolveBadges(snapshot)
    }
    val avatarPath = iconCachePaths["avatar_${client.uid}"]

    LaunchedEffect(client.id) {
        onRequestClientDetails(client.id)
    }

    var volume by remember(client.uid) { mutableFloatStateOf(initialVolumePct.toFloat()) }
    var muted by remember(client.uid) { mutableStateOf(initialLocalMuted) }
    var moveMenuOpen by remember { mutableStateOf(false) }

    var awayChecked by remember(client.id, isSelfAway, client.isAway) {
        mutableStateOf(if (isMe) (isSelfAway || client.isAway) else client.isAway)
    }
    var awayMsg by remember(client.id) { mutableStateOf(client.awayMessage) }

    var showRenameSelfDialog by remember { mutableStateOf(false) }
    var showEditDescDialog by remember { mutableStateOf(false) }
    var showGroupManageDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Member Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(colors.cardElevated)
                        .border(
                            width = 1.5.dp,
                            color = if (client.isChannelCommander) colors.marigold else colors.border,
                            shape = CircleShape
                        )
                ) {
                    if (avatarPath != null) {
                        AsyncImage(
                            model = File(avatarPath),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp)
                        )
                    } else {
                        Text(
                            text = client.nickname.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.periwinkle,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = badges.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (client.unsignedIconId != 0L) {
                            TsIconBadge(
                                iconId = client.unsignedIconId,
                                iconCachePaths = iconCachePaths,
                                size = 18.dp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isMe) {
                            SmallStatusTag(strings.meBadge, colors.periwinkle, colors.periwinkleSoft)
                        }
                        if (client.isChannelCommander) {
                            SmallStatusTag(
                                text = if (isEn) "Commander" else "频道指挥官",
                                fg = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                                bg = colors.marigoldSoft
                            )
                        }
                        if (client.talkPowerGranted) {
                            SmallStatusTag(
                                text = if (isEn) "Talker Granted" else "已获授发言权",
                                fg = colors.emerald,
                                bg = colors.emeraldSoft
                            )
                        }
                        if (client.isAway) {
                            val awayText = if (client.awayMessage.isNotBlank()) {
                                "${strings.awayBadge}: ${client.awayMessage}"
                            } else strings.awayBadge
                            SmallStatusTag(
                                text = awayText,
                                fg = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                                bg = colors.marigoldSoft
                            )
                        }
                        SmallStatusTag(
                            text = if (isEn) "Talk Power ${client.talkPower}" else "发言权 ${client.talkPower}",
                            fg = colors.textSecondary,
                            bg = colors.cardElevated
                        )
                    }
                }
            }

            // Copy Nickname & Copy UID Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppSecondaryPillButton(
                    text = if (isEn) "Copy Name" else "复制昵称",
                    leadingIcon = Icons.Default.ContentCopy,
                    onClick = {
                        onCopyText(
                            "nickname",
                            client.nickname,
                            if (isEn) "Copied nickname" else "已复制成员昵称"
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                if (client.uid.isNotBlank()) {
                    AppSecondaryPillButton(
                        text = if (isEn) "Copy UID" else "复制 UID",
                        leadingIcon = Icons.Default.ContentCopy,
                        onClick = {
                            onCopyText(
                                "uid",
                                client.uid,
                                if (isEn) "Copied Unique ID" else "已复制成员 UID"
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Active Talk Power Request Approval Banner
            if (client.hasTalkPowerRequest && !isMe) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.marigoldSoft)
                        .border(1.dp, colors.marigold.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PanTool,
                        contentDescription = null,
                        tint = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isEn) "Requesting Talk Power" else "正在举手申请发言权",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                            fontWeight = FontWeight.Bold
                        )
                        if (client.talkPowerRequestMsg.isNotBlank()) {
                            Text(
                                text = client.talkPowerRequestMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.emerald)
                            .clickable { onSetClientTalkPowerGranted(client.id, true) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isEn) "Approve" else "批准发言",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Server Groups & Channel Group Card
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.cardElevated)
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isEn) "Roles & Groups" else "服务器组与频道组",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.periwinkleSoft)
                            .clickable { showGroupManageDialog = true }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isEn) "Manage Groups" else "设置权限组",
                            color = colors.periwinkle,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    badges.serverGroups.forEach { sg ->
                        GroupBadgePill(
                            name = sg.name,
                            iconId = sg.unsignedIconId,
                            iconCachePaths = iconCachePaths,
                            isChannelGroup = false
                        )
                    }
                    badges.channelGroup?.let { cg ->
                        GroupBadgePill(
                            name = cg.name,
                            iconId = cg.unsignedIconId,
                            iconCachePaths = iconCachePaths,
                            isChannelGroup = true
                        )
                    }
                    if (badges.serverGroups.isEmpty() && badges.channelGroup == null) {
                        Text(
                            text = if (isEn) "Default Guest" else "默认访客组",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            // Member Description & Connection Info Card
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.cardElevated)
                    .padding(14.dp)
            ) {
                if (client.description.isNotBlank()) {
                    val descAnnotated = remember(client.description) {
                        BBCodeParser.parse(client.description)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = if (isEn) "Description" else "个人简介",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textTertiary
                        )
                        @Suppress("DEPRECATION")
                        ClickableText(
                            text = descAnnotated,
                            style = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            ),
                            onClick = { offset ->
                                descAnnotated.getStringAnnotations(BBCodeParser.URL_TAG, offset, offset)
                                    .firstOrNull()
                                    ?.let { onClickUrl(it.item) }
                            }
                        )
                    }
                    DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val platformDisplay = if (isMe) {
                        "Android ${Build.VERSION.RELEASE} · TSCC v${BuildConfig.VERSION_NAME}"
                    } else {
                        listOf(client.platform, client.version.substringBefore(" ["))
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifBlank { if (isEn) "Syncing..." else "同步中..." }
                    }
                    ServerMetricCell(
                        label = if (isEn) "Client Platform" else "客户端平台与版本",
                        value = platformDisplay,
                        modifier = Modifier.weight(1.2f)
                    )
                    ServerMetricCell(
                        label = if (isEn) "Online / Idle" else "在线 / 空闲时长",
                        value = if (client.connectedSec > 0L) {
                            "${formatDurationHuman(client.connectedSec, isEn)} / ${formatDurationHuman(client.idleSec, isEn)}"
                        } else {
                            "—"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (client.clientPingMs > 0 || client.countryCode.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (client.clientPingMs > 0) {
                            ServerMetricCell(
                                label = if (isEn) "Member Ping" else "成员网络延迟",
                                value = "${client.clientPingMs} ms",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (client.countryCode.isNotBlank()) {
                            ServerMetricCell(
                                label = if (isEn) "Region" else "地区代码",
                                value = client.countryCode,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            if (isMe) {
                // --- Self Status & Profile Management ---
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.cardElevated)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Away Status (AFK)" else "离开状态 (AFK)",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isEn) "Pauses mic transmission while away" else "开启后展示离开标识并暂停麦克风发声",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                        AppPillSwitch(
                            checked = awayChecked,
                            onCheckedChange = { checked ->
                                awayChecked = checked
                                onSetSelfAway(checked, awayMsg)
                            }
                        )
                    }

                    if (awayChecked) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AppCapsuleField(
                                value = awayMsg,
                                onValueChange = { awayMsg = it },
                                placeholder = if (isEn) "Away note (e.g. Back in 5m)" else "离开留言（如：接电话稍回）",
                                modifier = Modifier.weight(1f)
                            )
                            AppSecondaryPillButton(
                                text = strings.save,
                                onClick = { onSetSelfAway(true, awayMsg) }
                            )
                        }
                    }

                    DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isEn) "Channel Commander" else "频道指挥官标识",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Text(
                                text = if (isEn) "Highlights your avatar ring in amber" else "开启指挥官金色光环（需服务器组允许）",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                        AppPillSwitch(
                            checked = client.isChannelCommander,
                            onCheckedChange = { onSetSelfChannelCommander(it) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppPrimaryPillButton(
                        text = if (isEn) "Change Nickname" else "修改当前服昵称",
                        leadingIcon = Icons.Default.Edit,
                        onClick = { showRenameSelfDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    AppSecondaryPillButton(
                        text = if (isEn) "Edit Bio" else "修改个人简介",
                        leadingIcon = Icons.Default.Edit,
                        onClick = { showEditDescDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                // --- Peer Audio Mixer & Member Actions ---
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.cardElevated)
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isEn) {
                                "User Volume: ${volume.roundToInt()}%"
                            } else {
                                "独立音量增益：${volume.roundToInt()}%"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (isEn) "Local Mute" else "本地屏蔽",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        AppPillSwitch(
                            checked = muted,
                            activeTrackColor = colors.coral,
                            onCheckedChange = {
                                muted = it
                                onVolumeChanged(volume.roundToInt(), it)
                            }
                        )
                    }
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            onVolumeChanged(it.roundToInt(), muted)
                        },
                        valueRange = 0f..200f,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.periwinkle,
                            activeTrackColor = colors.periwinkle
                        )
                    )
                }

                if (onWatchLive != null) {
                    AppPrimaryPillButton(
                        text = if (isEn) {
                            "Watch Live Stream"
                        } else {
                            "观看 TA 的直播${client.streamName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
                        },
                        containerColor = Color(0xFFE11D48),
                        contentColor = Color.White,
                        onClick = onWatchLive,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AppPrimaryPillButton(
                        text = if (isEn) "Direct Message" else "发起私聊",
                        leadingIcon = Icons.AutoMirrored.Filled.Chat,
                        onClick = onSendDm,
                        modifier = Modifier.weight(1f)
                    )
                    AppSecondaryPillButton(
                        text = if (isEn) "Poke" else "戳一戳",
                        leadingIcon = Icons.Default.NotificationsActive,
                        onClick = onPoke,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Permissions & Talk Power Management Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppSecondaryPillButton(
                        text = if (client.talkPowerGranted) {
                            if (isEn) "Revoke Talk" else "收回发言权"
                        } else {
                            if (isEn) "Grant Talk" else "授予发言权"
                        },
                        leadingIcon = if (client.talkPowerGranted) Icons.Default.MicOff else Icons.Default.Mic,
                        tint = if (client.talkPowerGranted) colors.marigold else colors.emerald,
                        onClick = { onSetClientTalkPowerGranted(client.id, !client.talkPowerGranted) },
                        modifier = Modifier.weight(1f)
                    )
                    AppSecondaryPillButton(
                        text = if (isEn) "Groups" else "权限组",
                        leadingIcon = Icons.Default.Groups,
                        tint = colors.periwinkle,
                        onClick = { showGroupManageDialog = true },
                        modifier = Modifier.weight(0.95f)
                    )
                    AppSecondaryPillButton(
                        text = if (isEn) "Bio" else "改简介",
                        leadingIcon = Icons.Default.Edit,
                        onClick = { showEditDescDialog = true },
                        modifier = Modifier.weight(0.85f)
                    )
                }

                // Channel Move, Kick & Ban Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1.2f)) {
                        AppSecondaryPillButton(
                            text = if (client.channelId != snapshot.myChannelId) {
                                if (isEn) "Move..." else "移动频道..."
                            } else {
                                if (isEn) "Move To..." else "移至其他频道..."
                            },
                            onClick = { moveMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                            expanded = moveMenuOpen,
                            onDismissRequest = { moveMenuOpen = false }
                        ) {
                            if (client.channelId != snapshot.myChannelId) {
                                DropdownMenuItem(
                                    text = { Text(if (isEn) "Pull to My Channel" else "拉到我当前所在频道") },
                                    onClick = {
                                        moveMenuOpen = false
                                        onMoveToChannel(snapshot.myChannelId)
                                    }
                                )
                            }
                            snapshot.channels.filter { it.id != client.channelId }.forEach { targetCh: ChannelNode ->
                                DropdownMenuItem(
                                    text = { Text(targetCh.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        moveMenuOpen = false
                                        onMoveToChannel(targetCh.id)
                                    }
                                )
                            }
                        }
                    }

                    AppSecondaryPillButton(
                        text = if (isEn) "Kick" else "踢出",
                        leadingIcon = Icons.Default.PersonRemove,
                        tint = colors.coral,
                        onClick = onKick,
                        modifier = Modifier.weight(1f)
                    )
                    AppSecondaryPillButton(
                        text = if (isEn) "Ban" else "封禁",
                        leadingIcon = Icons.Default.Gavel,
                        tint = colors.coral,
                        onClick = onBan,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    if (showRenameSelfDialog) {
        var newNick by remember(client.nickname) { mutableStateOf(client.nickname) }
        AppModalDialog(
            title = if (isEn) "Change Server Nickname" else "修改当前服务器昵称",
            subtitle = snapshot.serverName,
            onDismiss = { showRenameSelfDialog = false },
            confirmText = strings.save,
            confirmEnabled = newNick.trim().isNotEmpty(),
            onConfirm = {
                onUpdateSelfServerNickname(newNick.trim())
                showRenameSelfDialog = false
            }
        ) {
            AppCapsuleField(
                value = newNick,
                onValueChange = { newNick = it },
                label = strings.usernameLabel,
                placeholder = client.nickname
            )
        }
    }

    if (showEditDescDialog) {
        var descDraft by remember(client.description) { mutableStateOf(client.description) }
        AppModalDialog(
            title = if (isMe) {
                if (isEn) "Edit My Description" else "修改我的个人简介"
            } else {
                if (isEn) "Edit Description · ${client.nickname}" else "编辑成员简介 · ${client.nickname}"
            },
            onDismiss = { showEditDescDialog = false },
            confirmText = strings.save,
            onConfirm = {
                onEditClientDescription(client.id, descDraft)
                showEditDescDialog = false
            }
        ) {
            AppCapsuleField(
                value = descDraft,
                onValueChange = { descDraft = it },
                label = if (isEn) "Client Description" else "个人简介（支持留言或备注）",
                placeholder = if (isEn) "Leave blank to clear..." else "留空保存即可清空简介...",
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        }
    }

    if (showGroupManageDialog) {
        MemberGroupManageDialog(
            client = client,
            snapshot = snapshot,
            iconCachePaths = iconCachePaths,
            onToggleServerGroup = { sgid, add -> onSetClientServerGroup(client.id, sgid, add) },
            onSelectChannelGroup = { cgid -> onSetClientChannelGroup(client.id, client.channelId, cgid) },
            onDismiss = { showGroupManageDialog = false }
        )
    }
}

@Composable
private fun MemberGroupManageDialog(
    client: OnlineClient,
    snapshot: ServerSnapshot,
    iconCachePaths: Map<String, String>,
    onToggleServerGroup: (Long, Boolean) -> Unit,
    onSelectChannelGroup: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val isEn = strings.isEnglish
    var tab by remember { mutableIntStateOf(0) }

    val regularServerGroups = remember(snapshot.serverGroups) {
        snapshot.serverGroups.filter { it.groupType == 1 }
    }
    val regularChannelGroups = remember(snapshot.channelGroups) {
        snapshot.channelGroups.filter { it.groupType == 1 }
    }
    val assignedServerGroups = remember(client.serverGroups) {
        client.serverGroups.toHashSet()
    }

    AppModalDialog(
        title = if (isEn) "Roles · ${client.nickname}" else "权限组管理 · ${client.nickname}",
        subtitle = if (isEn) {
            "Changes take effect immediately (requires group add/remove power)"
        } else {
            "点击即可实时分配或移除权限组（需具备管理员组分配权限）"
        },
        onDismiss = onDismiss,
        confirmText = strings.close,
        dismissText = "",
        onConfirm = onDismiss
    ) {
        AppSegmentedPills(
            options = listOf(
                0 to (if (isEn) "Server Groups" else "服务器组（多选）"),
                1 to (if (isEn) "Channel Group" else "当前频道组（单选）")
            ),
            selected = tab,
            onSelect = { tab = it }
        )

        if (tab == 0) {
            if (regularServerGroups.isEmpty()) {
                Text(
                    text = if (isEn) "No assignable server groups available" else "当前服务器暂无可分配的常规服务器组",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    regularServerGroups.forEach { sg ->
                        val isDefaultGuest = sg.id == snapshot.defaultServerGroup
                        val isAssigned = sg.id in assignedServerGroups
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isAssigned) colors.periwinkleSoft else colors.cardElevated)
                                .border(
                                    width = 1.dp,
                                    color = if (isAssigned) colors.periwinkle.copy(alpha = 0.6f) else colors.border,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .pressableScale(0.98f, enabled = !isDefaultGuest) {
                                    onToggleServerGroup(sg.id, !isAssigned)
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            if (sg.unsignedIconId != 0L) {
                                TsIconBadge(
                                    iconId = sg.unsignedIconId,
                                    iconCachePaths = iconCachePaths,
                                    size = 18.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sg.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isAssigned) colors.periwinkle else colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (isDefaultGuest) {
                                    Text(
                                        text = if (isEn) "Auto default group" else "服务器默认访客组（无其他组时自动生效）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textTertiary
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (isAssigned) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = if (isAssigned) colors.periwinkle else colors.textTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        } else {
            if (regularChannelGroups.isEmpty()) {
                Text(
                    text = if (isEn) "No assignable channel groups available" else "当前暂无可分配的频道组",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    regularChannelGroups.forEach { cg ->
                        val isSelected = cg.id == client.channelGroup
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) colors.emeraldSoft else colors.cardElevated)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) colors.emerald.copy(alpha = 0.6f) else colors.border,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .pressableScale(0.98f) {
                                    if (!isSelected) onSelectChannelGroup(cg.id)
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            if (cg.unsignedIconId != 0L) {
                                TsIconBadge(
                                    iconId = cg.unsignedIconId,
                                    iconCachePaths = iconCachePaths,
                                    size = 18.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = cg.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) colors.emerald else colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (isSelected) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = if (isSelected) colors.emerald else colors.textTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallStatusTag(text: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GroupBadgePill(
    name: String,
    iconId: Long,
    iconCachePaths: Map<String, String>,
    isChannelGroup: Boolean
) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (isChannelGroup) colors.emeraldSoft else colors.card)
            .border(
                width = 1.dp,
                color = if (isChannelGroup) colors.emerald.copy(alpha = 0.4f) else colors.border,
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        if (iconId != 0L) {
            TsIconBadge(iconId = iconId, iconCachePaths = iconCachePaths, size = 14.dp)
        }
        Text(
            text = name,
            color = if (isChannelGroup) colors.emerald else colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

fun copyPlainTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun formatDurationHuman(totalSeconds: Long, isEnglish: Boolean): String {
    val sec = totalSeconds.coerceAtLeast(0L)
    val days = sec / 86400L
    val hours = (sec % 86400L) / 3600L
    val mins = (sec % 3600L) / 60L
    val secs = sec % 60L
    return if (isEnglish) {
        when {
            days > 0 -> "${days}d ${hours}h ${mins}m"
            hours > 0 -> "${hours}h ${mins}m"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    } else {
        when {
            days > 0 -> "${days}天 ${hours}小时 ${mins}分"
            hours > 0 -> "${hours}小时 ${mins}分"
            mins > 0 -> "${mins}分 ${secs}秒"
            else -> "${secs}秒"
        }
    }
}
