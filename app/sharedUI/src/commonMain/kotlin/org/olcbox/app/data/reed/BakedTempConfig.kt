package org.olcbox.app.data.reed

/**
 * ВШИТЫЙ (baked) sing-box-конфиг временного VPN — последний резерв для бутстрапа.
 *
 * Зачем: на свежей установке, где интернета с доступом к нашему API не было НИ РАЗУ
 * (строгие «белые списки» РФ, где reed-vpn.duckdns.org недоступен напрямую), нельзя
 * скачать ни /app/temp, ни ключи пользователя → приложение не подключается без Wi-Fi.
 *
 * Временный VPN не привязан к пользователю (общий UUID, белый SNI belkacar.ru) и
 * туннелирует трафик к нашему API. Поэтому, подняв временный туннель из этого вшитого
 * конфига, приложение делает API достижимым → можно войти и докачать реальные ключи.
 *
 * Приоритет при подключении к временному серверу: сеть (/app/temp, свежий) → кэш на
 * диске → этот вшитый конфиг. Вшитый может устареть (если сменится сервер/UUID/ключ
 * Reality), поэтому он именно резерв; свежий с сети всегда в приоритете и обновляет кэш.
 *
 * ВАЖНО: при смене параметров временного сервера (143.20.37.210 / UUID / reality) этот
 * конфиг тоже надо обновить — иначе на «глухих» сетях бутстрап будет вести на мёртвый
 * сервер. Источник истины — ответ GET /app/temp (sub_server.py).
 */
private const val BAKED_TEMP_SINGBOX_TEMPLATE =
    """{"log": {"level": "warn", "timestamp": true}, "dns": {"servers": [{"tag": "remote", "type": "udp", "server": "94.140.14.14", "detour": "proxy"}, {"tag": "local", "type": "udp", "server": "77.88.8.8"}], "rules": [], "final": "remote", "strategy": "ipv4_only"}, "outbounds": [{"type": "selector", "tag": "proxy", "outbounds": ["Только приложение и Telegram", "direct"], "default": "Только приложение и Telegram"}, {"type": "vless", "tag": "Только приложение и Telegram", "server": "143.20.37.210", "server_port": 443, "uuid": "843d28cc-3af2-4008-b624-3aecef713065", "flow": "xtls-rprx-vision", "packet_encoding": "xudp", "tls": {"enabled": true, "server_name": "belkacar.ru", "utls": {"enabled": true, "fingerprint": "qq"}, "reality": {"enabled": true, "public_key": "sbM9hBocbIxCo-trXcMTrojHF4Z4d1YXrgNSEMoSPwo", "short_id": "5dcf3b83"}}}, {"type": "direct", "tag": "direct"}], "route": {"rules": [{"action": "sniff"}, {"protocol": "dns", "action": "hijack-dns"}, {"domain_suffix": ["an.yandex.ru", "bs.yandex.ru", "awaps.yandex.ru", "yandexadexchange.net", "adfox.ru", "adfox.net", "mc.yandex.ru", "mc.yandex.com", "ad.mail.ru", "rs.mail.ru", "r.mradx.net", "top-fwz1.mail.ru", "dmg.mail.ru", "target.my.com", "ads.vk.com", "ad.vk.com", "adriver.ru", "adriver.net", "betweendigital.com", "buzzoola.com", "dsp.rambler.ru", "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "criteo.com", "criteo.net", "adnxs.com", "amazon-adsystem.com", "moatads.com", "scorecardresearch.com"], "action": "reject"}, {"network": "udp", "port": [443], "action": "reject"}, {"ip_is_private": true, "outbound": "direct"}], "final": "proxy", "auto_detect_interface": false, "default_domain_resolver": {"server": "local"}}, "inbounds": [{"type": "socks", "tag": "socks-in", "listen": "127.0.0.1", "listen_port": __SOCKS_PORT__}]}"""

/** Вшитый конфиг временного VPN с подставленным локальным SOCKS-портом. */
fun bakedTempSingboxConfig(socksPort: Int): String =
    BAKED_TEMP_SINGBOX_TEMPLATE.replace("__SOCKS_PORT__", socksPort.toString())
