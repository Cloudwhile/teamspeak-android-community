package com.cloudwhile.tscc.ui.screens.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cloudwhile.tscc.data.AppSettings
import com.cloudwhile.tscc.data.db.IdentityEntity
import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.ui.components.AppBrandLogo
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppFieldIconButton
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppSecondaryPillButton
import com.cloudwhile.tscc.ui.components.AppSegmentedPills
import com.cloudwhile.tscc.ui.components.DashedHorizontalLine
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.i18n.localizeIdentityDisplayName
import com.cloudwhile.tscc.ui.theme.AppTheme

@Composable
fun OnboardingScreen(
    settings: AppSettings,
    identities: List<IdentityEntity>,
    hasMicPermission: Boolean,
    onUpdateLanguage: (AppLanguage) -> Unit,
    onUpdateDarkTheme: (Boolean) -> Unit,
    onUpdateNickname: (String) -> Unit,
    onImportIdentityAsDefault: (name: String, rawIni: String) -> Unit,
    onSelectVoiceMode: (VoiceMode) -> Unit,
    onPermissionsUpdated: () -> Unit,
    onShowMessage: (String) -> Unit,
    onComplete: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val isEn = strings.isEnglish
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var step by rememberSaveable { mutableIntStateOf(0) }
    var nicknameDraft by rememberSaveable { mutableStateOf(settings.defaultNickname) }
    var showImportSection by rememberSaveable { mutableStateOf(false) }
    var importName by rememberSaveable { mutableStateOf("") }
    var importContent by rememberSaveable { mutableStateOf("") }

    var hasNotifPerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var hasBtPerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    BackHandler(enabled = step > 0) {
        step -= 1
    }

    val iniFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                importContent = content
                showImportSection = true
            } else {
                onShowMessage(if (isEn) "Unable to read selected .ini file" else "无法读取所选 .ini 文件")
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsUpdated()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasNotifPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasBtPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    val defaultIdentity = remember(identities) {
        identities.firstOrNull { it.isDefault } ?: identities.firstOrNull()
    }

    val commitNicknameAndFinish: () -> Unit = {
        val clean = nicknameDraft.trim().ifBlank { "TSCC_Player" }
        onUpdateNickname(clean)
        onComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        // Top Step Indicator & Skip Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                repeat(3) { idx ->
                    val widthFrac by animateFloatAsState(
                        targetValue = if (idx == step) 28f else 10f,
                        animationSpec = tween(220, easing = FastOutSlowInEasing),
                        label = "stepPillWidth"
                    )
                    Box(
                        modifier = Modifier
                            .width(widthFrac.dp)
                            .height(7.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                when {
                                    idx == step -> colors.periwinkle
                                    idx < step -> colors.periwinkle.copy(alpha = 0.45f)
                                    else -> colors.cardElevated
                                }
                            )
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${step + 1} / 3",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (step < 2) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.card)
                        .border(1.dp, colors.border, RoundedCornerShape(50))
                        .clickable { commitNicknameAndFinish() }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isEn) "Skip" else "跳过",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Main Animated Step Body
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                if (forward) {
                    (slideInHorizontally(tween(240, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(140))
                        )
                } else {
                    (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(180)))
                        .togetherWith(
                            slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(140))
                        )
                }
            },
            label = "onboardingStep",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (page) {
                    0 -> OnboardingWelcomeStep(
                        isEn = isEn,
                        language = settings.appLanguage,
                        darkTheme = settings.darkTheme,
                        onUpdateLanguage = onUpdateLanguage,
                        onUpdateDarkTheme = onUpdateDarkTheme
                    )

                    1 -> OnboardingIdentityStep(
                        isEn = isEn,
                        nicknameDraft = nicknameDraft,
                        onNicknameChange = { nicknameDraft = it },
                        onDoneKeyboard = {
                            onUpdateNickname(nicknameDraft)
                            focusManager.clearFocus()
                        },
                        defaultIdentity = defaultIdentity,
                        showImportSection = showImportSection,
                        onToggleImportSection = { showImportSection = !showImportSection },
                        importName = importName,
                        onImportNameChange = { importName = it },
                        importContent = importContent,
                        onImportContentChange = { importContent = it },
                        onPickIniFile = { iniFileLauncher.launch(arrayOf("*/*")) },
                        onConfirmImport = {
                            if (importContent.isNotBlank()) {
                                onImportIdentityAsDefault(importName, importContent)
                                importContent = ""
                                importName = ""
                                showImportSection = false
                            }
                        }
                    )

                    else -> OnboardingVoicePermStep(
                        isEn = isEn,
                        selectedMode = settings.voiceMode,
                        hasMicPermission = hasMicPermission,
                        hasNotifPermission = hasNotifPerm,
                        hasBtPermission = hasBtPerm,
                        onSelectVoiceMode = onSelectVoiceMode,
                        onRequestPermissions = {
                            val perms = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    add(Manifest.permission.BLUETOOTH_CONNECT)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            permLauncher.launch(perms.toTypedArray())
                        }
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Bottom Navigation Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (step > 0) {
                AppSecondaryPillButton(
                    text = if (isEn) "Back" else "上一步",
                    onClick = { step -= 1 },
                    modifier = Modifier.weight(0.9f)
                )
            }
            AppPrimaryPillButton(
                text = when (step) {
                    0 -> if (isEn) "Next · Profile & Key" else "下一步 · 昵称与身份"
                    1 -> if (isEn) "Next · Voice & Perms" else "下一步 · 语音与权限"
                    else -> if (isEn) "Get Started" else "完成设置 · 进入 TSCC"
                },
                trailingIcon = if (step < 2) Icons.AutoMirrored.Filled.ArrowForward else null,
                onClick = {
                    when (step) {
                        0 -> step = 1
                        1 -> {
                            onUpdateNickname(nicknameDraft)
                            focusManager.clearFocus()
                            step = 2
                        }
                        else -> commitNicknameAndFinish()
                    }
                },
                modifier = Modifier.weight(if (step > 0) 1.5f else 1f)
            )
        }
    }
}

