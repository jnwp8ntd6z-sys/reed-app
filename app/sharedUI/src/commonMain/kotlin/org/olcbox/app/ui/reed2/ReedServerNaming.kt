package org.olcbox.app.ui.reed2

/**
 * Человеческие названия серверов (Reed 2.0). Сервер отдаёт служебные имена вроде
 * «🇳🇱 SMART-Нидерланды 2», «🇩🇪 LTE-Германия» (olcRTC), «🇪🇺 ЛТЕ (β)» (быстрый обход) —
 * в интерфейсе показываем страну, флаг и подпись: где сервер или зачем он нужен.
 * Такая же логика — в iOS (ReedAppModel.describeServer).
 */
object ReedServerNaming {
    enum class Kind { Regular, ViaMoscow, FastBypass, StableBypass }

    data class Display(
        val iso: String,        // ISO-2 для флага
        val title: String,      // «Германия»
        val subtitle: String,   // «Франкфурт» / «Через Москву» / «Быстрый обход белых списков»
        val network: String,    // wifi | cell
        val kind: Kind,
    )

    const val SUB_FAST = "Быстрый обход белых списков"
    const val SUB_STABLE = "Надёжный · подключение ~20 с"
    const val SUB_VIA_MOSCOW = "Через Москву"

    private val COUNTRY = mapOf(
        "DE" to "Германия", "NL" to "Нидерланды", "FI" to "Финляндия", "SE" to "Швеция", "PL" to "Польша",
        "US" to "США", "RU" to "Россия", "TR" to "Турция", "CH" to "Швейцария", "FR" to "Франция",
        "GB" to "Великобритания", "KZ" to "Казахстан", "LV" to "Латвия", "EE" to "Эстония", "AT" to "Австрия",
    )
    private val CITY = mapOf(
        "DE" to "Франкфурт", "NL" to "Амстердам", "FI" to "Хельсинки", "SE" to "Стокгольм", "PL" to "Варшава",
        "US" to "Нью-Йорк", "RU" to "Москва", "TR" to "Стамбул", "CH" to "Цюрих", "FR" to "Париж",
        "GB" to "Лондон", "KZ" to "Алматы", "LV" to "Рига", "EE" to "Таллин", "AT" to "Вена",
    )

    fun describe(fullName: String, isOlcRtc: Boolean): Display {
        var iso = flagIso(fullName)
        val stripped = stripName(fullName)
        val upper = fullName.uppercase()
        val isFast = !isOlcRtc && (upper.contains("ЛТЕ") || upper.contains("LTE"))
        if (isFast && (iso.isEmpty() || iso == "EU")) {
            // Быстрые обходы: выход GCP — Польша (Варшава), остальные — Германия.
            iso = if (upper.contains("GCP")) "PL" else "DE"
        }
        if (iso.isEmpty()) iso = COUNTRY.entries.firstOrNull { stripped.startsWith(it.value, ignoreCase = true) }?.key ?: ""
        val title = COUNTRY[iso] ?: stripped.ifBlank { fullName.trim() }
        val viaMoscow = upper.contains("BRIDGE") || Regex("\\s\\d+\\s*$").containsMatchIn(stripped)
        return when {
            isOlcRtc -> Display(iso, title, SUB_STABLE, "cell", Kind.StableBypass)
            isFast -> Display(iso, title, SUB_FAST, "cell", Kind.FastBypass)
            viaMoscow -> Display(iso, title, SUB_VIA_MOSCOW, "wifi", Kind.ViaMoscow)
            else -> Display(iso, title, CITY[iso] ?: "", "wifi", Kind.Regular)
        }
    }

    /** ISO-2 из эмодзи-флага (пара regional indicator), без java.* — работает в commonMain. */
    fun flagIso(s: String): String {
        val cps = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                cps += ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else { cps += c.code; i++ }
        }
        for (k in 0 until cps.size - 1) {
            val a = cps[k]; val b = cps[k + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                return "${'A' + (a - 0x1F1E6)}${'A' + (b - 0x1F1E6)}"
            }
        }
        return ""
    }

    /** «🇳🇱 SMART-Нидерланды 2» → «Нидерланды 2»: без эмодзи и служебных префиксов. */
    fun stripName(s: String): String = s
        .filter { !it.isSurrogate() && it != '️' }
        .trim()
        .replace(Regex("^(SMART|BRIDGE|LTE|ЛТЕ)[-\\s]+", RegexOption.IGNORE_CASE), "")
        .trim()
}
