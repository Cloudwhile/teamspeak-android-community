package com.cloudwhile.tscc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cloudwhile.tscc.data.model.ConnectionStatus
import com.cloudwhile.tscc.data.model.ToastAction
import com.cloudwhile.tscc.data.model.ToastTone
import com.cloudwhile.tscc.data.model.UiToastMessage
import com.cloudwhile.tscc.data.util.TsccUriParser
import com.cloudwhile.tscc.service.VoiceCallService
import com.cloudwhile.tscc.ui.MainViewModel
import com.cloudwhile.tscc.ui.components.AppModalDialog
import com.cloudwhile.tscc.ui.components.AppNavItem
import com.cloudwhile.tscc.ui.components.AppPillBottomBar
import com.cloudwhile.tscc.ui.components.AppTopToast
import com.cloudwhile.tscc.ui.components.AppUpdateDialog
import com.cloudwhile.tscc.ui.components.LaunchSplashOverlay
import com.cloudwhile.tscc.ui.screens.bookmarks.BookmarksScreen
import com.cloudwhile.tscc.ui.screens.onboarding.OnboardingScreen
import com.cloudwhile.tscc.ui.screens.room.IncomingPokeDialog
import com.cloudwhile.tscc.ui.screens.room.ServerRoomScreen
import com.cloudwhile.tscc.ui.screens.settings.SettingsScreen
import com.cloudwhile.tscc.ui.theme.AppTheme
import com.cloudwhile.tscc.ui.theme.TsccTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class MainTab(val icon: ImageVector) {
    BOOKMARKS(Icons.Default.BookmarkBorder),
    ROOM(Icons.Default.HeadsetMic),
    SETTINGS(Icons.Default.Tune)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val settings by viewModel.settings.collectAsState()
            TsccTheme(
                darkTheme = settings.darkTheme,
                language = settings.appLanguage
            ) {
                MainAppShell(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(VoiceCallService.EXTRA_REQUEST_MIC_PERMISSION, false)) {
            intent.removeExtra(VoiceCallService.EXTRA_REQUEST_MIC_PERMISSION)
            viewModel.requestMicPermissionFlow()
        }
        val dataStr = intent.dataString
        if (!dataStr.isNullOrBlank() && TsccUriParser.isTsccDeepLink(dataStr)) {
            intent.data = null
            viewModel.handleIncomingDeepLink(dataStr)
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val extractedUri = TsccUriParser.extractFirstTsccUri(sharedText)
            if (extractedUri != null) {
                intent.removeExtra(Intent.EXTRA_TEXT)
                viewModel.handleIncomingDeepLink(extractedUri)
            }
        }
    }
}

