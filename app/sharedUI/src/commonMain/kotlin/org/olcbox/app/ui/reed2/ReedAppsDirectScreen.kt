package org.olcbox.app.ui.reed2

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Приложение в списке «напрямую» (мимо туннеля). */
data class DirectAppUi(val packageName: String, val label: String)

/**
 * Reed 2.0 — «Приложения напрямую» (ТЗ 7.5, Android): поиск, сверху группа «Банки и госуслуги»
 * (только установленные), ниже все приложения. Отмеченные идут мимо туннеля
 * (VpnService.Builder.addDisallowedApplication). Иконки рисует платформа через [icon].
 */
@Composable
fun ReedAppsDirectScreen(
    presets: List<DirectAppUi>,
    apps: List<DirectAppUi>,
    selected: Set<String>,
    loading: Boolean,
    onToggle: (String) -> Unit,
    onSetPresets: (Boolean) -> Unit,
    onBack: () -> Unit,
    icon: @Composable (packageName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    fun matches(a: DirectAppUi) = q.isEmpty() || a.label.contains(q, ignoreCase = true) ||
        a.packageName.contains(q, ignoreCase = true)
    val presetPkgs = presets.map { it.packageName }.toSet()
    val shownPresets = presets.filter(::matches)
    val shownApps = apps.filter { it.packageName !in presetPkgs && matches(it) }
    val allPresetsOn = presets.isNotEmpty() && presets.all { it.packageName in selected }

    Column(
        modifier.fillMaxSize().background(Reed2.ground000)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Reed2.surface200)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.ChevronLeft, "Назад", tint = Reed2.ink, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Приложения напрямую", color = Reed2.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (selected.isEmpty()) "Все приложения идут через Reed"
                    else "Мимо туннеля: ${selected.size}",
                    color = Reed2.inkMuted, fontSize = 13.sp,
                )
            }
        }

        // Поиск.
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(48.dp)
                .clip(RoundedCornerShape(Reed2.pillRadius)).background(Reed2.surface200)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = Reed2.chrome600, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Поиск приложения", color = Reed2.chrome600, fontSize = 15.sp)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = Reed2.ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(Reed2.ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (loading && apps.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Reed2.inkMuted, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (shownPresets.isNotEmpty()) {
                item(key = "presets-header") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("БАНКИ И ГОСУСЛУГИ", color = Reed2.inkMuted, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (allPresetsOn) "Снять все" else "Отметить все",
                            color = Reed2.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(Reed2.pillRadius))
                                .background(Reed2.surface300)
                                .clickable { onSetPresets(!allPresetsOn) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                items(shownPresets, key = { "p:" + it.packageName }) { a ->
                    DirectAppRow(a, a.packageName in selected, { onToggle(a.packageName) }, icon)
                }
                item(key = "gap") { Spacer(Modifier.height(18.dp)) }
            }
            if (shownApps.isNotEmpty()) {
                item(key = "apps-header") {
                    Text("ВСЕ ПРИЛОЖЕНИЯ", color = Reed2.inkMuted, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                items(shownApps, key = { it.packageName }) { a ->
                    DirectAppRow(a, a.packageName in selected, { onToggle(a.packageName) }, icon)
                }
            }
            if (shownPresets.isEmpty() && shownApps.isEmpty()) {
                item(key = "empty") {
                    Text("Ничего не нашли", color = Reed2.inkMuted, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
                }
            }
        }
    }
}

@Composable
private fun DirectAppRow(
    app: DirectAppUi,
    checked: Boolean,
    onClick: () -> Unit,
    icon: @Composable (String) -> Unit,
) {
    val bg by animateColorAsState(if (checked) Reed2.selectedRowBg else Reed2.ground000, tween(220), label = "rowBg")
    val box by animateColorAsState(if (checked) Reed2.ink else Reed2.ground000, tween(200), label = "check")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Reed2.surface300),
            contentAlignment = Alignment.Center) { icon(app.packageName) }
        Spacer(Modifier.width(12.dp))
        Text(app.label, color = Reed2.ink, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(box)
                .border(1.5.dp, if (checked) Reed2.ink else Reed2.chrome600, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Rounded.Check, null, tint = Reed2.onInk, modifier = Modifier.size(15.dp))
        }
    }
}
