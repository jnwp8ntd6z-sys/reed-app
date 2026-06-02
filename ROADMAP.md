# Reed App — план работ

## Этап 0 — Бэкенд-API ✅ (готово)
- `/app/health|servers|carriers|subscription` в `sub_server.py`, протестировано.
- Дальше (v2): deeplink-логин (`/app/auth/*`), `/app/device`, `/app/operator/config`.

## Этап 1 — Каркас приложения (в работе)
- Форк olcbox → ребренд «Reed» (имя пакета, иконка, цвета, маскот, строки).
- Перенос дизайна из `design/` в Compose Multiplatform: онбординг + 4 вкладки
  (Главная, Управление, Личный кабинет, Поддержка).
- Слой данных: клиент к `/app/*` (Ktor client), модели, кэш.
- Сборки: Android (APK), Windows (EXE) — через GitHub Actions.

## Этап 2 — Туннель
- VLESS/Reality через встроенный sing-box/xray (обычный режим, выбор сервера из списка).
- olcRTC (обход) — из olcbox; «Сменить оператора».
- Системный VPN на каждой платформе: Android VpnService, Windows wintun, iOS NetworkExtension.
- Split-routing РФ (ru_routing.json уже на сервере).

## Этап 3 — Вход и синхронизация
- Deeplink-логин через бота (`?start=app_<nonce>`), привязка ЛК.
- Автоподтягивание подписки/трафика/операторов из API.

## Этап 4 — iOS
- macos-runner (GitHub Actions) → IPA.
- Без Apple-аккаунта: IPA без подписи для sideload (AltStore/Sideloadly) на свой айфон.
- С Apple Developer ($99/год): подпись → TestFlight → App Store.

## Этап 5 — Миграция с Happ
- Приложение как опция, Happ остаётся запасным. Затем плавный перевод всех.

## Что нужно от владельца
- **GitHub-аккаунт** (или организация) — чтобы залить репозиторий и включить облачные
  сборки (APK/EXE/IPA). Без удалённого репозитория артефакты собрать негде (этот VPS не тянет).
- Позже: Apple Developer для публикации iOS; ключи подписи Android (для релизных APK).
- Финальные правки дизайна/текстов/бренда (лого в высоком разрешении, иконка, палитра).
