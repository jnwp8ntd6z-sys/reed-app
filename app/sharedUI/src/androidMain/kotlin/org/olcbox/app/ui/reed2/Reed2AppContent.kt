package org.olcbox.app.ui.reed2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.olcbox.app.data.datasource.REED_ACCOUNT_SUBSCRIPTION_PREFIX
import org.olcbox.app.data.datasource.ReedTempServer
import org.olcbox.app.data.reed.AppNotification
import org.olcbox.app.data.reed.CodeKind
import org.olcbox.app.data.reed.DevicesResponse
import org.olcbox.app.data.reed.MembersResponse
import org.olcbox.app.data.reed.ReedApi
import org.olcbox.app.data.reed.ReedBuildFlags
import org.olcbox.app.data.reed.ReedLinks
import org.olcbox.app.data.reed.ReedSession
import org.olcbox.app.data.reed.SubscriptionItem
import org.olcbox.app.data.reed.SubscriptionResponse
import org.olcbox.app.data.reed.reedDeviceModel
import org.olcbox.app.data.reed.reedPlatformName
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.features.locations.PingsState
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

private const val BOT_URL = "https://t.me/ReedVPNbot"
private const val SUPPORT_URL = "https://t.me/reedvps"
private const val OLCCONF_BASE = "https://reedapp.ru/app/olcconf?token="
private const val LOCATIONS_BASE = "https://reedapp.ru/app/locations?token="
private const val BYTES_IN_GB = 1024.0 * 1024.0 * 1024.0

private enum class Stage { Login, Codes, App }

/**
 * Reed 2.0 — Android-корень. Связывает stateless-экраны reed2 с рабочим движком
 * (HomeScreenViewModel / LocationViewModel / ReedApi / ReedSession). Платформенная обвязка
 * (разрешение VPN, QR-камера, сплит-туннель по приложениям) приходит колбэками из
 * AndroidMainScreen — её код не меняется.
 */
