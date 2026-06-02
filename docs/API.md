# Reed App API — контракт (v1)

База: `https://reed-vpn.duckdns.org` (тот же хост, что и подписки; реализовано в
`reed_vpn_bot/sub_server.py`, префикс `/app/*`). Ответы — JSON, UTF-8.

Авторизация v1: по `token` = `sub_token` подписки (бот выдаёт его пользователю).
v2 (план): вход по deeplink из бота (`?start=app_<nonce>`) → обмен nonce на app-токен.

## GET /app/health
Проверка живости.
```json
{ "ok": true, "service": "reed-app-api", "version": 1 }
```

## GET /app/servers
Публичный список серверов для отображения (без секретов).
```json
{ "servers": [ { "id": 0, "name": "🇳🇱 SMART-Нидерланды", "desc": "...", "type": "SMART", "lte": false } ] }
```
`type` ∈ `SMART | BRIDGE | LTE`.

## GET /app/carriers
Операторы РФ для LTE/обхода белых списков.
```json
{ "operators": ["МТС","Мегафон","Yota","Билайн","Т2","Т-Мобайл","Ростелеком"] }
```

## GET /app/subscription?token=<sub_token>
Статус подписки + конфиг для подключения.
```json
{
  "subscription": { "status": "active", "plan_id": "family_maximum", "plan_type": "family",
                    "expires_at": "2027-04-08 01:57:53", "seconds_left": 26709386, "days_left": 309 },
  "traffic": { "up": 0, "down": 0, "used": 0, "total": 2199023255552 },
  "lte": { "used_bytes": 0, "used_gb": 0.0, "total_gb": 40 },
  "referral": { "code": "5350900909", "bonus_balance": 97072, "percent": 30, "withdraw_min": 3000 },
  "operators": ["МТС", "..."],
  "current_operator": null,
  "servers": [ { "id": 0, "name": "🇳🇱 SMART-Нидерланды", "desc": "...", "type": "SMART",
                 "lte": false, "vless": "vless://<uuid>@...#..." } ],
  "bot_url": "https://t.me/ReedVPNbot"
}
```
- `traffic.total` — лимит обычного трафика в байтах (1 ТБ индивидуальный / 2 ТБ семейный).
- `lte.total_gb` — базовая квота (40 ГБ) + докупленные пакеты.
- `servers[].vless` — готовая VLESS-ссылка под этого пользователя (для sing-box/xray).

## Планируемые эндпоинты (v2)
- `POST /app/auth/start` → `{ nonce, deeplink }` (бот-deeplink логин).
- `GET  /app/auth/poll?nonce=` → `{ token }` после привязки в боте.
- `POST /app/device` → регистрация/гейтинг устройства по HWID (движок уже есть в боте).
- `GET  /app/operator/config?operator=` → olcRTC комната/ключ под оператора.
