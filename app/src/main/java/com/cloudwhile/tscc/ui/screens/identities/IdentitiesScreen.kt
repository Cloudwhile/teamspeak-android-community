package com.cloudwhile.tscc.ui.screens.identities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudwhile.tscc.data.db.IdentityEntity
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppCircleIconButton
import com.cloudwhile.tscc.ui.components.AppModalDialog
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppScreenHeader
import com.cloudwhile.tscc.ui.components.AppSecondaryPillButton
import com.cloudwhile.tscc.ui.components.AppSegmentedPills
import com.cloudwhile.tscc.ui.components.DashedHorizontalLine
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.theme.AppTheme

@Composable
fun IdentitiesScreen(
    identities: List<IdentityEntity>,
    upgradeProgress: Pair<Int, Long>?,
    onCreateIdentity: (String) -> Unit,
    onImportIdentity: (name: String, rawIni: String) -> Unit,
    onUpgradeIdentity: (IdentityEntity, Int) -> Unit,
    onCancelUpgrade: () -> Unit,
    onSetDefault: (Long) -> Unit,
    onDeleteIdentity: (IdentityEntity) -> Unit,
    onShowMessage: (String) -> Unit,
    onNavigateBack: (() -> Unit)? = null
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importedText by remember { mutableStateOf("") }
    var importedName by remember { mutableStateOf("") }
    var upgradeTargetIdentity by remember { mutableStateOf<IdentityEntity?>(null) }
    var pendingDeleteIdentity by remember { mutableStateOf<IdentityEntity?>(null) }
    var pendingSaveIdentity by remember { mutableStateOf<IdentityEntity?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { content ->
                importedText = content
                showImportDialog = true
            } ?: onShowMessage(if (strings.isEnglish) "Unable to read .ini file" else "无法读取所选 .ini 文件")
        }
    }

    val saveIniLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val target = pendingSaveIdentity
        pendingSaveIdentity = null
        if (uri != null && target != null) {
            val savedOk = runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(target.iniPayload)
                } != null
            }.getOrDefault(false)
            onShowMessage(
                if (savedOk) {
                    if (strings.isEnglish) "Saved ${target.name}.ini" else "已导出保存 ${target.name}.ini"
                } else {
                    if (strings.isEnglish) "Failed to save .ini file" else "保存 .ini 文件失败"
                }
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppScreenHeader(
                title = strings.identityManageTitle,
                subtitle = if (strings.isEnglish) {
                    "P-256 ECC Keys · Desktop .ini Sync"
                } else {
                    "椭圆曲线 P-256 身份 · 兼容 PC 端 .ini 同步"
                },
                leading = if (onNavigateBack != null) {
                    {
                        AppCircleIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                            onClick = onNavigateBack
                        )
                    }
                } else null
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppPrimaryPillButton(
                    text = if (strings.isEnglish) "New Key" else "新建身份",
                    leadingIcon = Icons.Default.Add,
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.weight(1f)
                )
                AppSecondaryPillButton(
                    text = if (strings.isEnglish) "Import .ini" else "导入 PC .ini",
                    leadingIcon = Icons.Default.FileOpen,
                    onClick = {
                        importedText = ""
                        importedName = ""
                        showImportDialog = true
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (upgradeProgress != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(colors.periwinkleSoft)
                        .border(1.dp, colors.periwinkle.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (strings.isEnglish) {
                                    "Multi-core Security Upgrade · Lv.${upgradeProgress.first}"
                                } else {
                                    "正在多线程提升安全等级 · 当前 Lv.${upgradeProgress.first}"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.periwinkle
                            )
                            Text(
                                text = if (strings.isEnglish) {
                                    "Hash offset checked: ${upgradeProgress.second}"
                                } else {
                                    "已计算哈希偏移量：${upgradeProgress.second}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                        AppSecondaryPillButton(
                            text = if (strings.isEnglish) "Stop" else "停止",
                            tint = colors.coral,
                            onClick = onCancelUpgrade
                        )
                    }
                    LinearProgressIndicator(
                        color = colors.periwinkle,
                        trackColor = colors.cardElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                    )
                }
            }
        }

        items(identities, key = { it.id }) { item ->
            IdentityCard(
                identity = item,
                isUpgrading = upgradeProgress != null,
                canDelete = identities.size > 1,
                onSetDefault = { onSetDefault(item.id) },
                onUpgradeClick = { upgradeTargetIdentity = item },
                onCopyUid = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("ts_uid", item.uniqueId))
                    onShowMessage(if (strings.isEnglish) "Copied UID" else "已复制唯一 UID")
                },
                onSaveIniFile = {
                    pendingSaveIdentity = item
                    val safeFileName = item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "ts_identity" }
                    saveIniLauncher.launch("$safeFileName.ini")
                },
                onExportIni = {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${item.name}.ini")
                        putExtra(Intent.EXTRA_TEXT, item.iniPayload)
                    }
                    context.startActivity(
                        Intent.createChooser(
                            sendIntent,
                            if (strings.isEnglish) "Export Identity .ini" else "导出 TeamSpeak .ini 身份"
                        )
                    )
                },
                onDelete = { pendingDeleteIdentity = item }
            )
        }
    }

    pendingDeleteIdentity?.let { target ->
        AppModalDialog(
            title = if (strings.isEnglish) "Delete Identity Key?" else "确认删除身份密钥？",
            subtitle = "${target.name} · Lv.${target.securityLevel}",
            onDismiss = { pendingDeleteIdentity = null },
            confirmText = strings.delete,
            confirmColor = colors.coral,
            onConfirm = {
                onDeleteIdentity(target)
                pendingDeleteIdentity = null
            }
        ) {
            Text(
                text = if (strings.isEnglish) {
                    "Deleting this P-256 private key permanently revokes any server admin roles, groups, and badges bound to UID ${target.uniqueId} unless you have exported its .ini backup."
                } else {
                    "删除此 P-256 密钥后，若未提前导出 .ini 备份，绑定在 UID (${target.uniqueId}) 上的服务器管理组与权限将永久丢失且无法恢复。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        AppModalDialog(
            title = if (strings.isEnglish) "Generate Identity Key" else "生成新身份密钥",
            subtitle = if (strings.isEnglish) {
                "Creates a new P-256 ECC keypair at Lv.8"
            } else {
                "自动生成全新的 P-256 密钥对并初始化安全等级"
            },
            onDismiss = { showCreateDialog = false },
            confirmText = strings.confirm,
            onConfirm = {
                onCreateIdentity(newName)
                showCreateDialog = false
            }
        ) {
            AppCapsuleField(
                value = newName,
                onValueChange = { newName = it },
                label = if (strings.isEnglish) "Identity Name" else "身份备注名称",
                placeholder = if (strings.isEnglish) "e.g. Primary Key" else "如 主力身份 / 备用小号"
            )
        }
    }

    if (showImportDialog) {
        AppModalDialog(
            title = if (strings.isEnglish) "Import Desktop Identity" else "导入 PC 端身份",
            subtitle = if (strings.isEnglish) {
                "Keep your server groups and admin roles across devices"
            } else {
                "继承桌面端服务器管理组与个人徽章权限"
            },
            onDismiss = { showImportDialog = false },
            confirmText = strings.confirm,
            confirmEnabled = importedText.isNotBlank(),
            onConfirm = {
                onImportIdentity(importedName, importedText)
                showImportDialog = false
            }
        ) {
            AppSecondaryPillButton(
                text = if (strings.isEnglish) "Pick .ini File" else "从手机文件选择 .ini 身份文件",
                leadingIcon = Icons.Default.FileOpen,
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            )
            AppCapsuleField(
                value = importedName,
                onValueChange = { importedName = it },
                label = if (strings.isEnglish) "Label (Optional)" else "备注名称（选填）",
                placeholder = if (strings.isEnglish) "e.g. Desktop Sync" else "如 PC 同步主身份"
            )
            AppCapsuleField(
                value = importedText,
                onValueChange = { importedText = it },
                label = if (strings.isEnglish) "Or Paste .ini / Key String" else "或粘贴 .ini 内容 / 密钥串",
                placeholder = "identity=\"...\"",
                singleLine = false,
                minLines = 3,
                maxLines = 6
            )
        }
    }

    upgradeTargetIdentity?.let { target ->
        val presets = listOf(15, 20, 23, 25, 27)
        var desiredLevel by remember(target) {
            val nextPreset = presets.firstOrNull { it > target.securityLevel } ?: 27
            mutableIntStateOf(nextPreset)
        }
        AppModalDialog(
            title = if (strings.isEnglish) {
                "Upgrade Security · ${target.name}"
            } else {
                "提升安全等级 · ${target.name}"
            },
            subtitle = if (strings.isEnglish) {
                "Current: Lv.${target.securityLevel}. Each +1 level roughly doubles SHA-1 work."
            } else {
                "当前 Lv.${target.securityLevel}。部分高防社区服要求 Lv.20–Lv.25，等级每 +1 计算量约翻倍。"
            },
            onDismiss = { upgradeTargetIdentity = null },
            confirmText = "Lv.$desiredLevel",
            confirmEnabled = desiredLevel > target.securityLevel,
            onConfirm = {
                onUpgradeIdentity(target, desiredLevel)
                upgradeTargetIdentity = null
            }
        ) {
            AppSegmentedPills(
                options = presets.map { it to "Lv.$it" },
                selected = desiredLevel,
                onSelect = { lvl ->
                    if (lvl > target.securityLevel) desiredLevel = lvl
                }
            )
        }
    }
}

@Composable
private fun IdentityCard(
    identity: IdentityEntity,
    isUpgrading: Boolean,
    canDelete: Boolean,
    onSetDefault: () -> Unit,
    onUpgradeClick: () -> Unit,
    onCopyUid: () -> Unit,
    onSaveIniFile: () -> Unit,
    onExportIni: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val badgeBg = when {
        identity.securityLevel >= 23 -> colors.emeraldSoft
        identity.securityLevel >= 15 -> colors.periwinkleSoft
        else -> colors.marigoldSoft
    }
    val badgeFg = when {
        identity.securityLevel >= 23 -> colors.emerald
        identity.securityLevel >= 15 -> colors.periwinkle
        else -> if (colors.isDark) colors.marigold else colors.inkOnPastel
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(
                width = if (identity.isDefault) 1.5.dp else 1.dp,
                color = if (identity.isDefault) colors.periwinkle else colors.border,
                shape = RoundedCornerShape(22.dp)
            )
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(badgeBg)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = badgeFg,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = identity.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "UID: ${identity.uniqueId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(badgeBg)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "Lv.${identity.securityLevel}",
                    color = badgeFg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        DashedHorizontalLine(
            color = colors.border,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (identity.isDefault) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.periwinkleSoft)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = colors.periwinkle,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (strings.isEnglish) "Default" else "默认身份",
                        color = colors.periwinkle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .pressableScale(0.95f, onClick = onSetDefault)
                        .clip(RoundedCornerShape(50))
                        .background(colors.cardElevated)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (strings.isEnglish) "Set Default" else "设为默认",
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .pressableScale(0.95f, enabled = !isUpgrading, onClick = onUpgradeClick)
                    .clip(RoundedCornerShape(50))
                    .background(colors.cardElevated)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                        contentDescription = null,
                        tint = if (!isUpgrading) colors.textPrimary else colors.textTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (strings.isEnglish) "Upgrade" else "提升等级",
                        color = if (!isUpgrading) colors.textPrimary else colors.textTertiary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            AppCircleIconButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = if (strings.isEnglish) "Copy UID" else "复制唯一 UID",
                size = 34.dp,
                iconSize = 15.dp,
                containerColor = colors.cardElevated,
                onClick = onCopyUid
            )
            AppCircleIconButton(
                icon = Icons.Default.Download,
                contentDescription = if (strings.isEnglish) "Save .ini File" else "保存 .ini 文件到本地",
                size = 34.dp,
                iconSize = 15.dp,
                containerColor = colors.cardElevated,
                onClick = onSaveIniFile
            )
            AppCircleIconButton(
                icon = Icons.Default.Share,
                contentDescription = if (strings.isEnglish) "Share .ini" else "分享导出 .ini",
                size = 34.dp,
                iconSize = 15.dp,
                containerColor = colors.cardElevated,
                onClick = onExportIni
            )
            if (canDelete) {
                AppCircleIconButton(
                    icon = Icons.Default.DeleteOutline,
                    contentDescription = strings.delete,
                    size = 34.dp,
                    iconSize = 15.dp,
                    containerColor = colors.coralSoft,
                    tint = colors.coral,
                    onClick = onDelete
                )
            }
        }
    }
}
