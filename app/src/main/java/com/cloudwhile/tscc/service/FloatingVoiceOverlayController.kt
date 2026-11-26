package com.cloudwhile.tscc.service

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cloudwhile.tscc.MainActivity
import com.cloudwhile.tscc.data.TsccRepository
import com.cloudwhile.tscc.data.model.VoiceMode
import com.cloudwhile.tscc.ui.theme.AccentCoral
import com.cloudwhile.tscc.ui.theme.AccentEmerald
import com.cloudwhile.tscc.ui.theme.AccentPeriwinkle
import com.cloudwhile.tscc.ui.theme.AppDarkBorder
import com.cloudwhile.tscc.ui.theme.AppDarkCanvas
import com.cloudwhile.tscc.ui.theme.AppDarkElevated
import com.cloudwhile.tscc.ui.theme.AppTheme
import com.cloudwhile.tscc.ui.theme.TsccTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * High-frame-rate, magnetic edge-docking background floating HUD.
 */
class FloatingVoiceOverlayController(
    private val context: Context,
    private val repository: TsccRepository
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null
    private val isDockedRight = MutableStateFlow(true)
    private var savedY: Int = 280

    private var downRawX = 0f
    private var downRawY = 0f
    private var startParamX = 0
    private var startParamY = 0
    private var draggingActive = false

    fun showIfPermitted() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(context)) return
        val wm = windowManager ?: return

        val dockedRightNow = isDockedRight.value
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or if (dockedRightNow) Gravity.END else Gravity.START
            x = 0
            y = savedY
        }
        layoutParams = params

        val owner = OverlayLifecycleOwner().apply { onCreate() }
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                val dockedRight by isDockedRight.collectAsState()
                val settings by repository.appSettings.collectAsState()
                TsccTheme(
                    darkTheme = true,
                    language = settings.appLanguage,
                    animateTheme = false
                ) {
                    FloatingOverlayHud(
                        repository = repository,
                        dockedRight = dockedRight,
                        onRawTouch = { event, onSingleTap, onDragActiveChanged ->
                            handleRawOverlayTouch(
                                wm = wm,
                                view = this,
                                params = params,
                                event = event,
                                onSingleTap = onSingleTap,
                                onDragActiveChanged = onDragActiveChanged
                            )
                        },
                        onOpenApp = { requestMic ->
                            val intent = Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                if (requestMic) {
                                    putExtra(VoiceCallService.EXTRA_REQUEST_MIC_PERMISSION, true)
                                }
                            }
                            context.startActivity(intent)
                        }
                    )
                }
            }
        }

        runCatching {
            wm.addView(view, params)
            owner.onResume()
            overlayView = view
        }
    }

    private fun handleRawOverlayTouch(
        wm: WindowManager,
        view: ComposeView,
        params: WindowManager.LayoutParams,
        event: MotionEvent,
        onSingleTap: (() -> Unit)?,
        onDragActiveChanged: (Boolean) -> Unit
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                downRawX = event.rawX
                downRawY = event.rawY
                startParamX = params.x
                startParamY = params.y
                draggingActive = false
                onDragActiveChanged(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!draggingActive && hypot(dx, dy) > touchSlop) {
                    draggingActive = true
                }
                if (draggingActive) {
                    val (screenW, screenH) = getScreenBoundsPx(wm)
                    val deltaFromEdge = if (isDockedRight.value) (-dx).roundToInt() else dx.roundToInt()
                    val nextX = (startParamX + deltaFromEdge)
                        .coerceIn(0, (screenW - view.width).coerceAtLeast(0))
                    val nextY = (startParamY + dy.roundToInt())
                        .coerceIn(80, (screenH - view.height - 80).coerceAtLeast(80))

                    if (nextX != params.x || nextY != params.y) {
                        params.x = nextX
                        params.y = nextY
                        savedY = nextY
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onDragActiveChanged(false)
                if (!draggingActive && event.actionMasked == MotionEvent.ACTION_UP && onSingleTap != null) {
                    onSingleTap()
                } else if (params.x != 0) {
                    animateMagneticEdgeSnap(wm, view, params)
                }
                draggingActive = false
                return true
            }
        }
        return false
    }

    private fun animateMagneticEdgeSnap(
        wm: WindowManager,
        view: ComposeView,
        params: WindowManager.LayoutParams
    ) {
        val (screenW, _) = getScreenBoundsPx(wm)
        val viewW = view.width.coerceAtLeast(1)
        val centerDistFromCurrentEdge = params.x + (viewW / 2)

        if (centerDistFromCurrentEdge > screenW / 2) {
            val nextDockedRight = !isDockedRight.value
            isDockedRight.value = nextDockedRight
            params.gravity = Gravity.TOP or if (nextDockedRight) Gravity.END else Gravity.START
            params.x = (screenW - params.x - viewW).coerceAtLeast(0)
        }

        val fromX = params.x
        if (fromX <= 0) {
            params.x = 0
            runCatching { wm.updateViewLayout(view, params) }
            return
        }

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(fromX, 0).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                if (overlayView === view) {
                    params.x = anim.animatedValue as Int
                    runCatching { wm.updateViewLayout(view, params) }
                }
            }
            start()
        }
    }

    fun hide() {
        snapAnimator?.cancel()
        snapAnimator = null
        val wm = windowManager
        overlayView?.let { v ->
            runCatching { wm?.removeView(v) }
        }
        overlayView = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }

    private fun getScreenBoundsPx(wm: WindowManager): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private class OverlayLifecycleOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {

        private val registry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun onCreate() {
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun onResume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun onDestroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FloatingOverlayHud(
    repository: TsccRepository,
    dockedRight: Boolean,
    onRawTouch: (
        event: MotionEvent,
        onSingleTap: (() -> Unit)?,
        onDragActiveChanged: (Boolean) -> Unit
    ) -> Boolean,
    onOpenApp: (requestMicPermission: Boolean) -> Unit
) {
    val snapshot by repository.serverSnapshot.collectAsState()
    val speakingIds by repository.speakingClientIds.collectAsState()
    val isTransmitting by repository.isVoiceTransmitting.collectAsState()
    val isMicMuted by repository.isSelfMicMuted.collectAsState()
    val isOutputMuted by repository.isSelfOutputMuted.collectAsState()
    val isSelfAway by repository.isSelfAway.collectAsState()
    val isPttPressed by repository.isPttPressed.collectAsState()
    val hasMicPerm by repository.hasMicPermission.collectAsState()
    val settings by repository.appSettings.collectAsState()

    var expanded by remember { mutableStateOf(false) }
    var isTouching by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }

    val canSelfTransmit = remember(snapshot, isMicMuted, isOutputMuted, isSelfAway, hasMicPerm) {
        val me = snapshot.clients.firstOrNull { it.id == snapshot.myClientId }
        val ch = snapshot.channels.firstOrNull { it.id == snapshot.myChannelId }
        val talkRestricted = ch != null && ch.neededTalkPower > 0 &&
            me != null && me.talkPower < ch.neededTalkPower && !me.talkPowerGranted
        val away = isSelfAway || me?.isAway == true
        hasMicPerm && !isMicMuted && !isOutputMuted && !away && !talkRestricted
    }
    val effectiveTransmitting = canSelfTransmit && isTransmitting
    val effectivePttPressed = canSelfTransmit && isPttPressed
    val effectiveMicBlocked = !canSelfTransmit

    val allActiveSpeakers = remember(snapshot.clients, speakingIds, effectiveTransmitting, canSelfTransmit) {
        snapshot.clients.filter { c ->
            if (c.id == snapshot.myClientId) {
                canSelfTransmit && (c.id in speakingIds || effectiveTransmitting)
            } else {
                c.id in speakingIds
            }
        }
    }
    val activeSpeakers = remember(allActiveSpeakers) { allActiveSpeakers.take(2) }
    val anyoneSpeaking = allActiveSpeakers.isNotEmpty() || effectiveTransmitting || effectivePttPressed

    LaunchedEffect(expanded, isTouching, interactionNonce, effectivePttPressed) {
        if (expanded && !isTouching && !effectivePttPressed) {
            delay(5000L)
            expanded = false
        }
    }

    val enterScale = remember { Animatable(1f) }
    LaunchedEffect(expanded) {
        enterScale.snapTo(0.92f)
        enterScale.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
    }

    if (!expanded) {
        EdgeMicroDockTab(
            dockedRight = dockedRight,
            isMicMuted = effectiveMicBlocked,
            anyoneSpeaking = anyoneSpeaking,
            speakerCount = allActiveSpeakers.size,
            scaleProvider = { enterScale.value },
            modifier = Modifier.pointerInteropFilter { event ->
                onRawTouch(
                    event,
                    {
                        interactionNonce++
                        expanded = true
                    },
                    { touching -> isTouching = touching }
                )
            }
        )
    } else {
        ExpandedVoiceCapsule(
            dockedRight = dockedRight,
            serverName = snapshot.serverName,
            hasMicPerm = hasMicPerm,
            isMicMuted = effectiveMicBlocked,
            isPttMode = settings.voiceMode == VoiceMode.PTT && canSelfTransmit,
            isPttPressed = effectivePttPressed,
            isTransmitting = effectiveTransmitting,
            activeSpeakers = activeSpeakers.map { it.nickname },
            scaleProvider = { enterScale.value },
            dragHandleModifier = Modifier.pointerInteropFilter { event ->
                onRawTouch(
                    event,
                    null,
                    { touching -> isTouching = touching }
                )
            },
            collapseHandleModifier = Modifier.pointerInteropFilter { event ->
                onRawTouch(
                    event,
                    { expanded = false },
                    { touching -> isTouching = touching }
                )
            },
            onToggleMute = {
                interactionNonce++
                if (!hasMicPerm) {
                    expanded = false
                    onOpenApp(true)
                } else {
                    repository.toggleSelfMicMute()
                }
            },
            onPttPressChanged = { pressed ->
                interactionNonce++
                if (hasMicPerm) {
                    repository.setPttPressed(pressed)
                }
            },
            onOpenApp = { onOpenApp(false) }
        )
    }
}

@Composable
private fun EdgeMicroDockTab(
    dockedRight: Boolean,
    isMicMuted: Boolean,
    anyoneSpeaking: Boolean,
    speakerCount: Int,
    scaleProvider: () -> Float,
    modifier: Modifier = Modifier
) {
    val tabShape = if (dockedRight) {
        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 0.dp, bottomEnd = 0.dp)
    } else {
        RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp)
    }

    val rimColor = when {
        anyoneSpeaking -> AccentEmerald
        isMicMuted -> AccentCoral.copy(alpha = 0.65f)
        else -> Color(0x28FFFFFF)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .width(28.dp)
            .height(52.dp)
            .graphicsLayer {
                val s = scaleProvider()
                scaleX = s
                scaleY = s
                alpha = if (anyoneSpeaking) 0.95f else 0.56f
            }
            .clip(tabShape)
            .background(AppDarkCanvas)
            .border(1.2.dp, rimColor, tabShape)
    ) {
        Icon(
            imageVector = when {
                isMicMuted -> Icons.Default.MicOff
                anyoneSpeaking -> Icons.Default.GraphicEq
                else -> Icons.Default.Mic
            },
            contentDescription = null,
            tint = when {
                isMicMuted -> AccentCoral
                anyoneSpeaking -> AccentEmerald
                else -> Color.White
            },
            modifier = Modifier.size(15.dp)
        )

        if (anyoneSpeaking) {
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(AccentEmerald)
                )
                if (speakerCount > 0) {
                    Text(
                        text = speakerCount.toString(),
                        color = AccentEmerald,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        lineHeight = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedVoiceCapsule(
    dockedRight: Boolean,
    serverName: String,
    hasMicPerm: Boolean,
    isMicMuted: Boolean,
    isPttMode: Boolean,
    isPttPressed: Boolean,
    isTransmitting: Boolean,
    activeSpeakers: List<String>,
    scaleProvider: () -> Float,
    dragHandleModifier: Modifier,
    collapseHandleModifier: Modifier,
    onToggleMute: () -> Unit,
    onPttPressChanged: (Boolean) -> Unit,
    onOpenApp: () -> Unit
) {
    val strings = AppTheme.strings
    val borderColor = when {
        isPttPressed || isTransmitting -> AccentEmerald
        isMicMuted -> AccentCoral.copy(alpha = 0.6f)
        else -> AppDarkBorder
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .graphicsLayer {
                val s = scaleProvider()
                scaleX = s
                scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                    pivotFractionX = if (dockedRight) 1f else 0f,
                    pivotFractionY = 0.5f
                )
            }
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF2141417))
            .border(1.2.dp, borderColor, RoundedCornerShape(22.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isMicMuted -> AccentCoral.copy(alpha = 0.22f)
                            isTransmitting -> AccentEmerald.copy(alpha = 0.20f)
                            else -> AppDarkElevated
                        }
                    )
                    .clickable { onToggleMute() }
            ) {
                Icon(
                    imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (strings.isEnglish) "Toggle Microphone" else "麦克风开关",
                    tint = when {
                        isMicMuted -> AccentCoral
                        isTransmitting -> AccentEmerald
                        else -> Color.White
                    },
                    modifier = Modifier.size(16.dp)
                )
            }

            if (isPttMode && !isMicMuted) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isPttPressed) AccentEmerald else AccentPeriwinkle.copy(alpha = 0.28f)
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onPttPressChanged(true)
                                try {
                                    waitForUpOrCancellation()
                                } finally {
                                    onPttPressChanged(false)
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = if (isPttPressed) {
                            if (strings.isEnglish) "● Live" else "● 发言中"
                        } else {
                            if (strings.isEnglish) "Hold PTT" else "按住说话"
                        },
                        color = if (isPttPressed) Color(0xFF06291D) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = if (!hasMicPerm) {
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onToggleMute() }
                            .height(30.dp)
                            .padding(horizontal = 6.dp)
                    } else {
                        dragHandleModifier
                            .height(30.dp)
                            .padding(horizontal = 4.dp)
                    }
                ) {
                    Text(
                        text = if (!hasMicPerm) {
                            if (strings.isEnglish) "Grant Mic" else "点击授权开麦"
                        } else {
                            serverName.ifBlank { if (strings.isEnglish) "Voice Online" else "语音在线" }
                        },
                        color = if (!hasMicPerm) AccentCoral else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 92.dp)
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AppDarkElevated)
                    .clickable { onOpenApp() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = if (strings.isEnglish) "Open App" else "返回主应用",
                    tint = Color(0xFFD1D1D6),
                    modifier = Modifier.size(14.dp)
                )
            }

            // Collapse + Drag handle (supports both dragging capsule in PTT mode and single-tap collapse!)
            Box(
                contentAlignment = Alignment.Center,
                modifier = collapseHandleModifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AppDarkElevated)
            ) {
                Icon(
                    imageVector = if (dockedRight) {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft
                    },
                    contentDescription = if (strings.isEnglish) "Collapse Overlay" else "收起悬浮窗",
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        if (activeSpeakers.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = dragHandleModifier.padding(start = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AccentEmerald)
                )
                Text(
                    text = activeSpeakers.joinToString(" · "),
                    color = AccentEmerald,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp)
                )
            }
        }
    }
}
