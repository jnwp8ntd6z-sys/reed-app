package org.olcbox.app.ui.reed2

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/**
 * Reed 2.0 — дизайн-токены темы Graphite (ТЗ 3.2). Единый источник цвета/радиусов
 * для нативных Android-экранов. Значения 1-в-1 из ТЗ.
 *
 * lime используется ТОЛЬКО для: включённой кнопки подключения, точки «Подключено»,
 * точки распознанного кода. Больше нигде.
 */
object Reed2 {
    // ── Фон / поверхности ──
    val ground000 = Color(0xFF08090A)   // фон экранов
    val ground100 = Color(0xFF101113)   // второй цвет градиента фона
    val surface100 = Color(0xFF17191B)  // подложки, навбар, клавиатурная зона
    val surface200 = Color(0xFF1F2225)  // карточки
    val surface300 = Color(0xFF282C30)  // плитки, иконки в строках, вторичные кнопки

    // ── Хром (металлик) ──
    val chrome050 = Color(0xFFF5F7F8)   // блик
    val chrome200 = Color(0xFFCDD3D8)
    val chrome400 = Color(0xFF949BA2)
    val chrome600 = Color(0xFF626A71)   // неактивный текст, выключенные элементы
    val chrome800 = Color(0xFF383D42)   // дорожки, неактивные сегменты

    // ── Текст ──
    val ink = Color(0xFFF5F7F8)         // основной текст, основная кнопка
    val inkMuted = Color(0xFFA4ACB3)    // вторичный текст
    val onInk = Color(0xFF0A0B0C)       // текст на светлой кнопке
    val hairline = Color(0xFF2B2F33)    // разделители, обводки карточек

    // ── Акценты ──
    val lime = Color(0xFFAAFF00)        // включённая кнопка, точка «Подключено»/код
    val onLime = Color(0xFF0D1400)

    // ── Статусы ──
    val statusOk = Color(0xFF5FD39A)    // «Работает», пинг < 60, включённый Toggle iOS
    val statusWarn = Color(0xFFE6B45C)  // «Ограничено», пинг ≥ 100, новое устройство
    val statusDanger = Color(0xFFF0705F) // «Удалить аккаунт»

    // ── Радиусы (ТЗ 3.2) ──
    val cardRadius = 28.dp              // карточки Android
    val tileRadius = 13.dp              // плитки
    val pillRadius = 999.dp             // кнопки-капсулы

    // ── Подсветка выбранной строки сервера (#F5F7F80F ≈ 6% ink) ──
    val selectedRowBg = Color(0x0FF5F7F8)

    /** Хромовый градиент для «CLIENT» в логотипе и «БЕЗ ОБРЫВОВ» на входе (ТЗ 3.2).
     * 176° ≈ почти вертикально сверху вниз. */
    fun chromeBrush(widthPx: Float = 1000f, heightPx: Float = 1000f): Brush =
        Brush.linearGradient(
            colorStops = arrayOf(
                0.00f to chrome050,
                0.18f to chrome200,
                0.34f to chrome600,
                0.47f to chrome050,
                0.58f to chrome800,
                0.74f to chrome200,
                0.90f to chrome050,
                1.00f to chrome400,
            ),
            // 176° от вертикали: почти сверху вниз с лёгким наклоном.
            start = Offset(widthPx * 0.03f, 0f),
            end = Offset(widthPx * -0.03f + widthPx * 0f, heightPx),
        )

    /** Цвет пинга по порогам (ТЗ 4.4): <60 ok, 60–99 muted, ≥100 warn, нет ответа — «—». */
    fun pingColor(ms: Int?): Color = when {
        ms == null || ms < 0 -> chrome600
        ms < 60 -> statusOk
        ms < 100 -> inkMuted
        else -> statusWarn
    }

    fun pingText(ms: Int?): String = if (ms == null || ms < 0) "—" else "$ms мс"
}
