package com.cloudwhile.tscc.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import com.cloudwhile.tscc.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cloudwhile.tscc.data.model.ToastTone
import com.cloudwhile.tscc.data.model.UiToastMessage
import com.cloudwhile.tscc.ui.theme.AppTheme

fun Modifier.pressableScale(
    scaleDown: Float = 0.96f,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleState = animateFloatAsState(
        targetValue = if (isPressed && enabled) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 600f
        ),
        label = "pressScale"
    )
    val scaled = this.graphicsLayer {
        val s = scaleState.value
        scaleX = s
        scaleY = s
    }
    if (onClick != null) {
        scaled.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
    } else {
        scaled
    }
}

@Composable
fun AppBrandLogo(
    size: Dp = 80.dp,
    cornerRadius: Dp = 22.dp,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(1.dp, Color(0x28FFFFFF), shape)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = 1.42f
                    scaleY = 1.42f
                }
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = 1.42f
                    scaleY = 1.42f
                }
        )
    }
}

@Composable
fun AppScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing
        )
    }
}

@Composable
fun AppCircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 18.dp,
    badgeCount: Int = 0,
    showDot: Boolean = false,
    active: Boolean = false,
    containerColor: Color? = null,
    tint: Color? = null
) {
    val colors = AppTheme.colors
    val bg = containerColor ?: when {
        active -> colors.pillActiveBg
        else -> colors.card
    }
    val iconTint = tint ?: when {
        active -> colors.pillActiveText
        else -> colors.textPrimary
    }

    Box(
        modifier = modifier
            .size(size)
            .pressableScale(scaleDown = 0.91f, onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, colors.border, CircleShape)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }

        if (badgeCount > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .clip(CircleShape)
                    .background(colors.coral)
                    .border(1.5.dp, colors.canvas, CircleShape)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = badgeCount.coerceAtMost(99).toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 11.sp
                )
            }
        } else if (showDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 1.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(colors.coral)
                    .border(1.5.dp, colors.canvas, CircleShape)
            )
        }
    }
}

@Immutable
data class AppNavItem<T>(
    val id: T,
    val label: String,
    val icon: ImageVector,
    val liveDot: Boolean = false,
    val badgeCount: Int = 0
)

