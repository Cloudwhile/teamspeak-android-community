package com.cloudwhile.tscc.ui.screens.bookmarks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudwhile.tscc.data.db.BookmarkEntity
import com.cloudwhile.tscc.data.db.IdentityEntity
import com.cloudwhile.tscc.data.model.ConnectionStatus
import com.cloudwhile.tscc.data.util.Ts3ServerUri
import com.cloudwhile.tscc.data.util.Ts3UriParser
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppCircleIconButton
import com.cloudwhile.tscc.ui.components.AppModalDialog
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppScreenHeader
import com.cloudwhile.tscc.ui.components.DashedHorizontalLine
import com.cloudwhile.tscc.ui.components.PulsingEmeraldDot
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.theme.AppTheme

@Composable
fun BookmarksScreen(
    bookmarks: List<BookmarkEntity>,
    selectedBookmarkId: Long?,
    activeBookmarkId: Long? = null,
    connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    identities: List<IdentityEntity>,
    defaultNickname: String,
    darkTheme: Boolean = true,
    onToggleDarkTheme: () -> Unit = {},
    onSelectBookmark: (Long) -> Unit,
    onConnectBookmark: (BookmarkEntity) -> Unit,
    onReturnToRoom: () -> Unit = {},
    onSaveBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (BookmarkEntity) -> Unit,
    onCopyMessage: (String) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val context = LocalContext.current
    var editingBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var pendingDeleteBookmark by remember { mutableStateOf<BookmarkEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredBookmarks = remember(bookmarks, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) bookmarks
        else bookmarks.filter { bm ->
            bm.label.lowercase().contains(q) ||
                bm.address.lowercase().contains(q) ||
                bm.nickname.lowercase().contains(q)
        }
    }

    val isCallActive = connectionStatus == ConnectionStatus.CONNECTED ||
        connectionStatus == ConnectionStatus.CONNECTING ||
        connectionStatus == ConnectionStatus.RECONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AppScreenHeader(
                    title = strings.bookmarksTitle,
                    subtitle = strings.bookmarksSubtitle
                ) {
                    AppCircleIconButton(
                        icon = if (darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                        contentDescription = strings.darkThemeTitle,
                        onClick = onToggleDarkTheme
                    )
                    AppCircleIconButton(
                        icon = Icons.Default.Add,
                        contentDescription = strings.addBookmark,
                        active = true,
                        onClick = {
                            editingBookmark = null
                            showEditDialog = true
                        }
                    )
                }
            }

            if (bookmarks.size >= 2) {
                item {
                    AppCapsuleField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = strings.searchBookmarksHint,
                        leadingIcon = Icons.Default.Search,
                        trailing = if (searchQuery.isNotEmpty()) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.clearInputDesc,
                                    tint = colors.textSecondary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { searchQuery = "" }
                                )
                            }
                        } else null
                    )
                }
            }

            if (bookmarks.isEmpty()) {
                item {
                    EmptyBookmarksCard(
                        onAddClick = {
                            editingBookmark = null
                            showEditDialog = true
                        }
                    )
                }
            } else {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.savedBookmarksHeader,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(colors.card)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${filteredBookmarks.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                }

                items(filteredBookmarks, key = { it.id }) { bm ->
                    val isConnectedToThis = isCallActive && activeBookmarkId == bm.id
                    BookmarkCard(
                        bookmark = bm,
                        isConnectedToThis = isConnectedToThis,
                        onCardTap = {
                            if (isConnectedToThis) {
                                onReturnToRoom()
                            } else {
                                onSelectBookmark(bm.id)
                                onConnectBookmark(bm)
                            }
                        },
                        onEdit = {
                            editingBookmark = bm
                            showEditDialog = true
                        },
                        onShare = {
                            val link = Ts3ServerUri(
                                host = bm.address,
                                port = bm.port,
                                nickname = bm.nickname,
                                channel = bm.defaultChannel.takeIf { it.isNotBlank() },
                                label = bm.label
                            ).toUriString()
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("ts3server", link))
                            onCopyMessage(if (strings.isEnglish) "Server link copied" else "已复制服务器链接")
                        },
                        onDelete = { pendingDeleteBookmark = bm }
                    )
                }
            }
        }
    }

    pendingDeleteBookmark?.let { target ->
        val displayTitle = target.label.ifBlank {
            if (target.port == 9987) target.address else "${target.address}:${target.port}"
        }
        AppModalDialog(
            title = if (strings.isEnglish) "Delete Bookmark?" else "确认删除该服务器书签？",
            subtitle = displayTitle,
            onDismiss = { pendingDeleteBookmark = null },
            confirmText = strings.delete,
            confirmColor = colors.coral,
            onConfirm = {
                onDeleteBookmark(target)
                pendingDeleteBookmark = null
            }
        ) {
            Text(
                text = if (strings.isEnglish) {
                    "Saved server address, channel path, and password for this bookmark will be removed."
                } else {
                    "删除后将移除该书签保存的服务器地址、子频道路径与登录口令。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }

    if (showEditDialog) {
        BookmarkEditDialog(
            initial = editingBookmark,
            identities = identities,
            defaultNickname = defaultNickname,
            onDismiss = { showEditDialog = false },
            onConfirm = { saved ->
                onSaveBookmark(saved)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun EmptyBookmarksCard(onAddClick: () -> Unit) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 38.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.periwinkleSoft)
        ) {
            Icon(
                imageVector = Icons.Default.BookmarkBorder,
                contentDescription = null,
                tint = colors.periwinkle,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = strings.emptyBookmarksTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            color = colors.textPrimary
        )
        Text(
            text = strings.emptyBookmarksDesc,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        AppPrimaryPillButton(
            text = strings.addFirstBookmark,
            leadingIcon = Icons.Default.Add,
            onClick = onAddClick
        )
    }
}

@Composable
private fun BookmarkCard(
    bookmark: BookmarkEntity,
    isConnectedToThis: Boolean,
    onCardTap: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    var showPasswordPlain by remember(bookmark.id) { mutableStateOf(false) }

    val addrDisplay = if (bookmark.port == 9987) {
        bookmark.address
    } else {
        "${bookmark.address}:${bookmark.port}"
    }

    val primaryInk = colors.textPrimary
    val secondaryInk = colors.textSecondary
    val pillChipBg = colors.cardElevated
    val avatarCircleBg = colors.cardElevated
    val avatarCircleFg = colors.periwinkle

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressableScale(scaleDown = 0.98f, onClick = onCardTap)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(
                width = if (isConnectedToThis) 1.6.dp else 1.dp,
                color = if (isConnectedToThis) colors.emerald else colors.border,
                shape = RoundedCornerShape(22.dp)
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(avatarCircleBg)
            ) {
                Text(
                    text = bookmark.label.ifBlank { bookmark.address }.take(1).uppercase(),
                    color = avatarCircleFg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = bookmark.label.ifBlank { addrDisplay },
                        style = MaterialTheme.typography.titleMedium,
                        color = primaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isConnectedToThis) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (colors.isDark) Color(0xFF06291D) else colors.emeraldSoft)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            PulsingEmeraldDot(size = 6.dp, color = colors.emerald)
                            Text(
                                text = strings.inCallBadge,
                                color = colors.emerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
                if (bookmark.defaultChannel.isNotBlank()) {
                    Text(
                        text = "${strings.defaultSubChannelPrefix}${bookmark.defaultChannel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                MiniCardActionCircle(
                    icon = Icons.Default.Share,
                    contentDescription = if (strings.isEnglish) "Copy Server Link" else "分享复制服务器链接",
                    bg = pillChipBg,
                    tint = primaryInk,
                    onClick = onShare
                )
                MiniCardActionCircle(
                    icon = Icons.Default.Edit,
                    contentDescription = strings.edit,
                    bg = pillChipBg,
                    tint = primaryInk,
                    onClick = onEdit
                )
                MiniCardActionCircle(
                    icon = Icons.Default.DeleteOutline,
                    contentDescription = strings.delete,
                    bg = pillChipBg,
                    tint = colors.coral,
                    onClick = onDelete
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(primaryInk)
                )
                DashedHorizontalLine(
                    color = secondaryInk.copy(alpha = 0.45f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .border(2.dp, primaryInk, CircleShape)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.usernameLabel,
                        fontSize = 11.sp,
                        color = secondaryInk,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = bookmark.nickname,
                        fontSize = 14.sp,
                        color = primaryInk,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(1.2f)
                ) {
                    Text(
                        text = strings.serverAddressLabel,
                        fontSize = 11.sp,
                        color = secondaryInk,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = addrDisplay,
                        fontSize = 14.sp,
                        color = primaryInk,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        val hasPwd = bookmark.serverPassword.isNotEmpty()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(pillChipBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = if (hasPwd) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = null,
                tint = primaryInk,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when {
                    !hasPwd -> strings.serverPasswordUnset
                    showPasswordPlain -> bookmark.serverPassword
                    else -> "••••••••"
                },
                fontSize = 12.sp,
                color = primaryInk,
                fontWeight = if (hasPwd) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasPwd) {
                Icon(
                    imageVector = if (showPasswordPlain) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = if (showPasswordPlain) {
                        strings.hidePasswordDesc
                    } else {
                        strings.showPasswordDesc
                    },
                    tint = primaryInk,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { showPasswordPlain = !showPasswordPlain }
                )
            }
        }
    }
}

@Composable
private fun MiniCardActionCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    bg: Color,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(34.dp)
            .pressableScale(0.9f, onClick = onClick)
            .clip(CircleShape)
            .background(bg)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarkEditDialog(
    initial: BookmarkEntity?,
    identities: List<IdentityEntity>,
    defaultNickname: String,
    onDismiss: () -> Unit,
    onConfirm: (BookmarkEntity) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    var nickname by remember { mutableStateOf(initial?.nickname ?: defaultNickname) }
    var rawHostPort by remember {
        mutableStateOf(
            if (initial == null) ""
            else if (initial.port == 9987) initial.address
            else "${initial.address}:${initial.port}"
        )
    }
    var serverPassword by remember { mutableStateOf(initial?.serverPassword.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }

    var showAdvanced by remember {
        mutableStateOf(
            initial != null && (
                (initial.label.isNotBlank() && initial.label != initial.address) ||
                    initial.defaultChannel.isNotBlank() ||
                    initial.identityId != null
                )
        )
    }
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var defaultChannel by remember { mutableStateOf(initial?.defaultChannel.orEmpty()) }
    var channelPassword by remember { mutableStateOf(initial?.channelPassword.orEmpty()) }
    var selectedIdentityId by remember { mutableStateOf(initial?.identityId) }

    val parsedAddress = remember(rawHostPort) { Ts3UriParser.parse(rawHostPort) }
    val canSubmit = nickname.isNotBlank() && parsedAddress != null

    AppModalDialog(
        title = if (initial == null) strings.addBookmark else strings.editBookmark,
        subtitle = strings.bookmarksSubtitle,
        onDismiss = onDismiss,
        confirmText = strings.save,
        confirmEnabled = canSubmit,
        onConfirm = {
            val parsed = parsedAddress ?: return@AppModalDialog
            val defaultLabel = if (parsed.port == 9987) parsed.host else "${parsed.host}:${parsed.port}"
            val resolvedLabel = label.trim().ifBlank { parsed.label?.trim().orEmpty() }.ifBlank { defaultLabel }
            val resolvedServerPwd = serverPassword.ifEmpty { parsed.serverPassword.orEmpty() }
            val resolvedChannel = defaultChannel.trim().ifBlank { parsed.channel?.trim().orEmpty() }
            val resolvedChannelPwd = channelPassword.ifEmpty { parsed.channelPassword.orEmpty() }
            onConfirm(
                BookmarkEntity(
                    id = initial?.id ?: 0L,
                    label = resolvedLabel,
                    address = parsed.host,
                    port = parsed.port,
                    nickname = nickname.trim(),
                    serverPassword = resolvedServerPwd,
                    defaultChannel = resolvedChannel,
                    channelPassword = resolvedChannelPwd,
                    identityId = selectedIdentityId,
                    lastConnectedAt = initial?.lastConnectedAt ?: 0L
                )
            )
        }
    ) {
        AppCapsuleField(
            value = nickname,
            onValueChange = { nickname = it },
            label = strings.usernameLabel,
            placeholder = "TSCC_Player",
            leadingIcon = Icons.Default.Person
        )

        AppCapsuleField(
            value = rawHostPort,
            onValueChange = { nextInput ->
                val trimmed = nextInput.trim()
                if (trimmed.startsWith("ts3server://", ignoreCase = true) ||
                    trimmed.startsWith("teamspeak://", ignoreCase = true)
                ) {
                    val uri = Ts3UriParser.parse(trimmed)
                    if (uri != null) {
                        rawHostPort = if (uri.port == 9987) uri.host else "${uri.host}:${uri.port}"
                        uri.nickname?.takeIf { it.isNotBlank() && (nickname.isBlank() || nickname == defaultNickname) }
                            ?.let { nickname = it }
                        uri.serverPassword?.takeIf { it.isNotEmpty() && serverPassword.isEmpty() }
                            ?.let { serverPassword = it }
                        uri.label?.takeIf { it.isNotBlank() && label.isBlank() }?.let {
                            label = it
                            showAdvanced = true
                        }
                        uri.channel?.takeIf { it.isNotBlank() && defaultChannel.isBlank() }?.let {
                            defaultChannel = it
                            showAdvanced = true
                        }
                        uri.channelPassword?.takeIf { it.isNotEmpty() && channelPassword.isEmpty() }
                            ?.let { channelPassword = it }
                        return@AppCapsuleField
                    }
                }
                rawHostPort = nextInput
            },
            label = strings.serverAddressLabel,
            placeholder = "ts.example.com / ts3server://...",
            leadingIcon = Icons.Default.Dns
        )

        AppCapsuleField(
            value = serverPassword,
            onValueChange = { serverPassword = it },
            label = strings.serverPasswordLabel,
            placeholder = strings.serverPasswordUnset,
            leadingIcon = Icons.Default.Lock,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailing = {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = if (passwordVisible) {
                        strings.hidePasswordDesc
                    } else {
                        strings.showPasswordDesc
                    },
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { passwordVisible = !passwordVisible }
                )
            }
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .clickable { showAdvanced = !showAdvanced }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                text = strings.moreOptionalFields,
                style = MaterialTheme.typography.labelMedium,
                color = colors.periwinkle,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = colors.periwinkle,
                modifier = Modifier.size(18.dp)
            )
        }

        if (showAdvanced) {
            AppCapsuleField(
                value = label,
                onValueChange = { label = it },
                label = if (strings.isEnglish) "Bookmark Label" else "书签备注名称（可选）",
                placeholder = if (strings.isEnglish) "Defaults to server address" else "留空默认显示服务器地址"
            )
            AppCapsuleField(
                value = defaultChannel,
                onValueChange = { defaultChannel = it },
                label = if (strings.isEnglish) "Default Sub-channel" else "默认进入子频道（可选）",
                placeholder = if (strings.isEnglish) "e.g. Lobby / Squad 1" else "如 大厅/一队频道"
            )
            if (defaultChannel.isNotBlank()) {
                AppCapsuleField(
                    value = channelPassword,
                    onValueChange = { channelPassword = it },
                    label = if (strings.isEnglish) "Sub-channel Password" else "子频道密码（可选）",
                    placeholder = "",
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            if (identities.isNotEmpty()) {
                Text(
                    text = if (strings.isEnglish) "Identity Key" else "绑定身份密钥",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 6.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IdentitySelectChip(
                        label = if (strings.isEnglish) "Default Identity" else "跟随默认身份",
                        selected = selectedIdentityId == null,
                        onClick = { selectedIdentityId = null }
                    )
                    identities.forEach { idItem ->
                        IdentitySelectChip(
                            label = "${idItem.name} (Lv.${idItem.securityLevel})",
                            selected = selectedIdentityId == idItem.id,
                            onClick = { selectedIdentityId = idItem.id }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdentitySelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .pressableScale(0.95f, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(if (selected) colors.pillActiveBg else colors.cardElevated)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else colors.border,
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) colors.pillActiveText else colors.textSecondary
        )
    }
}