@Composable
private fun OnboardingWelcomeStep(
    isEn: Boolean,
    language: AppLanguage,
    darkTheme: Boolean,
    onUpdateLanguage: (AppLanguage) -> Unit,
    onUpdateDarkTheme: (Boolean) -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
    ) {
        AppBrandLogo(size = 76.dp, cornerRadius = 22.dp)
        Text(
            text = if (isEn) "Welcome to TSCC" else "欢迎使用 TSCC",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = if (isEn) {
                "Encrypted 48kHz Opus VoIP & Live Screen Share compatible with TeamSpeak 3 & 6 servers"
            } else {
                "兼容 TeamSpeak 3 / 6 协议的 48kHz 低延迟加密语音与屏幕直播客户端"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }

    OnboardingCard(
        title = if (isEn) "Language & Appearance" else "界面语言与主题外观"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (isEn) "Display Language" else "显示语言",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            AppSegmentedPills(
                options = listOf(
                    AppLanguage.SYSTEM to (if (isEn) "System" else "跟随系统"),
                    AppLanguage.ZH_CN to "简体中文",
                    AppLanguage.EN to "English"
                ),
                selected = language,
                onSelect = onUpdateLanguage
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (isEn) "Color Theme" else "色彩外观",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
            AppSegmentedPills(
                options = listOf(
                    true to (if (isEn) "Carbon Dark" else "碳黑深色"),
                    false to (if (isEn) "Crisp Light" else "纯净浅色")
                ),
                selected = darkTheme,
                onSelect = onUpdateDarkTheme
            )
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(
            text = if (isEn) "Unofficial Community Project" else "非官方开源社区声明",
            style = MaterialTheme.typography.titleSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (isEn) {
                "TSCC is an independent open-source project maintained by the community and is NOT affiliated with or endorsed by TeamSpeak Systems GmbH."
            } else {
                "TSCC 是由社区独立开发维护的非官方开源安卓客户端，与 TeamSpeak Systems GmbH 无任何隶属、授权或官方背书关系。所有身份密钥与密码均在本地加密存储。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
    }
}

@Composable
private fun OnboardingIdentityStep(
    isEn: Boolean,
    nicknameDraft: String,
    onNicknameChange: (String) -> Unit,
    onDoneKeyboard: () -> Unit,
    defaultIdentity: IdentityEntity?,
    showImportSection: Boolean,
    onToggleImportSection: () -> Unit,
    importName: String,
    onImportNameChange: (String) -> Unit,
    importContent: String,
    onImportContentChange: (String) -> Unit,
    onPickIniFile: () -> Unit,
    onConfirmImport: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (isEn) "Profile & Identity Key" else "设置昵称与身份密钥",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = if (isEn) {
                "Choose your default display name and review or import your TeamSpeak cryptographic identity."
            } else {
                "填写进入频道默认展示的昵称；如果你在电脑端拥有管理员或权限组身份，可直接导入 .ini 密钥同步权限。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
    }

    OnboardingCard(
        title = if (isEn) "Default Nickname" else "默认连接昵称"
    ) {
        AppCapsuleField(
            value = nicknameDraft,
            onValueChange = onNicknameChange,
            placeholder = "TSCC_Player",
            leadingIcon = Icons.Default.Person,
            trailing = if (nicknameDraft.isNotEmpty()) {
                {
                    AppFieldIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = strings.clearInputDesc,
                        tint = colors.textTertiary,
                        onClick = { onNicknameChange("") }
                    )
                }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDoneKeyboard() })
        )
    }

    OnboardingCard(
        title = if (isEn) "P-256 Cryptographic Identity" else "本地加密身份密钥"
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.cardElevated)
                .border(1.dp, colors.periwinkle.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .padding(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.periwinkleSoft)
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = colors.periwinkle,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = strings.localizeIdentityDisplayName(defaultIdentity?.name),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.emeraldSoft)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Lv.${defaultIdentity?.securityLevel ?: 8}",
                            color = colors.emerald,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = defaultIdentity?.uniqueId?.let { "UID: $it" }
                        ?: (if (isEn) "Auto-generating P-256 key..." else "正在生成椭圆曲线身份密钥..."),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())

        AppSecondaryPillButton(
            text = if (showImportSection) {
                if (isEn) "Collapse Desktop .ini Import" else "收起导入面板"
            } else {
                if (isEn) "Have a Desktop Key? Import .ini" else "已有电脑端身份？导入 .ini 密钥"
            },
            leadingIcon = Icons.Default.FileOpen,
            onClick = onToggleImportSection,
            modifier = Modifier.fillMaxWidth()
        )

        AnimatedVisibility(visible = showImportSection) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppSecondaryPillButton(
                    text = if (isEn) "Select .ini File from Storage" else "从手机存储选择 .ini 文件",
                    leadingIcon = Icons.Default.FileOpen,
                    tint = colors.periwinkle,
                    onClick = onPickIniFile,
                    modifier = Modifier.fillMaxWidth()
                )
                AppCapsuleField(
                    value = importName,
                    onValueChange = onImportNameChange,
                    label = if (isEn) "Identity Remark (Optional)" else "备注名称（选填）",
                    placeholder = if (isEn) "e.g. PC Main Key" else "例如：电脑端主身份"
                )
                AppCapsuleField(
                    value = importContent,
                    onValueChange = onImportContentChange,
                    label = if (isEn) "Or Paste .ini / Raw Identity String" else "或直接粘贴 .ini 文本 / 密钥串",
                    placeholder = "identity=\"...\"",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4
                )
                AppPrimaryPillButton(
                    text = if (isEn) "Import & Set as Default" else "导入并设为默认身份",
                    enabled = importContent.isNotBlank(),
                    onClick = onConfirmImport,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun OnboardingVoicePermStep(
    isEn: Boolean,
    selectedMode: VoiceMode,
    hasMicPermission: Boolean,
    hasNotifPermission: Boolean,
    hasBtPermission: Boolean,
    onSelectVoiceMode: (VoiceMode) -> Unit,
    onRequestPermissions: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val allGranted = hasMicPermission && hasNotifPermission && hasBtPermission

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (isEn) "Voice Mode & Permissions" else "开麦方式与运行权限",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = if (isEn) {
                "Pick how your microphone activates in channels and grant audio/notification permissions."
            } else {
                "选择平时习惯的说话触发模式（后续随时可在设置中微调门限与降噪），并授予通话所需权限。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )
    }

    OnboardingCard(
        title = if (isEn) "Voice Trigger Mode" else "默认开麦方式"
    ) {
        VoiceModeOptionRow(
            icon = Icons.Default.GraphicEq,
            title = if (isEn) "Voice Activity (VAD)" else "语音感应自动开麦 (VAD)",
            subtitle = if (isEn) {
                "Automatically transmits when you speak and gates background silence"
            } else {
                "说话自动发声、安静自动闭麦，解放双手（推荐耳机环境使用）"
            },
            selected = selectedMode == VoiceMode.VAD,
            onClick = { onSelectVoiceMode(VoiceMode.VAD) }
        )
        VoiceModeOptionRow(
            icon = Icons.Default.TouchApp,
            title = if (isEn) "Push-To-Talk (PTT)" else "按住说话 (PTT)",
            subtitle = if (isEn) {
                "Hold the talk bar to speak and release to mute"
            } else {
                "按住房间底部说话条发声，松手立即静音，适合外放或嘈杂环境"
            },
            selected = selectedMode == VoiceMode.PTT,
            onClick = { onSelectVoiceMode(VoiceMode.PTT) }
        )
        VoiceModeOptionRow(
            icon = Icons.Default.HeadsetMic,
            title = if (isEn) "Continuous Transmission" else "常开麦模式",
            subtitle = if (isEn) {
                "Keeps microphone continuously active until manually muted"
            } else {
                "进房后保持持续发声，仅在点击闭麦按钮时静音"
            },
            selected = selectedMode == VoiceMode.CONTINUOUS,
            onClick = { onSelectVoiceMode(VoiceMode.CONTINUOUS) }
        )
    }

    OnboardingCard(
        title = if (isEn) "Audio & Notification Permissions" else "通话与通知权限"
    ) {
        PermStatusItem(
            icon = Icons.Default.Mic,
            title = if (isEn) "Microphone Access" else "麦克风录音权限",
            subtitle = if (isEn) {
                "Required for speaking and mic self-test (you can still listen without it)"
            } else {
                "用于语音频道发言与麦克风自检（未授权时仍可进房收听）"
            },
            granted = hasMicPermission
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermStatusItem(
                icon = Icons.Default.BluetoothAudio,
                title = if (isEn) "Bluetooth Audio Routing" else "蓝牙耳机连接权限",
                subtitle = if (isEn) {
                    "Detects and routes voice calls to Bluetooth headsets automatically"
                } else {
                    "自动识别并切换无线蓝牙耳机通话声道"
                },
                granted = hasBtPermission
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermStatusItem(
                icon = Icons.Default.NotificationsActive,
                title = if (isEn) "Active Call Notification" else "通话状态通知",
                subtitle = if (isEn) {
                    "Keeps voice calls connected in the background with quick mute controls"
                } else {
                    "切换至后台或锁屏时保持语音通话，支持通知栏快捷闭麦"
                },
                granted = hasNotifPermission
            )
        }

        AppSecondaryPillButton(
            text = if (allGranted) {
                if (isEn) "All Permissions Granted" else "所需权限已全部开启"
            } else {
                if (isEn) "Grant Recommended Permissions" else "一键授予通话与通知权限"
            },
            leadingIcon = if (allGranted) Icons.Default.CheckCircle else Icons.Default.Mic,
            tint = if (allGranted) colors.emerald else colors.periwinkle,
            enabled = !allGranted,
            onClick = onRequestPermissions,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun VoiceModeOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressableScale(0.98f, onClick = onClick)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.periwinkleSoft else colors.cardElevated)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) colors.periwinkle else colors.border,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) colors.periwinkle else colors.card)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) Color.White else colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) colors.periwinkle else colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}

@Composable
private fun PermStatusItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (granted) colors.emeraldSoft else colors.cardElevated)
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else icon,
                contentDescription = null,
                tint = if (granted) colors.emerald else colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (granted) colors.emeraldSoft else colors.cardElevated)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (granted) "✓" else "—",
                color = if (granted) colors.emerald else colors.textTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun OnboardingCard(
    title: String,
    content: @Composable () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(22.dp))
            .padding(18.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        content()
    }
}