@Composable
fun <T> AppPillBottomBar(
    items: List<AppNavItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.canvas)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.card)
                .border(1.dp, colors.border, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val isSelected = item.id == selected
                val pillProgress = animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    label = "navPill"
                )
                val activeBg = colors.pillActiveBg
                val contentTint = if (isSelected) colors.pillActiveText else colors.textSecondary

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .pressableScale(scaleDown = 0.94f) {
                            if (!isSelected) onSelect(item.id)
                        }
                        .graphicsLayer {
                            val p = pillProgress.value
                            val s = 0.96f + 0.04f * p
                            scaleX = if (isSelected) s else 1f
                            scaleY = if (isSelected) s else 1f
                        }
                        .drawBehind {
                            val alpha = pillProgress.value
                            if (alpha > 0.01f) {
                                val r = size.height / 2f
                                drawRoundRect(
                                    color = activeBg.copy(alpha = alpha),
                                    cornerRadius = CornerRadius(r, r)
                                )
                            }
                        }
                        .padding(horizontal = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = contentTint,
                                modifier = Modifier.size(19.dp)
                            )
                            if (item.liveDot && !isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-2).dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(colors.emerald)
                                )
                            } else if (item.badgeCount > 0 && !isSelected) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 3.dp, y = (-2).dp)
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(colors.coral)
                                )
                            }
                        }

                        Text(
                            text = item.label,
                            color = contentTint,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                        if (isSelected && item.liveDot) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.emerald)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppTopToast(
    toast: UiToastMessage?,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    var cachedToast by remember { mutableStateOf<UiToastMessage?>(null) }
    var cachedActionLabel by remember { mutableStateOf<String?>(null) }
    if (toast != null) {
        cachedToast = toast
        cachedActionLabel = actionLabel
    }

    val active = cachedToast
    val dotColor = when (active?.tone) {
        ToastTone.ERROR -> colors.coral
        ToastTone.WARNING -> colors.marigold
        else -> colors.emerald
    }
    val rimColor = when (active?.tone) {
        ToastTone.ERROR -> colors.coral.copy(alpha = 0.65f)
        ToastTone.WARNING -> colors.marigold.copy(alpha = 0.55f)
        else -> Color(0x24FFFFFF)
    }

    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically(
            initialOffsetY = { -it - 32 },
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 460f)
        ) + fadeIn(tween(150)),
        exit = slideOutVertically(
            targetOffsetY = { -it - 32 },
            animationSpec = tween(180)
        ) + fadeOut(tween(150)),
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 10.dp, start = 18.dp, end = 18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF517171B))
                .border(1.2.dp, rimColor, RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 15.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = active?.text.orEmpty(),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (!cachedActionLabel.isNullOrBlank() && onActionClick != null) {
                val isWarn = active?.tone == ToastTone.WARNING
                val chipBg = if (isWarn) colors.marigold.copy(alpha = 0.22f) else colors.coral.copy(alpha = 0.24f)
                val chipBorder = if (isWarn) colors.marigold.copy(alpha = 0.65f) else colors.coral.copy(alpha = 0.65f)
                val chipText = if (isWarn) colors.marigold else Color(0xFFFFB4AC)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(chipBg)
                        .border(1.dp, chipBorder, RoundedCornerShape(50))
                        .clickable {
                            onDismiss()
                            onActionClick()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = cachedActionLabel.orEmpty(),
                        color = chipText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AppPrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    containerColor: Color? = null,
    contentColor: Color? = null
) {
    val colors = AppTheme.colors
    val bg = when {
        !enabled -> colors.cardElevated
        containerColor != null -> containerColor
        else -> colors.pillActiveBg
    }
    val fg = when {
        !enabled -> colors.textTertiary
        contentColor != null -> contentColor
        else -> colors.pillActiveText
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(50.dp)
            .pressableScale(scaleDown = 0.97f, enabled = enabled, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 20.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Composable
fun AppSecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    tint: Color? = null
) {
    val colors = AppTheme.colors
    val fg = when {
        !enabled -> colors.textTertiary
        tint != null -> tint
        else -> colors.textPrimary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(46.dp)
            .pressableScale(scaleDown = 0.96f, enabled = enabled, onClick = onClick)
            .clip(RoundedCornerShape(50))
            .background(colors.cardElevated)
            .border(1.dp, colors.border, RoundedCornerShape(50))
            .padding(horizontal = 16.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AppCapsuleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val colors = AppTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = if (singleLine) RoundedCornerShape(50) else RoundedCornerShape(18.dp)
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.periwinkle else colors.border,
        animationSpec = tween(160),
        label = "fieldBorder"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        Row(
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.cardElevated)
                .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (focused) colors.periwinkle else colors.textSecondary,
                    modifier = Modifier
                        .padding(end = 10.dp, top = if (singleLine) 0.dp else 2.dp)
                        .size(18.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                        maxLines = if (singleLine) 1 else maxLines,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    cursorBrush = SolidColor(colors.periwinkle),
                    textStyle = TextStyle(
                        color = if (enabled) colors.textPrimary else colors.textTertiary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                )
            }

            if (trailing != null) {
                Spacer(modifier = Modifier.width(6.dp))
                trailing()
            }
        }
    }
}

@Composable
fun <T> AppSegmentedPills(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(colors.cardElevated)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (item, title) ->
            val isSelected = item == selected
            val progress = animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = tween(160),
                label = "segPill"
            )
            val activeBg = colors.pillActiveBg
            val fg = if (isSelected) colors.pillActiveText else colors.textSecondary

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .pressableScale(0.96f) {
                        if (!isSelected) onSelect(item)
                    }
                    .drawBehind {
                        val a = progress.value
                        if (a > 0.01f) {
                            val r = size.height / 2f
                            drawRoundRect(
                                color = activeBg.copy(alpha = a),
                                cornerRadius = CornerRadius(r, r)
                            )
                        }
                    }
                    .padding(vertical = 9.dp, horizontal = 8.dp)
            ) {
                Text(
                    text = title,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun DashedHorizontalLine(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 3f
) {
    Canvas(modifier = modifier.height(2.dp)) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
        )
    }
}

@Composable
fun DashedVerticalLine(
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 3f
) {
    Canvas(modifier = modifier.width(2.dp)) {
        drawLine(
            color = color,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f), 0f)
        )
    }
}

@Composable
fun PulsingEmeraldDot(
    size: Dp = 9.dp,
    color: Color = AppTheme.colors.emerald,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "emeraldPulse")
    val progressState = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress"
    )

    Canvas(modifier = modifier.size(size * 2.2f)) {
        val p = progressState.value
        val coreRadius = size.toPx() / 2f
        val haloRadius = coreRadius * (1f + 1.2f * p)
        val haloAlpha = 0.50f * (1f - p)
        drawCircle(
            color = color.copy(alpha = haloAlpha),
            radius = haloRadius,
            center = center
        )
        drawCircle(
            color = color,
            radius = coreRadius,
            center = center
        )
    }
}

@Composable
fun AppModalDialog(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    confirmColor: Color? = null,
    dismissText: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = AppTheme.colors
    val strings = AppTheme.strings
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(colors.card)
                .border(1.dp, colors.border, RoundedCornerShape(26.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                    color = colors.textPrimary
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppSecondaryPillButton(
                    text = dismissText ?: strings.cancel,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                AppPrimaryPillButton(
                    text = confirmText,
                    enabled = confirmEnabled,
                    containerColor = confirmColor,
                    contentColor = if (confirmColor != null) Color.White else null,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
