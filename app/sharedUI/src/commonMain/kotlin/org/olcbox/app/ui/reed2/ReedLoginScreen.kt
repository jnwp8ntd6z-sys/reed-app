package org.olcbox.app.ui.reed2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reed 2.0 — экран входа (ТЗ 4.1 + правки пользователя):
 *  • согласие с политиками ПРИЖАТО К НИЗУ экрана (не под кнопкой);
 *  • «Продолжить без кода» — приглушённая вторичная (НЕ светлее).
 * Stateless: поле-кнопка ведёт на экран ввода кода, QR — сразу камера.
 */
@Composable
fun ReedLoginScreen(
    consent: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onOpenCodeEntry: () -> Unit,
    onScanQr: () -> Unit,
    onContinueWithoutCode: () -> Unit,
    showTelegram: Boolean = false,           // Android + Россия
    onTelegram: () -> Unit = {},
    onOpenTerms: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Reed2.ground000)
            .padding(horizontal = 24.dp),
    ) {
        // ── Логотип ──
        Spacer(Modifier.height(20.dp))
        ReedLogoRow()

        // ── Декор: тёмная подложка со скруглением снизу + серебряная звезда ──
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            Box(
                Modifier.fillMaxWidth().height(220.dp)
                    .clip(RoundedCornerShape(bottomStart = 48.dp, bottomEnd = 48.dp))
                    .background(Reed2.surface100),
            )
            Canvas(
                Modifier.size(190.dp).align(Alignment.TopEnd)
                    .offset(x = 34.dp, y = (-26).dp),
            ) {
                val path = starCookiePath(size.width / 2f, size.height / 2f, size.minDimension * 0.42f, 12)
                drawPath(
                    path,
                    brush = Brush.linearGradient(
                        colors = listOf(Reed2.chrome050, Reed2.chrome200, Reed2.chrome600,
                            Reed2.chrome050, Reed2.chrome800, Reed2.chrome200, Reed2.chrome050),
                        start = Offset(size.width * 0.1f, 0f),
                        end = Offset(size.width * 0.9f, size.height),
                    ),
                    style = Fill,
                )
            }
            // Заголовок в две строки поверх декора, снизу-слева.
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp)) {
                Text("ИНТЕРНЕТ", color = Reed2.ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp)
                Text("БЕЗ ОБРЫВОВ", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    style = chromeTextStyle(androidx.compose.material3.MaterialTheme.typography.headlineLarge)
                        .copy(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Вставь код, ключ подписки или приглашение — или отсканируй QR.",
            color = Reed2.inkMuted, fontSize = 15.sp, lineHeight = 21.sp,
        )

        Spacer(Modifier.height(20.dp))
        Text("Код, ключ подписки или приглашение", color = Reed2.inkMuted, fontSize = 13.sp,
            fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))

        // ── Поле-кнопка + квадратная кнопка QR 56×56 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Reed2.surface200)
                    .clickable(enabled = consent, onClick = onOpenCodeEntry)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("RD-XXXX  или  ссылка", color = Reed2.chrome600,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Reed2.ink)
                    .clickable(enabled = consent, onClick = onScanQr),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.QrCodeScanner, "Сканировать QR", tint = Reed2.onInk,
                    modifier = Modifier.size(26.dp))
            }
        }

        if (showTelegram) {
            Spacer(Modifier.height(16.dp))
            DividerOr()
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(52.dp)
                    .clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface300)
                    .clickable(onClick = onTelegram),
                contentAlignment = Alignment.Center,
            ) {
                Text("Войти через Telegram", color = Reed2.ink, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium)
            }
        }

        // ── «Продолжить без кода» — приглушённая вторичная (НЕ светлее) ──
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val i = androidx.compose.runtime.remember { MutableInteractionSource() }
            Text(
                "Продолжить без кода",
                color = Reed2.inkMuted,           // приглушённо, не ink
                fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(i, null, onClick = onContinueWithoutCode)
                    .padding(vertical = 8.dp, horizontal = 12.dp),
            )
        }

        // ── ВНИЗ: согласие с политиками (прижато к низу экрана) ──
        Spacer(Modifier.weight(1f))
        ConsentRow(
            consent = consent, onConsentChange = onConsentChange,
            onOpenTerms = onOpenTerms, onOpenPrivacy = onOpenPrivacy,
            modifier = Modifier.padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun ReedLogoRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("REED", color = Reed2.ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Reed2.lime))
        Spacer(Modifier.width(8.dp))
        Text("CLIENT", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
            style = chromeTextStyle(androidx.compose.material3.MaterialTheme.typography.titleLarge)
                .copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp))
    }
}

@Composable
private fun DividerOr() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Reed2.hairline))
        Text("или", color = Reed2.chrome600, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Reed2.hairline))
    }
}

@Composable
private fun ConsentRow(
    consent: Boolean,
    onConsentChange: (Boolean) -> Unit,
    onOpenTerms: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(50))
                .background(if (consent) Reed2.ink else Reed2.surface300)
                .clickable { onConsentChange(!consent) },
            contentAlignment = Alignment.Center,
        ) {
            if (consent) Icon(Icons.Rounded.Check, null, tint = Reed2.onInk, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(8.dp))
        // «Принимаю соглашение и политику конфиденциальности» — 12sp chrome-600, ссылки подчёркнуты.
        Column {
            Row {
                Text("Принимаю ", color = Reed2.chrome600, fontSize = 12.sp)
                Text("соглашение", color = Reed2.chrome200, fontSize = 12.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onOpenTerms))
                Text(" и ", color = Reed2.chrome600, fontSize = 12.sp)
                Text("политику", color = Reed2.chrome200, fontSize = 12.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onOpenPrivacy))
            }
            Text("конфиденциальности", color = Reed2.chrome200, fontSize = 12.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onOpenPrivacy))
        }
    }
}

/** 12-лучевая «звезда-печенька» для декора входа (хром-градиент). */
private fun starCookiePath(cx: Float, cy: Float, radius: Float, points: Int): Path {
    val path = Path()
    val steps = points * 8
    val amp = 0.10f
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val angle = (t * 2f * PI).toFloat() - (PI / 2f).toFloat()
        val wave = 1f + amp * cos((points * t * 2f * PI).toFloat())
        val rr = radius * wave
        val x = cx + rr * cos(angle)
        val y = cy + rr * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}
