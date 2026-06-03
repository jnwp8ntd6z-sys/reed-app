# singbox-mobile (WIP) — ядро VLESS Reality для приложения Reed

Тонкая gomobile-обёртка над [sing-box](https://github.com/SagerNet/sing-box), дающая
приложению VLESS Reality по той же схеме, что и olcRTC: ядро работает внутри приложения
и поднимает **локальный SOCKS5**, на который наводится `tun2socks`.

## Зачем
Движок приложения (форк olcbox) умеет только olcRTC и не понимает VLESS. Чтобы заработали
наши основные VLESS Reality серверы, нужно второе ядро. sing-box — на Go, как и olcRTC,
и собирается тем же `gomobile bind`.

## Серверная часть (готова)
`GET https://reed-vpn.duckdns.org/app/singbox?token=<sub_token>&socks_port=10810`
отдаёт полный sing-box конфиг: `inbound socks 127.0.0.1:10810` + 9 VLESS Reality
outbounds + RU split-routing. См. `app_singbox` в `reed_vpn_bot/sub_server.py`.

## API (gomobile)
- `Start(configJSON string) error`
- `Stop() error`
- `IsRunning() bool`

## Сборка (в CI, по образцу olcrtc в `.github/workflows/build.yml`)
```sh
cd singbox-mobile
go mod tidy                 # сгенерировать go.sum
gomobile bind -target=android -androidapi 23 -o singbox.aar .
gomobile bind -target=ios -o SingBoxMobile.xcframework .
```

## Осталось подключить (следующие шаги)
1. CI: шаг `go mod tidy` + `gomobile bind` (android AAR + ios XCFramework), env `SINGBOX_REPO` по образцу `OLCRTC_REPO`.
2. gradle `app/sharedUI/build.gradle.kts`: задачи сборки AAR/XCFramework + подключение (по образцу `buildOlcrtcIosXcframework` и `app/sharedUI/olcrtc-bin`).
3. Swift `app/iosApp/iosApp/SwiftSingBoxManager.swift` (зеркало `SwiftOlcRtcManager`: `import SingBoxMobile`, `SingboxmobileStart/Stop/IsRunning`), зарегистрировать в `OlcboxIosApp.swift`.
4. Kotlin: expect/actual мост `SingBoxTunnel` (android — AAR, ios — через bridge), Android subprocess-вариант не нужен (ядро in-process через AAR).
5. VpnManager: по типу локации (olcRTC/VLESS/SOCKS) поднимать нужный транспорт; VLESS = скачать `/app/singbox` → `SingBoxTunnel.start(config)` → навести tun2socks на 127.0.0.1:10810.

## Статус
WIP. `mobile.go` написан по исходнику sing-box **v1.13.13** (`box.New`, `include.Context`,
`json.UnmarshalExtendedContext[option.Options]`). В среде разработки нет Go → не
скомпилирован; перед подключением прогнать `go mod tidy` + сборку.
