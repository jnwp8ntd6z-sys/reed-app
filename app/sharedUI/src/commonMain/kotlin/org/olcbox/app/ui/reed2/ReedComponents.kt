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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Videocam
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
        Text(text, color = color, fontFamily = LocalReedFonts.current.mono, fontSize = 15.sp)
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
    val iso = country.uppercase()
    Box(
        modifier = modifier
            .size(width = 30.dp, height = 20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Reed2.surface300),
        contentAlignment = Alignment.Center,
    ) {
        if (iso in DRAWN_FLAGS) {
            Canvas(Modifier.matchParentSize()) { drawFlag(iso) }
        } else {
            // Нет флага в комплекте — серая плашка с кодом страны (ТЗ 3.5).
            Text(iso.take(2).ifBlank { "··" }, color = Reed2.chrome200, fontFamily = LocalReedFonts.current.mono,
                fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.matchParentSize().border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(5.dp)))
    }
}

private val DRAWN_FLAGS = setOf("DE", "NL", "RU", "PL", "FI", "SE", "US", "FR", "AT", "LV", "EE", "CH", "TR", "KZ", "GB", "EU")

/** Флаги рисуются кодом (цвета — как в flag-icons), одинаково на всех устройствах. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlag(iso: String) {
    val w = size.width; val h = size.height
    fun hStripes(vararg c: Long) { val sh = h / c.size; c.forEachIndexed { i, col -> drawRect(Color(col), Offset(0f, sh * i), androidx.compose.ui.geometry.Size(w, sh + 0.5f)) } }
    fun vStripes(vararg c: Long) { val sw = w / c.size; c.forEachIndexed { i, col -> drawRect(Color(col), Offset(sw * i, 0f), androidx.compose.ui.geometry.Size(sw + 0.5f, h)) } }
    fun nordic(bg: Long, cross: Long, inner: Long? = null) {
        drawRect(Color(bg))
        val t = h * 0.25f; val cx = w * 0.36f
        drawRect(Color(cross), Offset(cx - t / 2, 0f), androidx.compose.ui.geometry.Size(t, h))
        drawRect(Color(cross), Offset(0f, h / 2 - t / 2), androidx.compose.ui.geometry.Size(w, t))
        if (inner != null) {
            val ti = t * 0.5f
            drawRect(Color(inner), Offset(cx - ti / 2, 0f), androidx.compose.ui.geometry.Size(ti, h))
            drawRect(Color(inner), Offset(0f, h / 2 - ti / 2), androidx.compose.ui.geometry.Size(w, ti))
        }
    }
    when (iso) {
        "DE" -> hStripes(0xFF000000, 0xFFDD0000, 0xFFFFCE00)
        "NL" -> hStripes(0xFFAE1C28, 0xFFFFFFFF, 0xFF21468B)
        "RU" -> hStripes(0xFFFFFFFF, 0xFF0039A6, 0xFFD52B1E)
        "PL" -> hStripes(0xFFFFFFFF, 0xFFDC143C)
        "AT" -> hStripes(0xFFC8102E, 0xFFFFFFFF, 0xFFC8102E)
        "EE" -> hStripes(0xFF0072CE, 0xFF000000, 0xFFFFFFFF)
        "LV" -> { hStripes(0xFF9E3039, 0xFF9E3039, 0xFFFFFFFF, 0xFF9E3039, 0xFF9E3039); drawRect(Color(0xFFFFFFFF), Offset(0f, h * 0.4f), androidx.compose.ui.geometry.Size(w, h * 0.2f)) }
        "FR" -> vStripes(0xFF002654, 0xFFFFFFFF, 0xFFCE1126)
        "FI" -> nordic(0xFFFFFFFF, 0xFF002F6C)
        "SE" -> nordic(0xFF006AA7, 0xFFFECC00)
        "CH" -> {
            drawRect(Color(0xFFDA291C))
            val a = h * 0.6f; val t = a / 3f
            drawRect(Color.White, Offset(w / 2 - t / 2, h / 2 - a / 2), androidx.compose.ui.geometry.Size(t, a))
            drawRect(Color.White, Offset(w / 2 - a / 2, h / 2 - t / 2), androidx.compose.ui.geometry.Size(a, t))
        }
        "US" -> {
            val sh = h / 13f
            for (i in 0 until 13) drawRect(if (i % 2 == 0) Color(0xFFB22234) else Color.White, Offset(0f, sh * i), androidx.compose.ui.geometry.Size(w, sh + 0.3f))
            drawRect(Color(0xFF3C3B6E), Offset.Zero, androidx.compose.ui.geometry.Size(w * 0.42f, sh * 7))
            val r = h * 0.025f
            for (row in 0 until 4) for (col in 0 until 5) {
                drawCircle(Color.White, r, Offset(w * 0.42f * (col + 0.6f) / 5.2f, sh * 7 * (row + 0.6f) / 4.2f))
            }
        }
        "TR" -> {
            drawRect(Color(0xFFE30A17))
            val c = Offset(w * 0.4f, h / 2)
            drawCircle(Color.White, h * 0.26f, c)
            drawCircle(Color(0xFFE30A17), h * 0.21f, Offset(c.x + h * 0.065f, c.y))
            drawCircle(Color.White, h * 0.07f, Offset(w * 0.6f, h / 2))
        }
        "KZ" -> {
            drawRect(Color(0xFF00AFCA))
            drawCircle(Color(0xFFFEC50C), h * 0.18f, Offset(w / 2, h * 0.45f))
        }
        "EU" -> {
            drawRect(Color(0xFF003399))
            for (i in 0 until 12) {
                val a = i * PI.toFloat() / 6f
                drawCircle(Color(0xFFFFCC00), h * 0.045f, Offset(w / 2 + h * 0.3f * kotlin.math.cos(a), h / 2 + h * 0.3f * sin(a)))
            }
        }
        "GB" -> {
            drawRect(Color(0xFF012169))
            val sw = h * 0.2f
            drawLine(Color.White, Offset.Zero, Offset(w, h), sw)
            drawLine(Color.White, Offset(0f, h), Offset(w, 0f), sw)
            drawLine(Color(0xFFC8102E), Offset.Zero, Offset(w, h), sw * 0.35f)
            drawLine(Color(0xFFC8102E), Offset(0f, h), Offset(w, 0f), sw * 0.35f)
            drawRect(Color.White, Offset(w / 2 - h * 0.17f, 0f), androidx.compose.ui.geometry.Size(h * 0.34f, h))
            drawRect(Color.White, Offset(0f, h / 2 - h * 0.17f), androidx.compose.ui.geometry.Size(w, h * 0.34f))
            drawRect(Color(0xFFC8102E), Offset(w / 2 - h * 0.1f, 0f), androidx.compose.ui.geometry.Size(h * 0.2f, h))
            drawRect(Color(0xFFC8102E), Offset(0f, h / 2 - h * 0.1f), androidx.compose.ui.geometry.Size(w, h * 0.2f))
        }
    }
}

/**
 * Строка сервера (ТЗ 4.4 + правки): флаг, страна, подпись, пинг. Выбранный — подсветка строки;
 * справа шарик состояния: подключаюсь — мигает жёлтым, подключено — лаймовый с «дыханием» ореола.
 */
