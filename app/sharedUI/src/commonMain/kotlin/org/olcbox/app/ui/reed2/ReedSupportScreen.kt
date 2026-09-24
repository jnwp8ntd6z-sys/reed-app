package org.olcbox.app.ui.reed2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ChatState { Sent, Sending, Failed }

/** Сообщение чата поддержки; время и день уже отформатированы платформой. */
data class ChatItemUi(
    val id: String,
    val mine: Boolean,
    val text: String,
    val time: String,       // «14:32»
    val dayKey: String,     // «2026-09-24» — для разделителей
    val dayTitle: String,   // «Сегодня» / «Вчера» / «12 сентября»
    val epochSec: Long,
    val state: ChatState = ChatState.Sent,
)

private val SUPPORT_CHIPS = listOf("Не подключается", "Медленно работает", "Не открывается сайт", "Вопрос по подписке")

private sealed class ChatRow(val key: String) {
    class Day(val title: String, key: String) : ChatRow(key)
    class Msg(val item: ChatItemUi, val first: Boolean, val last: Boolean) : ChatRow(item.id)
}

/**
 * Reed 2.0 — чат поддержки (Android). Новые сообщения въезжают снизу на пружине, лента сама
 * доезжает до последнего, кнопка отправки проявляется, когда есть текст.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReedSupportScreen(
    items: List<ChatItemUi>,
    loaded: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Что было на экране при открытии — без анимации; всё новое — с въездом.
    var baseline by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(loaded) { if (loaded && baseline == null) baseline = items.map { it.id }.toSet() }

    val rows = remember(items) {
        val out = ArrayList<ChatRow>()
        items.forEachIndexed { i, it ->
            val prev = items.getOrNull(i - 1)
            val next = items.getOrNull(i + 1)
            val newDay = prev == null || prev.dayKey != it.dayKey
            if (newDay) out += ChatRow.Day(it.dayTitle, "day:" + it.dayKey)
            val first = newDay || prev!!.mine != it.mine || it.epochSec - prev.epochSec > 300
            val last = next == null || next.dayKey != it.dayKey || next.mine != it.mine || next.epochSec - it.epochSec > 300
            out += ChatRow.Msg(it, first, last)
        }
        out.asReversed().toList()          // reverseLayout: индекс 0 — внизу
    }
    val listState = rememberLazyListState()
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    Column(modifier.fillMaxSize().background(Reed2.ground000).statusBarsPadding()) {
        // Верхняя панель.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Reed2.ink, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(4.dp))
            Column {
                Text("Поддержка", color = Reed2.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Reed2.statusOk))
                    Spacer(Modifier.width(6.dp))
                    Text("Ответим здесь же", color = Reed2.inkMuted, fontSize = 13.sp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp).background(Reed2.hairline).heightIn(min = 1.dp, max = 1.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Пустой чат — отдельно от списка (иначе список перехватывает нажатия на подсказки).
            if (loaded && items.isEmpty()) {
                SupportEmpty(onChip = onDraft)
            } else LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is ChatRow.Day -> DaySeparator(row.title, Modifier.animateItem())
                        is ChatRow.Msg -> Bubble(
                            row.item, row.first, row.last,
                            animateIn = baseline?.let { row.item.id !in it } ?: false,
                            onRetry = onRetry,
                            modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = tween(160)),
                        )
                    }
                }
            }
        }

        InputBar(draft, onDraft, onSend)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportEmpty(onChip: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Reed2.surface200), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Reed2.ink, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.size(14.dp))
        Text("Напиши, что случилось", color = Reed2.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        Text("Опиши проблему своими словами — ответим прямо в этом чате.", color = Reed2.inkMuted,
            fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.size(18.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SUPPORT_CHIPS.forEachIndexed { i, chip ->
                val a = remember { Animatable(0f) }
                LaunchedEffect(Unit) { a.animateTo(1f, tween(360, delayMillis = 80 + i * 60)) }
                Text(chip, color = Reed2.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 10.dp.toPx() }
                        .clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface200)
                        .border(1.dp, Reed2.hairline, RoundedCornerShape(Reed2.pillRadius))
                        .clickable { onChip(chip) }
                        .padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
    }
}

@Composable
private fun DaySeparator(title: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(title, color = Reed2.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface100)
                .padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@Composable
private fun Bubble(
    item: ChatItemUi,
    first: Boolean,
    last: Boolean,
    animateIn: Boolean,
    onRetry: (String) -> Unit,
    modifier: Modifier,
) {
    val enter = remember(item.id) { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(item.id) { if (enter.value < 1f) enter.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 420f)) }
    val big = 20.dp; val small = 6.dp
    val shape = if (item.mine) RoundedCornerShape(big, if (first) big else small, small, big)
                else RoundedCornerShape(if (first) big else small, big, big, small)
    val fade by animateFloatAsState(if (item.state == ChatState.Failed) 0.6f else 1f, tween(220), label = "failed")

    Column(
        modifier.fillMaxWidth().padding(top = if (first) 8.dp else 2.dp)
            .graphicsLayer {
                val v = enter.value
                alpha = v
                translationY = (1f - v) * 26.dp.toPx()
                scaleX = 0.92f + 0.08f * v; scaleY = scaleX
                transformOrigin = if (item.mine) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
            },
        horizontalAlignment = if (item.mine) Alignment.End else Alignment.Start,
    ) {
        if (first && !item.mine) {
            Text("Поддержка Reed", color = Reed2.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
        }
        Text(
            linkified(item.text, if (item.mine) Reed2.onInk else Reed2.chrome200),
            color = if (item.mine) Reed2.onInk else Reed2.ink,
            fontSize = 16.sp, lineHeight = 22.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .graphicsLayer { alpha = fade }
                .clip(shape)
                .background(if (item.mine) Reed2.ink else Reed2.surface200)
                .then(if (item.mine) Modifier else Modifier.border(1.dp, Reed2.hairline, shape))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (last || item.state != ChatState.Sent) {
            Row(Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (item.state) {
                    ChatState.Failed -> Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRetry(item.id) }.padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = Reed2.statusDanger, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Не отправлено · Повторить", color = Reed2.statusDanger, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    ChatState.Sending -> {
                        Text(item.time, color = Reed2.chrome600, fontSize = 11.sp, fontFamily = LocalReedFonts.current.mono)
                        Spacer(Modifier.width(5.dp))
                        val pulse = rememberInfiniteTransition(label = "sending")
                        val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                        Icon(Icons.Rounded.Schedule, null, tint = Reed2.chrome600,
                            modifier = Modifier.size(12.dp).graphicsLayer { alpha = a })
                    }
                    ChatState.Sent -> {
                        Text(item.time, color = Reed2.chrome600, fontSize = 11.sp, fontFamily = LocalReedFonts.current.mono)
                        if (item.mine) {
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Rounded.Check, null, tint = Reed2.chrome600, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InputBar(draft: String, onDraft: (String) -> Unit, onSend: () -> Unit) {
    val canSend = draft.isNotBlank()
    val scale by animateFloatAsState(if (canSend) 1f else 0.82f, spring(dampingRatio = 0.62f, stiffness = 520f), label = "sendScale")
    val alpha by animateFloatAsState(if (canSend) 1f else 0.35f, tween(200), label = "sendAlpha")
    Row(
        Modifier.fillMaxWidth().background(Reed2.ground000)
            .navigationBarsPadding().imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(Reed2.surface200)
                .border(1.dp, Reed2.hairline, RoundedCornerShape(23.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) Text("Сообщение", color = Reed2.chrome600, fontSize = 16.sp)
            BasicTextField(
                value = draft, onValueChange = onDraft, maxLines = 6,
                textStyle = TextStyle(color = Reed2.ink, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(Reed2.ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(46.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                .clip(CircleShape).background(Reed2.ink)
                .clickable(enabled = canSend, interactionSource = remember { MutableInteractionSource() }, indication = null) { onSend() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.ArrowUpward, "Отправить", tint = Reed2.onInk, modifier = Modifier.size(22.dp)) }
    }
}

private val URL_RE = Regex("""https?://[^\s]+""")

/** Ссылки в тексте нажимаются (операторы присылают инструкции). */
private fun linkified(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var pos = 0
    for (m in URL_RE.findAll(text)) {
        append(text.substring(pos, m.range.first))
        val url = m.value.trimEnd('.', ',', ')', '»')
        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
            append(url)
        }
        append(m.value.substring(url.length))
        pos = m.range.last + 1
    }
    append(text.substring(pos))
}
