package org.olcbox.app.data.reed

/**
 * Reed 2.0: распознавание того, что человек вставил в поле входа (ТЗ 4.2).
 * Одна общая логика на iOS и Android — UI только показывает [chipText] с точкой lime
 * и по [CodeKind] решает, какой запрос слать серверу.
 *
 * Типы:
 *  - [SubscriptionLink] — ссылка/стандартный конфиг (vless/vmess/trojan/ss/http[s]) →
 *    добавляем её серверы у себя, на сервер вход не шлём.
 *  - [FamilyInvite] (RDI-) → /app/share/redeem, войдёшь в семью владельца.
 *  - [DeviceCode]   (RDX-) → /app/share/redeem, войдёшь в свой аккаунт новым устройством.
 *  - [AccountCode]  (RD-/REED-/иначе) → /app/code/login, откроется твой аккаунт.
 */
enum class CodeKind(val chipText: String) {
    SubscriptionLink("Ссылка подписки — добавим её серверы"),
    FamilyInvite("Приглашение — войдёшь в семью владельца"),
    DeviceCode("Код устройства — войдёшь в свой аккаунт"),
    AccountCode("Код входа — откроется твой аккаунт");

    companion object {
        private val LINK_SCHEMES = listOf(
            "https://", "http://", "vless://", "vmess://", "trojan://", "ss://", "ssconf://",
        )

        /** Распознаёт вставленный текст. Пустая строка → null (поле пустое). */
        fun detect(raw: String): CodeKind? {
            val input = raw.trim()
            if (input.isEmpty()) return null
            val lower = input.lowercase()
            if (LINK_SCHEMES.any { lower.startsWith(it) }) return SubscriptionLink
            val upper = input.uppercase()
            if (upper.startsWith("RDI-")) return FamilyInvite
            if (upper.startsWith("RDX-")) return DeviceCode
            // RD-, REED-, или голый код — всё это вход в аккаунт.
            return AccountCode
        }

        /** true — этот ввод входит СРАЗУ, без кнопки «Войти» (скан QR / вставка из буфера). */
        fun entersImmediately(kind: CodeKind?): Boolean = kind != null
    }
}