@Composable
fun ReedServerRow(
    country: String,
    countryName: String,
    city: String,
    pingMs: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    connState: ReedConnState = ReedConnState.Off,
) {
    val bg by animateColorAsState(if (selected) Reed2.selectedRowBg else Color.Transparent, tween(260), label = "rowbg")
    val edge by animateColorAsState(if (selected) Reed2.chrome800 else Color.Transparent, tween(260), label = "rowedge")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Reed2.tileRadius))
            .background(bg)
            .border(1.dp, edge, RoundedCornerShape(Reed2.tileRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReedFlag(country)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(countryName, color = Reed2.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (city.isNotBlank()) {
                Text(city, color = Reed2.inkMuted, fontSize = 13.sp, maxLines = 1)
            }
        }
        Text(
            Reed2.pingText(pingMs),
            color = Reed2.pingColor(pingMs),
            fontFamily = LocalReedFonts.current.mono,
            fontSize = 13.sp,
        )
        Box(Modifier.padding(start = 10.dp).size(18.dp), contentAlignment = Alignment.Center) {
            ServerStatusDot(if (selected) connState else ReedConnState.Off)
        }
    }
}

/** Шарик состояния подключения у выбранного сервера. */
@Composable
private fun ServerStatusDot(state: ReedConnState) {
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (state == ReedConnState.Off) 0f else 1f, tween(260), label = "dotShown")
    if (shown < 0.01f) return
    val color by animateColorAsState(if (state == ReedConnState.On) Reed2.lime else Reed2.statusWarn, tween(300), label = "dotColor")
    val t = rememberInfiniteTransition(label = "dot")
    // Подключаюсь — частое мигание; подключено — медленный расходящийся ореол.
    val blink by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "blink")
    val halo by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "halo")
    Canvas(Modifier.size(18.dp)) {
        val r = 4.5.dp.toPx()
        if (state == ReedConnState.On) {
            drawCircle(color.copy(alpha = 0.45f * (1f - halo) * shown), r + (size.minDimension / 2 - r) * halo)
            drawCircle(color.copy(alpha = shown), r)
        } else {
            drawCircle(color.copy(alpha = blink * shown), r)
        }
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
    val p by androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0f, 1f), tween(700), label = "wavyP")
    val infinite = rememberInfiniteTransition(label = "wavy")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    // Серая волна — вся дорожка, белая волна поверх — использованная часть (одна и та же фаза).
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width; val midY = size.height / 2f
        val amp = 3.dp.toPx()
        val wavelength = 24.dp.toPx().coerceAtLeast(1f)
        val stroke = 3.5.dp.toPx()
        fun wave(to: Float): Path {
            val path = Path()
            var x = stroke / 2
            path.moveTo(x, midY + amp * sin((x / wavelength) * 2f * PI.toFloat() + phase))
            while (x < to) {
                x = minOf(x + 1.5f, to)
                path.lineTo(x, midY + amp * sin((x / wavelength) * 2f * PI.toFloat() + phase))
            }
            return path
        }
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawPath(wave(w - stroke / 2), color = Reed2.chrome800, style = Stroke(width = stroke, cap = cap))
        val fill = (w - stroke / 2) * p
        if (fill > stroke) drawPath(wave(fill), color = Reed2.chrome050, style = Stroke(width = stroke, cap = cap))
    }
}

/** Сегмент Wi-Fi / Мобильный (ТЗ 4.4): белый овал плавно переезжает к выбранному (пружина). */
@Composable
fun ReedSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(Reed2.pillRadius))
            .background(Reed2.surface100)
            .padding(4.dp),
    ) {
        val segW = maxWidth / options.size.coerceAtLeast(1)
        val x by androidx.compose.animation.core.animateDpAsState(
            segW * selectedIndex,
            androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 420f),
            label = "segX",
        )
        Box(
            Modifier.offset(x = x).width(segW).height(38.dp)
                .clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.chrome050)
        )
        Row(Modifier.fillMaxWidth().height(38.dp)) {
            options.forEachIndexed { i, label ->
                val fg by animateColorAsState(if (i == selectedIndex) Reed2.onInk else Reed2.inkMuted, tween(260), label = "segfg")
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(Reed2.pillRadius))
                        .clickable(interaction, null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
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
