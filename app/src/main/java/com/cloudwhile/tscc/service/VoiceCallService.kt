package com.cloudwhile.tscc.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cloudwhile.tscc.MainActivity
import com.cloudwhile.tscc.R
import com.cloudwhile.tscc.TsccApp
import com.cloudwhile.tscc.data.model.ConnectionStatus
import com.cloudwhile.tscc.ui.i18n.resolveAppStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class VoiceCallService : LifecycleService() {

    companion object {
        const val ACTION_START_CALL = "com.cloudwhile.tscc.ACTION_START_CALL"
        const val ACTION_START_LOOPBACK = "com.cloudwhile.tscc.ACTION_START_LOOPBACK"
        const val ACTION_STOP_CALL = "com.cloudwhile.tscc.ACTION_STOP_CALL"
        const val ACTION_TOGGLE_MUTE = "com.cloudwhile.tscc.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_DEAFEN = "com.cloudwhile.tscc.ACTION_TOGGLE_DEAFEN"
        const val ACTION_DISCONNECT = "com.cloudwhile.tscc.ACTION_DISCONNECT"
        const val ACTION_PROMOTE_SCREEN_SHARE = "com.cloudwhile.tscc.ACTION_PROMOTE_SCREEN_SHARE"
        const val ACTION_PROMOTE_CAMERA_SHARE = "com.cloudwhile.tscc.ACTION_PROMOTE_CAMERA_SHARE"
        const val ACTION_DEMOTE_STREAM_SHARE = "com.cloudwhile.tscc.ACTION_DEMOTE_STREAM_SHARE"
        const val ACTION_REFRESH_MIC_PERMISSION = "com.cloudwhile.tscc.ACTION_REFRESH_MIC_PERMISSION"
        const val EXTRA_REQUEST_MIC_PERMISSION = "com.cloudwhile.tscc.EXTRA_REQUEST_MIC_PERMISSION"

        private const val NOTIF_ID_CALL = 1001
        private const val NOTIF_ID_POKE = 1002

        private val _mediaProjectionFgsReady = MutableStateFlow(false)
        val mediaProjectionFgsReady: StateFlow<Boolean> = _mediaProjectionFgsReady.asStateFlow()
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()
    private lateinit var audioIo: AndroidAudioIo
    private lateinit var routeController: AudioRouteController
    private lateinit var overlayController: FloatingVoiceOverlayController

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastAlertedPokeId: String? = null
    private var isScreenProjectionActive: Boolean = false
    private var isCameraActive: Boolean = false
    private var pendingStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as TsccApp
        val repo = app.tsccRepository

        routeController = repo.routeController
        audioIo = AndroidAudioIo(
            context = this,
            onAudioFocusRestored = { routeController.reapplyActiveRoute() }
        )
        overlayController = FloatingVoiceOverlayController(this, repo)

        lifecycleScope.launch {
            repo.isSelfOutputMuted.collect { deafened ->
                audioIo.setOutputDeafened(deafened)
            }
        }

        lifecycleScope.launch {
            repo.appSettings.collect { settings ->
                audioIo.updateHardwareEffects(settings)
            }
        }

        lifecycleScope.launch {
            combine(
                repo.appSettings,
                repo.connectionStatus,
                repo.isLoopbackTesting,
                app.isAppInForeground
            ) { settings, status, loopback, inForeground ->
                settings.overlayEnabled &&
                    !inForeground &&
                    (status == ConnectionStatus.CONNECTED || loopback)
            }.collect { shouldShowOverlay ->
                if (shouldShowOverlay) {
                    overlayController.showIfPermitted()
                } else {
                    overlayController.hide()
                }
            }
        }

        lifecycleScope.launch {
            combine(
                repo.connectionStatus,
                repo.serverSnapshot,
                repo.isSelfMicMuted,
                repo.isSelfOutputMuted,
                combine(repo.isLoopbackTesting, repo.appSettings, repo.hasMicPermission) { lb, s, micPerm ->
                    Triple(lb, s.appLanguage, micPerm)
                }
            ) { status, snap, micMuted, outMuted, (loopback, lang, hasMicPerm) ->
                NotificationState(
                    status = status,
                    serverName = snap.serverName,
                    channelName = snap.channels.firstOrNull { it.id == snap.myChannelId }?.name.orEmpty(),
                    micMuted = micMuted,
                    outMuted = outMuted,
                    hasMicPerm = hasMicPerm,
                    loopback = loopback,
                    isEnglish = resolveAppStrings(lang).isEnglish
                )
            }.collect { st ->
                if (st.status != ConnectionStatus.DISCONNECTED || st.loopback) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIF_ID_CALL, buildCallNotification(st))
                }
            }
        }

        lifecycleScope.launch {
            repo.pokeQueue.collect { pokes ->
                val latest = pokes.lastOrNull() ?: return@collect
                if (latest.id != lastAlertedPokeId) {
                    lastAlertedPokeId = latest.id
                    val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                    triggerPokeVibrationAndHeadsUp(latest.fromName, latest.message, isEn)
                }
            }
        }

        lifecycleScope.launch {
            repo.liveStreamManager.broadcastingState.collect { bState ->
                if (!bState.isBroadcasting && !bState.isStarting && (isScreenProjectionActive || isCameraActive)) {
                    isScreenProjectionActive = false
                    isCameraActive = false
                    _mediaProjectionFgsReady.value = false
                    if (repo.connectionStatus.value != ConnectionStatus.DISCONNECTED || repo.isLoopbackTesting.value) {
                        val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                        promoteToForeground(currentNotifState(repo, isEn))
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val app = application as TsccApp
        val repo = app.tsccRepository

        when (intent?.action) {
            ACTION_START_CALL, ACTION_START_LOOPBACK -> {
                pendingStopJob?.cancel()
                pendingStopJob = null
                val hasMic = repo.refreshMicPermission()
                val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                promoteToForeground(
                    NotificationState(
                        status = repo.connectionStatus.value,
                        serverName = repo.serverSnapshot.value.serverName,
                        channelName = "",
                        micMuted = repo.isSelfMicMuted.value,
                        outMuted = repo.isSelfOutputMuted.value,
                        hasMicPerm = hasMic,
                        loopback = intent.action == ACTION_START_LOOPBACK,
                        isEnglish = isEn
                    )
                )
                acquireLocks()
                routeController.start()
                audioIo.start(repo.appSettings.value)
            }

            ACTION_REFRESH_MIC_PERMISSION -> {
                pendingStopJob?.cancel()
                pendingStopJob = null
                val hasMic = repo.refreshMicPermission()
                if (repo.connectionStatus.value != ConnectionStatus.DISCONNECTED || repo.isLoopbackTesting.value) {
                    val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                    promoteToForeground(currentNotifState(repo, isEn))
                    if (hasMic) {
                        audioIo.ensureCaptureStarted(repo.appSettings.value)
                    }
                }
            }

            ACTION_PROMOTE_SCREEN_SHARE -> {
                pendingStopJob?.cancel()
                pendingStopJob = null
                isScreenProjectionActive = true
                isCameraActive = false
                val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                promoteToForeground(currentNotifState(repo, isEn))
                _mediaProjectionFgsReady.value = true
            }

            ACTION_PROMOTE_CAMERA_SHARE -> {
                pendingStopJob?.cancel()
                pendingStopJob = null
                isCameraActive = true
                isScreenProjectionActive = false
                _mediaProjectionFgsReady.value = false
                val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                promoteToForeground(currentNotifState(repo, isEn))
            }

            ACTION_DEMOTE_STREAM_SHARE -> {
                isScreenProjectionActive = false
                isCameraActive = false
                _mediaProjectionFgsReady.value = false
                val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                if (repo.connectionStatus.value != ConnectionStatus.DISCONNECTED || repo.isLoopbackTesting.value) {
                    promoteToForeground(currentNotifState(repo, isEn))
                }
            }

            ACTION_TOGGLE_MUTE -> {
                if (repo.refreshMicPermission()) {
                    repo.toggleSelfMicMute()
                }
            }
            ACTION_TOGGLE_DEAFEN -> repo.toggleSelfOutputDeafen()
            ACTION_DISCONNECT -> {
                val isEn = resolveAppStrings(repo.appSettings.value.appLanguage).isEnglish
                repo.disconnect(if (isEn) "Disconnected from notification" else "主动退出", manual = true)
                scheduleGracefulStop()
            }

            ACTION_STOP_CALL -> {
                scheduleGracefulStop()
            }
        }

        return START_STICKY
    }

    private fun scheduleGracefulStop() {
        stopCleanup()
        pendingStopJob?.cancel()
        pendingStopJob = lifecycleScope.launch {
            delay(180)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onDestroy() {
        pendingStopJob?.cancel()
        pendingStopJob = null
        stopCleanup()
        super.onDestroy()
    }

    private fun stopCleanup() {
        isScreenProjectionActive = false
        isCameraActive = false
        _mediaProjectionFgsReady.value = false
        overlayController.hide()
        audioIo.stop()
        routeController.stop()
        releaseLocks()
    }

    private fun currentNotifState(repo: com.cloudwhile.tscc.data.TsccRepository, isEn: Boolean) =
        NotificationState(
            status = repo.connectionStatus.value,
            serverName = repo.serverSnapshot.value.serverName,
            channelName = repo.serverSnapshot.value.channels
                .firstOrNull { it.id == repo.serverSnapshot.value.myChannelId }?.name.orEmpty(),
            micMuted = repo.isSelfMicMuted.value,
            outMuted = repo.isSelfOutputMuted.value,
            hasMicPerm = repo.hasMicPermission.value,
            loopback = repo.isLoopbackTesting.value,
            isEnglish = isEn
        )

    private fun promoteToForeground(state: NotificationState) {
        val notif = buildCallNotification(state)
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        var fgsType = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPerm) {
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Listen-only fallback when RECORD_AUDIO permission is denied: guarantees valid FGS startup on Android 14+
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isScreenProjectionActive) {
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isCameraActive && hasCamPerm) {
            fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }

        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID_CALL, notif, fgsType)
        }
    }

    private fun buildCallNotification(state: NotificationState): Notification {
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val requestMicPending = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REQUEST_MIC_PERMISSION, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mutePending = serviceActionPending(ACTION_TOGGLE_MUTE, 1)
        val deafenPending = serviceActionPending(ACTION_TOGGLE_DEAFEN, 2)
        val hangupPending = serviceActionPending(ACTION_DISCONNECT, 3)

        val en = state.isEnglish
        val title = when {
            state.loopback -> if (en) "Local Opus Loopback Test" else "本地麦克风回路自检中"
            state.status == ConnectionStatus.CONNECTING -> if (en) "Connecting to server..." else "正在连接服务器..."
            state.serverName.isNotBlank() -> state.serverName
            else -> if (en) "Voice Call Active" else "TeamSpeak 语音通话"
        }

        val statusBadge = when {
            state.outMuted -> if (en) "Deafened" else "已闭听"
            !state.hasMicPerm -> if (en) "Listen Only (No Mic Permission)" else "仅收听（未授麦克风权限）"
            state.micMuted -> if (en) "Mic Muted" else "已闭麦"
            else -> if (en) "Voice Online" else "语音在线"
        }
        val subtitle = if (state.channelName.isNotBlank()) {
            "${state.channelName} · $statusBadge"
        } else {
            statusBadge
        }

        val firstActionLabel = when {
            !state.hasMicPerm -> if (en) "Grant Mic" else "授权开麦"
            state.micMuted -> if (en) "Unmute" else "开麦"
            else -> if (en) "Mute" else "闭麦"
        }
        val firstActionPending = if (!state.hasMicPerm) requestMicPending else mutePending

        return NotificationCompat.Builder(this, TsccApp.CHANNEL_VOICE_CALL)
            .setSmallIcon(R.drawable.ic_notif_headset)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openAppPending)
            .addAction(0, firstActionLabel, firstActionPending)
            .addAction(
                0,
                if (state.outMuted) (if (en) "Undeafen" else "恢复收听") else (if (en) "Deafen" else "闭听"),
                deafenPending
            )
            .addAction(0, if (en) "Disconnect" else "断开", hangupPending)
            .build()
    }

    private fun serviceActionPending(action: String, reqCode: Int): PendingIntent {
        val i = Intent(this, VoiceCallService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            reqCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun triggerPokeVibrationAndHeadsUp(fromName: String, message: String, isEn: Boolean) {
        val am = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val isSilent = am?.ringerMode == AudioManager.RINGER_MODE_SILENT ||
            nm?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            nm?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        if (!isSilent) {
            runCatching {
                val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 140, 80, 180), -1)
                )
            }
        }

        val openPending = PendingIntent.getActivity(
            this,
            99,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, TsccApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notif_headset)
            .setContentTitle(if (isEn) "$fromName poked you" else "$fromName 戳了你一下")
            .setContentText(message.ifBlank { if (isEn) "Tap to view" else "点击返回查看" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID_POKE, notif)
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TSCC:VoiceWakeLock")
                ?.apply {
                    setReferenceCounted(false)
                    runCatching { acquire(6 * 60 * 60 * 1000L) }
                }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "TSCC:VoiceWifiLock")?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock = null
    }

    private data class NotificationState(
        val status: ConnectionStatus,
        val serverName: String,
        val channelName: String,
        val micMuted: Boolean,
        val outMuted: Boolean,
        val hasMicPerm: Boolean,
        val loopback: Boolean,
        val isEnglish: Boolean
    )
}
