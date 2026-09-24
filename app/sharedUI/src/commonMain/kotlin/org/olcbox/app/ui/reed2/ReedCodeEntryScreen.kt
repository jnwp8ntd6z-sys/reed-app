package org.olcbox.app.ui.reed2

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.olcbox.app.data.reed.CodeKind

/**
 * Reed 2.0 — Ввод кода (ТЗ 4.2). Поле в фокусе, чип распознавания с точкой lime,
 * «Войти» над клавиатурой: серая/неактивная пока пусто, активная — ink.
 * Для приглашения в семью (RDI-) появляется поле имени (его видит владелец).
 * Скан QR и вставка из буфера входят сразу — это решает адаптер (onCodeChange).
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
    busy: Boolean = false,
    name: String = "",
    onNameChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val needsName = detected == CodeKind.FamilyInvite
    val canSubmit = code.isNotBlank() && !busy && (!needsName || name.isNotBlank())
    val codeFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }
    LaunchedEffect(needsName) { if (needsName) runCatching { nameFocus.requestFocus() } }

    Column(
        modifier.fillMaxSize().background(Reed2.ground000)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Reed2.surface200)
                    .clickable(enabled = !busy, onClick = onBack),
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

        val borderColor by animateColorAsState(
            if (error != null) Reed2.statusDanger else Reed2.ink, tween(200), label = "codeBorder")
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp))
                .background(Reed2.surface200)
                .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (code.isEmpty()) {
                    Text("RD-XXXX  или  ссылка", color = Reed2.chrome600,
                        fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                }
                BasicTextField(
                    value = code, onValueChange = onCodeChange,
                    singleLine = true, enabled = !busy,
                    textStyle = TextStyle(color = Reed2.ink, fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                    cursorBrush = SolidColor(Reed2.lime),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = if (needsName) ImeAction.Next else ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { if (canSubmit) onSubmit() },
                        onNext = { runCatching { nameFocus.requestFocus() } },
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                )
            }
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Reed2.surface300)
                    .clickable(enabled = !busy, onClick = onScanQr),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.QrCodeScanner, "Сканировать QR", tint = Reed2.ink, modifier = Modifier.size(18.dp)) }
        }

        Spacer(Modifier.height(10.dp))
        // Чип распознавания / подсказка / ошибка — плавная смена без прыжков.
        AnimatedContent(
            targetState = Triple(error, detected, hint),
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "codeChip",
        ) { (err, kind, h) ->
            when {
                err != null -> Text(err, color = Reed2.statusDanger, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                kind != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Reed2.lime))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface200)
                        .padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(kind.chipText, color = Reed2.ink, fontSize = 13.sp)
                    }
                }
                else -> Text(h, color = Reed2.inkMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }

        // Имя — только для приглашения в семью.
        AnimatedVisibility(
            visible = needsName,
            enter = expandVertically(tween(260)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(220)) + fadeOut(tween(160)),
        ) {
            Column {
                Spacer(Modifier.height(20.dp))
                Text("Как тебя зовут? Имя увидит владелец семьи", color = Reed2.inkMuted, fontSize = 13.sp,
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(Reed2.surface200).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (name.isEmpty()) Text("Имя", color = Reed2.chrome600, fontSize = 15.sp)
                    BasicTextField(
                        value = name, onValueChange = { onNameChange(it.take(40)) },
                        singleLine = true, enabled = !busy,
                        textStyle = TextStyle(color = Reed2.ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(Reed2.lime),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (canSubmit) onSubmit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        // «Войти» над клавиатурой (safeDrawing включает клавиатуру).
        val bg by animateColorAsState(if (canSubmit || busy) Reed2.ink else Reed2.surface300, tween(200), label = "goBg")
        Box(
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(Reed2.pillRadius))
                .background(bg)
                .clickable(enabled = canSubmit, onClick = onSubmit),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Reed2.onInk, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Входим…", color = Reed2.onInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("Войти", color = if (canSubmit) Reed2.onInk else Reed2.chrome600,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
