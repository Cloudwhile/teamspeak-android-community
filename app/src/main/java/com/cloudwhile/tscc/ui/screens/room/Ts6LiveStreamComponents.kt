package com.cloudwhile.tscc.ui.screens.room

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cloudwhile.tscc.data.model.BroadcastingStreamState
import com.cloudwhile.tscc.data.model.LiveStreamSourceType
import com.cloudwhile.tscc.data.model.LiveWatchStatus
import com.cloudwhile.tscc.data.model.OnlineClient
import com.cloudwhile.tscc.data.model.WatchingStreamState
import com.cloudwhile.tscc.service.Ts6LiveStreamManager
import com.cloudwhile.tscc.ui.components.AppCapsuleField
import com.cloudwhile.tscc.ui.components.AppPrimaryPillButton
import com.cloudwhile.tscc.ui.components.AppSecondaryPillButton
import com.cloudwhile.tscc.ui.components.AppSegmentedPills
import com.cloudwhile.tscc.ui.components.PulsingEmeraldDot
import com.cloudwhile.tscc.ui.components.pressableScale
import com.cloudwhile.tscc.ui.theme.AppTheme
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class WebRtcTextureRenderer(context: Context) :
    TextureView(context),
    TextureView.SurfaceTextureListener,
    VideoSink {

    private val eglRenderer = EglRenderer("TS6TextureRenderer")
    private var initialized = false
    private var released = false
    private var mirror = false

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun init(sharedContext: EglBase.Context?) {
        if (initialized || released || sharedContext == null) return
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        eglRenderer.disableFpsReduction()
        initialized = true
        if (isAvailable) {
            surfaceTexture?.let { eglRenderer.createEglSurface(it) }
        }
    }

    fun setMirror(mirror: Boolean) {
        if (this.mirror != mirror) {
            this.mirror = mirror
            updateTransformMatrix()
        }
    }

    override fun onFrame(frame: VideoFrame?) {
        val f = frame ?: return
        if (!initialized || released) return
        val rw = f.rotatedWidth
        val rh = f.rotatedHeight
        if (rw > 0 && rh > 0 && (rw != frameWidth || rh != frameHeight)) {
            frameWidth = rw
            frameHeight = rh
            post {
                if (!released) updateTransformMatrix()
            }
        }
        eglRenderer.onFrame(f)
    }

    private fun updateTransformMatrix() {
        val vw = width
        val vh = height
        val fw = frameWidth
        val fh = frameHeight
        if (vw <= 0 || vh <= 0) return

        val matrix = Matrix()
        var scaleX = 1f
        var scaleY = 1f
        if (fw > 0 && fh > 0) {
            val viewAspect = vw.toFloat() / vh.toFloat()
            val frameAspect = fw.toFloat() / fh.toFloat()
            if (frameAspect > viewAspect) {
                scaleY = viewAspect / frameAspect
            } else {
                scaleX = frameAspect / viewAspect
            }
        }
        val finalScaleX = if (mirror) -scaleX else scaleX
        matrix.setScale(finalScaleX, scaleY, vw / 2f, vh / 2f)
        setTransform(matrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTransformMatrix()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized && !released) {
            eglRenderer.createEglSurface(surface)
        }
        updateTransformMatrix()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        updateTransformMatrix()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized && !released) {
            val latch = CountDownLatch(1)
            eglRenderer.releaseEglSurface { latch.countDown() }
            runCatching { latch.await(400L, TimeUnit.MILLISECONDS) }
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun release() {
        if (released) return
        released = true
        surfaceTextureListener = null
        if (initialized) {
            initialized = false
            runCatching { eglRenderer.release() }
        }
    }
}

@Composable
fun WebRtcVideoSurface(
    videoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    mirror: Boolean = false,
    modifier: Modifier = Modifier
) {
    val rendererHolder = remember { arrayOfNulls<WebRtcTextureRenderer>(1) }
    val boundTrackHolder = remember { arrayOfNulls<VideoTrack>(1) }

    DisposableEffect(Unit) {
        onDispose {
            val view = rendererHolder[0]
            val prev = boundTrackHolder[0]
            if (view != null && prev != null) {
                Ts6LiveStreamManager.unbindTrackSink(prev, view)
            }
            boundTrackHolder[0] = null
            if (view != null) {
                runCatching { view.release() }
                rendererHolder[0] = null
            }
        }
    }

    if (videoTrack == null || eglContext == null) {
        val view = rendererHolder[0]
        val prev = boundTrackHolder[0]
        if (view != null && prev != null) {
            Ts6LiveStreamManager.unbindTrackSink(prev, view)
            boundTrackHolder[0] = null
        }
        Box(modifier = modifier.background(Color(0xFF0A0A0D)))
        return
    }

    AndroidView(
        factory = { ctx ->
            WebRtcTextureRenderer(ctx).apply {
                init(eglContext)
                setMirror(mirror)
                rendererHolder[0] = this
                Ts6LiveStreamManager.bindTrackSink(videoTrack, this)
                boundTrackHolder[0] = videoTrack
            }
        },
        update = { view ->
            view.init(eglContext)
            view.setMirror(mirror)
            val prev = boundTrackHolder[0]
            if (prev !== videoTrack) {
                if (prev != null) {
                    Ts6LiveStreamManager.unbindTrackSink(prev, view)
                }
                Ts6LiveStreamManager.bindTrackSink(videoTrack, view)
                boundTrackHolder[0] = videoTrack
            }
        },
        modifier = modifier
    )
}

@Composable
fun LiveBadgePill(
    isEnglish: Boolean,
    viewers: Int = 0,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val label = buildString {
        append(if (isEnglish) "LIVE" else "直播中")
        if (viewers > 0) append(" · $viewers")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFE11D48))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        PulsingEmeraldDot(size = 5.dp, color = Color.White)
        Text(
            text = label,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
fun ActiveChannelStreamsStrip(
    streamers: List<OnlineClient>,
    myClientId: Int,
    watchingStreamerId: Int,
    isEnglish: Boolean,
    onWatchPeer: (OnlineClient) -> Unit
) {
    val colors = AppTheme.colors
    val peers = streamers.filter { it.id != myClientId }
    if (peers.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        peers.forEach { streamer ->
            val isWatchingThis = watchingStreamerId == streamer.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .pressableScale(0.98f) { onWatchPeer(streamer) }
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.card)
                    .border(
                        width = 1.dp,
                        color = if (isWatchingThis) Color(0xFFE11D48) else colors.border,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                LiveBadgePill(isEnglish = isEnglish, viewers = streamer.streamViewers)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = streamer.streamName.ifBlank {
                            if (isEnglish) "${streamer.nickname}'s Screen Share" else "${streamer.nickname} 的屏幕直播"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (streamer.streamType == LiveStreamSourceType.CAMERA.tsType) {
                            if (isEnglish) "${streamer.nickname} · Camera" else "${streamer.nickname} · 摄像头画面"
                        } else {
                            if (isEnglish) "${streamer.nickname} · TS6 WebRTC" else "${streamer.nickname} · TS6 屏幕共享"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isWatchingThis) Color(0x22E11D48) else colors.periwinkleSoft)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isWatchingThis) Color(0xFFE11D48) else colors.periwinkle,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isWatchingThis) {
                            if (isEnglish) "Watching" else "观看中"
                        } else {
                            if (isEnglish) "Watch" else "观看直播"
                        },
                        color = if (isWatchingThis) Color(0xFFE11D48) else colors.periwinkle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SelfBroadcastingBanner(
    state: BroadcastingStreamState,
    isEnglish: Boolean,
    onSwitchCamera: () -> Unit,
    onStopBroadcast: () -> Unit
) {
    if (!state.isBroadcasting && !state.isStarting) return
    val colors = AppTheme.colors

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x1FE11D48))
            .border(1.dp, Color(0x66E11D48), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        LiveBadgePill(isEnglish = isEnglish, viewers = state.viewerCount)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.streamTitle.ifBlank {
                    if (isEnglish) "Sharing Live" else "正在向频道推流直播"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isEnglish) {
                    "${state.width}×${state.height} ${state.fps}fps · ${state.viewerCount} watching"
                } else {
                    "${state.width}×${state.height} ${state.fps}fps · ${state.viewerCount} 人正在观看"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = colors.textSecondary
            )
        }

        if (state.sourceType == LiveStreamSourceType.CAMERA) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(colors.cardElevated)
                    .clickable { onSwitchCamera() }
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .pressableScale(0.95f, onClick = onStopBroadcast)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFE11D48))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.StopScreenShare,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (isEnglish) "Stop" else "结束直播",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun Ts6LiveStreamViewerStage(
    state: WatchingStreamState,
    videoTrack: VideoTrack?,
    eglContext: EglBase.Context?,
    isEnglish: Boolean,
    onToggleMute: () -> Unit,
    onSetMinimized: (Boolean) -> Unit,
    onSetFullscreen: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    if (state.status == LiveWatchStatus.IDLE) return
    val colors = AppTheme.colors

    if (state.isMinimized) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.cardElevated)
                .border(1.dp, Color(0x66E11D48), RoundedCornerShape(50))
                .clickable { onSetMinimized(false) }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            LiveBadgePill(isEnglish = isEnglish)
            Text(
                text = "${state.streamerName} · ${state.streamTitle}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (isEnglish) "Expand" else "展开画面",
                color = colors.periwinkle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onClose() }
            )
        }
        return
    }

    if (state.isFullscreen) {
        Dialog(
            onDismissRequest = { onSetFullscreen(false) },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true
            )
        ) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = nextScale
                            if (nextScale > 1.02f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
            ) {
                if (videoTrack != null && state.status != LiveWatchStatus.ERROR) {
                    WebRtcVideoSurface(
                        videoTrack = videoTrack,
                        eglContext = eglContext,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                    )
                }

                if (state.status == LiveWatchStatus.CONNECTING) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xCC0A0A0D))
                            .padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFE11D48),
                            strokeWidth = 2.6.dp,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = if (isEnglish) {
                                "Negotiating TS6 WebRTC Stream with ${state.streamerName}..."
                            } else {
                                "正在与 ${state.streamerName} 建立 TS6 P2P 直播通道..."
                            },
                            color = Color(0xFFE5E7EB),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                ViewerOverlayBar(
                    state = state,
                    isFullscreen = true,
                    isEnglish = isEnglish,
                    onToggleMute = onToggleMute,
                    onMinimize = { onSetMinimized(true) },
                    onToggleFullscreen = { onSetFullscreen(false) },
                    onClose = onClose,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                )
            }
        }
        return
    }

    // Docked 16:9 Stream Stage Card inside Room Screen
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF0A0A0D))
            .border(1.dp, colors.border, RoundedCornerShape(22.dp))
    ) {
        if (videoTrack != null && state.status != LiveWatchStatus.ERROR) {
            WebRtcVideoSurface(
                videoTrack = videoTrack,
                eglContext = eglContext,
                modifier = Modifier.fillMaxSize()
            )
        }

        when (state.status) {
            LiveWatchStatus.PLAYING -> {}

            LiveWatchStatus.CONNECTING -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC0A0A0D))
                        .padding(16.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFE11D48),
                        strokeWidth = 2.6.dp,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isEnglish) {
                            "Negotiating TS6 WebRTC Stream with ${state.streamerName}..."
                        } else {
                            "正在与 ${state.streamerName} 建立 TS6 P2P 直播通道..."
                        },
                        color = Color(0xFFE5E7EB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            LiveWatchStatus.ERROR -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                ) {
                    Text(
                        text = state.errorMessage ?: if (isEnglish) "Stream connection failed" else "直播连接失败",
                        color = Color(0xFFFDA4AF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppPrimaryPillButton(
                            text = if (isEnglish) "Retry" else "重试连接",
                            onClick = onRetry
                        )
                        AppSecondaryPillButton(
                            text = if (isEnglish) "Close" else "关闭",
                            onClick = onClose
                        )
                    }
                }
            }

            LiveWatchStatus.IDLE -> {}
        }

        ViewerOverlayBar(
            state = state,
            isFullscreen = false,
            isEnglish = isEnglish,
            onToggleMute = onToggleMute,
            onMinimize = { onSetMinimized(true) },
            onToggleFullscreen = { onSetFullscreen(true) },
            onClose = onClose,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(10.dp)
        )
    }
}

