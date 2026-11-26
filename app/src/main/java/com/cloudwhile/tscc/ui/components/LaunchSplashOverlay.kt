package com.cloudwhile.tscc.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudwhile.tscc.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LaunchSplashOverlay(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings

    val badgeScale = remember { Animatable(0.76f) }
    val contentAlpha = remember { Animatable(0f) }
    val waveProgress = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            badgeScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.68f, stiffness = 380f)
            )
        }
        launch {
            contentAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(240, easing = LinearOutSlowInEasing)
            )
        }
        launch {
            waveProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(520, easing = FastOutSlowInEasing)
            )
        }
        delay(380L)
        exitProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        )
        onFinished()
    }

    val logoGlowPrimary = Color(0xFF4F9CF9)
    val logoGlowSecondary = Color(0xFF2563EB)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .graphicsLayer {
                val ex = exitProgress.value
                alpha = 1f - ex
                val sc = 1f + (0.06f * ex)
                scaleX = sc
                scaleY = sc
            }
            .background(colors.canvas)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.graphicsLayer {
                alpha = contentAlpha.value
            }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(152.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = waveProgress.value
                    val baseR = 42.dp.toPx()
                    val r1 = baseR + (26.dp.toPx() * w)
                    val r2 = baseR + (44.dp.toPx() * (w * 0.85f))
                    drawCircle(
                        color = logoGlowPrimary.copy(alpha = 0.34f * (1f - w * 0.72f)),
                        radius = r1,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = logoGlowSecondary.copy(alpha = 0.24f * (1f - w * 0.8f)),
                        radius = r2,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                AppBrandLogo(
                    size = 86.dp,
                    cornerRadius = 24.dp,
                    modifier = Modifier.graphicsLayer {
                        val s = badgeScale.value
                        scaleX = s
                        scaleY = s
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = strings.appTitle,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = colors.textPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = strings.splashSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
    }
}
