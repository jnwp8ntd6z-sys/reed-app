# Reed VPN — приложение (Android / Windows / iOS)

Единый кроссплатформенный клиент Reed VPN: обычный VPN (VLESS/Reality) + обход белых
списков (olcRTC). Покупка и выдача конфигов — через Telegram-бот. База — форк
**olcbox** (Kotlin Multiplatform / Compose, MIT).

## Структура репозитория
- `design/` — UI-эталон (экспорт из Figma Make, React+Tailwind-прототип). Из него
  переносим экраны в Compose Multiplatform. Запустить эталон: `cd design && npm i && npm run dev`.
- `docs/API.md` — контракт серверного API приложения (`/app/*`, уже работает на
  `reed-vpn.duckdns.org`).
- `app/` — исходники приложения (KMP/Compose) — *в работе*.
- `.github/workflows/` — облачная сборка артефактов (APK / EXE / IPA) через GitHub Actions.

## Бренд
- Тёмная тема. Акценты: лаймовый `rgb(155,210,0)`, оранжевый `rgb(255,140,80)`.
- Маскот — оранжевый «цветок» (`design/src/imports/photo_*no-bg*.png`).

## Где что собирается (важно)
Сервер бота (1 CPU / ~1 ГБ RAM, Linux) **НЕ** собирает приложения — он только хостит
бэкенд-API. Артефакты собираются в **облаке (GitHub Actions)**:
- **Android APK** — ubuntu-runner (JDK + Android SDK + Gradle).
- **Windows EXE/MSI** — windows-runner.
- **iOS IPA** — **только macos-runner** (Xcode). Для своего айфона — IPA без подписи
  (sideload через AltStore/Sideloadly); для App Store/TestFlight — нужен Apple Developer
  ($99/год) и подпись.

## Статус
- ✅ Бэкенд-API приложения (`/app/health|servers|carriers|subscription`) — задеплоен и протестирован.
- ✅ Дизайн (все экраны) — готов, лежит в `design/`.
- ⏳ Каркас приложения (форк olcbox + ребренд + перенос дизайна) — следующий шаг.
- ⏳ Туннель: VLESS (sing-box) + olcRTC.
- ⏳ Облачные сборки APK/EXE/IPA.

См. `ROADMAP.md`.
