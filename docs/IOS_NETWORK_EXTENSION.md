# iOS Network Extension (системный VPN) — план интеграции Reed VPN

Статус: ПОДГОТОВЛЕНО для финальной сборки в Xcode. Код-скелеты в `app/iosApp/PacketTunnel/`
и `app/iosApp/iosApp/ReedVPNManager.swift`. Сборка/подпись/тест на устройстве — в Xcode
с платным Apple Developer аккаунтом (individual достаточно для теста; organization — для
публикации в App Store, см. memory `apple_appstore_vpn_requirements`).

## Зачем
Сейчас iOS-приложение запускает sing-box ВНУТРИ основного процесса на локальном SOCKS5
(`SwiftSingBoxManager` + `SingboxmobileStart`) и держится фоном через AVAudioSession-хак.
Это НЕ системный VPN: трафик телефона не перехватывается, iOS не спрашивает «Разрешить VPN»,
подключение «сбрасывается». Чтобы был настоящий VPN, нужен **NEPacketTunnelProvider** —
app-extension, который создаёт TUN-интерфейс и через него гонит весь трафик в туннель.

## Архитектура (рекомендованная: sing-box держит TUN сам, через libbox)
Стандартный путь sing-box на iOS (как в официальном SFI):
1. Extension (`PacketTunnelProvider`) запускается системой при старте VPN.
2. Он строит `NEPacketTunnelNetworkSettings` (адрес TUN, DNS, MTU, маршруты) и применяет их.
3. Передаёт sing-box **файловый дескриптор tun** через `LibboxPlatformInterface` →
   `LibboxNewService(configWithTunInbound, platformInterface)` → `service.start()`.
4. sing-box сам читает/пишет пакеты в TUN и маршрутизирует по конфигу (`tun` inbound +
   `route` правила — split-routing РФ уже в конфиге, см. `build_singbox_config` на сервере).

Это требует, чтобы gomobile-обёртка экспортировала libbox (сейчас экспортирует только
`Start/Stop/IsRunning` с socks-inbound — недостаточно для TUN). См. раздел «Изменение обёртки».

> Альтернатива (если не пересобирать обёртку): оставить `SingboxmobileStart` (локальный SOCKS)
> и добавить tun2socks в extension (packetFlow ↔ 127.0.0.1:SOCKS). Минус — нужен ещё один
> нативный компонент (tun2socks для iOS) и ручной мост packetFlow. Путь через libbox чище.

## Конфиг sing-box для TUN-режима
Сервер уже умеет отдавать sing-box-конфиг (`/app/singbox?token=&server=&split=`). Для NE
нужно, чтобы inbound был **`tun`**, а не `socks`. Варианты:
- добавить на сервер параметр `?inbound=tun` → отдавать `tun` inbound вместо `socks`; или
- extension сам подменяет inbound на tun перед передачей в libbox (проще, без серверных правок).
`tun` inbound (минимум): `{"type":"tun","tag":"tun-in","mtu":9000,"auto_route":true,
"strict_route":false,"stack":"system","sniff":true}` — но на iOS NE auto_route не нужен
(маршруты задаёт `NEPacketTunnelNetworkSettings`); используем `stack: gvisor`, без auto_route,
fd передаёт PlatformInterface. Точную форму даст реализация PlatformInterface (openTun).

## Xcode: что создать (делает издатель/владелец Mac)
1. **Новый таргет**: App Extension → Network Extension → Packet Tunnel Provider.
   - Имя: `PacketTunnel`. Bundle ID: `<APP_BUNDLE_ID>.PacketTunnel`
     (напр. `org.reedvpn.app.PacketTunnel`).
   - Подключить исходники из `app/iosApp/PacketTunnel/` (PacketTunnelProvider.swift,
     Info.plist, PacketTunnel.entitlements).
   - Линковать XCFramework `OlcRtcMobile` (там же лежат Singboxmobile*/libbox-функции).
2. **Capabilities** на ОБА таргета (app + extension):
   - Network Extensions → Packet Tunnel.
   - App Groups → общий контейнер `group.<APP_BUNDLE_ID>` (для передачи токена/настроек
     из приложения в extension).
3. **Embed** extension в app (Build Phases → Embed App Extensions).
4. **Подпись**: provisioning profiles обоих таргетов с NE-entitlement (создаёт аккаунт-владелец;
   на этапе теста — individual аккаунт; на публикации — organization-аккаунт издателя).
5. **Info.plist приложения**: добавить `ITSAppUsesNonExemptEncryption = false`
   (или пройти export compliance) — VPN использует шифрование.

## Изменение gomobile-обёртки (`singbox-mobile/mobile.go`)
Текущая: `Start(configJSON)/Stop()/IsRunning()` — поднимает socks-inbound.
Нужно добавить экспорт, позволяющий запустить libbox-сервис с PlatformInterface, который
отдаёт tun fd из NE. Базово sing-box уже включает пакет `libbox`. Минимальный путь:
экспортировать функцию `StartWithTun(configJSON string, tunFd int) error`, которая
конструирует sing-box box с готовым tun fd (через `tun.Options` / `option`-overrides),
либо переключиться на `libbox.NewService(config, platformInterface)` и реализовать
`PlatformInterface` (метод `OpenTun` возвращает tunFd, переданный из Swift).
Точную реализацию подтвердить по версии sing-box в go.mod. Пересобрать
`gradle buildOlcrtcIosXcframework` (combined bind — он же тянет sing-box, см. memory
`vless_singbox_integration_plan`).

## App-side (приложение)
`ReedVPNManager.swift` (скелет добавлен):
- `loadOrCreateManager()` → `NETunnelProviderManager`, ставит `NETunnelProviderProtocol`
  (providerBundleID = `<APP_BUNDLE_ID>.PacketTunnel`, serverAddress = "Reed VPN"),
  `saveToPreferences()` → ПЕРВЫЙ запуск вызывает системный запрос «Разрешить VPN-конфигурацию».
- `start(token:server:split:)` → кладёт параметры в providerConfiguration / App Group,
  `manager.connection.startVPNTunnel()`.
- `stop()` → `stopVPNTunnel()`.
- статус через `NEVPNStatusDidChange`.
Затем в Kotlin (`IosVpnManager`) для VLESS-локаций вызывать НЕ `SwiftSingBoxManager`, а
`ReedVPNManager` (через новый bridge-протокол). olcRTC можно оставить как есть или тоже
завести в extension (позже).

## Тест на устройстве (издатель/владелец)
1. Открыть `app/iosApp/iosApp.xcodeproj` в Xcode, создать таргет PacketTunnel (см. выше).
2. Выбрать команду подписи (аккаунт), включить capabilities.
3. Run на реальном iPhone → войти через Telegram → подключить → iOS спросит разрешение VPN →
   проверить, что трафик идёт (2ip.io показывает зарубежный IP), а РФ-сайты — напрямую (split).
4. Логи extension — в Console.app (фильтр по процессу PacketTunnel).

## Чек-лист готовности к App Store (см. memory apple_appstore_vpn_requirements)
- [x] Нет payment-CTA в приложении
- [x] Удаление аккаунта (`/app/account/delete` + кнопка)
- [x] Политики + согласие на главном экране
- [ ] NEPacketTunnelProvider (этот документ)
- [ ] Экран политики ПЕРЕД первым подключением (можно гейтить кнопку Connect согласием)
- [ ] ITSAppUsesNonExemptEncryption / export compliance
- [ ] Демо-доступ ревьюеру (тестовый Telegram-вход в App Review Notes)
- [ ] Публикация через ORG-аккаунт (издатель)