@Composable
fun Reed2AppContent(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    onToggleClick: () -> Unit,
    onScanQr: (onResult: (String) -> Unit) -> Unit,
    onOpenAppsDirect: () -> Unit,
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val isRu = remember { isRuUser(context) }
    val showBot = ReedBuildFlags.showBotLinks && isRu
    val versionLabel = remember { appVersionLabel(context) }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val state by homeViewModel.state.collectAsState()
    val locations = locationViewModel.locations.toList()
    val pingsState = locationViewModel.pingsState
    val selectedId = locationViewModel.selectedLocationId

    var stage by rememberSaveable { mutableStateOf(if (ReedSession.onboardingDone) Stage.App else Stage.Login) }
    var codesReturn by rememberSaveable { mutableStateOf(Stage.Login) }
    var tab by rememberSaveable { mutableStateOf(ReedTab.Home) }
    var token by remember { mutableStateOf(ReedSession.token) }
    var sheet by remember { mutableStateOf<ReedSheetSpec?>(null) }
    var showNotifications by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(ReedSession.consentAccepted) }

    // ── Вход ──
    var code by remember { mutableStateOf("") }
    var codeName by remember { mutableStateOf("") }
    var codeError by remember { mutableStateOf<String?>(null) }
    var codeBusy by remember { mutableStateOf(false) }
    var tgBusy by remember { mutableStateOf(false) }
    var loginStatus by remember { mutableStateOf<String?>(null) }

    // ── Данные аккаунта ──
    var sub by remember { mutableStateOf<SubscriptionResponse?>(null) }
    var subLoaded by remember { mutableStateOf(false) }
    var subsList by remember { mutableStateOf<List<SubscriptionItem>>(emptyList()) }
    var notifications by remember { mutableStateOf<List<AppNotification>>(emptyList()) }
    var devices by remember { mutableStateOf<DevicesResponse?>(null) }
    var members by remember { mutableStateOf<MembersResponse?>(null) }
    var familyReload by remember { mutableStateOf(0) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var deviceCode by remember { mutableStateOf<String?>(null) }
    var memberBlocked by remember { mutableStateOf(false) }
    var newDevAck by remember { mutableStateOf(ReedSession.newDeviceAckMaxId) }
    var notifSeen by remember { mutableStateOf(ReedSession.notifSeenMaxId) }

    // ── Главная ──
    var network by remember {
        mutableStateOf(ReedSession.serverNetwork ?: (if (isCellularNow(context)) "cell" else "wifi"))
    }
    var refreshing by remember { mutableStateOf(false) }
    var sessionSeconds by remember { mutableStateOf(0L) }
    var importAttempt by remember { mutableStateOf(0) }
    var olcRefreshed by remember { mutableStateOf(false) }
    var autoPinged by remember { mutableStateOf(false) }
    var autoConnected by remember { mutableStateOf(false) }

    // ── Профиль: проверка сети ──
    var netRows by remember { mutableStateOf(defaultNetRows()) }
    var netChecking by remember { mutableStateOf(false) }
    var netCheckedAt by remember { mutableStateOf(0L) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowTick = System.currentTimeMillis() } }

    // ─────────────────────────── логика ───────────────────────────

    suspend fun importConfig(raw: String): String? = suspendCancellableCoroutine { cont ->
        homeViewModel.onImportFullConfig(
            rawText = raw,
            onComplete = { if (cont.isActive) cont.resume(null) },
            onError = { m -> if (cont.isActive) cont.resume(m.ifBlank { "Не удалось распознать ключ" }) },
        )
    }

    fun loginWith(t: String, member: Boolean, memberName: String?) {
        val switching = ReedSession.token != null && ReedSession.token != t
        if (switching && (state.isVpnConnected || state.isVpnLoading)) onToggleClick()
        ReedSession.token = t
        ReedSession.joinedViaCode = member
        ReedSession.memberName = memberName
        ReedSession.noCodeMode = false
        ReedSession.consentAccepted = true
        ReedSession.onboardingDone = true
        ReedSession.importedForToken = null
        if (switching) locationViewModel.deleteReedAccountLocations { locationViewModel.loadLocations { } }
        sub = null; subLoaded = false; devices = null; members = null; inviteCode = null; deviceCode = null
        memberBlocked = false
        token = t
        code = ""; codeName = ""; codeError = null
        tab = ReedTab.Home
        stage = Stage.App
    }

    fun doLogout() {
        if (state.isVpnConnected || state.isVpnLoading) onToggleClick()
        ReedSession.logout()
        locationViewModel.deleteReedAccountLocations { locationViewModel.loadLocations { } }
        token = null; sub = null; subLoaded = false; subsList = emptyList(); notifications = emptyList()
        devices = null; members = null; inviteCode = null; deviceCode = null; memberBlocked = false
        tab = ReedTab.Home
        stage = Stage.Login
    }

    // Вход по любому распознанному вводу. Возвращает текст ошибки или null.
    suspend fun submit(raw: String, name: String): String? {
        val input = raw.trim()
        val kind = CodeKind.detect(input) ?: return "Введи код или ссылку"
        return try {
            when (kind) {
                CodeKind.SubscriptionLink -> {
                    val err = importConfig(reedLocationsUrl(input) ?: input)
                    if (err != null) return "Не удалось добавить ключ. Проверь ссылку и попробуй ещё раз."
                    locationViewModel.loadLocations { homeViewModel.loadCurrentConfig() }
                    if (ReedSession.token == null) {
                        ReedSession.noCodeMode = true
                        ReedSession.consentAccepted = true
                        ReedSession.onboardingDone = true
                    }
                    code = ""; codeError = null
                    tab = ReedTab.Home
                    stage = Stage.App
                    null
                }
                CodeKind.AccountCode -> {
                    val r = ReedApi.codeLogin(input, locationViewModel.deviceHwid(),
                        deviceModel = reedDeviceModel(), deviceOs = reedPlatformName())
                    if (r.ok && !r.sub_token.isNullOrBlank()) { loginWith(r.sub_token, false, null); null }
                    else r.message ?: when (r.error) {
                        "not_found" -> "Код не найден"
                        "all_full", "no_slot" -> "На подписке нет свободных мест для устройства"
                        "no_subscription" -> "У аккаунта нет активной подписки"
                        else -> "Не удалось войти по коду"
                    }
                }
                CodeKind.FamilyInvite, CodeKind.DeviceCode -> {
                    val isDevice = kind == CodeKind.DeviceCode
                    val who = if (isDevice) reedDeviceModel().ifBlank { "Android-телефон" } else name.trim()
                    if (who.isBlank()) return "Напиши своё имя — его увидит владелец"
                    val r = ReedApi.shareRedeem(input, who, hwid = locationViewModel.deviceHwid(),
                        deviceModel = reedDeviceModel(), deviceOs = reedPlatformName())
                    val t = r.member_token ?: r.sub_token
                    if (r.ok && !t.isNullOrBlank()) {
                        val member = r.kind != "device"
                        loginWith(t, member, if (member) (r.name ?: who) else null); null
                    } else r.message ?: when (r.error) {
                        "not_found" -> "Код не найден"
                        "expired" -> "Код истёк"
                        "used" -> "Приглашение уже использовано"
                        else -> "Не удалось войти по коду"
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Нет связи с сервером. Попробуй ещё раз."
        }
    }

    fun submitCode() {
        if (codeBusy) return
        codeBusy = true; codeError = null
        scope.launch {
            codeError = submit(code, codeName)
            codeBusy = false
        }
    }

    fun openCodes(from: Stage) { codesReturn = from; codeError = null; stage = Stage.Codes }

    // QR: код/ссылка входят сразу; приглашению в семью нужно имя — ведём на экран ввода.
    fun scanAndEnter(from: Stage) {
        onScanQr { text ->
            val t = text.trim()
            if (t.isBlank()) return@onScanQr
            code = t
            codesReturn = from
            stage = Stage.Codes
            if (CodeKind.detect(t) != CodeKind.FamilyInvite) submitCode()
        }
    }

    fun startTelegram() {
        if (tgBusy) return
        tgBusy = true; loginStatus = null
        scope.launch {
            try {
                val start = ReedApi.authStart()
                uri.openUri(start.deeplink)
                repeat(60) {
                    delay(2000)
                    val poll = ReedApi.authPoll(start.nonce)
                    if (poll.status == "ok" && !poll.token.isNullOrBlank()) {
                        tgBusy = false
                        loginWith(poll.token, false, null)
                        return@launch
                    }
                }
                loginStatus = "Вход не завершён. Попробуй ещё раз."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                loginStatus = "Не удалось связаться с сервером. Попробуй ещё раз."
            }
            tgBusy = false
        }
    }

    suspend fun reloadSubscription() {
        val t = ReedSession.token ?: return
        val cached = ReedApi.cachedSubscription()
        if (sub == null && cached != null) { sub = cached; subLoaded = true }
        sub = try { ReedApi.subscription(t) } catch (e: CancellationException) { throw e } catch (e: Throwable) { sub ?: cached }
        subLoaded = true
    }

    suspend fun reloadNotifications() {
        val t = ReedSession.token ?: return
        notifications = try { ReedApi.notifications(t).notifications } catch (e: CancellationException) { throw e } catch (e: Throwable) { notifications }
    }

    fun ping() {
        locationViewModel.refreshPings(targetLocationIds = null,
            performPing = { cfg -> homeViewModel.performPingFor(cfg) })
    }

    fun refreshAll() {
        if (refreshing) return
        refreshing = true
        scope.launch { delay(12_000); refreshing = false }
        scope.launch {
            reloadSubscription()
            homeViewModel.refreshSubscriptions {
                locationViewModel.loadLocations {
                    homeViewModel.prewarmAllConfigs(force = true)
                    refreshing = false
                }
            }
        }
    }

    fun selectServer(s: ServerUi) {
        locationViewModel.selectLocation(s.key) {
            homeViewModel.loadCurrentConfig()
            homeViewModel.restartVpnIfRunning()
        }
    }

    fun runNetCheck() {
        if (netChecking) return
        netChecking = true
        scope.launch {
            val real = locations.filter { !ReedTempServer.isTemp(it.storageId) }
            val wifiLoc = real.firstOrNull { it.config?.isVless() == true && serverNetwork(it) == "wifi" }
            val cellLoc = real.firstOrNull { it.config?.isVless() == true && serverNetwork(it) == "cell" }
            val rows = coroutineScope {
                val w = async { wifiLoc?.config?.let { homeViewModel.performPingFor(it) } }
                val c = async { cellLoc?.config?.let { homeViewModel.performPingFor(it) } }
                val d = async { httpHeadMs("https://www.gosuslugi.ru/") }
                listOf(
                    netRow("Wi-Fi", wifiLoc != null, w.await()?.toInt()),
                    netRow("Мобильный", cellLoc != null, c.await()?.toInt()),
                    netRow("Прямое", true, d.await()),
                )
            }
            netRows = rows
            netCheckedAt = System.currentTimeMillis(); nowTick = netCheckedAt
            netChecking = false
        }
    }

    // ─────────────────────── фоновые эффекты ───────────────────────

    LaunchedEffect(token) { if (token != null) reloadSubscription() }
    LaunchedEffect(token) {
        val t = token ?: return@LaunchedEffect
        subsList = try { ReedApi.subscriptions(t).subscriptions } catch (e: CancellationException) { throw e } catch (e: Throwable) { emptyList() }
    }
    LaunchedEffect(token) {
        if (token == null) return@LaunchedEffect
        while (true) { reloadNotifications(); delay(5 * 60_000L) }
    }
    // Гейт участника: владелец мог приостановить или удалить доступ.
    LaunchedEffect(token) {
        val t = token ?: return@LaunchedEffect
        if (!ReedSession.joinedViaCode) return@LaunchedEffect
        while (true) {
            val s = ReedApi.session(t)
            when {
                s.kind == "deleted" -> { doLogout(); return@LaunchedEffect }
                s.kind == "member" -> memberBlocked = s.blocked || s.member_status == "blocked"
            }
            delay(60_000)
        }
    }
    // Импорт серверов аккаунта (olcRTC, затем VLESS — он становится сервером по умолчанию),
    // с повтором, пока серверы не появятся (плохая сеть / рестарт сервера).
    LaunchedEffect(token, locations.size, importAttempt) {
        val t = token ?: return@LaunchedEffect
        val hasReed = locations.any { it.subscriptionUrl?.startsWith(REED_ACCOUNT_SUBSCRIPTION_PREFIX) == true }
        if (hasReed) return@LaunchedEffect
        importConfig("$OLCCONF_BASE$t")
        val err = importConfig("$LOCATIONS_BASE$t")
        locationViewModel.loadLocations { homeViewModel.loadCurrentConfig() }
        if (err != null) {
            delay((3000L + importAttempt * 2000L).coerceAtMost(20_000L))
            importAttempt++
        }
    }
    // Раз за запуск освежаем olcRTC-конфиг (комнаты меняются на сервере).
    LaunchedEffect(token, locations.size, state.isVpnConnected) {
        val t = token ?: return@LaunchedEffect
        val hasReal = locations.any { !ReedTempServer.isTemp(it.storageId) }
        if (hasReal && !olcRefreshed && !state.isVpnConnected && !state.isVpnLoading) {
            olcRefreshed = true
            homeViewModel.onImportFullConfig(rawText = "$OLCCONF_BASE$t",
                onComplete = { locationViewModel.loadLocations { } }, onError = { })
        }
    }
    // Авто-пинг один раз + прогрев конфигов; автоподключение (если включено).
    LaunchedEffect(locations.size, state.canStartVpn, state.isVpnConnected) {
        val hasReal = locations.any { !ReedTempServer.isTemp(it.storageId) }
        if (hasReal && !autoPinged) {
            autoPinged = true
            ping()
            homeViewModel.prewarmAllConfigs()
        }
        if (ReedSession.autoConnect && !autoConnected && hasReal &&
            !state.isVpnConnected && !state.isVpnLoading && state.canStartVpn) {
            autoConnected = true
            onToggleClick()
        }
    }
    // Таймер сессии — от реального времени подъёма туннеля.
    LaunchedEffect(state.isVpnConnected) {
        if (state.isVpnConnected) {
            val fallback = System.currentTimeMillis()
            while (true) {
                val start = homeViewModel.vpnConnectedAtMillis() ?: fallback
                sessionSeconds = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
                delay(1000)
            }
        } else sessionSeconds = 0
    }
    // Семья: устройства и участники — при открытии вкладки и после действий.
    LaunchedEffect(token, tab, familyReload) {
        val t = token ?: return@LaunchedEffect
        if (tab != ReedTab.Family || ReedSession.joinedViaCode) return@LaunchedEffect
        devices = try { ReedApi.devices(t) } catch (e: CancellationException) { throw e } catch (e: Throwable) { devices }
        members = try { ReedApi.members(t) } catch (e: CancellationException) { throw e } catch (e: Throwable) { members }
    }

    // ─────────────────────── производные данные ───────────────────────

    val realLocations = locations.filter { !ReedTempServer.isTemp(it.storageId) }
    val servers = realLocations.map { loc ->
        val geo = serverGeo(loc)
        ServerUi(loc.storageId, geo.iso, geo.name, geo.city, pingOf(pingsState, loc.storageId), serverNetwork(loc))
    }
    val selected = servers.firstOrNull { it.key == selectedId }
    val connState = when {
        state.isVpnLoading -> ReedConnState.Connecting
        state.isVpnConnected -> ReedConnState.On
        else -> ReedConnState.Off
    }
    val subInfo = sub?.subscription
    val subActive = subInfo?.status == "active"
    val unread = (notifications.maxOfOrNull { it.id } ?: 0) > notifSeen
    val newDeviceAlert = notifications
        .filter { it.kind == "new_device" && it.id > newDevAck && it.deviceId != null }
        .maxByOrNull { it.id }

    // ─────────────────────────── экраны ───────────────────────────

    BackHandler(enabled = sheet != null || showNotifications || stage == Stage.Codes ||
        (stage == Stage.App && tab != ReedTab.Home)) {
        when {
            sheet != null -> sheet = null
            showNotifications -> showNotifications = false
            stage == Stage.Codes -> { if (!codeBusy) stage = codesReturn }
            else -> tab = ReedTab.Home
        }
    }

    Box(Modifier.fillMaxSize().background(Reed2.ground000)) {
        AnimatedContent(
            targetState = if (memberBlocked && stage == Stage.App) null else stage,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 8 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -it / 8 })
            },
            label = "stage",
        ) { st ->
            when (st) {
                null -> ReedBlockedScreen(onLogout = { doLogout() })
                Stage.Login -> ReedLoginScreen(
                    consent = consent,
                    onConsentChange = { consent = it; ReedSession.consentAccepted = it },
                    onOpenCodeEntry = { openCodes(Stage.Login) },
                    onScanQr = { scanAndEnter(Stage.Login) },
                    onContinueWithoutCode = {
                        ReedSession.noCodeMode = true
                        ReedSession.onboardingDone = true
                        tab = ReedTab.Home
                        stage = Stage.App
                    },
                    showTelegram = showBot,
                    onTelegram = { startTelegram() },
                    onOpenTerms = { uri.openUri(ReedLinks.TERMS_OF_SERVICE) },
                    onOpenPrivacy = { uri.openUri(ReedLinks.PRIVACY_POLICY) },
                    telegramBusy = tgBusy,
                    statusText = loginStatus,
                )
                Stage.Codes -> ReedCodeEntryScreen(
                    code = code,
                    onCodeChange = { new ->
                        val grewByPaste = new.length - code.length > 1
                        code = new; codeError = null
                        val kind = CodeKind.detect(new)
                        // Вставка из буфера входит сразу (ТЗ 4.2); приглашению сначала нужно имя.
                        if (grewByPaste && kind != null && kind != CodeKind.FamilyInvite) submitCode()
                    },
                    detected = CodeKind.detect(code),
                    hint = if (showBot) "Код приходит в Telegram-боте. Скан QR или вставка из буфера входят сразу."
                        else "Скан QR или вставка из буфера входят сразу, без кнопки.",
                    error = codeError,
                    onSubmit = { submitCode() },
                    onScanQr = { scanAndEnter(codesReturn) },
                    onBack = { stage = codesReturn },
                    busy = codeBusy,
                    name = codeName,
                    onNameChange = { codeName = it },
                )
                Stage.App -> ReedAppShell(selectedTab = tab, onSelectTab = { tab = it }) {
                    Crossfade(targetState = tab, animationSpec = tween(260), label = "tab") { t ->
                        when (t) {
                            ReedTab.Home -> {
                                if (token == null && realLocations.isEmpty()) {
                                    ReedHomeNoCodeScreen(
                                        cardText = noCodeCardText(showBot),
                                        onEnterCode = { openCodes(Stage.App) },
                                        onScanQr = { scanAndEnter(Stage.App) },
                                        onBell = { showNotifications = true },
                                    )
                                } else {
                                    val selIsCell = selected?.network == "cell"
                                    ReedHomeScreen(
                                        connState = connState,
                                        statusWord = when (connState) {
                                            ReedConnState.On -> "Подключено"
                                            ReedConnState.Connecting -> "Подключаюсь…"
                                            ReedConnState.Off -> "Не подключено"
                                        },
                                        timer = if (connState == ReedConnState.On) formatTimer(sessionSeconds) else "",
                                        locationSubtitle = selected?.let { s ->
                                            listOf(s.countryName, s.city).filter { it.isNotBlank() }.joinToString(" · ")
                                        } ?: "",
                                        subscription = if (token != null && subActive) subscriptionUi(sub!!) else null,
                                        subscriptionMessage = when {
                                            token == null -> null
                                            !subLoaded && sub == null -> "Загружаем подписку…"
                                            !subActive -> "Подписка закончилась"
                                            else -> null
                                        },
                                        subscriptionActionLabel = if (token != null && subLoaded && !subActive && showBot) "Продлить в боте" else null,
                                        onSubscriptionAction = { uri.openUri(BOT_URL) },
                                        onSubscriptionClick = if (subsList.size > 1) ({
                                            sheet = ReedSheetSpec(
                                                title = "Подписки аккаунта",
                                                subtitle = "Выбери, какую подписку использовать на этом устройстве",
                                                actions = subsList.map { item ->
                                                    val cur = item.current || item.sub_token == token
                                                    ReedSheetAction(
                                                        (item.plan_label ?: "Подписка") + " · ещё ${item.days_left} дн." +
                                                            if (cur) " · сейчас" else "",
                                                    ) {
                                                        sheet = null
                                                        if (!cur) loginWith(item.sub_token, ReedSession.joinedViaCode, ReedSession.memberName)
                                                    }
                                                },
                                            )
                                        }) else null,
                                        servers = servers,
                                        selectedKey = selectedId,
                                        network = network,
                                        hasUnread = unread,
                                        onToggleConnect = {
                                            if (state.isVpnConnected || state.isVpnLoading || state.canStartVpn) onToggleClick()
                                            else if (servers.isEmpty()) toast("Серверы ещё загружаются")
                                        },
                                        onSelectServer = { selectServer(it) },
                                        onSelectNetwork = { network = it; ReedSession.serverNetwork = it },
                                        onBell = { showNotifications = true },
                                        onPing = { ping() },
                                        onRefresh = { refreshAll() },
                                        errorText = if (state.connectionErrorMessage != null && connState == ReedConnState.Off)
                                            "Не удалось подключиться. Попробуй ещё раз или выбери другой сервер." else null,
                                        hintText = if (connState == ReedConnState.Connecting && selIsCell)
                                            "Мобильные серверы поднимаются чуть дольше — около 20 секунд" else null,
                                        refreshing = refreshing,
                                        pinging = pingsState is PingsState.Loading,
                                        emptyServersText = if (token != null) "Загружаем твои серверы…" else "Серверов пока нет",
                                    )
                                }
                            }
                            ReedTab.Family -> {
                                if (token == null) {
                                    ReedNoCodeTabScreen("СЕМЬЯ", noCodeCardText(showBot),
                                        onEnterCode = { openCodes(Stage.App) }, onScanQr = { scanAndEnter(Stage.App) })
                                } else {
                                    val dd = devices
                                    val ownerDevices = (dd?.devices ?: emptyList()) + (dd?.blocked ?: emptyList())
                                    ReedFamilyScreen(
                                        placesUsed = dd?.used ?: 0,
                                        placesTotal = dd?.limit ?: 0,
                                        alert = newDeviceAlert?.let { n ->
                                            NewDeviceAlertUi(n.deviceId ?: 0, "Новое устройство",
                                                listOf(n.body.ifBlank { "Подключилось новое устройство." },
                                                    relativeTime(n.created_at, nowTick)).filter { it.isNotBlank() }.joinToString(" · "))
                                        },
                                        members = buildList {
                                            add(MemberUi(0, "Ты", "Владелец · ${ownerDevices.size} ${plural(ownerDevices.size, "устройство", "устройства", "устройств")}",
                                                isOwnerRow = true))
                                            members?.members?.forEach { m ->
                                                add(MemberUi(m.id, m.name.ifBlank { "Участник" },
                                                    m.device.ifBlank { "Участник" },
                                                    statusText = if (m.blocked) "Приостановлен" else null,
                                                    statusKind = if (m.blocked) "danger" else "muted",
                                                    blocked = m.blocked))
                                            }
                                        },
                                        devices = ownerDevices.map { d ->
                                            DeviceUi(d.id, d.name.ifBlank { "Устройство" },
                                                if (d.blocked) "Заблокировано" else lastSeenLabel(d.last_seen, nowTick),
                                                deviceKind(d.os, d.type, d.name), d.blocked)
                                        },
                                        inviteCode = inviteCode,
                                        deviceCode = deviceCode,
                                        canManage = !ReedSession.joinedViaCode,
                                        onAlertItsMe = {
                                            newDeviceAlert?.let { newDevAck = it.id; ReedSession.newDeviceAckMaxId = it.id }
                                        },
                                        onAlertBlock = { devId ->
                                            val t = token ?: return@ReedFamilyScreen
                                            newDeviceAlert?.let { newDevAck = it.id; ReedSession.newDeviceAckMaxId = it.id }
                                            scope.launch {
                                                runCatching { ReedApi.deviceAction(t, devId, "toggle_block") }
                                                    .onSuccess { toast("Устройство заблокировано") }
                                                    .onFailure { toast("Не удалось заблокировать. Попробуй ещё раз.") }
                                                familyReload++
                                            }
                                        },
                                        onMemberClick = { m ->
                                            if (m.isOwnerRow) return@ReedFamilyScreen
                                            val t = token ?: return@ReedFamilyScreen
                                            fun act(action: String, done: String) {
                                                sheet = null
                                                scope.launch {
                                                    runCatching { ReedApi.memberAction(t, m.id, action) }
                                                        .onSuccess { toast(done) }.onFailure { toast("Не получилось. Попробуй ещё раз.") }
                                                    familyReload++
                                                }
                                            }
                                            sheet = ReedSheetSpec(
                                                title = m.name,
                                                subtitle = if (m.blocked) "Доступ приостановлен" else "Участник семьи",
                                                actions = listOf(
                                                    if (m.blocked) ReedSheetAction("Возобновить доступ") { act("unblock", "Доступ возобновлён") }
                                                    else ReedSheetAction("Приостановить доступ") { act("block", "Доступ приостановлен") },
                                                    ReedSheetAction("Удалить из семьи", danger = true) { act("delete", "Участник удалён") },
                                                ),
                                            )
                                        },
                                        onDeviceClick = { d ->
                                            val t = token ?: return@ReedFamilyScreen
                                            fun act(action: String, done: String) {
                                                sheet = null
                                                scope.launch {
                                                    runCatching { ReedApi.deviceAction(t, d.id, action) }
                                                        .onSuccess { toast(done) }.onFailure { toast("Не получилось. Попробуй ещё раз.") }
                                                    familyReload++
                                                }
                                            }
                                            sheet = ReedSheetSpec(
                                                title = d.name,
                                                subtitle = d.subtitle,
                                                actions = listOf(
                                                    ReedSheetAction(if (d.blocked) "Разблокировать" else "Заблокировать") {
                                                        act("toggle_block", if (d.blocked) "Устройство разблокировано" else "Устройство заблокировано")
                                                    },
                                                    ReedSheetAction("Удалить устройство", danger = true) { act("delete", "Устройство удалено") },
                                                ),
                                            )
                                        },
                                        onRequestInvite = {
                                            val t = token ?: return@ReedFamilyScreen
                                            if (inviteCode == null) scope.launch {
                                                val r = runCatching { ReedApi.shareCreate(t, "member") }.getOrNull()
                                                if (r?.ok == true && r.code.isNotBlank()) inviteCode = r.code
                                                else toast(if (r?.error == "members_cannot_share") "Приглашать может только владелец" else "Не удалось создать код")
                                            }
                                        },
                                        onRequestDevice = {
                                            val t = token ?: return@ReedFamilyScreen
                                            if (deviceCode == null) scope.launch {
                                                val r = runCatching { ReedApi.shareCreate(t, "device") }.getOrNull()
                                                if (r?.ok == true && r.code.isNotBlank()) deviceCode = r.code
                                                else toast("Не удалось создать код")
                                            }
                                        },
                                        onCopy = { c -> copyToClipboard(context, c); toast("Код скопирован") },
                                    )
                                }
                            }
                            ReedTab.Profile -> {
                                if (token == null) {
                                    ReedNoCodeTabScreen("ПРОФИЛЬ", noCodeCardText(showBot),
                                        onEnterCode = { openCodes(Stage.App) }, onScanQr = { scanAndEnter(Stage.App) },
                                        footer = versionLabel)
                                } else {
                                    val prof = sub?.profile
                                    val displayName = when {
                                        !prof?.username.isNullOrBlank() -> "@" + prof!!.username
                                        !prof?.name.isNullOrBlank() -> prof!!.name
                                        !ReedSession.memberName.isNullOrBlank() -> ReedSession.memberName!!
                                        else -> "Аккаунт"
                                    }
                                    var autoConnectUi by remember { mutableStateOf(ReedSession.autoConnect) }
                                    var ruDirectUi by remember { mutableStateOf(ReedSession.splitRouting) }
                                    ReedProfileScreen(
                                        username = displayName,
                                        subLabel = when {
                                            ReedSession.joinedViaCode -> "Участник семьи"
                                            subActive -> "Подписка активна ${untilLabel(subInfo?.expires_at)}".trim()
                                            subLoaded -> "Подписка закончилась"
                                            else -> "Загружаем подписку…"
                                        },
                                        lastCheckLabel = if (netCheckedAt == 0L) "" else relativeMillis(nowTick - netCheckedAt),
                                        netRows = netRows,
                                        autoConnect = autoConnectUi,
                                        ruDirect = ruDirectUi,
                                        version = versionLabel,
                                        showBotButtons = showBot && !ReedSession.joinedViaCode,
                                        onCheckNetwork = { runNetCheck() },
                                        onAutoConnect = { autoConnectUi = it; ReedSession.autoConnect = it },
                                        onRuDirect = {
                                            ruDirectUi = it; ReedSession.splitRouting = it
                                            homeViewModel.restartVpnIfRunning()
                                        },
                                        onServicesDirect = { onOpenAppsDirect() },
                                        onNotifications = { showNotifications = true },
                                        onSupport = { uri.openUri(SUPPORT_URL) },
                                        onTerms = { uri.openUri(ReedLinks.TERMS_OF_SERVICE) },
                                        onLoginOtherCode = { openCodes(Stage.App) },
                                        onLogout = {
                                            sheet = ReedSheetSpec("Выйти из аккаунта?",
                                                "Серверы аккаунта пропадут с этого устройства. Вернуться можно по коду.",
                                                listOf(ReedSheetAction("Выйти", danger = true) { sheet = null; doLogout() }))
                                        },
                                        onDeleteAccount = {
                                            sheet = ReedSheetSpec("Удалить аккаунт навсегда?",
                                                "Удалим аккаунт, подписки и все устройства. Отменить это нельзя.",
                                                listOf(ReedSheetAction("Удалить аккаунт", danger = true) {
                                                    sheet = null
                                                    val t = token
                                                    scope.launch {
                                                        val ok = t != null && runCatching { ReedApi.deleteAccount(t).ok }.getOrDefault(false)
                                                        if (ok) { toast("Аккаунт удалён"); doLogout() }
                                                        else toast("Не удалось удалить аккаунт. Попробуй ещё раз.")
                                                    }
                                                }))
                                        },
                                        onBotSubscription = { uri.openUri(BOT_URL) },
                                        onReferrals = { uri.openUri(BOT_URL) },
                                        servicesDirectLabel = "Приложения напрямую",
                                        servicesDirectSubtitle = "Выбери приложения, которые идут мимо туннеля",
                                        autoConnectSubtitle = "При запуске и после перезагрузки",
                                        onTitleLongPress = {
                                            homeViewModel.onShareLogs(
                                                onShared = { toast("Логи готовы") },
                                                onError = { toast("Не удалось выгрузить логи") },
                                            )
                                        },
                                        netChecking = netChecking,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Уведомления — поверх, въезжают справа.
        androidx.compose.animation.AnimatedVisibility(
            visible = showNotifications,
            enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(240)),
            exit = slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
        ) {
            LaunchedEffect(Unit) {
                val max = notifications.maxOfOrNull { it.id } ?: 0
                if (max > ReedSession.notifSeenMaxId) { ReedSession.notifSeenMaxId = max }
            }
            ReedNotificationsScreen(
                items = notifications.map { n ->
                    NotificationUi(n.id, n.title.ifBlank { "Уведомление" }, n.body,
                        relativeTime(n.created_at, nowTick), n.kind == "new_device", n.id > notifSeen)
                },
                onBack = { showNotifications = false; notifSeen = ReedSession.notifSeenMaxId },
                onOpen = { showNotifications = false; notifSeen = ReedSession.notifSeenMaxId; tab = ReedTab.Family },
            )
        }

        ReedSheetHost(spec = sheet, onDismiss = { sheet = null })
    }
}

// ─────────────────────────── вспомогательное ───────────────────────────

private data class Geo(val iso: String, val name: String, val city: String)

private val CITY_BY_ISO = mapOf(
    "DE" to "Франкфурт", "NL" to "Амстердам", "FI" to "Хельсинки", "TR" to "Стамбул",
    "SE" to "Стокгольм", "PL" to "Варшава", "US" to "Нью-Йорк", "RU" to "Москва",
    "CH" to "Цюрих", "FR" to "Париж", "GB" to "Лондон", "KZ" to "Алматы",
)

/** «🇩🇪 SMART-Германия» → DE / «Германия» / «Франкфурт». Флаг-эмодзи → ISO-2. */
private fun serverGeo(loc: LocationItem): Geo {
    val full = loc.fullName
    // Кодпоинты вручную (String.codePoints() только с API 24, а minSdk 23).
    val cps = ArrayList<Int>()
    var idx = 0
    while (idx < full.length) {
        val cp = Character.codePointAt(full, idx)
        cps += cp
        idx += Character.charCount(cp)
    }
    var iso = ""
    for (i in 0 until cps.size - 1) {
        val a = cps[i]; val b = cps[i + 1]
        if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
            iso = "${('A' + (a - 0x1F1E6))}${('A' + (b - 0x1F1E6))}"; break
        }
    }
    val name = full
        .filter { !Character.isSurrogate(it) }
        .replace(Regex("^[\\s\\p{So}\\uFE0F]+"), "")
        .replace(Regex("^(SMART|BRIDGE|LTE)[-\\s]+", RegexOption.IGNORE_CASE), "")
        .trim()
        .ifBlank { full.trim() }
    val isOlc = loc.config?.isVless() == false
    val city = when {
        isOlc -> "olcRTC"
        full.contains("BRIDGE", ignoreCase = true) -> "Через Москву"
        else -> CITY_BY_ISO[iso] ?: ""
    }
    return Geo(if (isOlc) "RTC" else (if (iso == "EU") "EU" else iso), name, city)
}

/** Список Wi-Fi (обычные) / Мобильный (olcRTC и LTE-обходы для мобильных белых списков). */
private fun serverNetwork(loc: LocationItem): String {
    if (loc.config?.isVless() == false) return "cell"
    val n = loc.fullName
    return if (n.contains("ЛТЕ", true) || n.contains("LTE", true)) "cell" else "wifi"
}

private fun pingOf(state: PingsState, id: String): Int? = when (state) {
    is PingsState.Success -> state.pings[id]
    is PingsState.Loading -> state.currentPings[id] ?: state.lastPings?.get(id)
    is PingsState.Error -> state.lastPings?.get(id)
    PingsState.Idle -> null
}

private fun subscriptionUi(r: SubscriptionResponse): SubscriptionUi {
    val used = r.traffic.used / BYTES_IN_GB
    val total = r.traffic.total / BYTES_IN_GB
    val days = r.subscription.days_left
    val parts = mutableListOf<String>()
    if (days > 0) parts += "Осталось $days ${plural(days.toInt(), "день", "дня", "дней")}"
    if (total <= 0) parts += "трафик без лимита"
    val lteTotal = r.lte.total_gb
    parts += if (lteTotal <= 0) "мобильный без лимита"
        else "мобильный ${fmtGb(r.lte.used_gb)} / ${fmtGb(lteTotal)} ГБ"
    return SubscriptionUi(true, untilLabel(r.subscription.expires_at), used, total,
        parts.joinToString(" · ").replaceFirstChar { it.uppercase() })
}

private fun fmtGb(v: Double): String {
    val r = (v * 10).toLong() / 10.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString().replace('.', ',')
}

private val MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля",
    "августа", "сентября", "октября", "ноября", "декабря")

/** «2026-10-15 12:00:00» → «до 15 октября» (год — только если не текущий). */
private fun untilLabel(expiresAt: String?): String {
    val d = parseServerTime(expiresAt) ?: return ""
    val cal = Calendar.getInstance().apply { timeInMillis = d }
    val nowYear = Calendar.getInstance().get(Calendar.YEAR)
    val y = cal.get(Calendar.YEAR)
    return "до ${cal.get(Calendar.DAY_OF_MONTH)} ${MONTHS_GEN[cal.get(Calendar.MONTH)]}" + if (y != nowYear) " $y" else ""
}

private fun parseServerTime(s: String?, utc: Boolean = false): Long? {
    if (s.isNullOrBlank()) return null
    for (p in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd")) {
        try {
            val f = SimpleDateFormat(p, Locale.US)
            if (utc) f.timeZone = TimeZone.getTimeZone("UTC")
            return f.parse(s.take(19))?.time
        } catch (e: Throwable) { }
    }
    return null
}

/** created_at из SQLite datetime('now') — это UTC. */
private fun relativeTime(createdAt: String, now: Long): String {
    val t = parseServerTime(createdAt, utc = true) ?: return ""
    return relativeMillis(now - t)
}

private fun relativeMillis(diff: Long): String {
    val min = diff / 60_000
    return when {
        min < 1 -> "только что"
        min < 60 -> "$min мин назад"
        min < 24 * 60 -> "${min / 60} ч назад"
        min < 48 * 60 -> "вчера"
        else -> "${min / (24 * 60)} ${plural((min / (24 * 60)).toInt(), "день", "дня", "дней")} назад"
    }
}

private fun lastSeenLabel(lastSeen: String, now: Long): String {
    val t = parseServerTime(lastSeen, utc = true) ?: return "Подключено"
    val diffMin = (now - t) / 60_000
    return when {
        diffMin < 24 * 60 -> "Сегодня"
        diffMin < 48 * 60 -> "Вчера"
        else -> "Был ${relativeMillis(now - t)}"
    }
}

private fun deviceKind(os: String, type: String, name: String): String {
    val s = "$os $type $name".lowercase()
    return when {
        listOf("windows", "mac", "linux", "ноутбук", "laptop", "desktop", "pc").any { it in s } -> "laptop"
        else -> "phone"
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

private fun formatTimer(sec: Long): String {
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

private fun noCodeCardText(showBot: Boolean): String =
    if (showBot) "Вставь код из Telegram-бота, ключ подписки или приглашение от владельца семьи."
    else "Вставь код, ключ подписки или приглашение от владельца семьи."

private fun defaultNetRows() = listOf(
    NetCheckRowUi("Wi-Fi", null, "Не проверено", "muted"),
    NetCheckRowUi("Мобильный", null, "Не проверено", "muted"),
    NetCheckRowUi("Прямое", null, "Не проверено", "muted"),
)

private fun netRow(label: String, hasServer: Boolean, ms: Int?): NetCheckRowUi = when {
    !hasServer -> NetCheckRowUi(label, null, "Нет сервера", "muted")
    ms == null || ms < 0 -> NetCheckRowUi(label, null, "Не отвечает", "danger")
    ms >= 400 -> NetCheckRowUi(label, ms, "Ограничено", "warn")
    else -> NetCheckRowUi(label, ms, "Работает", "ok")
}

private suspend fun httpHeadMs(url: String): Int? = withContext(Dispatchers.IO) {
    try {
        val t0 = System.nanoTime()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"; connectTimeout = 5000; readTimeout = 5000; instanceFollowRedirects = false
        }
        c.responseCode
        c.disconnect()
        ((System.nanoTime() - t0) / 1_000_000).toInt()
    } catch (e: Throwable) { null }
}

/** Reed-ссылка подписки …/sub/<token> → /app/locations?token= (иначе null — импорт как есть). */
private fun reedLocationsUrl(raw: String): String? {
    val t = raw.trim()
    if ((!t.contains("reedapp.ru") && !t.contains("reed-vpn.duckdns.org")) || !t.contains("/sub/")) return null
    val token = t.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
    if (token.length !in 8..64 || !token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
    return "https://reedapp.ru/app/locations?token=$token"
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Reed", text))
}

private fun isCellularNow(context: Context): Boolean = try {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
} catch (e: Throwable) { false }

private val RU_ZONES = setOf(
    "Europe/Moscow", "Europe/Kaliningrad", "Europe/Samara", "Europe/Volgograd", "Europe/Saratov",
    "Europe/Ulyanovsk", "Europe/Astrakhan", "Europe/Kirov", "Asia/Yekaterinburg", "Asia/Omsk",
    "Asia/Novosibirsk", "Asia/Barnaul", "Asia/Tomsk", "Asia/Novokuznetsk", "Asia/Krasnoyarsk",
    "Asia/Irkutsk", "Asia/Chita", "Asia/Yakutsk", "Asia/Khandyga", "Asia/Vladivostok", "Asia/Ust-Nera",
    "Asia/Magadan", "Asia/Sakhalin", "Asia/Srednekolymsk", "Asia/Kamchatka", "Asia/Anadyr",
)

/** ТЗ 7.8: Россия — по SIM; без SIM — по локали и часовому поясу. */
private fun isRuUser(context: Context): Boolean {
    val sim = try {
        (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso.orEmpty()
    } catch (e: Throwable) { "" }
    if (sim.isNotBlank()) return sim.equals("ru", ignoreCase = true)
    if (Locale.getDefault().country.equals("RU", ignoreCase = true)) return true
    return TimeZone.getDefault().id in RU_ZONES
}

private fun appVersionLabel(context: Context): String = try {
    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    "Reed ${pi.versionName ?: ""} · сборка $code".replace("  ", " ")
} catch (e: Throwable) { "Reed" }
