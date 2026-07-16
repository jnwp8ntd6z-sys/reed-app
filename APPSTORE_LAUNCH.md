# Reed VPN — подготовка к выпуску в App Store

Черновик метаданных и записки ревьюеру. Финальные тексты/цены — за владельцем.

## Технический статус (готовит Claude)
- [x] Удаление аккаунта в приложении (App Store требует) — есть.
- [x] Нет стиринга к внешней оплате в App Store-сборке (переходы к боту скрыты).
- [x] Export compliance: `ITSAppUsesNonExemptEncryption=false`.
- [x] Убран фон-режим `audio` (Apple отклоняет audio без реального звука; VPN держит фон через NEPacketTunnelProvider).
- [x] Добавлен `PrivacyInfo.xcprivacy` (tracking=false, UserDefaults CA92.1).
- [ ] Скриншоты (6.7"/6.5" + iPad 12.9") — снять с устройства.
- [ ] Проверить возрастной рейтинг и категорию (Utilities).

## ⚠️ Ключевой риск ревью (решение владельца)
VPN работает по подписке, купленной ВНЕ приложения (Telegram-бот), покупок внутри нет.
Apple 3.1.1 обычно требует IAP для цифровых подписок. Два пути:
- **А (текущий):** вход по коду, оплата в боте, в приложении никаких цен/ссылок на оплату. Риск отклонения, но проходят как «доступ по аккаунту».
- **Б:** добавить IAP (комиссия Apple 15–30%).

## Записка ревьюеру (App Review Notes) — EN
```
Reed is a proxy client. Users connect using access they already have — either an
access CODE or by pasting a config KEY (VLESS/VMess/Trojan/Shadowsocks/SOCKS).
The app does not sell or provide servers and has NO in-app purchases.

Demo access code: REED-MX4ZBBV5
Steps to test: open the app → "Sign in with code" → enter the code →
a server list appears → tap the big round button to connect.
(You can also tap "Paste subscription key" to import a config manually.)

Reed collects no data, keeps no traffic logs, and does not track users.
Export compliance: standard encryption (TLS) only.
```
Демо-аккаунт: имя в кабинете «TEST CODE», без Telegram/аватара, подписка бессрочная,
рефералка в приложении отсутствует. Код: **REED-MX4ZBBV5** (sub_token 2akxYLYkwrMwRfaqW4MBm5VCGgA0jhI-, tg_id 8888888888, sub_id 519).

## Описание (адаптировано из Happ — «прокси-клиент», обсуждается с owner)

### EN (черновик v1)
```
Reed is a lightweight proxy client with a clean, one-tap interface. Enter your
access code and connect — no sign-up, no clutter.

Reed does not sell or provide server access or VPN services. You connect using
configurations and access you already have; the app is only a client. Beware of
anyone claiming to sell "official Reed servers" — Reed is a client application.

Features:
• One-tap connect, simple modern interface
• Sign in with an access code — no registration
• Rule-based routing (keep local services direct)
• Multiple protocols and transports
• Fast, low-latency connections

Supported protocols:
• VLESS (Reality)
• VMess
• Trojan
• Shadowsocks
• SOCKS
• WebRTC-based transport for restricted networks

Privacy: Reed does not collect any data. Your information stays on your device
and is not sent to external servers. No traffic logging.

Reed does not offer server subscriptions for purchase inside the app. Users are
responsible for obtaining or configuring their own access and for complying with
the laws of their jurisdiction when using the app.
```

### RU (черновик v1)
```
Reed — лёгкий прокси-клиент с простым интерфейсом и подключением в одно нажатие.
Введите код доступа и подключайтесь — без регистраций и лишнего.

Reed не продаёт и не предоставляет серверы или VPN-услуги. Вы подключаетесь по
конфигурации/доступу, который у вас уже есть; приложение — только клиент.
Остерегайтесь тех, кто предлагает купить «официальные серверы Reed».

Возможности:
• Подключение одной кнопкой, простой современный интерфейс
• Вход по коду доступа — без регистрации
• Маршрутизация по правилам (локальные сервисы — напрямую)
• Несколько протоколов и транспортов
• Быстрые соединения с низкой задержкой

Поддерживаемые протоколы:
• VLESS (Reality)
• VMess
• Trojan
• Shadowsocks
• SOCKS
• Транспорт на базе WebRTC для сетей с ограничениями

Конфиденциальность: Reed не собирает никаких данных. Ваша информация остаётся на
устройстве и не отправляется на внешние серверы. Логи трафика не ведутся.

Reed не предлагает покупку серверных подписок внутри приложения. Пользователь сам
получает или настраивает свой доступ и соблюдает законы своей юрисдикции.
```

## Метаданные (черновик)
**Название:** Reed
**Подзаголовок (RU):** Быстрый и приватный доступ
**Подзаголовок (EN):** Fast, private access
**Категория:** Utilities (осн.) / Productivity (доп.)

**Описание (RU, черновик):**
```
Reed — быстрый доступ к открытому интернету без рекламы и слежки.
• Быстрые серверы в разных странах
• Простое подключение одной кнопкой
• Никакого логирования трафика
• Вход по коду — без лишних регистраций
Подписка оформляется отдельно; в приложении вводится код доступа.
```
**Описание (EN, черновик):**
```
Reed gives you fast, private access to the open internet — no ads, no tracking.
• Fast servers in multiple countries
• One-tap connection
• No traffic logging
• Sign in with a code — no sign-up hassle
A subscription is provided separately; enter your access code in the app.
```
**Ключевые слова (черновик):** vpn, proxy, privacy, secure, fast, unblock, private browsing, wifi security

**URL:**
- Privacy Policy: https://reedapp.ru/privacy-app
- Support: https://reedapp.ru (или страница поддержки)
- Marketing: https://reedapp.ru

## Нужно от владельца
1. Доступ к App Store Connect (org-аккаунт — обязателен для VPN).
2. Решение по риску: путь А или Б.
3. Скриншоты + финальные тексты (название/описание/ключевые слова).
4. Рабочий демо-код для записки ревьюеру.
