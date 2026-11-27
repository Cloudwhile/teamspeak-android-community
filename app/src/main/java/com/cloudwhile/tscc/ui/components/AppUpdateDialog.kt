package com.cloudwhile.tscc.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudwhile.tscc.data.update.UpdateStatus
import com.cloudwhile.tscc.data.update.UpdateUiState
import com.cloudwhile.tscc.data.util.MdAlertKind
import com.cloudwhile.tscc.data.util.MdBlock
import com.cloudwhile.tscc.data.util.ReleaseNotesParser
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

    var showNotesInEnglish by remember(release.tagName, isEn) { mutableStateOf(isEn) }
    val activeNotesMarkdown = remember(release, showNotesInEnglish) {
        release.localizedNotes(showNotesInEnglish)
    }

    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

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
                    openUrl(release.htmlUrl)
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

        if (activeNotesMarkdown.isNotBlank()) {
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
                        text = if (isEn) "Release Notes" else "更新内容",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        fontWeight = FontWeight.Bold
                    )
                    if (release.hasBilingualNotes) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(colors.cardElevated)
                                .border(1.dp, colors.border, RoundedCornerShape(50))
                                .clickable { showNotesInEnglish = !showNotesInEnglish }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = if (isEn) {
                                    "Switch Release Notes Language"
                                } else {
                                    "切换更新日志语言"
                                },
                                tint = colors.periwinkle,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (showNotesInEnglish) "中文" else "EN",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.periwinkle
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 270.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.cardElevated)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    ReleaseNotesMarkdownContent(
                        markdown = activeNotesMarkdown,
                        isEnglish = showNotesInEnglish,
                        onOpenUrl = openUrl
                    )
                }
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
                    .clickable { openUrl(release.htmlUrl) }
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

@Composable
fun ReleaseNotesMarkdownContent(
    markdown: String,
    isEnglish: Boolean,
    onOpenUrl: (String) -> Unit
) {
    val colors = AppTheme.colors
    val blocks = remember(markdown) { ReleaseNotesParser.parseBlocks(markdown) }
    val codeBg = colors.periwinkleSoft
    val codeFg = colors.periwinkle
    val linkColor = colors.periwinkle

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MdBlock.Heading -> {
                    val topPad = if (index > 0) 4.dp else 0.dp
                    val fontSize = when (block.level) {
                        1 -> 15.sp
                        2 -> 14.sp
                        3 -> 13.sp
                        else -> 12.5.sp
                    }
                    MarkdownInlineText(
                        raw = block.text,
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = fontSize,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = (fontSize.value + 5f).sp
                        ),
                        codeBg = codeBg,
                        codeFg = codeFg,
                        linkColor = linkColor,
                        onOpenUrl = onOpenUrl,
                        modifier = Modifier.padding(top = topPad)
                    )
                }

                is MdBlock.Alert -> {
                    val (bg, accent, icon, titleText) = when (block.kind) {
                        MdAlertKind.WARNING -> AlertVisual(
                            bg = colors.marigoldSoft,
                            accent = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                            icon = Icons.Default.WarningAmber,
                            label = if (isEnglish) "Experimental / Warning" else "实验性功能说明"
                        )
                        MdAlertKind.CAUTION -> AlertVisual(
                            bg = colors.coralSoft,
                            accent = colors.coral,
                            icon = Icons.Default.WarningAmber,
                            label = if (isEnglish) "Caution" else "重要风险提示"
                        )
                        MdAlertKind.IMPORTANT -> AlertVisual(
                            bg = colors.periwinkleSoft,
                            accent = colors.periwinkle,
                            icon = Icons.Default.Info,
                            label = if (isEnglish) "Important" else "重要说明"
                        )
                        MdAlertKind.TIP -> AlertVisual(
                            bg = colors.emeraldSoft,
                            accent = colors.emerald,
                            icon = Icons.Default.Lightbulb,
                            label = if (isEnglish) "Tip" else "提示"
                        )
                        MdAlertKind.NOTE -> AlertVisual(
                            bg = colors.periwinkleSoft.copy(alpha = 0.65f),
                            accent = colors.periwinkle,
                            icon = Icons.Default.Info,
                            label = if (isEnglish) "Note" else "说明"
                        )
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(bg)
                            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = titleText,
                                color = accent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        block.lines.forEach { ln ->
                            MarkdownInlineText(
                                raw = ln,
                                style = TextStyle(
                                    color = colors.textPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                ),
                                codeBg = codeBg,
                                codeFg = codeFg,
                                linkColor = linkColor,
                                onOpenUrl = onOpenUrl
                            )
                        }
                    }
                }

                is MdBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(colors.periwinkle.copy(alpha = 0.65f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            block.lines.forEach { ln ->
                                MarkdownInlineText(
                                    raw = ln,
                                    style = TextStyle(
                                        color = colors.textSecondary,
                                        fontSize = 11.5.sp,
                                        lineHeight = 16.5.sp
                                    ),
                                    codeBg = codeBg,
                                    codeFg = codeFg,
                                    linkColor = linkColor,
                                    onOpenUrl = onOpenUrl
                                )
                            }
                        }
                    }
                }

                is MdBlock.BulletItem -> {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indentLevel * 12).dp)
                    ) {
                        Text(
                            text = block.marker,
                            color = colors.periwinkle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 17.5.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        MarkdownInlineText(
                            raw = block.text,
                            style = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                lineHeight = 17.5.sp
                            ),
                            codeBg = codeBg,
                            codeFg = codeFg,
                            linkColor = linkColor,
                            onOpenUrl = onOpenUrl,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.canvas)
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = colors.textSecondary
                        )
                    }
                }

                is MdBlock.TableBlock -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.canvas.copy(alpha = 0.6f))
                            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            block.headers.forEach { h ->
                                Text(
                                    text = h,
                                    color = colors.textSecondary,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())
                        block.rows.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                block.headers.indices.forEach { colIdx ->
                                    MarkdownInlineText(
                                        raw = row.getOrElse(colIdx) { "" },
                                        style = TextStyle(
                                            color = colors.textPrimary,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        ),
                                        codeBg = codeBg,
                                        codeFg = codeFg,
                                        linkColor = linkColor,
                                        onOpenUrl = onOpenUrl,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                is MdBlock.Paragraph -> {
                    MarkdownInlineText(
                        raw = block.text,
                        style = TextStyle(
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.5.sp
                        ),
                        codeBg = codeBg,
                        codeFg = codeFg,
                        linkColor = linkColor,
                        onOpenUrl = onOpenUrl
                    )
                }

                MdBlock.Divider -> {
                    DashedHorizontalLine(
                        color = colors.border,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private data class AlertVisual(
    val bg: Color,
    val accent: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String
)

@Composable
private fun MarkdownInlineText(
    raw: String,
    style: TextStyle,
    codeBg: Color,
    codeFg: Color,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotated = remember(raw, codeBg, codeFg, linkColor) {
        ReleaseNotesParser.parseInline(
            raw = raw,
            codeBg = codeBg,
            codeFg = codeFg,
            linkColor = linkColor
        )
    }
    @Suppress("DEPRECATION")
    ClickableText(
        text = annotated,
        style = style,
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations(ReleaseNotesParser.URL_TAG, offset, offset)
                .firstOrNull()
                ?.let { onOpenUrl(it.item) }
        }
    )
}

private fun formatBytesMb(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}
