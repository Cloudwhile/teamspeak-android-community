package com.cloudwhile.tscc.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudwhile.tscc.data.update.UpdateStatus
import com.cloudwhile.tscc.data.update.UpdateUiState
import com.cloudwhile.tscc.ui.theme.AppTheme
import kotlin.math.roundToInt

@Composable
fun AppUpdateDialog(
    state: UpdateUiState,
    onStartDownload: () -> Unit,
    onInstallReadyApk: () -> Unit,
    onCancelDownload: () -> Unit,
    onSkipVersion: () -> Unit,
    onDismiss: () -> Unit
) {
    val release = state.latestRelease ?: return
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val context = LocalContext.current
    val isEn = strings.isEnglish
    val isDownloading = state.status == UpdateStatus.DOWNLOADING
    val isReady = state.status == UpdateStatus.READY_TO_INSTALL

    val primaryBtnText = when {
        isDownloading -> {
            val pct = (state.progress * 100f).roundToInt()
            if (isEn) "Downloading $pct%..." else "正在下载 $pct%..."
        }
        isReady -> if (isEn) "Install Update Now" else "立即安装新版本"
        release.selectedAsset == null -> if (isEn) "Open GitHub Release" else "前往 GitHub 下载"
        else -> if (isEn) "Download & Update" else "立即下载并更新"
    }

    val dismissBtnText = if (isDownloading) {
        if (isEn) "Cancel Download" else "取消下载"
    } else {
        if (isEn) "Later" else "稍后提醒"
    }

    AppModalDialog(
        title = if (isEn) "New Version Available" else "发现新版本更新",
        subtitle = "v${state.currentVersion} → v${release.versionName}",
        onDismiss = {
            if (isDownloading) onCancelDownload() else onDismiss()
        },
        dismissText = dismissBtnText,
        confirmText = primaryBtnText,
        confirmEnabled = !isDownloading,
        onConfirm = {
            when {
                isReady -> onInstallReadyApk()
                release.selectedAsset == null -> {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                    }
                    onDismiss()
                }
                else -> onStartDownload()
            }
        }
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.periwinkleSoft)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (isReady) Icons.Default.InstallMobile else Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = colors.periwinkle,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = release.selectedAsset?.matchedAbi ?: "GitHub",
                        color = colors.periwinkle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            release.selectedAsset?.takeIf { it.sizeBytes > 0L }?.let { asset ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.cardElevated)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = formatBytesMb(asset.sizeBytes),
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (release.isPrerelease) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.marigoldSoft)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isEn) "Pre-release" else "预览版",
                        color = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (release.body.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 190.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.cardElevated)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = release.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textPrimary,
                    lineHeight = 18.sp
                )
            }
        }

        if (isDownloading || isReady) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isReady) {
                            if (isEn) "Verified & ready to install" else "安装包校验通过，可立即安装"
                        } else {
                            "${formatBytesMb(state.downloadedBytes)} / ${
                                if (state.totalBytes > 0L) formatBytesMb(state.totalBytes) else "..."
                            }"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isReady) colors.emerald else colors.textSecondary
                    )
                    Text(
                        text = "${(state.progress * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isReady) colors.emerald else colors.periwinkle,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (state.totalBytes > 0L || isReady) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        color = if (isReady) colors.emerald else colors.periwinkle,
                        trackColor = colors.cardElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                    )
                } else {
                    LinearProgressIndicator(
                        color = colors.periwinkle,
                        trackColor = colors.cardElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                    )
                }
            }
        }

        if (!state.errorMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.coralSoft)
                    .border(1.dp, colors.coral.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.coral
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isDownloading && !isReady) {
                Text(
                    text = if (isEn) "Skip v${release.versionName}" else "忽略此版本",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSkipVersion() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )
            } else {
                Spacer(modifier = Modifier.width(4.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = colors.periwinkle,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (isEn) "Download via Browser" else "浏览器下载",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.periwinkle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = colors.periwinkle,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

private fun formatBytesMb(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}
