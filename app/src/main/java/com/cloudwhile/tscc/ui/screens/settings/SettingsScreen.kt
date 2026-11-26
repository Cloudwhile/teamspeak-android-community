package com.cloudwhile.tscc.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudwhile.tscc.BuildConfig
import com.cloudwhile.tscc.data.AppSettings
import com.cloudwhile.tscc.data.db.IdentityEntity
import com.cloudwhile.tscc.data.model.AppLanguage
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.ui.components.AppBrandLogo
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppCircleIconButton
import com.cloudwhile.tscc.ui.components.AppModalDialog
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppScreenHeader
import com.cloudwhile.tscc.ui.components.AppSegmentedPills
import com.cloudwhile.tscc.ui.components.DashedHorizontalLine
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.screens.identities.IdentitiesScreen
import com.cloudwhile.tscc.ui.theme.AppTheme
import kotlin.math.roundToInt

private enum class SettingsSubPage(val depth: Int) {
    ROOT(0),
    IDENTITIES(1),
    VOICE_DSP(1),
    ABOUT(1),
    OPEN_SOURCE(1)
}

private data class OpenSourceComponentItem(
    val name: String,
    val license: String,
    val summaryZh: String,
    val summaryEn: String,
    val url: String
)

private val OPEN_SOURCE_COMPONENTS = listOf(
    OpenSourceComponentItem(
        name = "ReSpeak · tsclientlib & tsproto",
        license = "MIT / Apache-2.0",
        summaryZh = "Rust 原生 TeamSpeak 3/6 加密协议栈、状态同步与高速文件传输内核",
        summaryEn = "Rust TeamSpeak 3/6 encrypted protocol state machine & file transfer core",
        url = "https://github.com/ReSpeak/tsclientlib"
    ),
    OpenSourceComponentItem(
        name = "Xiph.Org · Opus (libopus & audiopus)",
        license = "BSD-3-Clause",
        summaryZh = "48,000 Hz 超低延迟交互式人声与立体声音乐编解码器（支持带内 FEC 抗丢包）",
        summaryEn = "48,000 Hz low-latency interactive speech and music audio codec with in-band FEC",
        url = "https://opus-codec.org/"
    ),
    OpenSourceComponentItem(
        name = "WebRTC Android (libwebrtc / stream-webrtc-android)",
        license = "BSD-3-Clause / Apache-2.0",
        summaryZh = "TeamSpeak 6 点对点屏幕共享与摄像头实时硬件视频编解码及 ICE/STUN 传输引擎",
        summaryEn = "TeamSpeak 6 P2P screen share & camera hardware video codec and ICE/STUN transport",
        url = "https://github.com/GetStream/webrtc-android"
    ),
    OpenSourceComponentItem(
        name = "Tokio Asynchronous Runtime",
        license = "MIT",
        summaryZh = "高性能多线程异步事件驱动网络 I/O 与定时器引擎",
        summaryEn = "Event-driven, non-blocking I/O asynchronous runtime for Rust",
        url = "https://tokio.rs/"
    ),
    OpenSourceComponentItem(
        name = "Hickory DNS Resolver",
        license = "MIT / Apache-2.0",
        summaryZh = "支持 Android 自定义 DNS 的异步 SRV (_ts3._udp / _tsdns._tcp) 与双栈解析器",
        summaryEn = "Async DNS & SRV (_ts3._udp / _tsdns._tcp) resolver library",
        url = "https://github.com/hickory-dns/hickory-dns"
    ),
    OpenSourceComponentItem(
        name = "Android Jetpack Compose, Room & DataStore",
        license = "Apache-2.0",
        summaryZh = "现代化声明式原生界面引擎、本地 SQLite 持久化与类型安全偏好存储",
        summaryEn = "Modern declarative Android UI toolkit, SQLite persistence & DataStore",
        url = "https://developer.android.com/jetpack"
    ),
    OpenSourceComponentItem(
        name = "Kotlin Coroutines & Kotlinx Serialization",
        license = "Apache-2.0",
        summaryZh = "结构化并发协程调度与零反射 JSON 数据序列化框架",
        summaryEn = "Structured concurrency coroutines & reflectionless JSON serialization",
        url = "https://github.com/Kotlin/kotlinx.coroutines"
    ),
    OpenSourceComponentItem(
        name = "Coil Image Loader",
        license = "Apache-2.0",
        summaryZh = "基于协程的轻量级频道图标与用户头像本地缓存加载库",
        summaryEn = "Coroutine-backed image loading library for server/channel icons & avatars",
        url = "https://coil-kt.github.io/coil/"
    )
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    identities: List<IdentityEntity>,
    hasMicPermission: Boolean = true,
    upgradeProgress: Pair<Int, Long>?,
    inputLevelDb: Float,
    isVoiceTransmitting: Boolean,
    isLoopbackTesting: Boolean,
    onRequestMicPermission: () -> Unit = {},
    onUpdateNickname: (String) -> Unit,
    onSelectVoiceMode: (VoiceMode) -> Unit,
    onUpdateVadThreshold: (Float) -> Unit,
    onToggleLoopback: (Boolean) -> Unit,
    onUpdateAntiPlosive: (Boolean) -> Unit,
    onUpdateSoundCues: (Boolean) -> Unit,
    onUpdateHardwareAudio: (aec: Boolean, ns: Boolean, agc: Boolean) -> Unit,
    onUpdateOverlayEnabled: (Boolean) -> Unit,
    onUpdateDarkTheme: (Boolean) -> Unit,
    onUpdateLanguage: (AppLanguage) -> Unit,
    onCreateIdentity: (String) -> Unit,
    onImportIdentity: (String, String) -> Unit,
    onUpgradeIdentity: (IdentityEntity, Int) -> Unit,
    onCancelUpgrade: () -> Unit,
    onSetDefaultIdentity: (Long) -> Unit,
    onDeleteIdentity: (IdentityEntity) -> Unit,
    onClearAllAppData: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    var subPage by rememberSaveable { mutableStateOf(SettingsSubPage.ROOT) }
    var showClearDataConfirm by remember { mutableStateOf(false) }

    BackHandler(enabled = subPage != SettingsSubPage.ROOT) {
        subPage = SettingsSubPage.ROOT
    }

    AnimatedContent(
        targetState = subPage,
        transitionSpec = {
            val forward = targetState.depth > initialState.depth
            if (forward) {
                (slideInHorizontally(tween(230, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(180)))
                    .togetherWith(
                        slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 5 } + fadeOut(tween(140))
                    )
            } else {
                (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { -it / 5 } + fadeIn(tween(180)))
                    .togetherWith(
                        slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(140))
                    )
            }
        },
        label = "settingsSharedAxis",
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            SettingsSubPage.ROOT -> {
                SettingsHubRoot(
                    settings = settings,
                    identities = identities,
                    onUpdateNickname = onUpdateNickname,
                    onUpdateLanguage = onUpdateLanguage,
                    onUpdateDarkTheme = onUpdateDarkTheme,
                    onUpdateOverlayEnabled = onUpdateOverlayEnabled,
                    onOpenIdentities = { subPage = SettingsSubPage.IDENTITIES },
                    onOpenVoiceDsp = { subPage = SettingsSubPage.VOICE_DSP },
                    onOpenAbout = { subPage = SettingsSubPage.ABOUT },
                    onOpenOpenSource = { subPage = SettingsSubPage.OPEN_SOURCE },
                    onRequestClearAllData = { showClearDataConfirm = true }
                )
            }

            SettingsSubPage.IDENTITIES -> {
                IdentitiesScreen(
                    identities = identities,
                    upgradeProgress = upgradeProgress,
                    onCreateIdentity = onCreateIdentity,
                    onImportIdentity = onImportIdentity,
                    onUpgradeIdentity = onUpgradeIdentity,
                    onCancelUpgrade = onCancelUpgrade,
                    onSetDefault = onSetDefaultIdentity,
                    onDeleteIdentity = onDeleteIdentity,
                    onShowMessage = onShowMessage,
                    onNavigateBack = { subPage = SettingsSubPage.ROOT }
                )
            }

            SettingsSubPage.VOICE_DSP -> {
                VoiceDspSubScreen(
                    settings = settings,
                    hasMicPermission = hasMicPermission,
                    inputLevelDb = inputLevelDb,
                    isVoiceTransmitting = isVoiceTransmitting,
                    isLoopbackTesting = isLoopbackTesting,
                    onRequestMicPermission = onRequestMicPermission,
                    onSelectVoiceMode = onSelectVoiceMode,
                    onUpdateVadThreshold = onUpdateVadThreshold,
                    onToggleLoopback = onToggleLoopback,
                    onUpdateAntiPlosive = onUpdateAntiPlosive,
                    onUpdateSoundCues = onUpdateSoundCues,
                    onUpdateHardwareAudio = onUpdateHardwareAudio,
                    onBack = { subPage = SettingsSubPage.ROOT }
                )
            }

            SettingsSubPage.ABOUT -> {
                AboutAppSubScreen(
                    onOpenLicenses = { subPage = SettingsSubPage.OPEN_SOURCE },
                    onBack = { subPage = SettingsSubPage.ROOT }
                )
            }

            SettingsSubPage.OPEN_SOURCE -> {
                OpenSourceLicensesSubScreen(
                    onBack = { subPage = SettingsSubPage.ROOT }
                )
            }
        }
    }

    if (showClearDataConfirm) {
        val colors = AppTheme.colors
        val strings = AppTheme.strings
        AppModalDialog(
            title = strings.clearAllDataConfirmTitle,
            subtitle = strings.clearAllDataConfirmDesc,
            onDismiss = { showClearDataConfirm = false },
            confirmText = strings.clearAllDataConfirmBtn,
            confirmColor = colors.coral,
            onConfirm = {
                showClearDataConfirm = false
                subPage = SettingsSubPage.ROOT
                onClearAllAppData()
            }
        ) {}
    }
}