@Composable
private fun MainAppShell(viewModel: MainViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showLaunchSplash by rememberSaveable { mutableStateOf(true) }
    var hasCheckedStartupUpdate by rememberSaveable { mutableStateOf(false) }
    var pendingInstallAfterPermGrant by remember { mutableStateOf(false) }
    var currentTab by rememberSaveable { mutableStateOf(MainTab.BOOKMARKS) }
    var prevTabIndex by remember { mutableIntStateOf(currentTab.ordinal) }
    var activeToast by remember { mutableStateOf<UiToastMessage?>(null) }
    var openTokenDialogSeq by remember { mutableLongStateOf(0L) }
    var lastHandledManualDiscSeq by rememberSaveable { mutableLongStateOf(0L) }
    var lastHandledMicReqSeq by rememberSaveable { mutableLongStateOf(0L) }
    var showManualMicSettingsDialog by remember { mutableStateOf(false) }

    val settings by viewModel.settings.collectAsState()
    val identities by viewModel.identities.collectAsState()
    val hasMicPermission by viewModel.hasMicPermission.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val manualDisconnectSeq by viewModel.manualDisconnectSeq.collectAsState()
    val micPermissionRequestSeq by viewModel.micPermissionRequestSeq.collectAsState()
    val unreadChatCounts by viewModel.unreadChatCounts.collectAsState()
    val pokeQueue by viewModel.pokeQueue.collectAsState()
    val pendingDeepLink by viewModel.pendingDeepLink.collectAsState()
    val onboardingDismissedInSession by viewModel.onboardingDismissedInSession.collectAsState()

    LaunchedEffect(pendingDeepLink) {
        if (pendingDeepLink != null) {
            showLaunchSplash = false
        }
    }

    val colors = AppTheme.colors
    val strings = AppTheme.strings

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshMicPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val installPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingInstallAfterPermGrant && viewModel.updateManager.canRequestPackageInstalls()) {
            pendingInstallAfterPermGrant = false
            viewModel.updateManager.launchInstallerForReadyApk()
        } else {
            pendingInstallAfterPermGrant = false
        }
    }

    val triggerInstallReadyApk: () -> Unit = {
        if (viewModel.updateManager.canRequestPackageInstalls()) {
            if (!viewModel.updateManager.launchInstallerForReadyApk()) {
                viewModel.showToast(
                    if (strings.isEnglish) "Unable to open system package installer" else "无法打开系统安装程序",
                    ToastTone.ERROR
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pendingInstallAfterPermGrant = true
            runCatching {
                installPermLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }

    LaunchedEffect(showLaunchSplash, settings.isLoaded, settings.onboardingCompleted, settings.autoCheckUpdates) {
        if (!showLaunchSplash && settings.isLoaded && settings.onboardingCompleted && !hasCheckedStartupUpdate && settings.autoCheckUpdates) {
            delay(500L)
            hasCheckedStartupUpdate = true
            viewModel.checkForUpdates(manual = false)
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshMicPermission()
        if (granted) {
            viewModel.showToast(
                if (strings.isEnglish) "Microphone permission granted" else "已开启麦克风权限，可正常发言"
            )
        } else {
            val activity = context as? Activity
            val canShowRationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: false
            if (!canShowRationale) {
                showManualMicSettingsDialog = true
            } else {
                viewModel.showToast(
                    if (strings.isEnglish) {
                        "Microphone permission denied — mic features remain disabled"
                    } else {
                        "未授予麦克风权限，麦克风相关功能保持禁用"
                    },
                    ToastTone.WARNING
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshMicPermission()
    }

    LaunchedEffect(micPermissionRequestSeq) {
        if (micPermissionRequestSeq > lastHandledMicReqSeq) {
            lastHandledMicReqSeq = micPermissionRequestSeq
            if (!viewModel.refreshMicPermission()) {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Automatically jump into Room tab on connect
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.CONNECTING ||
            connectionStatus == ConnectionStatus.CONNECTED
        ) {
            currentTab = MainTab.ROOM
        }
    }

    // Automatically return to Bookmarks tab on intentional manual disconnect
    LaunchedEffect(manualDisconnectSeq) {
        if (manualDisconnectSeq > lastHandledManualDiscSeq) {
            lastHandledManualDiscSeq = manualDisconnectSeq
            currentTab = MainTab.BOOKMARKS
        }
    }

    // Subscribe to structured graded toast events
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { incoming ->
            activeToast = incoming
        }
    }

    LaunchedEffect(activeToast?.id) {
        val current = activeToast ?: return@LaunchedEffect
        val durationMs = when {
            current.action != null -> 4600L
            current.tone == ToastTone.ERROR -> 3800L
            current.tone == ToastTone.WARNING -> 3400L
            else -> 2300L
        }
        delay(durationMs)
        if (activeToast?.id == current.id) {
            activeToast = null
        }
    }

    // Stop local Opus loopback automatically if user switches away from Settings tab
    LaunchedEffect(currentTab) {
        if (currentTab != MainTab.SETTINGS && viewModel.isLoopbackTesting.value) {
            viewModel.repository.setLoopbackTesting(false)
        }
        val newIdx = currentTab.ordinal
        val oldIdx = prevTabIndex
        if (newIdx == oldIdx) return@LaunchedEffect
        val dir = if (newIdx > oldIdx) 1f else -1f
        prevTabIndex = newIdx
    }

    // Directional horizontal + alpha RenderNode transition on tab switch
    val tabAlpha = remember { Animatable(1f) }
    val tabTranslateX = remember { Animatable(0f) }
    var animPrevIdx by remember { mutableIntStateOf(currentTab.ordinal) }
    LaunchedEffect(currentTab) {
        val newIdx = currentTab.ordinal
        val oldIdx = animPrevIdx
        if (newIdx == oldIdx) return@LaunchedEffect
        val dir = if (newIdx > oldIdx) 1f else -1f
        animPrevIdx = newIdx

        tabAlpha.snapTo(0.80f)
        tabTranslateX.snapTo(28f * dir)
        launch {
            tabAlpha.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        }
        launch {
            tabTranslateX.animateTo(0f, tween(210, easing = FastOutSlowInEasing))
        }
    }

    val totalUnread = unreadChatCounts.values.sum()
    val navItems = remember(connectionStatus, totalUnread, strings) {
        listOf(
            AppNavItem(
                id = MainTab.BOOKMARKS,
                label = strings.tabBookmarks,
                icon = MainTab.BOOKMARKS.icon
            ),
            AppNavItem(
                id = MainTab.ROOM,
                label = strings.tabRoom,
                icon = MainTab.ROOM.icon,
                liveDot = connectionStatus == ConnectionStatus.CONNECTED,
                badgeCount = totalUnread
            ),
            AppNavItem(
                id = MainTab.SETTINGS,
                label = strings.tabSettings,
                icon = MainTab.SETTINGS.icon
            )
        )
    }

    var onboardingSessionLocked by rememberSaveable { mutableStateOf(false) }
    if (onboardingDismissedInSession && onboardingSessionLocked) {
        onboardingSessionLocked = false
    } else if (!onboardingDismissedInSession && settings.isLoaded && !settings.onboardingCompleted && !onboardingSessionLocked) {
        onboardingSessionLocked = true
    }
    val showOnboarding = !onboardingDismissedInSession &&
        (onboardingSessionLocked || (settings.isLoaded && !settings.onboardingCompleted))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
    ) {
        if (showOnboarding) {
            OnboardingScreen(
                settings = settings,
                identities = identities,
                hasMicPermission = hasMicPermission,
                onUpdateLanguage = viewModel::updateAppLanguage,
                onUpdateDarkTheme = viewModel::updateDarkTheme,
                onUpdateNickname = viewModel::updateDefaultNickname,
                onImportIdentityAsDefault = { name, rawIni ->
                    viewModel.importIdentity(name, rawIni, makeDefault = true)
                },
                onSelectVoiceMode = viewModel::updateVoiceMode,
                onPermissionsUpdated = { viewModel.refreshMicPermission() },
                onShowMessage = { viewModel.showToast(it) },
                onComplete = viewModel::completeOnboarding
            )
        } else {
            Scaffold(
                containerColor = colors.canvas,
                bottomBar = {
                    AppPillBottomBar(
                        items = navItems,
                        selected = currentTab,
                        onSelect = { currentTab = it }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .graphicsLayer {
                            alpha = tabAlpha.value
                            translationX = tabTranslateX.value
                        }
                ) {
                    when (currentTab) {
                        MainTab.BOOKMARKS -> BookmarksTabHost(
                            viewModel = viewModel,
                            onNavigateToRoom = { currentTab = MainTab.ROOM }
                        )
                        MainTab.ROOM -> RoomTabHost(
                            viewModel = viewModel,
                            openTokenDialogSeq = openTokenDialogSeq,
                            onNavigateToBookmarks = { currentTab = MainTab.BOOKMARKS }
                        )
                        MainTab.SETTINGS -> SettingsTabHost(viewModel = viewModel)
                    }
                }
            }
        }

        val actionLabel = when (activeToast?.action) {
            ToastAction.OPEN_PRIVILEGE_KEY -> strings.enterTokenAction
            ToastAction.EXIT_AFK -> strings.exitAfkAction
            ToastAction.REQUEST_TALK_POWER -> strings.requestTalkAction
            ToastAction.UNMUTE_MIC -> if (strings.isEnglish) "Unmute" else "开启麦克风"
            ToastAction.GRANT_MIC_PERMISSION -> if (strings.isEnglish) "Grant Mic" else "授权麦克风"
            null -> null
        }

        AppTopToast(
            toast = activeToast,
            actionLabel = actionLabel,
            onActionClick = {
                val act = activeToast?.action
                activeToast = null
                when (act) {
                    ToastAction.OPEN_PRIVILEGE_KEY -> {
                        currentTab = MainTab.ROOM
                        openTokenDialogSeq++
                    }
                    ToastAction.EXIT_AFK -> {
                        viewModel.repository.setSelfAway(false, "")
                        viewModel.showToast(
                            if (strings.isEnglish) "Exited Away (AFK) mode" else "已解除离开状态，可正常发言"
                        )
                    }
                    ToastAction.REQUEST_TALK_POWER -> {
                        viewModel.repository.requestTalkPower()
                    }
                    ToastAction.UNMUTE_MIC -> {
                        if (!viewModel.refreshMicPermission()) {
                            viewModel.requestMicPermissionFlow()
                        } else if (viewModel.isSelfMicMuted.value) {
                            viewModel.repository.toggleSelfMicMute()
                        }
                    }
                    ToastAction.GRANT_MIC_PERMISSION -> {
                        viewModel.requestMicPermissionFlow()
                    }
                    null -> {}
                }
            },
            onDismiss = { activeToast = null },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        if (showLaunchSplash) {
            LaunchSplashOverlay(
                onFinished = { showLaunchSplash = false }
            )
        }
    }

    if (showManualMicSettingsDialog) {
        AppModalDialog(
            title = if (strings.isEnglish) "Microphone Permission Required" else "需要开启麦克风权限",
            subtitle = if (strings.isEnglish) {
                "Microphone access is disabled with \"Don't ask again\". Please enable it in system settings."
            } else {
                "麦克风权限已被设为不再询问，请在系统设置中手动开启后使用。"
            },
            onDismiss = { showManualMicSettingsDialog = false },
            confirmText = if (strings.isEnglish) "Open Settings" else "前往系统设置开启",
            onConfirm = {
                showManualMicSettingsDialog = false
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }
            }
        ) {
            Text(
                text = if (strings.isEnglish) {
                    "Tap \"Open Settings\" → \"Permissions\" → \"Microphone\" and select \"Allow while using the app\"."
                } else {
                    "请点击「前往系统设置开启」→「权限管理」→「麦克风」，将其设为「仅在使用中允许」后返回即可立即开麦。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }

    pokeQueue.firstOrNull()?.let { firstPoke ->
        IncomingPokeDialog(
            poke = firstPoke,
            onDismiss = { viewModel.repository.dismissPoke(firstPoke.id) },
            onReplyPoke = { clid, reply ->
                viewModel.repository.sendPoke(clid, reply)
            }
        )
    }

    if (!showLaunchSplash && updateState.showDialog && updateState.latestRelease != null) {
        AppUpdateDialog(
            state = updateState,
            onStartDownload = {
                viewModel.updateManager.startDownload(
                    isEnglish = strings.isEnglish,
                    onReadyToInstall = { triggerInstallReadyApk() }
                )
            },
            onInstallReadyApk = triggerInstallReadyApk,
            onCancelDownload = viewModel::cancelUpdateDownload,
            onSkipVersion = viewModel::skipAvailableUpdate,
            onDismiss = viewModel::dismissUpdateDialog
        )
    }

    if (!showOnboarding) {
        pendingDeepLink?.let { uri ->
            var saveAsBookmark by remember(uri) { mutableStateOf(!uri.label.isNullOrBlank()) }
            AppModalDialog(
                title = if (strings.isEnglish) "Join External Voice Server?" else "加入外部语音频道？",
                subtitle = if (strings.isEnglish) {
                    "Voice server invite link detected"
                } else {
                    "检测到服务器语音邀请链接"
                },
                onDismiss = viewModel::consumePendingDeepLink,
                confirmText = strings.enterNow,
                onConfirm = {
                    viewModel.connectFromDeepLink(uri, saveBookmark = saveAsBookmark)
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!uri.label.isNullOrBlank()) {
                        Text(
                            text = uri.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "${uri.host}:${uri.port}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary
                    )
                    if (!uri.channel.isNullOrBlank()) {
                        Text(
                            text = "${strings.defaultSubChannelPrefix}${uri.channel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.cardElevated)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .clickable { saveAsBookmark = !saveAsBookmark }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (saveAsBookmark) colors.emerald else colors.textTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (strings.isEnglish) {
                                "Also save to Server Bookmarks"
                            } else {
                                "同时保存到服务器书签"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarksTabHost(
    viewModel: MainViewModel,
    onNavigateToRoom: () -> Unit
) {
    val bookmarks by viewModel.bookmarks.collectAsState()
    val selectedBookmarkId by viewModel.selectedBookmarkId.collectAsState()
    val activeBookmarkId by viewModel.activeBookmarkId.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val identities by viewModel.identities.collectAsState()
    val settings by viewModel.settings.collectAsState()

    BookmarksScreen(
        bookmarks = bookmarks,
        selectedBookmarkId = selectedBookmarkId,
        activeBookmarkId = activeBookmarkId,
        connectionStatus = connectionStatus,
        identities = identities,
        defaultNickname = settings.defaultNickname,
        darkTheme = settings.darkTheme,
        onToggleDarkTheme = { viewModel.updateDarkTheme(!settings.darkTheme) },
        onSelectBookmark = viewModel::selectBookmark,
        onConnectBookmark = viewModel::connectBookmark,
        onReturnToRoom = onNavigateToRoom,
        onSaveBookmark = viewModel::saveBookmark,
        onDeleteBookmark = viewModel::deleteBookmark,
        onCopyMessage = { viewModel.showToast(it) }
    )
}

@Composable
private fun RoomTabHost(
    viewModel: MainViewModel,
    openTokenDialogSeq: Long,
    onNavigateToBookmarks: () -> Unit
) {
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val snapshot by viewModel.serverSnapshot.collectAsState()
    val speakingIds by viewModel.speakingClientIds.collectAsState()
    val hasMicPermission by viewModel.hasMicPermission.collectAsState()
    val isVoiceTransmitting by viewModel.isVoiceTransmitting.collectAsState()
    val isSelfMicMuted by viewModel.isSelfMicMuted.collectAsState()
    val isSelfOutputMuted by viewModel.isSelfOutputMuted.collectAsState()
    val isSelfAway by viewModel.isSelfAway.collectAsState()
    val isPttPressed by viewModel.isPttPressed.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val availableRoutes by viewModel.availableAudioRoutes.collectAsState()
    val activeRoute by viewModel.activeAudioRoute.collectAsState()
    val collapsedChannelIds by viewModel.collapsedChannelIds.collectAsState()
    val userAudioPrefs by viewModel.userAudioPrefs.collectAsState()
    val iconCachePaths by viewModel.iconCachePaths.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()
    val unreadChatCounts by viewModel.unreadChatCounts.collectAsState()
    val activeDmPeer by viewModel.activeDmPeer.collectAsState()
    val passwordPrompt by viewModel.passwordPrompt.collectAsState()
    val lastConnectionRequest by viewModel.lastConnectionRequest.collectAsState()
    val watchingStreamState by viewModel.watchingStreamState.collectAsState()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsState()
    val broadcastingStreamState by viewModel.broadcastingStreamState.collectAsState()
    val strings = AppTheme.strings

    ServerRoomScreen(
        status = connectionStatus,
        statusMessage = statusMessage,
        snapshot = snapshot,
        speakingIds = speakingIds,
        hasMicPermission = hasMicPermission,
        isVoiceTransmitting = isVoiceTransmitting,
        isMicMuted = isSelfMicMuted,
        isOutputMuted = isSelfOutputMuted,
        isSelfAway = isSelfAway,
        isPttPressed = isPttPressed,
        voiceMode = settings.voiceMode,
        overlayEnabled = settings.overlayEnabled,
        collapsedChannelIds = collapsedChannelIds,
        userAudioPrefs = userAudioPrefs,
        iconCachePaths = iconCachePaths,
        chatMessages = chatMessages,
        unreadChatCounts = unreadChatCounts,
        activeDmPeer = activeDmPeer,
        availableRoutes = availableRoutes,
        activeRoute = activeRoute,
        passwordPrompt = passwordPrompt,
        lastConnectionRequest = lastConnectionRequest,
        openTokenDialogSeq = openTokenDialogSeq,
        watchingStreamState = watchingStreamState,
        remoteVideoTrack = remoteVideoTrack,
        broadcastingStreamState = broadcastingStreamState,
        eglContextProvider = viewModel::eglContext,
        onRequestMicPermission = viewModel::requestMicPermissionFlow,
        onSetActiveChatTarget = viewModel.repository::setActiveChatTarget,
        onWatchPeerStream = viewModel.repository.liveStreamManager::watchPeerStream,
        onStopWatchingStream = viewModel.repository.liveStreamManager::stopWatching,
        onToggleWatchAudioMute = viewModel.repository.liveStreamManager::toggleWatchAudioMute,
        onSetWatchMinimized = viewModel.repository.liveStreamManager::setWatchMinimized,
        onSetWatchFullscreen = viewModel.repository.liveStreamManager::setWatchFullscreen,
        onStartScreenBroadcast = { intent, title, w, h, fps, kbps ->
            viewModel.repository.liveStreamManager.startScreenBroadcast(intent, title, w, h, fps, kbps)
        },
        onStartCameraBroadcast = { title, w, h, fps, kbps ->
            viewModel.repository.liveStreamManager.startCameraBroadcast(title, true, w, h, fps, kbps)
        },
        onSwitchBroadcastCamera = viewModel.repository.liveStreamManager::switchBroadcastCamera,
        onStopBroadcast = viewModel.repository.liveStreamManager::stopBroadcast,
        onDismissPasswordPrompt = viewModel.repository::dismissPasswordPrompt,
        onToggleChannelCollapsed = viewModel::toggleChannelCollapsed,
        onRequestJoinChannel = viewModel.repository::requestJoinChannel,
        onRequestChannelDescription = viewModel.repository::requestChannelDescription,
        onToggleMicMute = viewModel.repository::toggleSelfMicMute,
        onToggleOutputDeafen = viewModel.repository::toggleSelfOutputDeafen,
        onSetSelfAway = viewModel.repository::setSelfAway,
        onSetPttPressed = viewModel.repository::setPttPressed,
        onSelectAudioRoute = viewModel::selectAudioRoute,
        onToggleOverlay = viewModel::updateOverlayEnabled,
        onDisconnect = {
            viewModel.repository.disconnect(
                if (strings.isEnglish) "Disconnected by user" else "用户主动断开",
                manual = true
            )
        },
        onRetryConnect = { viewModel.repository.reconnectLast() },
        onRetryWithPassword = { newPwd -> viewModel.repository.reconnectLast(newPwd) },
        onBackToBookmarks = onNavigateToBookmarks,
        onSendChatMessage = viewModel.repository::sendChatMessage,
        onClearUnreadChat = viewModel.repository::clearUnreadChat,
        onOpenDmWith = viewModel::openDirectMessageWith,
        onUpdateUserVolume = viewModel.repository::setUserVolumeAndMute,
        onSendPoke = viewModel.repository::sendPoke,
        onUsePrivilegeKey = viewModel.repository::usePrivilegeKey,
        onRequestTalkPower = { viewModel.repository.requestTalkPower() },
        onCreateChannel = { name, pid, pwd, topic, desc, maxC, lc, isDef, music, q, tp, autoJoin ->
            viewModel.repository.createChannel(
                name = name,
                parentId = pid,
                password = pwd,
                topic = topic,
                description = desc,
                maxClients = maxC,
                lifecycle = lc,
                isDefault = isDef,
                opusMusic = music,
                quality = q,
                neededTalkPower = tp,
                autoJoin = autoJoin
            )
        },
        onEditChannel = { cid, pid, name, pwdMode, pwd, topic, desc, maxC, lc, isDef, music, q, tp ->
            viewModel.repository.editChannel(
                channelId = cid,
                parentId = pid,
                name = name,
                passwordMode = pwdMode,
                password = pwd,
                topic = topic,
                description = desc,
                maxClients = maxC,
                lifecycle = lc,
                isDefault = isDef,
                opusMusic = music,
                quality = q,
                neededTalkPower = tp
            )
        },
        onDeleteChannel = viewModel.repository::deleteChannel,
        onMoveClientToChannel = { clid, targetCid ->
            viewModel.repository.moveClientToChannel(clid, targetCid)
        },
        onKickClient = viewModel.repository::kickClient,
        onBanClient = viewModel.repository::banClient,
        onRequestServerVariables = viewModel.repository::requestServerVariables,
        onRequestClientDetails = viewModel.repository::requestClientDetails,
        onUpdateSelfServerNickname = viewModel.repository::updateSelfServerNickname,
        onSetSelfChannelCommander = viewModel.repository::setSelfChannelCommander,
        onEditClientDescription = viewModel.repository::editClientDescription,
        onSetClientTalkPowerGranted = viewModel.repository::setClientTalkPowerGranted,
        onSetClientServerGroup = viewModel.repository::setClientServerGroup,
        onSetClientChannelGroup = viewModel.repository::setClientChannelGroup,
        onShowMessage = { viewModel.showToast(it) },
        onOpenDeepLink = viewModel::handleIncomingDeepLink
    )
}

@Composable
private fun SettingsTabHost(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val identities by viewModel.identities.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val hasMicPermission by viewModel.hasMicPermission.collectAsState()
    val upgradeProgress by viewModel.securityUpgradeProgress.collectAsState()
    val inputLevelDb by viewModel.inputLevelDb.collectAsState()
    val isVoiceTransmitting by viewModel.isVoiceTransmitting.collectAsState()
    val isLoopbackTesting by viewModel.isLoopbackTesting.collectAsState()

    SettingsScreen(
        settings = settings,
        identities = identities,
        bookmarksCount = bookmarks.size,
        hasMicPermission = hasMicPermission,
        updateState = updateState,
        upgradeProgress = upgradeProgress,
        inputLevelDb = inputLevelDb,
        isVoiceTransmitting = isVoiceTransmitting,
        isLoopbackTesting = isLoopbackTesting,
        onRequestMicPermission = viewModel::requestMicPermissionFlow,
        onCheckForUpdates = { viewModel.checkForUpdates(manual = true) },
        onUpdateAutoCheckUpdates = viewModel::updateAutoCheckUpdates,
        onUpdateNickname = viewModel::updateDefaultNickname,
        onSelectVoiceMode = viewModel::updateVoiceMode,
        onUpdateVadThreshold = viewModel::updateVadThreshold,
        onToggleLoopback = viewModel.repository::setLoopbackTesting,
        onUpdateAntiPlosive = viewModel::updateAntiPlosive,
        onUpdateSoundCues = viewModel::updateSoundCues,
        onUpdateHardwareAudio = viewModel::updateHardwareAudio,
        onUpdateOverlayEnabled = viewModel::updateOverlayEnabled,
        onUpdateDarkTheme = viewModel::updateDarkTheme,
        onUpdateLanguage = viewModel::updateAppLanguage,
        onCreateIdentity = viewModel::createIdentity,
        onImportIdentity = viewModel::importIdentity,
        onUpgradeIdentity = viewModel::upgradeIdentity,
        onCancelUpgrade = viewModel::cancelIdentityUpgrade,
        onSetDefaultIdentity = viewModel::setDefaultIdentity,
        onDeleteIdentity = viewModel::deleteIdentity,
        onExportConfig = viewModel::exportTsccConfig,
        onImportConfig = viewModel::importTsccConfig,
        onClearAllAppData = viewModel::clearAllAppData,
        onShowMessage = { viewModel.showToast(it) }
    )
}
