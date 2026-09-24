package org.olcbox.app.ui.reed2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Карточка Reed (surface-200, радиус 28). */
@Composable
fun ReedCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Reed2.cardRadius))
            .background(Reed2.surface200)
            .border(1.dp, Reed2.hairline, RoundedCornerShape(Reed2.cardRadius)),
    ) { content() }
}

/** Заголовок секции капсом (СЕРВЕРЫ, УЧАСТНИКИ, ПОДКЛЮЧЕНИЕ…). */
@Composable
fun ReedSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Reed2.inkMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
        modifier = modifier,
    )
}

/** Моно-капсула (таймер, трафик). */
@Composable
fun ReedMonoCapsule(text: String, modifier: Modifier = Modifier, color: Color = Reed2.ink) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Reed2.pillRadius))
            .background(Reed2.surface300)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
    }
}

/** Статусная строка «точка + слово» (ТЗ 3.4: статус всегда словом, не только цветом). */
@Composable
fun ReedStatusDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Text(text, color = Reed2.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Флаг-плашка (ТЗ 3.5): прямоугольник 30×20, скругление 5, обводка 1px.
 * Пока без SVG-набора флагов — серая плашка с ISO-2 моно-шрифтом (fallback по ТЗ).
 * Когда добавим flag-icons — заменить тело на изображение.
 */
@Composable
fun ReedFlag(country: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 30.dp, height = 20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Reed2.surface300)
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = country.take(2).uppercase().ifBlank { "··" },
            color = Reed2.chrome200,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Строка сервера в списке (ТЗ 4.4): флаг, страна (жирно), город, пинг моно, отметка выбора. */
@Composable
fun ReedServerRow(
    country: String,
    countryName: String,
    city: String,
    pingMs: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        if (selected) Reed2.selectedRowBg else Color.Transparent,
        tween(220), label = "rowbg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Reed2.tileRadius))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReedFlag(country)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(countryName, color = Reed2.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (city.isNotBlank()) {
                Text(city, color = Reed2.inkMuted, fontSize = 13.sp)
            }
        }
        Text(
            Reed2.pingText(pingMs),
            color = Reed2.pingColor(pingMs),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        ReedCheckCircle(selected)
    }
}

/** Круглая отметка выбора (ТЗ: галочка в круге на iOS; на Android radio — здесь общий круг-галка). */
@Composable
private fun ReedCheckCircle(selected: Boolean) {
    val border by animateColorAsState(if (selected) Reed2.ink else Reed2.chrome600, tween(200), label = "chk")
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(50))
            .background(if (selected) Reed2.ink else Color.Transparent)
            .border(1.5.dp, border, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", color = Reed2.onInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Волнистый индикатор трафика (ТЗ 4.4/3.1 — Android волнистый).
 * Заполненная часть — бегущая синусоида хромовым цветом; фаза плывёт линейно (непрерывно,
 * без рывка — это поток, а не переход). Незаполненная — дорожка chrome-800.
 */
@Composable
fun ReedWavyProgress(progress: Float, modifier: Modifier = Modifier) {
    val p = progress.coerceIn(0f, 1f)
    val infinite = rememberInfiniteTransition(label = "wavy")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val w = size.width; val h = size.height; val midY = h / 2f
        // Дорожка.
        drawLine(
            color = Reed2.chrome800,
            start = Offset(0f, midY), end = Offset(w, midY),
            strokeWidth = h, cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // Заполнение — волна до x = p*w.
        val fillW = w * p
        if (fillW > 1f) {
            val path = Path()
            val amp = h * 0.35f
            val wavelength = 26.dp.toPx().coerceAtLeast(1f)
            var x = 0f
            path.moveTo(0f, midY)
            while (x <= fillW) {
                val y = midY + amp * sin((x / wavelength) * 2f * PI.toFloat() + phase)
                path.lineTo(x, y)
                x += 2f
            }
            drawPath(
                path,
                brush = Brush.horizontalGradient(listOf(Reed2.chrome400, Reed2.chrome050)),
                style = Stroke(width = h * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}

/** Сегмент Wi-Fi / Мобильный (ТЗ 4.4): капсула, выбранный сегмент — светлый. */
@Composable
fun ReedSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Reed2.pillRadius))
            .background(Reed2.surface100)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selectedIndex
            val bg by animateColorAsState(if (active) Reed2.chrome050 else Color.Transparent, tween(240), label = "segbg")
            val fg by animateColorAsState(if (active) Reed2.onInk else Reed2.inkMuted, tween(240), label = "segfg")
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(Reed2.pillRadius)).background(bg)
                    .clickable(interaction, null) { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Текст хромовым градиентом (лого «CLIENT», заголовок «БЕЗ ОБРЫВОВ»). */
@Composable
fun chromeTextStyle(base: TextStyle): TextStyle =
    base.copy(brush = Brush.linearGradient(
        colors = listOf(Reed2.chrome050, Reed2.chrome200, Reed2.chrome600, Reed2.chrome050,
            Reed2.chrome800, Reed2.chrome200, Reed2.chrome050),
    ))