@Composable
private fun SettingsHubRoot(
    settings: AppSettings,
    identities: List<IdentityEntity>,
    onUpdateNickname: (String) -> Unit,
    onUpdateLanguage: (AppLanguage) -> Unit,
    onUpdateDarkTheme: (Boolean) -> Unit,
    onUpdateOverlayEnabled: (Boolean) -> Unit,
    onOpenIdentities: () -> Unit,
    onOpenVoiceDsp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenOpenSource: () -> Unit,
    onRequestClearAllData: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var nicknameDraft by remember { mutableStateOf(settings.defaultNickname) }
    var nicknameFocused by remember { mutableStateOf(false) }
    val latestNicknameDraft by rememberUpdatedState(nicknameDraft)
    val latestUpdateNickname by rememberUpdatedState(onUpdateNickname)

    LaunchedEffect(settings.defaultNickname, nicknameFocused) {
        if (!nicknameFocused && nicknameDraft != settings.defaultNickname) {
            nicknameDraft = settings.defaultNickname
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (latestNicknameDraft != settings.defaultNickname) {
                latestUpdateNickname(latestNicknameDraft)
            }
        }
    }

    val overlayPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onUpdateOverlayEnabled(Settings.canDrawOverlays(context))
    }

    val defaultIdentity = remember(identities) {
        identities.firstOrNull { it.isDefault } ?: identities.firstOrNull()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppScreenHeader(
                title = strings.settingsTitle,
                subtitle = strings.settingsSubtitle
            ) {
                AppCircleIconButton(
                    icon = if (settings.darkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = strings.darkThemeTitle,
                    onClick = { onUpdateDarkTheme(!settings.darkTheme) }
                )
            }
        }

        // Section 1: Profile & Identity Keys
        item {
            SettingsSectionCard(title = strings.sectionIdentityAndProfile) {
                AppCapsuleField(
                    value = nicknameDraft,
                    onValueChange = { nicknameDraft = it },
                    label = strings.defaultNicknameLabel,
                    placeholder = "TSCC_Player",
                    leadingIcon = Icons.Default.Person,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            onUpdateNickname(nicknameDraft)
                            focusManager.clearFocus()
                        }
                    ),
                    modifier = Modifier.onFocusChanged { state ->
                        val wasFocused = nicknameFocused
                        nicknameFocused = state.hasFocus
                        if (wasFocused && !state.hasFocus) {
                            onUpdateNickname(nicknameDraft)
                        }
                    }
                )

                DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())

                SettingsNavEntryRow(
                    icon = Icons.Default.Security,
                    iconBg = colors.periwinkleSoft,
                    iconTint = colors.periwinkle,
                    title = strings.identityManageTitle,
                    subtitle = strings.identityManageSub(
                        defaultIdentity?.name ?: "Default",
                        defaultIdentity?.securityLevel ?: 8,
                        identities.size.coerceAtLeast(1)
                    ),
                    badgeText = "Lv.${defaultIdentity?.securityLevel ?: 8}",
                    onClick = onOpenIdentities
                )
            }
        }

        // Section 2: Voice & Acoustic DSP
        item {
            SettingsSectionCard(title = strings.sectionVoiceAndDsp) {
                SettingsNavEntryRow(
                    icon = Icons.Default.Tune,
                    iconBg = colors.emeraldSoft,
                    iconTint = colors.emerald,
                    title = strings.voiceLabTitle,
                    subtitle = strings.voiceLabSub,
                    badgeText = settings.voiceMode.localizedLabel(strings.isEnglish).substringBefore(" "),
                    onClick = onOpenVoiceDsp
                )
            }
        }

        // Section 3: Language & Appearance
        item {
            SettingsSectionCard(title = strings.sectionGeneralAndAppearance) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.interfaceLanguageLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    AppSegmentedPills(
                        options = listOf(
                            AppLanguage.SYSTEM to (if (strings.isEnglish) "Auto" else "跟随系统"),
                            AppLanguage.ZH_CN to "简体中文",
                            AppLanguage.EN to "English"
                        ),
                        selected = settings.appLanguage,
                        onSelect = onUpdateLanguage
                    )
                }

                SettingToggleRow(
                    title = strings.darkThemeTitle,
                    subtitle = strings.darkThemeSub,
                    checked = settings.darkTheme,
                    onCheckedChange = onUpdateDarkTheme
                )

                SettingToggleRow(
                    title = strings.floatingOverlayTitle,
                    subtitle = strings.floatingOverlaySub,
                    checked = settings.overlayEnabled,
                    onCheckedChange = { wantEnable ->
                        if (wantEnable) {
                            if (Settings.canDrawOverlays(context)) {
                                onUpdateOverlayEnabled(true)
                            } else {
                                runCatching {
                                    overlayPermLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            }
                        } else {
                            onUpdateOverlayEnabled(false)
                        }
                    }
                )
            }
        }

        // Section 4: About, Open Source Licenses & Clear All App Data
        item {
            SettingsSectionCard(title = strings.sectionAboutAndData) {
                SettingsNavEntryRow(
                    icon = Icons.Default.Info,
                    iconBg = colors.periwinkleSoft,
                    iconTint = colors.periwinkle,
                    title = strings.aboutAppTitle,
                    subtitle = strings.aboutAppSub,
                    badgeText = "v${BuildConfig.VERSION_NAME}",
                    onClick = onOpenAbout
                )

                DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())

                SettingsNavEntryRow(
                    icon = Icons.Default.Code,
                    iconBg = colors.marigoldSoft,
                    iconTint = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                    title = strings.openSourceTitle,
                    subtitle = strings.openSourceSub,
                    onClick = onOpenOpenSource
                )

                DashedHorizontalLine(color = colors.border, modifier = Modifier.fillMaxWidth())

                SettingsNavEntryRow(
                    icon = Icons.Default.DeleteForever,
                    iconBg = colors.coralSoft,
                    iconTint = colors.coral,
                    title = strings.clearAllDataTitle,
                    titleColor = colors.coral,
                    subtitle = strings.clearAllDataSub,
                    onClick = onRequestClearAllData
                )
            }
        }
    }
}

