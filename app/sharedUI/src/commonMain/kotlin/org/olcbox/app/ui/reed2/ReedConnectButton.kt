package org.olcbox.app.ui.reed2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.runtime.Composable
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
) {
    val infinite = rememberInfiniteTransition(label = "connect")

    // Морф-фактор: 0 = печенька, 1 = круг. В покое держим форму, при подключении дышим.
    val morph by if (state == ReedConnState.Connecting) {
        infinite.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "morph",
        )
    } else {
        // Off — лёгкая «печенька» (0.12), On — почти круг (0.9). Плавно между состояниями.
        animateFloatAsState(
            targetValue = if (state == ReedConnState.On) 0.9f else 0.12f,
            animationSpec = tween(520, easing = FastOutSlowInEasing),
            label = "morphRest",
        )
    }

    // Дыхание масштаба при подключении — мягкая синусоида, амплитуда крошечная (1.0..1.035).
    val pulse by if (state == ReedConnState.Connecting) {
        infinite.animateFloat(
            initialValue = 1f, targetValue = 1.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    } else animateFloatAsState(1f, tween(300), label = "pulseRest")

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

    Box(
        modifier = modifier.size(size.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size.dp)) {
            val r = this.size.minDimension / 2f
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val rr = r * pulse

            // Свечение (мягкий ореол вокруг) — рисуем несколькими полупрозрачными кольцами.
            if (glowAlpha > 0.01f) {
                val glow = Reed2.lime.copy(alpha = glowAlpha)
                for (i in 3 downTo 1) {
                    drawCircle(
                        color = glow.copy(alpha = glowAlpha * (0.10f * i)),
                        radius = rr + i * (r * 0.12f),
                        center = Offset(cx, cy),
                        style = Fill,
                    )
                }
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
    val steps = waves * 8             // достаточно точек для гладкости
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
