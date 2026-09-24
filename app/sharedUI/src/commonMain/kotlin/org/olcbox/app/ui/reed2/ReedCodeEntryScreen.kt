package org.olcbox.app.ui.reed2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.reed.CodeKind

/**
 * Reed 2.0 — Ввод кода (ТЗ 4.2). Чип распознавания с точкой lime, «Войти» над клавиатурой:
 * серая/неактивная пока пусто, активная — ink. QR внутри поля. Скан/вставка входят сразу.
 */
@Composable
fun ReedCodeEntryScreen(
    code: String,
    onCodeChange: (String) -> Unit,
    detected: CodeKind?,
    hint: String,
    error: String?,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(Reed2.ground000).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        // Верхняя панель.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Reed2.surface200)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ChevronLeft, "Назад", tint = Reed2.ink, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.weight(1f))
            Text("Вход по коду", color = Reed2.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(40.dp))
        }

        Spacer(Modifier.height(24.dp))
        Text("Код, ключ подписки или приглашение", color = Reed2.inkMuted, fontSize = 13.sp,
            fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))

        // Поле в фокусе (обводка 2px ink) + QR внутри.
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                .background(Reed2.surface200)
                .border(2.dp, Reed2.ink, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (code.isEmpty()) {
                    Text("RD-XXXX  или  ссылка", color = Reed2.chrome600,
                        fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                }
                BasicTextField(
                    value = code, onValueChange = onCodeChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Reed2.ink, fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                    cursorBrush = SolidColor(Reed2.lime),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Reed2.surface300)
                    .clickable(onClick = onScanQr),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.QrCodeScanner, "QR", tint = Reed2.ink, modifier = Modifier.size(18.dp)) }
        }

        Spacer(Modifier.height(10.dp))
        // Чип распознавания (точка lime) либо подсказка/ошибка.
        when {
            error != null -> Text(error, color = Reed2.statusDanger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            detected != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Reed2.lime))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface200)
                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(detected.chipText, color = Reed2.ink, fontSize = 13.sp)
                }
            }
            else -> Text(hint, color = Reed2.inkMuted, fontSize = 13.sp, lineHeight = 18.sp)
        }

        Spacer(Modifier.weight(1f))
        // «Войти» над клавиатурой.
        val enabled = code.isNotBlank()
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                .background(if (enabled) Reed2.ink else Reed2.surface300)
                .clickable(enabled = enabled, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            Text("Войти", color = if (enabled) Reed2.onInk else Reed2.chrome600,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
    }
}