@Composable
private fun VoiceDspSubScreen(
    settings: AppSettings,
    hasMicPermission: Boolean = true,
    inputLevelDb: Float,
    isVoiceTransmitting: Boolean,
    isLoopbackTesting: Boolean,
    onRequestMicPermission: () -> Unit = {},
    onSelectVoiceMode: (VoiceMode) -> Unit,
    onUpdateVadThreshold: (Float) -> Unit,
    onToggleLoopback: (Boolean) -> Unit,
    onUpdateAntiPlosive: (Boolean) -> Unit,
    onUpdateSoundCues: (Boolean) -> Unit,
    onUpdateHardwareAudio: (aec: Boolean, ns: Boolean, agc: Boolean) -> Unit,
    onBack: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val latestLoopback by rememberUpdatedState(isLoopbackTesting)
    val latestToggleLoopback by rememberUpdatedState(onToggleLoopback)

    DisposableEffect(Unit) {
        onDispose {
            if (latestLoopback) {
                latestToggleLoopback(false)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppScreenHeader(
                title = strings.voiceLabTitle,
                subtitle = "48,000 Hz · 20 ms Opus VoIP Pipeline",
                leading = {
                    AppCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (strings.isEnglish) "Back" else "返回",
                        onClick = onBack
                    )
                }
            )
        }

        if (!hasMicPermission) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.coralSoft)
                        .border(1.dp, colors.coral.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MicOff,
                            contentDescription = null,
                            tint = colors.coral,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (strings.isEnglish) {
                                "Microphone Permission Disabled"
                            } else {
                                "麦克风权限未开启（麦克风相关功能不可用）"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.coral,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = if (strings.isEnglish) {
                            "Voice capture, VAD gate tuning, hardware acoustic processing, and Opus loopback self-test are disabled until microphone access is granted."
                        } else {
                            "当前仅支持收听频道语音；开麦触发设置、硬件声学降噪及本地语音回路自检均已禁用。点击下方按钮或任意麦克风设置项即可授权开启。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    AppPrimaryPillButton(
                        text = if (strings.isEnglish) "Grant Microphone Permission" else "授权开启麦克风",
                        containerColor = colors.coral,
                        contentColor = Color.White,
                        onClick = onRequestMicPermission,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = if (strings.isEnglish) "Voice Trigger & Mic Self-Test" else "开麦触发与麦克风自检"
            ) {
                AppSegmentedPills(
                    options = VoiceMode.entries.map { it to it.localizedLabel(strings.isEnglish) },
                    selected = settings.voiceMode,
                    onSelect = { mode ->
                        if (hasMicPermission) onSelectVoiceMode(mode) else onRequestMicPermission()
                    },
                    modifier = Modifier.alpha(if (hasMicPermission) 1f else 0.45f)
                )

                val effectiveDb = if (hasMicPermission) inputLevelDb else -90f
                val normLevel = if (hasMicPermission) ((effectiveDb + 80f) / 80f).coerceIn(0f, 1f) else 0f
                val normThreshold = ((settings.vadThresholdDb + 80f) / 80f).coerceIn(0f, 1f)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (hasMicPermission) 1f else 0.45f)
                        .then(
                            if (!hasMicPermission) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onRequestMicPermission
                                )
                            } else {
                                Modifier
                            }
                        )
                ) {
                    LiveVoiceLevelMeter(
                        inputLevelDb = effectiveDb,
                        normLevel = normLevel,
                        normThreshold = normThreshold,
                        showThreshold = settings.voiceMode == VoiceMode.VAD,
                        isVoiceTransmitting = hasMicPermission && isVoiceTransmitting,
                        isEnglish = strings.isEnglish
                    )
                }

                if (settings.voiceMode == VoiceMode.VAD) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (hasMicPermission) 1f else 0.45f)
                    ) {
                        Column {
                            Text(
                                text = if (strings.isEnglish) {
                                    "VAD Gate Threshold: ${settings.vadThresholdDb.roundToInt()} dB (Left = More Sensitive)"
                                } else {
                                    "语音感应门限：${settings.vadThresholdDb.roundToInt()} dB（越往左越灵敏）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                            Slider(
                                value = settings.vadThresholdDb,
                                onValueChange = { if (hasMicPermission) onUpdateVadThreshold(it) },
                                enabled = hasMicPermission,
                                valueRange = -72f..-10f,
                                colors = SliderDefaults.colors(
                                    thumbColor = colors.periwinkle,
                                    activeTrackColor = colors.periwinkle,
                                    inactiveTrackColor = colors.cardElevated
                                )
                            )
                        }
                        if (!hasMicPermission) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onRequestMicPermission
                                    )
                            )
                        }
                    }
                }

                AppPrimaryPillButton(
                    text = when {
                        !hasMicPermission -> if (strings.isEnglish) {
                            "Mic Disabled · Tap to Grant for Loopback Test"
                        } else {
                            "麦克风未授权 · 点击授权开启回路自检"
                        }
                        isLoopbackTesting -> if (strings.isEnglish) {
                            "Stop Opus Loopback Test"
                        } else {
                            "停止本地语音回路自检"
                        }
                        else -> if (strings.isEnglish) {
                            "Start Local Opus Loopback Test"
                        } else {
                            "开启本地 Opus 语音回路自检"
                        }
                    },
                    leadingIcon = when {
                        !hasMicPermission -> Icons.Default.MicOff
                        isLoopbackTesting -> Icons.Default.Stop
                        else -> Icons.Default.GraphicEq
                    },
                    containerColor = when {
                        !hasMicPermission -> colors.cardElevated
                        isLoopbackTesting -> colors.coral
                        else -> null
                    },
                    contentColor = when {
                        !hasMicPermission -> colors.textSecondary
                        isLoopbackTesting -> Color.White
                        else -> null
                    },
                    onClick = {
                        if (hasMicPermission) {
                            onToggleLoopback(!isLoopbackTesting)
                        } else {
                            onRequestMicPermission()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            SettingsSectionCard(
                title = if (strings.isEnglish) "Acoustic Protection & Sound Cues" else "防喷麦声学保护与提示音"
            ) {
                SettingToggleRow(
                    title = strings.antiPlosiveTitle,
                    subtitle = strings.antiPlosiveSub,
                    checked = settings.antiPlosiveEnabled,
                    visuallyEnabled = hasMicPermission,
                    onCheckedChange = {
                        if (hasMicPermission) onUpdateAntiPlosive(it) else onRequestMicPermission()
                    }
                )

                SettingToggleRow(
                    title = strings.soundCuesTitle,
                    subtitle = strings.soundCuesSub,
                    checked = settings.soundCuesEnabled,
                    onCheckedChange = onUpdateSoundCues
                )
            }
        }

        item {
            SettingsSectionCard(
                title = if (strings.isEnglish) "Android Hardware Audio Processing" else "硬件声学降噪处理"
            ) {
                SettingToggleRow(
                    title = if (strings.isEnglish) "Acoustic Echo Canceler (AEC)" else "硬件回声消除 (AEC)",
                    subtitle = if (strings.isEnglish) {
                        "Eliminates speakerphone feedback during calls"
                    } else {
                        "外放通话时消除扬声器回传啸叫"
                    },
                    checked = settings.hardwareAecEnabled,
                    visuallyEnabled = hasMicPermission,
                    onCheckedChange = {
                        if (hasMicPermission) {
                            onUpdateHardwareAudio(it, settings.hardwareNsEnabled, settings.hardwareAgcEnabled)
                        } else {
                            onRequestMicPermission()
                        }
                    }
                )
                SettingToggleRow(
                    title = if (strings.isEnglish) "Noise Suppressor (NS)" else "环境背景降噪 (NS)",
                    subtitle = if (strings.isEnglish) {
                        "Suppresses fan, keyboard, and ambient background noise"
                    } else {
                        "过滤风扇、键盘与周围环境底噪"
                    },
                    checked = settings.hardwareNsEnabled,
                    visuallyEnabled = hasMicPermission,
                    onCheckedChange = {
                        if (hasMicPermission) {
                            onUpdateHardwareAudio(settings.hardwareAecEnabled, it, settings.hardwareAgcEnabled)
                        } else {
                            onRequestMicPermission()
                        }
                    }
                )
                SettingToggleRow(
                    title = if (strings.isEnglish) "Automatic Gain Control (AGC)" else "自动麦克风增益 (AGC)",
                    subtitle = if (strings.isEnglish) {
                        "Balances near and far speech levels"
                    } else {
                        "平衡远近说话音量起伏"
                    },
                    checked = settings.hardwareAgcEnabled,
                    visuallyEnabled = hasMicPermission,
                    onCheckedChange = {
                        if (hasMicPermission) {
                            onUpdateHardwareAudio(settings.hardwareAecEnabled, settings.hardwareNsEnabled, it)
                        } else {
                            onRequestMicPermission()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AboutAppSubScreen(
    onOpenLicenses: () -> Unit,
    onBack: () -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val context = LocalContext.current
    val repoUrl = "https://github.com/Cloudwhile/teamspeak-android-community"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            AppScreenHeader(
                title = strings.aboutAppTitle,
                subtitle = strings.aboutAppSub,
                leading = {
                    AppCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = if (strings.isEnglish) "Back" else "返回",
                        onClick = onBack
                    )
                }
            )
        }

        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.card)
                    .border(1.dp, colors.border, RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 30.dp)
            ) {
                AppBrandLogo(
                    size = 84.dp,
                    cornerRadius = 24.dp
                )
                Text(
                    text = strings.appTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.cardElevated)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.periwinkle
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.marigoldSoft)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (strings.isEnglish) "Unofficial Community" else "非官方社区版",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (colors.isDark) colors.marigold else colors.inkOnPastel,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                DashedHorizontalLine(
                    color = colors.border,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.authorLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    Text(
                        text = "Cloudwhile",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)))
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (strings.isEnglish) "GitHub Repository" else "开源仓库",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Cloudwhile/teamspeak-android-community",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.periwinkle,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = colors.periwinkle,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        item {
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
                    text = if (strings.isEnglish) "Unofficial Project Disclaimer" else "非官方项目声明",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (strings.isEnglish) {
                        "TSCC (TeamSpeak Community Client) is an independent, unofficial open-source project maintained by the community. It is NOT affiliated with, authorized, endorsed, or sponsored by TeamSpeak Systems GmbH. TeamSpeak® is a registered trademark of its respective owner."
                    } else {
                        "TSCC 是由社区独立开发与维护的非官方开源安卓客户端，与 TeamSpeak Systems GmbH 无任何隶属、合作、授权或官方背书关系。TeamSpeak® 等相关名称与标识归其各自权利人所有。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }

        item {
            AppPrimaryPillButton(
                text = strings.openSourceTitle,
                leadingIcon = Icons.Default.Code,
                onClick = onOpenLicenses,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OpenSourceLicensesSubScreen(onBack: () -> Unit) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            AppScreenHeader(
                title = strings.openSourceTitle,
                subtitle = strings.openSourceSub,
                leading = {
                    AppCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        onClick = onBack
                    )
                }
            )
        }

        items(OPEN_SOURCE_COMPONENTS, key = { it.name }) { comp ->
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .pressableScale(0.985f) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(comp.url)))
                        }
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.card)
                    .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = comp.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(colors.periwinkleSoft)
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = comp.license,
                            color = colors.periwinkle,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = if (strings.isEnglish) comp.summaryEn else comp.summaryZh,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun SettingsNavEntryRow(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    subtitle: String,
    titleColor: Color? = null,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressableScale(0.98f, onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconBg)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor ?: colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!badgeText.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.cardElevated)
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = badgeText,
                    color = colors.periwinkle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun LiveVoiceLevelMeter(
    inputLevelDb: Float,
    normLevel: Float,
    normThreshold: Float,
    showThreshold: Boolean,
    isVoiceTransmitting: Boolean,
    isEnglish: Boolean
) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isEnglish) {
                    "Input Level: ${inputLevelDb.roundToInt()} dBFS"
                } else {
                    "实时输入电平：${inputLevelDb.roundToInt()} dBFS"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isVoiceTransmitting) colors.emerald else colors.textSecondary,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isVoiceTransmitting) colors.emeraldSoft else colors.cardElevated)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isVoiceTransmitting) {
                        if (isEnglish) "● Transmitting" else "● 已触发发送"
                    } else {
                        if (isEnglish) "Gated" else "静音门控中"
                    },
                    color = if (isVoiceTransmitting) colors.emerald else colors.textSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.cardElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(normLevel)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(if (isVoiceTransmitting) colors.emerald else colors.periwinkle)
            )
            if (showThreshold) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(normThreshold)
                        .fillMaxHeight()
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(colors.coral)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(colors.card)
            .border(1.dp, colors.border, RoundedCornerShape(22.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        content()
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    visuallyEnabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (visuallyEnabled) 1f else 0.45f)
            .pressableScale(0.985f) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        AppPillSwitch(checked = checked && visuallyEnabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AppPillSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = AppTheme.colors
    val trackBg by animateColorAsState(
        targetValue = if (checked) colors.pillActiveBg else colors.cardElevated,
        animationSpec = tween(200),
        label = "swTrack"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) colors.pillActiveText else colors.textSecondary,
        animationSpec = tween(200),
        label = "swThumb"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 500f),
        label = "swOffset"
    )

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .pressableScale(0.92f) { onCheckedChange(!checked) }
            .clip(RoundedCornerShape(50))
            .background(trackBg)
            .border(1.dp, colors.border, RoundedCornerShape(50))
            .padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(thumbColor)
        )
    }
}