@Composable
private fun ViewerOverlayBar(
    state: WatchingStreamState,
    isFullscreen: Boolean,
    isEnglish: Boolean,
    onToggleMute: () -> Unit,
    onMinimize: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Color(0x9909090B))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        LiveBadgePill(isEnglish = isEnglish)
        Text(
            text = buildString {
                append(state.streamerName)
                if (state.resolutionText.isNotEmpty()) {
                    append(" · ${state.resolutionText}")
                }
            },
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        OverlayTinyButton(
            icon = if (state.streamAudioMuted) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            },
            onClick = onToggleMute
        )
        OverlayTinyButton(
            icon = Icons.Default.Minimize,
            onClick = onMinimize
        )
        OverlayTinyButton(
            icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            onClick = onToggleFullscreen
        )
        OverlayTinyButton(
            icon = Icons.Default.Close,
            tint = Color(0xFFFDA4AF),
            onClick = onClose
        )
    }
}

@Composable
private fun OverlayTinyButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable { onClick() }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StartTs6BroadcastBottomSheet(
    defaultNickname: String,
    isEnglish: Boolean,
    onDismiss: () -> Unit,
    onStartScreenShare: (title: String, width: Int, height: Int, fps: Int, bitrateKbps: Int) -> Unit,
    onStartCameraShare: (title: String, width: Int, height: Int, fps: Int, bitrateKbps: Int) -> Unit
) {
    val colors = AppTheme.colors
    var sourceType by remember { mutableStateOf(LiveStreamSourceType.SCREEN) }
    var streamTitle by remember {
        mutableStateOf(if (isEnglish) "$defaultNickname's Stream" else "${defaultNickname} 的直播")
    }
    var presetIndex by remember { mutableIntStateOf(1) } // 0 = 720p30, 1 = 1080p30, 2 = 1080p60

    data class QualityPreset(
        val label: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val kbps: Int
    )

    val presets = remember(isEnglish) {
        listOf(
            QualityPreset("720p · 30fps (2.5 Mbps)", 1280, 720, 30, 2500),
            QualityPreset("1080p · 30fps (4.2 Mbps)", 1920, 1080, 30, 4200),
            QualityPreset("1080p · 60fps (6.5 Mbps)", 1920, 1080, 60, 6500)
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0x22E11D48))
                ) {
                    Icon(
                        imageVector = Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = Color(0xFFE11D48),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = if (isEnglish) "Start TS6 Live Stream" else "开启 TeamSpeak 6 频道直播",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (isEnglish) {
                            "WebRTC P2P channel streaming · Works directly on TS6 servers"
                        } else {
                            "基于 TS6 原生 WebRTC 点对点协议，连接 TS6 服务端即可直接推流"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary
                    )
                }
            }

            AppSegmentedPills(
                options = listOf(
                    LiveStreamSourceType.SCREEN to (if (isEnglish) "Screen Share" else "共享手机屏幕"),
                    LiveStreamSourceType.CAMERA to (if (isEnglish) "Camera Live" else "摄像头画面")
                ),
                selected = sourceType,
                onSelect = { sourceType = it }
            )

            AppCapsuleField(
                value = streamTitle,
                onValueChange = { streamTitle = it },
                label = if (isEnglish) "Stream Title" else "直播标题",
                placeholder = if (isEnglish) "Screen Share" else "桌面/游戏画面共享"
            )

            Text(
                text = if (isEnglish) "Video Quality & Bitrate" else "画质与帧率档位",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 4.dp)
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEachIndexed { index, p ->
                    val selected = index == presetIndex
                    Box(
                        modifier = Modifier
                            .pressableScale(0.95f) { presetIndex = index }
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) colors.pillActiveBg else colors.cardElevated)
                            .border(
                                width = 1.dp,
                                color = if (selected) Color.Transparent else colors.border,
                                shape = RoundedCornerShape(50)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = p.label,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) colors.pillActiveText else colors.textSecondary
                        )
                    }
                }
            }

            val chosen = presets[presetIndex]
            AppPrimaryPillButton(
                text = if (sourceType == LiveStreamSourceType.SCREEN) {
                    if (isEnglish) "Authorize & Share Screen" else "授权并开始屏幕直播"
                } else {
                    if (isEnglish) "Start Camera Stream" else "开始摄像头直播"
                },
                leadingIcon = if (sourceType == LiveStreamSourceType.SCREEN) {
                    Icons.AutoMirrored.Filled.ScreenShare
                } else {
                    Icons.Default.Videocam
                },
                containerColor = Color(0xFFE11D48),
                contentColor = Color.White,
                onClick = {
                    if (sourceType == LiveStreamSourceType.SCREEN) {
                        onStartScreenShare(
                            streamTitle.trim(),
                            chosen.width,
                            chosen.height,
                            chosen.fps,
                            chosen.kbps
                        )
                    } else {
                        onStartCameraShare(
                            streamTitle.trim(),
                            chosen.width,
                            chosen.height,
                            chosen.fps,
                            chosen.kbps
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
