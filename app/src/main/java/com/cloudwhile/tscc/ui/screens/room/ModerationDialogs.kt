package com.cloudwhile.tscc.ui.screens.room

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudwhile.tscc.data.model.ChannelLifecycle
import com.cloudwhile.tscc.data.model.ChannelNode
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.PasswordEditMode
import com.cloudwhile.tscc.data.model.PokeAlert
import com.cloudwhile.tscc.data.model.computeExcludedParentChannelIds
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppModalDialog
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppSecondaryPillButton
import com.cloudwhile.tscc.ui.components.AppSegmentedPills
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.theme.AppTheme
import kotlin.math.roundToInt

@Composable
fun IncomingPokeDialog(
    poke: PokeAlert,
    onDismiss: () -> Unit,
    onReplyPoke: (clientId: Int, replyMsg: String) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    var replyText by remember { mutableStateOf("") }
    var showReplyInput by remember { mutableStateOf(false) }

    AppModalDialog(
        title = if (strings.isEnglish) "${poke.fromName} poked you" else "${poke.fromName} 戳了你一下",
        subtitle = if (strings.isEnglish) "Instant room alert" else "来自语音频道的即时提醒",
        onDismiss = onDismiss,
        dismissText = if (strings.isEnglish) "Dismiss" else "知道了",
        confirmText = if (showReplyInput) {
            if (strings.isEnglish) "Send Poke" else "发送回戳"
        } else {
            if (strings.isEnglish) "Poke Back" else "回戳 TA"
        },
        onConfirm = {
            if (showReplyInput) {
                onReplyPoke(poke.fromClientId, replyText.ifBlank { if (strings.isEnglish) "Got it!" else "收到！" })
                onDismiss()
            } else {
                showReplyInput = true
            }
        }
    ) {
        Text(
            text = poke.message.ifBlank { if (strings.isEnglish) "(No message attached)" else "（对方未附带留言）" },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
        if (showReplyInput) {
            AppCapsuleField(
                value = replyText,
                onValueChange = { replyText = it },
                label = if (strings.isEnglish) "Reply Message" else "回戳留言",
                placeholder = if (strings.isEnglish) "Type reply..." else "输入回戳内容..."
            )
        }
    }
}

@Composable
fun SendPokeDialog(
    targetClient: OnlineClient,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val strings = AppTheme.strings
    var msg by remember { mutableStateOf("") }
    AppModalDialog(
        title = if (strings.isEnglish) "Poke · ${targetClient.nickname}" else "戳一戳 · ${targetClient.nickname}",
        subtitle = if (strings.isEnglish) "Sends an instant alert & vibration" else "对方将收到弹窗与震动提醒",
        onDismiss = onDismiss,
        confirmText = if (strings.isEnglish) "Send Poke" else "发送提醒",
        onConfirm = {
            onConfirm(msg)
            onDismiss()
        }
    ) {
        AppCapsuleField(
            value = msg,
            onValueChange = { msg = it },
            label = if (strings.isEnglish) "Message (Optional)" else "附带留言（选填）",
            placeholder = if (strings.isEnglish) "e.g. Ready up!" else "例如：准备就绪，来一队频道"
        )
    }
}

@Composable
fun PrivilegeKeyDialog(
    onDismiss: () -> Unit,
    onSubmitToken: (String) -> Unit
) {
    val strings = AppTheme.strings
    var token by remember { mutableStateOf("") }
    AppModalDialog(
        title = strings.quickTokenTitle,
        subtitle = if (strings.isEnglish) "Redeem a server Privilege Key to unlock admin permissions" else "输入服务器生成的 Privilege Key 激活管理或特殊权限组",
        onDismiss = onDismiss,
        confirmText = if (strings.isEnglish) "Redeem" else "立即激活",
        confirmEnabled = token.isNotBlank(),
        onConfirm = {
            onSubmitToken(token)
            onDismiss()
        }
    ) {
        AppCapsuleField(
            value = token,
            onValueChange = { token = it },
            label = "Privilege Key",
            placeholder = if (strings.isEnglish) "Paste privilege token..." else "粘贴权限密钥串..."
        )
    }
}

@Composable
fun ChannelPasswordDialog(
    channelName: String,
    errorHint: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    var pwd by remember { mutableStateOf("") }
    AppModalDialog(
        title = if (strings.isEnglish) "Protected Channel" else "进入加密频道",
        subtitle = channelName,
        onDismiss = onDismiss,
        confirmText = strings.joinThisChannel,
        onConfirm = {
            onConfirm(pwd)
            onDismiss()
        }
    ) {
        if (!errorHint.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.coralSoft)
                    .border(1.dp, colors.coral.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    text = errorHint,
                    color = colors.coral,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        AppCapsuleField(
            value = pwd,
            onValueChange = { pwd = it },
            label = if (strings.isEnglish) "Channel Password" else "频道密码",
            placeholder = if (strings.isEnglish) "Enter password..." else "输入该子频道密码",
            visualTransformation = PasswordVisualTransformation()
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelFormDialog(
    editingChannel: ChannelNode?,
    initialParentId: Long = 0L,
    allChannels: List<ChannelNode>,
    onDismiss: () -> Unit,
    onCreate: (
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
    onEdit: (
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
    ) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val isEdit = editingChannel != null

    var formTab by remember { mutableIntStateOf(0) }
    var name by remember(editingChannel) { mutableStateOf(editingChannel?.name.orEmpty()) }
    var parentId by remember(editingChannel, initialParentId) {
        mutableLongStateOf(editingChannel?.parentId ?: initialParentId)
    }
    var parentMenuOpen by remember { mutableStateOf(false) }

    var lifecycle by remember(editingChannel) {
        mutableStateOf(editingChannel?.lifecycle() ?: ChannelLifecycle.TEMPORARY)
    }
    var isDefault by remember(editingChannel) {
        mutableStateOf(editingChannel?.isDefault ?: false)
    }
    var autoJoin by remember { mutableStateOf(true) }

    val hadExistingPassword = editingChannel?.hasPassword == true
    var passwordMode by remember(editingChannel) {
        mutableStateOf(
            if (isEdit && hadExistingPassword) PasswordEditMode.KEEP else PasswordEditMode.SET
        )
    }
    var password by remember { mutableStateOf("") }

    var topic by remember(editingChannel) { mutableStateOf(editingChannel?.topic.orEmpty()) }
    var description by remember(editingChannel) { mutableStateOf(editingChannel?.description.orEmpty()) }
    var opusMusic by remember(editingChannel) { mutableStateOf(editingChannel?.codec == 5) }
    var quality by remember(editingChannel) {
        mutableFloatStateOf((editingChannel?.codecQuality ?: 6).toFloat())
    }
    var maxClientsText by remember(editingChannel) {
        mutableStateOf(
            editingChannel?.maxClients?.takeIf { it > 0 }?.toString().orEmpty()
        )
    }
    var neededTalkPowerText by remember(editingChannel) {
        mutableStateOf(
            editingChannel?.neededTalkPower?.takeIf { it > 0 }?.toString() ?: "0"
        )
    }

    val excludedParentIds = remember(editingChannel?.id, allChannels) {
        computeExcludedParentChannelIds(editingChannel?.id, allChannels)
    }
    val validParentChannels = remember(allChannels, excludedParentIds) {
        allChannels.filter { it.id !in excludedParentIds }
    }
    val selectedParentLabel = remember(parentId, validParentChannels, strings) {
        if (parentId <= 0L) strings.rootChannelOption
        else validParentChannels.firstOrNull { it.id == parentId }?.name ?: strings.rootChannelOption
    }

    val canSubmit = name.isNotBlank() &&
        !(isEdit && passwordMode == PasswordEditMode.SET && hadExistingPassword && password.isBlank())

    AppModalDialog(
        title = if (isEdit) strings.editChannelTitle else strings.createChannelTitle,
        subtitle = if (isEdit) editingChannel?.name else strings.quickNewChannelSub,
        onDismiss = onDismiss,
        confirmText = if (isEdit) strings.save else strings.confirm,
        confirmEnabled = canSubmit,
        onConfirm = {
            val maxC = maxClientsText.toIntOrNull() ?: -1
            val talkPwr = neededTalkPowerText.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val effectiveLifecycle = if (isDefault) ChannelLifecycle.PERMANENT else lifecycle
            if (editingChannel != null) {
                val effectivePwdMode = when {
                    hadExistingPassword -> passwordMode
                    password.isNotEmpty() -> PasswordEditMode.SET
                    else -> PasswordEditMode.KEEP
                }
                onEdit(
                    editingChannel.id,
                    parentId,
                    name,
                    effectivePwdMode,
                    password,
                    topic,
                    description,
                    maxC,
                    effectiveLifecycle,
                    isDefault,
                    opusMusic,
                    quality.roundToInt(),
                    talkPwr
                )
            } else {
                onCreate(
                    name,
                    parentId,
                    password,
                    topic,
                    description,
                    maxC,
                    effectiveLifecycle,
                    isDefault,
                    opusMusic,
                    quality.roundToInt(),
                    talkPwr,
                    autoJoin
                )
            }
            onDismiss()
        }
    ) {
        AppSegmentedPills(
            options = listOf(0 to strings.tabBasicInfo, 1 to strings.tabAudioAndPerm),
            selected = formTab,
            onSelect = { formTab = it }
        )

        if (formTab == 0) {
            AppCapsuleField(
                value = name,
                onValueChange = { name = it },
                label = strings.channelNameLabel,
                placeholder = if (strings.isEnglish) "Enter channel name..." else "输入频道名称..."
            )

            // Parent Channel Selector
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = strings.parentChannelLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 6.dp)
                )
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(colors.cardElevated)
                            .border(1.dp, colors.border, RoundedCornerShape(50))
                            .clickable { parentMenuOpen = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = selectedParentLabel,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (parentMenuOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }

                    DropdownMenu(
                        expanded = parentMenuOpen,
                        onDismissRequest = { parentMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.rootChannelOption) },
                            onClick = {
                                parentId = 0L
                                parentMenuOpen = false
                            }
                        )
                        validParentChannels.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    parentId = candidate.id
                                    parentMenuOpen = false
                                }
                            )
                        }
                    }
                }
            }

            // Channel Lifecycle Type
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = if (strings.isEnglish) "Channel Type" else "频道留存类型",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 6.dp)
                )
                AppSegmentedPills(
                    options = listOf(
                        ChannelLifecycle.TEMPORARY to strings.lifecycleTemp,
                        ChannelLifecycle.SEMI_PERMANENT to strings.lifecycleSemi,
                        ChannelLifecycle.PERMANENT to strings.lifecyclePerm
                    ),
                    selected = if (isDefault) ChannelLifecycle.PERMANENT else lifecycle,
                    onSelect = { chosen ->
                        if (!isDefault) lifecycle = chosen
                    }
                )
            }

            // Password Management
            if (isEdit && hadExistingPassword) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (strings.isEnglish) "Channel Password" else "频道密码设置",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    AppSegmentedPills(
                        options = listOf(
                            PasswordEditMode.KEEP to strings.pwdKeep,
                            PasswordEditMode.SET to strings.pwdChange,
                            PasswordEditMode.CLEAR to strings.pwdClear
                        ),
                        selected = passwordMode,
                        onSelect = { passwordMode = it }
                    )
                    if (passwordMode == PasswordEditMode.SET) {
                        AppCapsuleField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = if (strings.isEnglish) "Enter new password..." else "输入新频道密码...",
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                }
            } else {
                AppCapsuleField(
                    value = password,
                    onValueChange = { password = it },
                    label = if (strings.isEnglish) "Channel Password (Optional)" else "频道密码（选填）",
                    placeholder = if (strings.isEnglish) "Leave blank for public channel" else "公开频道请留空",
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            if (!isEdit && lifecycle != ChannelLifecycle.TEMPORARY) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { autoJoin = !autoJoin }
                ) {
                    Checkbox(checked = autoJoin, onCheckedChange = { autoJoin = it })
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = strings.autoJoinCreatedChannel,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        } else {
            // Tab 1: Audio Codec, Description, Limits & Needed Talk Power
            AppCapsuleField(
                value = topic,
                onValueChange = { topic = it },
                label = strings.channelTopicLabel,
                placeholder = if (strings.isEnglish) "Short channel topic..." else "一句话简述频道当前主题"
            )

            AppCapsuleField(
                value = description,
                onValueChange = { description = it },
                label = strings.channelDescLabel,
                placeholder = if (strings.isEnglish) "Supports [b], [url], announcements..." else "支持填写公告、规则或 [url] 链接...",
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )

            AppSegmentedPills(
                options = listOf(false to strings.opusVoiceLabel, true to strings.opusMusicLabel),
                selected = opusMusic,
                onSelect = { opusMusic = it }
            )

            Column {
                Text(
                    text = if (strings.isEnglish) {
                        "Codec Quality: ${quality.roundToInt()} / 10"
                    } else {
                        "音质等级：${quality.roundToInt()} / 10"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary
                )
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.periwinkle,
                        activeTrackColor = colors.periwinkle
                    )
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCapsuleField(
                    value = maxClientsText,
                    onValueChange = { maxClientsText = it.filter { c -> c.isDigit() } },
                    label = strings.maxClientsLabel,
                    placeholder = "∞",
                    modifier = Modifier.weight(1f)
                )
                AppCapsuleField(
                    value = neededTalkPowerText,
                    onValueChange = { neededTalkPowerText = it.filter { c -> c.isDigit() } },
                    label = strings.neededTalkPowerLabel,
                    placeholder = "0",
                    modifier = Modifier.weight(1f)
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    0 to (if (strings.isEnglish) "0 Free" else "0 自由发言"),
                    25 to "≥ 25",
                    50 to "≥ 50",
                    75 to (if (strings.isEnglish) "75 Muted" else "75 仅管理发言")
                ).forEach { (preset, title) ->
                    val active = (neededTalkPowerText.toIntOrNull() ?: 0) == preset
                    Box(
                        modifier = Modifier
                            .pressableScale(0.95f) { neededTalkPowerText = preset.toString() }
                            .clip(RoundedCornerShape(50))
                            .background(if (active) colors.periwinkleSoft else colors.cardElevated)
                            .border(
                                1.dp,
                                if (active) colors.periwinkle else colors.border,
                                RoundedCornerShape(50)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) colors.periwinkle else colors.textSecondary
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        isDefault = !isDefault
                        if (isDefault) lifecycle = ChannelLifecycle.PERMANENT
                    }
            ) {
                Checkbox(
                    checked = isDefault,
                    onCheckedChange = {
                        isDefault = it
                        if (it) lifecycle = ChannelLifecycle.PERMANENT
                    }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = strings.makeDefaultChannelLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
fun DeleteChannelConfirmDialog(
    channel: ChannelNode,
    hasOccupantsOrChildren: Boolean,
    onDismiss: () -> Unit,
    onConfirmDelete: (force: Boolean) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    AppModalDialog(
        title = "${strings.deleteChannelTitle} · ${channel.name}",
        subtitle = strings.deleteChannelConfirmDesc,
        onDismiss = onDismiss,
        confirmText = strings.delete,
        confirmColor = colors.coral,
        onConfirm = {
            onConfirmDelete(true)
            onDismiss()
        }
    ) {
        if (hasOccupantsOrChildren) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.marigoldSoft)
                    .border(1.dp, colors.marigold.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = strings.deleteChannelOccupiedWarn,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (colors.isDark) colors.marigold else colors.inkOnPastel
                )
            }
        }
    }
}

@Composable
fun LeaveOrDisconnectDialog(
    defaultChannel: ChannelNode?,
    isInDefaultChannel: Boolean,
    onDismiss: () -> Unit,
    onReturnToDefaultLobby: (ChannelNode) -> Unit,
    onDisconnectServer: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val canMoveToDefault = !isInDefaultChannel && defaultChannel != null

    AppModalDialog(
        title = strings.exitModalTitle,
        subtitle = if (canMoveToDefault) strings.exitModalSubInSubchannel else strings.exitModalSubInDefault,
        onDismiss = onDismiss,
        confirmText = strings.disconnectServer,
        confirmColor = colors.coral,
        onConfirm = {
            onDismiss()
            onDisconnectServer()
        }
    ) {
        if (canMoveToDefault) {
            AppSecondaryPillButton(
                text = strings.returnToDefaultChannel(defaultChannel.name),
                leadingIcon = Icons.Default.MeetingRoom,
                tint = colors.periwinkle,
                onClick = {
                    onDismiss()
                    onReturnToDefaultLobby(defaultChannel)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun QuickRetryPasswordDialog(
    serverLabel: String,
    initialPassword: String,
    onDismiss: () -> Unit,
    onConfirmRetry: (String) -> Unit
) {
    val strings = AppTheme.strings
    var pwd by remember { mutableStateOf(initialPassword) }
    AppModalDialog(
        title = strings.editPasswordAndRetry,
        subtitle = serverLabel,
        onDismiss = onDismiss,
        confirmText = strings.retryConnectNow,
        onConfirm = {
            onConfirmRetry(pwd)
            onDismiss()
        }
    ) {
        AppCapsuleField(
            value = pwd,
            onValueChange = { pwd = it },
            label = strings.serverPasswordLabel,
            placeholder = strings.serverPasswordUnset,
            visualTransformation = PasswordVisualTransformation()
        )
    }
}

@Composable
fun KickOrBanDialog(
    targetClient: OnlineClient,
    isBan: Boolean,
    onDismiss: () -> Unit,
    onKick: (fromServer: Boolean, reason: String) -> Unit,
    onBan: (durationSec: Long, reason: String) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    var reason by remember { mutableStateOf("") }
    var kickFromServer by remember { mutableStateOf(true) }
    var banDurationSec by remember { mutableLongStateOf(3600L) }

    val durations = if (strings.isEnglish) {
        listOf(600L to "10m", 3600L to "1h", 86400L to "1d", 0L to "Perm")
    } else {
        listOf(600L to "10分钟", 3600L to "1小时", 86400L to "1天", 0L to "永久")
    }

    AppModalDialog(
        title = if (isBan) {
            if (strings.isEnglish) "Ban · ${targetClient.nickname}" else "封禁成员 · ${targetClient.nickname}"
        } else {
            if (strings.isEnglish) "Kick · ${targetClient.nickname}" else "踢出成员 · ${targetClient.nickname}"
        },
        onDismiss = onDismiss,
        confirmText = strings.confirm,
        confirmColor = colors.coral,
        onConfirm = {
            if (isBan) onBan(banDurationSec, reason)
            else onKick(kickFromServer, reason)
            onDismiss()
        }
    ) {
        if (!isBan) {
            AppSegmentedPills(
                options = if (strings.isEnglish) {
                    listOf(false to "From Channel", true to "From Server")
                } else {
                    listOf(false to "仅移出当前频道", true to "踢出服务器")
                },
                selected = kickFromServer,
                onSelect = { kickFromServer = it }
            )
        } else {
            AppSegmentedPills(
                options = durations,
                selected = banDurationSec,
                onSelect = { banDurationSec = it }
            )
        }

        AppCapsuleField(
            value = reason,
            onValueChange = { reason = it },
            label = if (strings.isEnglish) "Reason (Optional)" else "处理原因（选填）",
            placeholder = if (strings.isEnglish) "Optional reason..." else "输入管理备注原因..."
        )
    }
}
