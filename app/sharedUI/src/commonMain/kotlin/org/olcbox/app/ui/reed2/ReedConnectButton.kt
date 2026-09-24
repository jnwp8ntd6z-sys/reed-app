package org.olcbox.app.ui.reed2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Состояние кнопки подключения. */
enum class ReedConnState { Off, Connecting, On }

/**
 * Reed 2.0 — кнопка подключения Android (ТЗ 7.3, «печенька» Cookie12Sided).
 *
 * Выключено — surface-300, иконка ink; подключение — плавный морфинг «печенька ↔ круг»
 * (бесконечно, дыхание), приглушённый lime-нимб; подключено — lime + мягкое свечение.
 *
 * Анимации намеренно плавные: морф через FastOutSlowIn (без рывков), пульс — синусоидой
 * (не линейно), появление lime — tween 420мс. Никаких резких скачков.
 */
@Composable
fun ReedConnectButton(
    state: ReedConnState,
    modifier: Modifier = Modifier,
    size: Int = 180,
    onClick: () -> Unit = {},
    enabled: Boolean = true,      // без кода — приглушённая, не нажимается
) {
    // Один непрерывный аниматор формы и один — масштаба: при любой смене состояния анимация
    // продолжается с текущего значения (раньше переключение между бесконечной и одиночной
    // анимацией давало скачок «печенька → круг» за кадр).
    // morph: 0 = печенька, 1 = круг. Подключение — плавное «дыхание» туда-обратно.
    val morphAnim = remember { Animatable(0.12f) }
    val pulseAnim = remember { Animatable(1f) }
    LaunchedEffect(state) {
        when (state) {
            ReedConnState.Connecting -> coroutineScope {
                launch {
                    while (true) {
                        morphAnim.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
                        morphAnim.animateTo(0.15f, tween(900, easing = FastOutSlowInEasing))
                    }
                }
                launch {
                    while (true) {
                        pulseAnim.animateTo(1.035f, tween(900, easing = FastOutSlowInEasing))
                        pulseAnim.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
                    }
                }
            }
            else -> coroutineScope {
                // Подключено — идеально ровный круг (1.0), выключено — «печенька».
                launch { morphAnim.animateTo(if (state == ReedConnState.On) 1f else 0.12f, tween(560, easing = FastOutSlowInEasing)) }
                launch { pulseAnim.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
            }
        }
    }
    val morph = morphAnim.value
    val pulse = pulseAnim.value

    val fill by animateColorAsState(
        targetValue = when (state) {
            ReedConnState.On -> Reed2.lime
            ReedConnState.Connecting -> Reed2.surface300
            ReedConnState.Off -> Reed2.surface300
        },
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "fill",
    )
    val iconTint by animateColorAsState(
        targetValue = if (state == ReedConnState.On) Reed2.onLime else Reed2.ink,
        animationSpec = tween(420),
        label = "iconTint",
    )
    // Свечение: сильное при On, слабое пульсирующее при Connecting, нет при Off.
    val glowAlpha by animateFloatAsState(
        targetValue = when (state) {
            ReedConnState.On -> 0.42f
            ReedConnState.Connecting -> 0.22f
            ReedConnState.Off -> 0f
        },
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "glow",
    )

    // Нажатие: кнопка раньше была только картинкой (onClick не использовался) — теперь круглая
    // зона нажатия, лёгкое сжатие на пружине и вибрация.
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.95f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 600f), label = "press",
    )
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = modifier.size(size.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { role = androidx.compose.ui.semantics.Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size.dp)) {
            val r = this.size.minDimension / 2f
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val rr = r * pulse

            // Свечение — мягкий радиальный ореол (без «колец», плавное затухание к прозрачному).
            if (glowAlpha > 0.01f) {
                val glowR = rr * 1.7f
                drawCircle(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Reed2.lime.copy(alpha = glowAlpha),
                            Reed2.lime.copy(alpha = glowAlpha * 0.35f),
                            Reed2.lime.copy(alpha = 0f),
                        ),
                        center = Offset(cx, cy),
                        radius = glowR,
                    ),
                    radius = glowR,
                    center = Offset(cx, cy),
                    style = Fill,
                )
            }

            // Тело кнопки — 12-угольная «печенька», сглаженная к кругу по morph.
            val path = cookiePath(cx, cy, rr * 0.9f, points = 12, morph = morph)
            drawPath(path, color = fill, style = Fill)

            // Тонкий хромовый ободок в состоянии Off/Connecting (в On — обод lime внутри свечения).
            if (state != ReedConnState.On) {
                drawPath(
                    cookiePath(cx, cy, rr * 0.9f, 12, morph),
                    color = Reed2.hairline,
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }

        Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = if (state == ReedConnState.On) "Отключить" else "Подключить",
            tint = iconTint,
            modifier = Modifier.size((size * 0.26f).dp),
        )
    }
}

/**
 * Путь «печеньки»: 12 внешних лепестков, амплитуда волны гаснет к нулю при morph→1 (круг).
 * Строится как гладкая замкнутая кривая по точкам с чередованием радиуса.
 */
private fun cookiePath(cx: Float, cy: Float, radius: Float, points: Int, morph: Float): Path {
    val path = Path()
    val waves = points
    val amp = (1f - morph) * 0.085f   // глубина лепестков гаснет к кругу
    if (amp < 0.0005f) {              // полный круг — рисуем настоящий овал, без граней
        path.addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), radius))
        return path
    }
    val steps = waves * 16            // достаточно точек для гладкости
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val angle = (t * 2f * PI).toFloat() - (PI / 2f).toFloat()
        val wave = 1f + amp * cos((waves * t * 2f * PI).toFloat())
        val rr = radius * wave
        val x = cx + rr * cos(angle)
        val y = cy + rr * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
