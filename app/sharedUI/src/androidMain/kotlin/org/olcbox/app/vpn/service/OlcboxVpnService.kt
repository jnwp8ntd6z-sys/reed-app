package org.olcbox.app.vpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import org.olcbox.app.data.TUN2SOCKS_CONFIG_FILE_NAME
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.datasource.ReedTempServer
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.vpn.AndroidConnectionMode
import org.olcbox.app.vpn.SingBoxTunnel
import org.olcbox.app.vpn.AndroidSocksProxySettings
import org.olcbox.app.vpn.AndroidSplitTunnelMode
import org.olcbox.app.vpn.UpstreamCandidate
import org.olcbox.app.vpn.UpstreamNetworkSelector
import org.olcbox.app.vpn.UpstreamTransport
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.data.KEY_ANDROID_CONNECTION_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_MODE
import org.olcbox.app.vpn.data.KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_HOST
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PASSWORD
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_PORT
import org.olcbox.app.vpn.data.KEY_ANDROID_SOCKS_USERNAME
import org.olcbox.app.vpn.data.vpnPrefDataStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OlcboxVpnService : VpnService() {

    private external fun startTun2socksNative(configPath: String, fd: Int): Int
    private external fun stopTun2socksNative()
    private external fun getTun2socksStatsNative(): LongArray

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val tunnelMutex = Mutex()
    private val repository: LocationsRepository by lazy {
        LocationsRepositoryImpl(LocationsDataSourceImpl(applicationContext))
    }
    private val deviceIdentityProvider by lazy {
        PersistentDeviceIdentityProvider(LocationsDataSourceImpl(applicationContext))
    }

    private var startupJob: Job? = null
    private var watchdogJob: Job? = null
    private var cleanupJob: Job? = null
    private var networkLossJob: Job? = null
    private var recoveryJob: Job? = null
    // Отложенная проверка после ICE «disconnected»: даём WebRTC шанс восстановиться сам;
    // если за RTC_DISCONNECT_GRACE_MS не вернулся «connected» — пересобираем туннель.
    private var rtcDisconnectRecoveryJob: Job? = null
    private var reconnectAttempt = 0
    private var generation = 0L
    private var recoveryRequestedForGeneration = 0L
    private var watchdogTunStats: Tun2SocksStats? = null
    private var watchdogStalledSamples = 0

    // Учёт трафика временного VPN (reed-temp, лимит 5 ГБ на устройство).
    private var activeLocationId: String? = null
    private var tempSessionStartCumulative = -1L
    private var tempUsedAtSessionStart = 0L
    private var lastWakeLockRefreshAtMs = 0L
    @Volatile
    private var lastRtcConnectedAtMs = 0L
    @Volatile
    private var lastRtcFailureAtMs = 0L
    @Volatile
    private var rtcFailureCount = 0
    @Volatile
    private var lastMobileProvider: String? = null
    @Volatile
    private var lastMobileRoom: String? = null
    @Volatile
    private var lastJitsiStopCompletedAtMs = 0L
    @Volatile
    private var lastJitsiStoppedRoom: String? = null
    // Активен ли VLESS-движок (sing-box) вместо olcRTC для текущей сессии. Транспортная
    // абстракция: transportRunning()/stopTransport() ниже выбирают нужный движок.
    @Volatile
    private var vlessActive = false
    // olcRTC со split-routing: одновременно работают sing-box-роутер (на основном порту) и
    // движок olcRTC (на внутреннем порту). Нужно знать это для корректной остановки обоих.
    private var olcSplitActive = false
    // Для правки sing-box-конфига olcRTC (вставка кредов в socks-outbound).
    private val olcConfigJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tun2socksThread: Thread? = null
    @Volatile
    private var tun2socksStarted = false
    @Volatile
    private var tun2socksStopRequested = false

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private var currentNetwork: Network? = null
    private var currentNetworkTransport: UpstreamTransport? = null
    private var isCallbackRegistered = false
    private var connectionMode = AndroidConnectionMode.Tun
    private var socksListenHost = AndroidSocksProxySettings.DEFAULT_HOST
    private var socksListenPort = AndroidSocksProxySettings.DEFAULT_PORT
    private var socksUsername = ""
    private var socksPassword = ""
    private var splitTunnelMode = AndroidSplitTunnelMode.AllApps
    private var splitTunnelProxyApps = emptySet<String>()
    private var splitTunnelBypassApps = emptySet<String>()
    private var socksProxy: AuthenticatedSocksProxy? = null

    private data class StartOptions(
        val connectionMode: AndroidConnectionMode,
        val socksListenHost: String,
        val socksListenPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val splitTunnelMode: AndroidSplitTunnelMode,
        val splitTunnelProxyApps: Set<String>,
        val splitTunnelBypassApps: Set<String>
    )

    private data class Tun2SocksStats(
        val txPackets: Long,
        val txBytes: Long,
        val rxPackets: Long,
        val rxBytes: Long
    )

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(network, "Available")
        }

        override fun onLost(network: Network) {
            addLog("Network lost")
            if (network != currentNetwork) return

            networkLossJob?.cancel()
            networkLossJob = scope.launch {
                delay(NETWORK_LOSS_GRACE_MS)
                if (network != currentNetwork) return@launch

                val upstream = findActiveUpstreamNetwork()
                if (upstream != null) {
                    handleNetworkChange(upstream, "Fallback")
                    return@launch
                }

                if (OlcboxVpnState.status.value is VpnStatus.Connected ||
                    OlcboxVpnState.status.value is VpnStatus.Reconnecting
                ) {
                    updateUnderlyingNetwork(null)
                    unbindProcessFromNetwork()
                    setStatus(VpnStatus.Reconnecting)
                    updateNotification("Waiting for network...")
                    addLog("Waiting for upstream network")
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network == currentNetwork || caps.isUsableUpstream()) {
                handleNetworkChange(network, "Capabilities")
            }
        }

        private fun handleNetworkChange(network: Network, reason: String) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return
            if (!caps.isUsableUpstream()) return
            networkLossJob?.cancel()

            val upstream = findActiveUpstreamNetwork() ?: return
            if (currentNetwork == upstream) {
                if (OlcboxVpnState.status.value is VpnStatus.Reconnecting &&
                    startupJob?.isActive != true
                ) {
                    addLog("Network $reason: ${getNetName(upstream)}")
                    requestTransportRecovery(
                        reason = "Network available",
                        fullRestart = false,
                        delayMs = NETWORK_STABILITY_GRACE_MS
                    )
                }
                return
            }

            val previousTransport = currentNetworkTransport
            val nextTransport = upstream.transportOrNull()
            updateUnderlyingNetwork(upstream)
            addLog("Network $reason: ${getNetName(upstream)}")

            when (OlcboxVpnState.status.value) {
                is VpnStatus.Connected -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Keeping transport on refreshed Wi-Fi network")
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS,
                            setReconnectingImmediately = false
                        )
                    }
                }

                is VpnStatus.Reconnecting -> {
                    if (isBenignWifiRefresh(previousTransport, nextTransport) &&
                        transportRunning() &&
                        canReconnectTransportInPlace()
                    ) {
                        setStatus(VpnStatus.Connected)
                        updateNotification(connectedNotificationText())
                        startWatchdog()
                    } else {
                        requestTransportRecovery(
                            reason = "Upstream network changed",
                            fullRestart = false,
                            delayMs = NETWORK_STABILITY_GRACE_MS
                        )
                    }
                }

                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Olcbox::VpnWakeLock")
            .apply { setReferenceCounted(false) }

        installMobileCallbacks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OlcboxVpnActions.ACTION_STOP_VPN -> {
                addLog("Stop VPN requested")
                cleanup()
                return START_NOT_STICKY
            }

            OlcboxVpnActions.ACTION_START_VPN -> Unit
            else -> {
                cleanup()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // TUN-сигнатура до применения новых опций: если параметры, влияющие на
        // сам TUN-интерфейс (режим, SOCKS-порт/хост, split-tunnel), не изменились,
        // смену сервера можно выполнить бесшовно (in-place), не пересоздавая туннель.
        val previousTunSignature = tunBuildSignature()
        applyStartOptions(loadStartOptions(intent))
        val isRestart = shouldRestartForStartCommand()
        val allowInPlaceRestart = isRestart && tunBuildSignature() == previousTunSignature
        if (isRestart) {
            addLog("Restarting ${activeModeLabel()} for selected location")
        }
        startForeground(
            if (connectionMode == AndroidConnectionMode.Proxy) {
                "Starting proxy..."
            } else {
                "Protecting your connection"
            }
        )
        startTunnel(
            isMigration = false,
            isRestart = isRestart,
            allowInPlaceRestart = allowInPlaceRestart
        )
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup(stopService = false)
    }

    override fun onRevoke() {
        addLog("VPN permission revoked")
        cleanup()
        stopSelf()
        super.onRevoke()
    }

    private fun installMobileCallbacks() {
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                if (connectionMode == AndroidConnectionMode.Proxy) return true
                return this@OlcboxVpnService.protect(fd.toInt())
            }
        })
        Mobile.setProviders()
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String) {
                val line = msg.trimEnd()
                addLog("rtc: $line")
                Log.v("olcrtc", line)
                handleRtcLine(line)
            }
        })
    }

    private fun loadStartOptions(intent: Intent): StartOptions {
        val preferences = runCatching {
            runBlocking { applicationContext.vpnPrefDataStore.data.first() }
        }.getOrNull()

        val socksPort = if (intent.hasExtra(OlcboxVpnActions.EXTRA_SOCKS_PORT)) {
            intent.getIntExtra(
                OlcboxVpnActions.EXTRA_SOCKS_PORT,
                AndroidSocksProxySettings.DEFAULT_PORT
            )
        } else {
            preferences?.get(KEY_ANDROID_SOCKS_PORT)
        }

        return StartOptions(
            connectionMode = AndroidConnectionMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_CONNECTION_MODE)
                    ?: preferences?.get(KEY_ANDROID_CONNECTION_MODE)
            ),
            socksListenHost = AndroidSocksProxySettings.sanitizeHost(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_HOST)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_HOST)
            ),
            socksListenPort = AndroidSocksProxySettings.sanitizePort(socksPort),
            socksUsername = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_USERNAME)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_USERNAME)
                ).orEmpty().takeIf { it.isNotBlank() }.orEmpty(),
            socksPassword = (
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SOCKS_PASSWORD)
                    ?: preferences?.get(KEY_ANDROID_SOCKS_PASSWORD)
                ).orEmpty(),
            splitTunnelMode = AndroidSplitTunnelMode.fromValue(
                intent.getStringExtra(OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_MODE)
                    ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_MODE)
            ),
            splitTunnelProxyApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_PROXY_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_PROXY_APPS).orEmpty(),
            splitTunnelBypassApps = intent.stringCollectionExtra(
                OlcboxVpnActions.EXTRA_SPLIT_TUNNEL_BYPASS_APPS
            ) ?: preferences?.get(KEY_ANDROID_SPLIT_TUNNEL_BYPASS_APPS).orEmpty()
        )
    }

    private fun applyStartOptions(options: StartOptions) {
        connectionMode = options.connectionMode
        socksListenHost = options.socksListenHost
        socksListenPort = options.socksListenPort
        socksUsername = options.socksUsername
        socksPassword = options.socksPassword
        splitTunnelMode = options.splitTunnelMode
        splitTunnelProxyApps = options.splitTunnelProxyApps
        splitTunnelBypassApps = options.splitTunnelBypassApps
    }

    private fun Intent.stringCollectionExtra(key: String): Set<String>? {
        @Suppress("DEPRECATION")
        val value = extras?.get(key) ?: return null
        val items = when (value) {
            is ArrayList<*> -> value.asSequence()
            is Set<*> -> value.asSequence()
            is Array<*> -> value.asSequence()
            else -> return emptySet()
        }
        return items
            .mapNotNull { (it as? String)?.trim()?.takeIf { item -> item.isNotBlank() } }
            .toSet()
    }

    private fun startTunnel(
        isMigration: Boolean,
        forceFullRestart: Boolean = false,
        isRestart: Boolean = false,
        allowInPlaceRestart: Boolean = false
    ) {
        val previousStartupJob = startupJob
        val hadPendingStartup = previousStartupJob?.isActive == true
        previousStartupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        if (hadPendingStartup) {
            // ВАЖНО: не останавливаем движок здесь синхронно. Предыдущий запуск мог быть
            // в нативном Mobile.startWithTransport (он выполняется ПОД tunnelMutex, но ВНЕ
            // его сейчас) — одновременный вызов Mobile.stop() из этого потока с нативным
            // start в другом валит приложение при смене сервера «во время подключения»
            // (краш LTE→LTE). Очистку и перезапуск сделает новый startupJob под tunnelMutex,
            // где start/stop сериализованы и безопасны. Новый job ниже сначала дождётся
            // завершения предыдущего (его нативный вызов раскрутится), затем заберёт замок.
            addLog("Canceling pending start; cleanup will run under tunnel lock")
        }
        if (!isMigration) {
            resetRecoveryState()
        }
        val requestedGeneration = ++generation
        refreshWakeLock(force = true)

        startupJob = scope.launch {
            try {
                cleanupJob?.takeIf { it.isActive }?.let {
                    addLog("Waiting for previous olcRTC cleanup")
                    val completed = withTimeoutOrNull(PREVIOUS_STOP_WAIT_MS) {
                        it.join()
                        true
                    } ?: false

                    if (!completed) {
                        addLog("Previous olcRTC cleanup is still pending; forcing transport cleanup")
                        it.cancel()
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                    }
                }

                if (!isMigration) {
                    registerNetworkMonitor()
                    updateUnderlyingNetwork(findActiveUpstreamNetwork())
                }

                tunnelMutex.withLock {
                    coroutineContext.ensureActive()
                    if (requestedGeneration != generation) return@withLock

                    val active = repository.getActiveLocation()
                    val location = active?.location?.normalized()
                    if (location == null || !location.isComplete()) {
                        setStatus(VpnStatus.Error("No active location"))
                        updateNotification("Add a location first")
                        stopTransportProcesses(closeTun = true, waitForSocksPort = false)
                        return@withLock
                    }

                    // Бесшовная смена сервера: при переключении на другую локацию
                    // (isRestart) НЕ закрываем TUN-интерфейс и tun2socks, а только
                    // перезапускаем движок на новый сервер — переключение почти
                    // мгновенное, без разрыва «отключился-подключился».
                    if (((isMigration) || (isRestart && allowInPlaceRestart)) &&
                        !forceFullRestart &&
                        canReconnectTransportInPlace()
                    ) {
                        if (isRestart) addLog("Seamless server switch (keeping tunnel)")
                        reconnectTransport(location, requestedGeneration)
                    } else {
                        startFullTunnel(location, requestedGeneration, isMigration, isRestart)
                    }
                }
            } finally {
                if (requestedGeneration == generation) {
                    releaseWakeLock()
                }
            }
        }
    }

    private suspend fun reconnectTransport(location: LocationConfig, requestedGeneration: Long) {
        setStatus(VpnStatus.Reconnecting)
        updateNotification("Reconnecting...")
        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            updateNotification("Waiting for network...")
            addLog("No upstream network; keeping tunnel alive")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }

        updateUnderlyingNetwork(upstream)
        stopMobileAndWait()
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (startMobile(location, upstream, requestedGeneration, setErrorOnFailure = false)) {
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("${activeModeLabel()} transport reconnected")
            startWatchdog()
        } else {
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Waiting for transport...")
            scheduleTransportRetry(requestedGeneration, "transport reconnect failed")
        }
    }

    private suspend fun startFullTunnel(
        location: LocationConfig,
        requestedGeneration: Long,
        isMigration: Boolean,
        isRestart: Boolean
    ) {
        setStatus(if (isMigration || isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)
        updateNotification("Connecting...")
        stopTransportProcesses(closeTun = true, waitForSocksPort = true)
        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        val upstream = findActiveUpstreamNetwork()
        if (upstream == null) {
            // Сети сейчас нет (полностью офлайн). VPN физически не поднимется без сети,
            // но ведём себя как Happ: НЕ висим молча и НЕ выкидываем ошибку, а переходим в
            // «Ожидание сети» и АВТОМАТИЧЕСКИ подключаемся, как только сеть появится
            // (через networkCallback + периодический ретрай). Юзеру не нужно жать заново.
            updateUnderlyingNetwork(null)
            unbindProcessFromNetwork()
            addLog("Нет сети — подключусь автоматически, когда она появится")
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Ожидание сети…")
            scheduleTransportRetry(requestedGeneration, "no upstream network", NETWORK_RETRY_BASE_DELAY_MS)
            return
        }
        updateUnderlyingNetwork(upstream)

        if (!startMobile(location, upstream, requestedGeneration, setErrorOnFailure = false)) {
            // Как Happ: НЕ сдаёмся с ошибкой после первой же неудачи (из-за этого
            // приходилось «перезаходить»/жать подключение заново), а уходим в
            // «Переподключение» и автоматически пробуем снова с нарастающей паузой.
            // Юзеру ничего жать не нужно — подключится само, когда канал поднимется.
            if (requestedGeneration != generation) return
            updateUnderlyingNetwork(null)
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Переподключение…")
            scheduleTransportRetry(requestedGeneration, "transport start failed")
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        if (connectionMode == AndroidConnectionMode.Proxy) {
//            if (!startAuthenticatedSocksProxy()) {
//                stopTransportProcesses(closeTun = true)
//                return
//            }
            setStatus(VpnStatus.Connected)
            resetRecoveryState()
            updateNotification(connectedNotificationText())
            addLog("Proxy mode connected on SOCKS $socksListenHost:$socksListenPort")
            startWatchdog()
            return
        }

        delay(TUNNEL_HANDOFF_DELAY_MS)
        coroutineContext.ensureActive()

        val pfd = establishSystemVpnTunnel()
        if (pfd == null) {
            stopMobileAndWait()
            return
        }

        vpnInterface = pfd
        if (!startTun2socks(pfd)) {
            stopTransportProcesses(closeTun = true)
            return
        }

        coroutineContext.ensureActive()
        if (requestedGeneration != generation) return

        setStatus(VpnStatus.Connected)
        resetRecoveryState()
        updateNotification(connectedNotificationText())
        addLog("VPN tunnel established")
        startWatchdog()
    }

    private suspend fun startMobile(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val keepProcessBound = shouldKeepProcessBound(upstream)
        val config = location.normalized()
        // Запоминаем активную локацию для учёта трафика временного VPN. Сбрасываем
        // базовую точку счётчика — она возьмётся из первой выборки статистики.
        if (activeLocationId != config.id) {
            activeLocationId = config.id
            tempSessionStartCumulative = -1L
        }
        if (config.engine == LocationConfig.ENGINE_VLESS) {
            return startVless(config, upstream, requestedGeneration, setErrorOnFailure)
        }
        return try {
            installMobileCallbacks()
            val targetSocksPort = socksListenPort
            val deviceId = deviceIdentityProvider.hwid()
            resetRtcHealthState()
            olcSplitActive = false

            // SPLIT-ROUTING для olcRTC: ставим sing-box ПЕРЕД движком olcRTC. РФ-домены/РФ-IP
            // идут direct (банки/госуслуги видят настоящий РФ-IP → не палят VPN), остальное —
            // в olcRTC-туннель. Конфиг тянем CACHE-FIRST: на «зарезанных» сетях с белыми
            // списками (где и нужен olcRTC) наш API недоступен, поэтому работаем по кэшу.
            // Если конфига нет совсем (ни разу не качали + API недоступен) — ТИХО откатываемся
            // на прямой olcRTC (как было), чтобы не сломать подключение.
            val splitWanted = org.olcbox.app.data.reed.ReedSession.splitRouting &&
                config.id != ReedTempServer.LOCATION_ID
            val olcSplitConfig =
                if (splitWanted) fetchOlcSingboxConfig(targetSocksPort, OLCRTC_INTERNAL_SOCKS_PORT)
                else null
            val useSplit = olcSplitConfig != null
            // В split-режиме движок olcRTC слушает ВНУТРЕННИЙ порт, а tun2socks ходит на
            // sing-box (targetSocksPort). Без split — движок прямо на targetSocksPort, как было.
            val mobilePort = if (useSplit) OLCRTC_INTERNAL_SOCKS_PORT else targetSocksPort

            waitForSocksPortReleased(targetSocksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            if (useSplit) {
                waitForSocksPortReleased(mobilePort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
                if (isLocalSocksPortOpen(mobilePort)) {
                    throw IllegalStateException("olcRTC internal port $mobilePort is still in use")
                }
            }
            waitForJitsiRoomCleanup(config.bypassProvider, config.id)
            bindProcessToNetwork(upstream, "Bound to ${getNetName(upstream)}")
            configureMobileTransport(config)
            addLog(
                "Starting olcRTC provider=${config.bypassProvider}, " +
                    "transport=${config.transport}, room=${config.id}" +
                    if (useSplit) " (split-routing → :$mobilePort)" else ""
            )
            lastMobileProvider = config.bypassProvider
            lastMobileRoom = config.id
            Mobile.startWithTransport(
                config.bypassProvider,
                config.transport,
                config.id,
                deviceId,
                config.key,
                mobilePort.toLong(),
                socksUsername,
                socksPassword
            )
            Mobile.waitReady(MOBILE_READY_TIMEOUT_MS)
            if (requestedGeneration != generation) {
                addLog("olcRTC start superseded")
                return false
            }
            coroutineContext.ensureActive()

            if (useSplit) {
                // sing-box-роутер на targetSocksPort: РФ → direct, остальное → olcRTC:$mobilePort.
                // ВАЖНО: локальный SOCKS движка olcRTC поднят с логином/паролем (requiresAuth),
                // поэтому в socks-outbound sing-box ОБЯЗАТЕЛЬНО прокидываем те же креды — иначе
                // sing-box не пройдёт авторизацию у olcRTC и трафик/DNS не пойдут (туннель
                // «подключён», но ничего не открывается).
                val splitConfigWithAuth =
                    injectOlcProxyAuth(olcSplitConfig!!, socksUsername, socksPassword)
                addLog("Starting split-routing (sing-box) on $socksListenHost:$targetSocksPort")
                SingBoxTunnel.start(splitConfigWithAuth)
                olcSplitActive = true
                val deadline = System.currentTimeMillis() + MOBILE_READY_TIMEOUT_MS
                var ready = false
                while (System.currentTimeMillis() < deadline) {
                    coroutineContext.ensureActive()
                    if (requestedGeneration != generation) {
                        addLog("olcRTC split start superseded")
                        return false
                    }
                    if (isLocalSocksPortOpen(targetSocksPort)) { ready = true; break }
                    delay(SOCKS_RELEASE_POLL_MS)
                }
                if (!ready) throw IllegalStateException("olcRTC split SOCKS not ready")
            }

            addLog("olcRTC ready on $socksListenHost:$targetSocksPort")
            markRtcConnected()
            if (keepProcessBound || useSplit) {
                addLog("Keeping olcRTC bound to ${getNetName(upstream)}")
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("olcRTC start canceled")
                unbindProcessFromNetwork()
                stopMobileAndWait()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "Transport failed"
            if (staleRequest) {
                addLog("olcRTC start canceled: $message")
            } else {
                addLog("olcRTC start failed: $message")
            }
            unbindProcessFromNetwork()
            stopMobileAndWait()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification("Connection failed")
            }
            false
        } finally {
            // В split-режиме держим процесс привязанным к upstream: sing-box «direct» (РФ)
            // должен ходить мимо TUN. Поэтому при olcSplitActive не отвязываем, пока жив движок.
            if ((!keepProcessBound && !olcSplitActive) || !Mobile.isRunning()) {
                unbindProcessFromNetwork()
            }
        }
    }

    /**
     * Запуск VLESS-транспорта (sing-box) вместо olcRTC. Поднимает встроенное ядро на тот
     * же ЛОКАЛЬНЫЙ SOCKS5 (socksListenPort), что потом использует tun2socks — дальнейший
     * путь (establishSystemVpnTunnel + startTun2socks) идентичен olcRTC.
     *
     * Сокеты sing-box должны обходить TUN, иначе петля. Для этого ДЕРЖИМ процесс
     * привязанным к upstream-сети на всю сессию (bindProcessToNetwork без unbind) —
     * исходящие соединения ядра идут мимо туннеля. tun2socks ходит на 127.0.0.1, его это
     * не касается.
     */
    private suspend fun startVless(
        location: LocationConfig,
        upstream: Network,
        requestedGeneration: Long,
        setErrorOnFailure: Boolean
    ): Boolean {
        val config = location.normalized()
        return try {
            val targetSocksPort = socksListenPort
            resetRtcHealthState()

            waitForSocksPortReleased(targetSocksPort, SOCKS_RELEASE_QUICK_TIMEOUT_MS)
            if (isLocalSocksPortOpen(targetSocksPort)) {
                throw IllegalStateException("SOCKS port $targetSocksPort is still in use")
            }
            // Привязка процесса к upstream — sing-box дозванивается до VLESS-сервера мимо TUN.
            bindProcessToNetwork(upstream, "Bound VLESS to ${getNetName(upstream)}")

            addLog("Fetching VLESS config server=${config.id}")
            val tFetch = System.currentTimeMillis()
            val singboxJson = fetchSingboxConfig(config, targetSocksPort)
                ?: throw IllegalStateException("Failed to fetch VLESS config")
            addLog("VLESS config fetched in ${System.currentTimeMillis() - tFetch}ms")

            addLog("Starting VLESS (sing-box) on $socksListenHost:$targetSocksPort")
            val tStart = System.currentTimeMillis()
            SingBoxTunnel.start(singboxJson)
            vlessActive = true

            // Ждём, пока локальный SOCKS5 ядра начнёт принимать соединения.
            val deadline = System.currentTimeMillis() + MOBILE_READY_TIMEOUT_MS
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                if (requestedGeneration != generation) {
                    addLog("VLESS start superseded")
                    return false
                }
                if (isLocalSocksPortOpen(targetSocksPort)) { ready = true; break }
                delay(SOCKS_RELEASE_POLL_MS)
            }
            if (!ready) throw IllegalStateException("VLESS SOCKS not ready")

            markRtcConnected()
            addLog("VLESS SOCKS ready in ${System.currentTimeMillis() - tStart}ms")
            addLog("VLESS ready on $socksListenHost:$targetSocksPort")

            // Обновляем кэш конфига ЧЕРЕЗ туннель. Приложение исключено из VPN, поэтому прямой
            // фоновый запрос конфига на «зарезанных» сетях (белые списки РФ) НЕ доходит до
            // нашего API → кэш не обновлялся, и серверные правки маршрутизации не долетали без
            // ручной очистки кэша. Теперь тянем свежий конфиг через локальный SOCKS (= через
            // VPN, где API всегда доступен) и пишем в кэш — следующее подключение уже свежее.
            if (config.id != "reed-temp") {
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        val fresh = fetchSingboxConfigViaSocks(config, targetSocksPort)
                        if (fresh != null) {
                            writeSingboxCache(config.id, targetSocksPort, fresh)
                            addLog("VLESS config refreshed via tunnel (cache updated)")
                        }
                    }
                }
            }
            true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                addLog("VLESS start canceled")
                runCatching { SingBoxTunnel.stop() }
                vlessActive = false
                unbindProcessFromNetwork()
            }
            throw e
        } catch (e: Exception) {
            val staleRequest = requestedGeneration != generation
            val message = e.message ?: "VLESS failed"
            addLog(if (staleRequest) "VLESS start canceled: $message" else "VLESS start failed: $message")
            runCatching { SingBoxTunnel.stop() }
            vlessActive = false
            unbindProcessFromNetwork()
            if (!staleRequest && setErrorOnFailure) {
                setStatus(VpnStatus.Error(message))
                updateNotification("Connection failed")
            }
            false
        }
    }

    /** Скачивает sing-box-конфиг с сервера: /app/singbox?token=&socks_port=&server=.
     *  Для временного VPN (id=reed-temp) — БЕЗ токена с /app/temp (работает до входа). */
    private suspend fun fetchSingboxConfig(location: LocationConfig, socksPort: Int): String? =
        withContext(Dispatchers.IO) {
            val token = location.key
            // «Любой» (чужой) ключ: key — это сам URI (vless/vmess/trojan/ss). Конфиг sing-box
            // собираем ЛОКАЛЬНО на устройстве, без нашего сервера (чужие провайдеры + работа на
            // заблокированных сетях РФ, где наш домен режут).
            if (token.contains("://")) {
                val parsed = org.olcbox.app.data.datasource.ProxyKeyImport.parseUri(token)
                if (parsed != null) {
                    addLog("VLESS config built locally from imported key (${location.id})")
                    return@withContext org.olcbox.app.data.datasource.ProxyKeyImport
                        .buildSingboxConfig(parsed.outbound, socksPort)
                }
                addLog("Imported key not recognized (${location.id})")
                return@withContext null
            }
            val server = URLEncoder.encode(location.id, "UTF-8")
            val split = if (org.olcbox.app.data.reed.ReedSession.splitRouting) "1" else "0"
            val url = if (location.id == "reed-temp") {
                "$REED_API_BASE/app/temp?socks_port=$socksPort"
            } else {
                "$REED_API_BASE/app/singbox?token=$token&socks_port=$socksPort&server=$server&split=$split"
            }
            // Один HTTP GET конфига (или null при сбое/не-200). КОРОТКИЙ таймаут: на
            // «зарезанных» сетях API недоступен, и мы должны быстро откатиться на кэш, а не
            // висеть. На нормальной сети ответ приходит за доли секунды.
            fun httpGet(): String? = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4_000
                    readTimeout = 4_000
                    requestMethod = "GET"
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        addLog("VLESS config HTTP ${conn.responseCode}")
                        return@runCatching null
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()

            // CACHE-FIRST + фоновое обновление. Подключение должно быть БЫСТРЫМ и работать на
            // «зарезанном» мобильном (РФ-белые списки), где наш API недоступен и network-first
            // висел до таймаута на каждом подключении. Поэтому: есть кэш → подключаемся СРАЗУ
            // из него, а свежий конфиг тянем в фоне и обновляем кэш на следующий раз (как Happ
            // по сути — мгновенный коннект, актуализация незаметно). Кэша нет (первый запуск) →
            // тянем с сети и кэшируем.
            val cached = readSingboxCache(location.id, socksPort)
            if (cached != null) {
                scope.launch(Dispatchers.IO) {
                    val fresh = httpGet()
                    if (fresh != null) runCatching { writeSingboxCache(location.id, socksPort, fresh) }
                }
                addLog("VLESS config from cache (instant) + background refresh")
                return@withContext cached
            }

            val fresh = httpGet()
            if (fresh != null) {
                runCatching { writeSingboxCache(location.id, socksPort, fresh) }
                addLog("VLESS config fresh (no cache yet → fetched)")
                return@withContext fresh
            }
            // Временный VPN на свежей установке без доступа к API: берём ВШИТЫЙ конфиг —
            // поднимаем временный туннель, через него API становится достижим → можно
            // войти и докачать реальные ключи (бутстрап без обязательного Wi-Fi).
            if (location.id == "reed-temp") {
                val baked = org.olcbox.app.data.reed.bakedTempSingboxConfig(socksPort)
                runCatching { writeSingboxCache(location.id, socksPort, baked) }
                addLog("temp VLESS config from baked-in (API unreachable, fresh install)")
                return@withContext baked
            }
            addLog("VLESS config unavailable (no cache, API unreachable)")
            null
        }

    /** Тот же конфиг, что fetchSingboxConfig, но запрос идёт ЧЕРЕЗ локальный SOCKS (= через
     *  поднятый VPN-туннель), где наш API доступен даже на сетях с белым списком. Нужен для
     *  обновления кэша после подключения — иначе серверные правки не долетали (прямой запрос
     *  из исключённого из VPN приложения на «зарезанных» сетях не проходит). */
    private suspend fun fetchSingboxConfigViaSocks(location: LocationConfig, socksPort: Int): String? =
        withContext(Dispatchers.IO) {
            if (location.id == "reed-temp") return@withContext null
            val token = location.key
            // Локальный («чужой») ключ — обновлять с сервера нечего (конфиг строится на устройстве).
            if (token.contains("://")) return@withContext null
            val server = URLEncoder.encode(location.id, "UTF-8")
            val split = if (org.olcbox.app.data.reed.ReedSession.splitRouting) "1" else "0"
            val url = "$REED_API_BASE/app/singbox?token=$token&socks_port=$socksPort&server=$server&split=$split"
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            runCatching {
                val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
                } finally { conn.disconnect() }
            }.getOrNull()
        }

    // Кэш sing-box-конфигов вынесен в общий SingboxConfigCache — тот же формат имени файла
    // использует AndroidVpnManager для предзагрузки всех серверов (офлайн-подключение к ещё
    // не использованным серверам). Делегируем, чтобы имена файлов не разошлись.
    private fun writeSingboxCache(serverId: String, socksPort: Int, json: String) =
        SingboxConfigCache.write(this, serverId, socksPort, json)

    private fun readSingboxCache(serverId: String, socksPort: Int): String? =
        SingboxConfigCache.read(this, serverId, socksPort)

    private fun transportRunning(): Boolean =
        when {
            // olcRTC + split: оба ядра должны быть живы (sing-box-роутер и движок olcRTC).
            olcSplitActive -> SingBoxTunnel.isRunning() && Mobile.isRunning()
            vlessActive -> SingBoxTunnel.isRunning()
            else -> Mobile.isRunning()
        }

    /** Скачивает sing-box-конфиг split-routing ДЛЯ olcRTC (/app/olcsingbox). CACHE-FIRST:
     *  на «зарезанных» сетях наш API недоступен → используем кэш; если кэша нет и сеть тоже
     *  недоступна — вернём null (вызывающий тихо откатится на прямой olcRTC). */
    private suspend fun fetchOlcSingboxConfig(socksPort: Int, olcPort: Int): String? =
        withContext(Dispatchers.IO) {
            val split = if (org.olcbox.app.data.reed.ReedSession.splitRouting) "1" else "0"
            val url = "$REED_API_BASE/app/olcsingbox?socks_port=$socksPort&olc_port=$olcPort&split=$split"
            val cacheId = "olcrtc_split_$olcPort"
            fun httpGet(): String? = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        addLog("olcRTC split config HTTP ${conn.responseCode}")
                        return@runCatching null
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()

            val cached = readSingboxCache(cacheId, socksPort)
            if (cached != null) {
                scope.launch(Dispatchers.IO) {
                    httpGet()?.let { runCatching { writeSingboxCache(cacheId, socksPort, it) } }
                }
                addLog("olcRTC split config from cache (cache-first)")
                return@withContext cached
            }
            val fetched = httpGet()
            if (fetched != null) {
                runCatching { writeSingboxCache(cacheId, socksPort, fetched) }
                return@withContext fetched
            }
            addLog("olcRTC split config unavailable → fallback to direct olcRTC")
            null
        }

    /** Добавляет логин/пароль в socks-outbound «proxy» (выход на локальный движок olcRTC).
     *  Конфиг с сервера приходит БЕЗ кредов (они per-device), а SOCKS движка olcRTC требует
     *  авторизацию — без этого sing-box не пройдёт у него аутентификацию. */
    private fun injectOlcProxyAuth(configJson: String, user: String, pass: String): String {
        if (user.isBlank() || pass.isBlank()) return configJson
        return runCatching {
            val root = olcConfigJson.parseToJsonElement(configJson).jsonObject
            val outbounds = root["outbounds"]!!.jsonArray.map { el ->
                val o = el.jsonObject
                if (o["tag"]?.jsonPrimitive?.contentOrNull == "proxy" &&
                    o["type"]?.jsonPrimitive?.contentOrNull == "socks"
                ) {
                    JsonObject(
                        o + mapOf(
                            "username" to JsonPrimitive(user),
                            "password" to JsonPrimitive(pass),
                        )
                    )
                } else {
                    el
                }
            }
            val newRoot = JsonObject(root + mapOf("outbounds" to JsonArray(outbounds)))
            olcConfigJson.encodeToString(JsonObject.serializer(), newRoot)
        }.getOrElse {
            addLog("olcRTC split: failed to inject proxy auth (${it.message}); using config as-is")
            configJson
        }
    }

    private suspend fun waitForJitsiRoomCleanup(provider: String, newRoom: String) {
        if (LocationConfig.normalizeProvider(provider) != LocationConfig.PROVIDER_JITSI) return

        // Пауза нужна ТОЛЬКО при перезаходе в ТУ ЖЕ комнату (Jitsi не успевает убрать
        // прошлую сессию участника → коллизия). При смене сервера комната ДРУГАЯ —
        // ждать незачем, поэтому переключение между LTE-серверами идёт без задержки.
        if (newRoom != lastJitsiStoppedRoom) return

        val waitMs = JITSI_RESTART_SETTLE_MS -
            (System.currentTimeMillis() - lastJitsiStopCompletedAtMs)
        if (waitMs <= 0L) return

        addLog("Waiting for previous Jitsi room cleanup")
        delay(waitMs)
    }

    private fun configureMobileTransport(location: LocationConfig) {
        val config = location.normalized()
        Mobile.setProviders()
        Mobile.setTransport(config.transport)
        Mobile.setDNS("1.1.1.1:53")
        Mobile.setSocksListenHost(socksListenHost)
        Mobile.setVP8Options(config.vp8Fps.toLong(), config.vp8Batch.toLong())
    }

    private fun startTun2socks(pfd: ParcelFileDescriptor): Boolean {
        return try {
            if (!ensureNativeLibrariesLoaded()) {
                addLog("tun2socks native libraries are unavailable")
                setStatus(VpnStatus.Error("tun2socks native libraries are unavailable"))
                updateNotification("Tunnel failed")
                return false
            }

            val nativeFd = ParcelFileDescriptor.dup(pfd.fileDescriptor).detachFd()
            val configFile = writeTun2socksConfig()
            tun2socksStarted = true
            tun2socksStopRequested = false
            tun2socksThread = thread(name = "OlcboxTun2Socks", isDaemon = true) {
                try {
                    val result = startTun2socksNative(configFile.absolutePath, nativeFd)
                    if (OlcboxVpnState.status.value !is VpnStatus.Stopping && result != 0) {
                        addLog("tun2socks exited with code $result")
                    } else {
                        addLog("tun2socks stopped")
                    }
                } finally {
                    tun2socksStarted = false
                    tun2socksStopRequested = false
                }
            }
            true
        } catch (e: Exception) {
            addLog("tun2socks start failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "tun2socks failed"))
            updateNotification("Tunnel failed")
            false
        }
    }

    private fun establishSystemVpnTunnel(): ParcelFileDescriptor? {
        return try {
            val builder = Builder()
                .setSession("Reed")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4_ADDRESS, IPV4_PREFIX_LENGTH)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(MAPDNS_ADDRESS)
                .setBlocking(true)

            // IPv6 ОБЯЗАТЕЛЬНО заворачиваем в TUN. Иначе на двойном стеке (Wi-Fi/LTE с IPv6)
            // приложения (Telegram, Google) ходят по IPv6 НАПРЯМУЮ мимо туннеля — а в РФ-сетях
            // с белыми списками этот прямой IPv6 режется → «Telegram не открывается». С захватом
            // v6 трафик идёт в hev → SOCKS5 → сервер (или happy-eyeballs падает на рабочий v4).
            runCatching {
                builder.addAddress(TUN_IPV6_ADDRESS, IPV6_PREFIX_LENGTH)
                builder.addRoute("::", 0)
            }.onFailure { addLog("IPv6 TUN setup skipped: ${it.message}") }

            if (!applySplitTunneling(builder)) return null

            currentNetwork?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            builder.establish()
        } catch (e: Exception) {
            addLog("VPN establish failed: ${e.message}")
            setStatus(VpnStatus.Error(e.message ?: "Не удалось подключиться"))
            updateNotification("Ошибка подключения")
            null
        }
    }

    // Приложения, которые ВСЕГДА идут мимо VPN (даже в режиме «все через VPN»). MAX
    // (ru.oneme.app) — гос-мессенджер, специально детектящий активный VPN на устройстве:
    // выводим его полностью из туннеля → он видит реальную РФ-сеть и свой обычный IP, а не
    // наш TUN. Работает он и так на РФ-сетях, тоннель ему не нужен.
    private val alwaysBypassPackages = listOf("ru.oneme.app")

    private fun applySplitTunneling(builder: Builder): Boolean {
        return when (splitTunnelMode) {
            AndroidSplitTunnelMode.AllApps -> {
                addDisallowedApp(builder, packageName, "Olcbox")
                alwaysBypassPackages.forEach { addDisallowedApp(builder, it, "always-bypass") }
                addLog("Split tunneling: all apps use TUN (MAX bypassed)")
                true
            }

            AndroidSplitTunnelMode.ProxySelected -> {
                val packages = splitTunnelProxyApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()

                if (packages.isEmpty()) {
                    addLog("Split tunneling proxy list is empty")
                    setStatus(VpnStatus.Error("Select apps for split tunneling"))
                    updateNotification("Split tunneling error")
                    return false
                }

                val applied = packages.count { addAllowedApp(builder, it) }
                if (applied == 0) {
                    addLog("Split tunneling has no valid proxy apps")
                    setStatus(VpnStatus.Error("Selected apps are unavailable"))
                    updateNotification("Split tunneling error")
                    false
                } else {
                    addLog("Split tunneling: $applied selected apps use TUN")
                    true
                }
            }

            AndroidSplitTunnelMode.BypassSelected -> {
                addDisallowedApp(builder, packageName, "Olcbox")
                alwaysBypassPackages.forEach { addDisallowedApp(builder, it, "always-bypass") }
                val applied = splitTunnelBypassApps
                    .filter { it.isNotBlank() && it != packageName }
                    .distinct()
                    .count { addDisallowedApp(builder, it) }

                if (applied == 0) {
                    addLog("Split tunneling: no selected apps bypass TUN")
                } else {
                    addLog("Split tunneling: $applied selected apps bypass TUN")
                }
                true
            }
        }
    }

    private fun addAllowedApp(builder: Builder, targetPackage: String): Boolean {
        return runCatching {
            builder.addAllowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to route $targetPackage through TUN: ${it.message}")
            false
        }
    }

    private fun addDisallowedApp(
        builder: Builder,
        targetPackage: String,
        label: String = targetPackage
    ): Boolean {
        return runCatching {
            builder.addDisallowedApplication(targetPackage)
            true
        }.getOrElse {
            addLog("Failed to bypass $label from TUN: ${it.message}")
            false
        }
    }

    private fun writeTun2socksConfig(): File {
        val file = File(filesDir, TUN2SOCKS_CONFIG_FILE_NAME)

        file.writeText(
            """
            tunnel:
              name: tun0
              mtu: $TUN_MTU
              multi-queue: false
              ipv4: $TUN_IPV4_ADDRESS
              ipv6: '$TUN_IPV6_ADDRESS'

            socks5:
              address: ${socksConnectHost()}
              port: $socksListenPort
              # 'udp' (НЕ 'tcp'): относим UDP как настоящий UDP через SOCKS5 UDP-ASSOCIATE
              # (VLESS-аутбаунд несёт его как xudp). С 'tcp' UDP заворачивался поверх TCP →
              # звонки в Telegram (real-time UDP) НЕ устанавливались вообще. HAPP использует
              # нативный UDP — поэтому у него звонки соединяются. Возвращаем как в эталонном
              # main.yml и как у HAPP.
              udp: 'udp'
              pipeline: false
              username: '$socksUsername'
              password: '$socksPassword'

            mapdns:
              address: $MAPDNS_ADDRESS
              port: 53
              network: $MAPDNS_NETWORK
              netmask: $MAPDNS_NETMASK
              cache-size: 10000

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              max-session-count: 1200
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: warn
            """.trimIndent()
        )
        return file
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogTunStats = null
        watchdogStalledSamples = 0
        val mode = connectionMode
        watchdogJob = scope.launch {
            while (isActive && OlcboxVpnState.status.value is VpnStatus.Connected) {
                delay(WATCHDOG_INTERVAL_MS)
                when {
                    !transportRunning() -> {
                        addLog("Watchdog: transport stopped")
                        requestTransportRecovery("transport stopped", fullRestart = false)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Tun && tun2socksThread?.isAlive != true -> {
                        addLog("Watchdog: tun2socks stopped")
                        requestTransportRecovery("tun2socks stopped", fullRestart = true)
                        return@launch
                    }

                    mode == AndroidConnectionMode.Proxy && !isLocalSocksPortOpen(socksListenPort) -> {
                        addLog("Watchdog: SOCKS port is not accepting connections")
                        requestTransportRecovery("SOCKS port unavailable", fullRestart = true)
                        return@launch
                    }
                }

                val upstream = findActiveUpstreamNetwork()
                if (upstream == null) {
                    addLog("Watchdog: no upstream network")
                    requestTransportRecovery("No upstream network", fullRestart = false)
                    return@launch
                }

                if (currentNetwork != upstream) {
                    val previousTransport = currentNetworkTransport
                    val nextTransport = upstream.transportOrNull()
                    updateUnderlyingNetwork(upstream)
                    if (isBenignWifiRefresh(previousTransport, nextTransport)) {
                        addLog("Watchdog: refreshed Wi-Fi upstream")
                        continue
                    }
                    addLog("Watchdog: upstream changed to ${getNetName(upstream)}")
                    requestTransportRecovery("Upstream network changed", fullRestart = false)
                    return@launch
                }

                if (mode == AndroidConnectionMode.Tun && isTunTrafficStalled()) {
                    addLog("Watchdog: TUN traffic has no upstream response")
                    // Полный перезапуск (а не in-place): при зависании канала (особенно
                    // olcRTC/WebRTC) перезапуск движка «на месте» часто НЕ оживляет его —
                    // помогает только полная пересборка туннеля. Раньше тут было in-place,
                    // из-за чего «чинилось только перезаходом в приложение».
                    requestTransportRecovery("TUN traffic stalled", fullRestart = true)
                    return@launch
                }

                if (accountTempTrafficAndCheckLimit()) {
                    addLog("Temp VPN: 5 GB limit reached — disconnecting")
                    cleanup()
                    return@launch
                }
            }
        }
    }

    /**
     * Учёт трафика временного VPN. Возвращает true, если достигнут лимит 5 ГБ.
     * Считаем дельту от tun2socks-статистики и копим в ReedSession.tempUsedBytes (персист).
     */
    private fun accountTempTrafficAndCheckLimit(): Boolean {
        if (activeLocationId != ReedTempServer.LOCATION_ID) return false
        val stats = readTun2SocksStats() ?: return false
        val cumulative = stats.txBytes + stats.rxBytes
        if (tempSessionStartCumulative < 0L) {
            tempSessionStartCumulative = cumulative
            tempUsedAtSessionStart = org.olcbox.app.data.reed.ReedSession.tempUsedBytes
            return false
        }
        val sessionUsed = (cumulative - tempSessionStartCumulative).coerceAtLeast(0L)
        val total = tempUsedAtSessionStart + sessionUsed
        org.olcbox.app.data.reed.ReedSession.tempUsedBytes = total
        return total >= ReedTempServer.LIMIT_BYTES
    }

    private fun cleanup(stopService: Boolean = true) {
        if (cleanupJob?.isActive == true) return
        activeLocationId = null
        tempSessionStartCumulative = -1L

        val status = OlcboxVpnState.status.value
        if (status is VpnStatus.Disconnected &&
            vpnInterface == null &&
            tun2socksThread == null &&
            socksProxy == null &&
            cleanupJob?.isActive != true
        ) {
            if (stopService) stopSelf()
            return
        }
        if (status is VpnStatus.Stopping && cleanupJob?.isActive == true) return

        val cleanupGeneration = ++generation
        setStatus(VpnStatus.Stopping)
        startupJob?.cancel()
        watchdogJob?.cancel()
        networkLossJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        rtcDisconnectRecoveryJob?.cancel()
        rtcDisconnectRecoveryJob = null
        releaseWakeLock()

        if (isCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            isCallbackRegistered = false
        }
        stopAuthenticatedSocksProxy()
        updateUnderlyingNetwork(null)
        unbindProcessFromNetwork()

        cleanupJob = scope.launch {
            try {
                stopVisibleVpnProcesses()
                if (generation == cleanupGeneration) {
                    setStatus(VpnStatus.Disconnected)
                    addLog("${activeModeLabel()} stopped")
                }

                stopMobileAndWait()
                resetRecoveryState()
            } finally {
                if (stopService && generation == cleanupGeneration) stopSelf()
            }
        }
    }

    private suspend fun stopVisibleVpnProcesses() {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        stopTun2socks()
        cleanupVpnInterface()
        tunThread?.interrupt()
        waitForTun2socksStopped(tunThread)
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        unbindProcessFromNetwork()
    }

    private suspend fun waitForTun2socksStopped(thread: Thread?) {
        if (thread == null) return
        val stopped = withTimeoutOrNull(TUN2SOCKS_STOP_WAIT_MS) {
            while (thread.isAlive) {
                delay(SOCKS_RELEASE_POLL_MS)
            }
            true
        } ?: false
        if (!stopped) {
            addLog("tun2socks cleanup is still pending")
        }
    }

    private suspend fun stopTransportProcesses(
        closeTun: Boolean,
        waitForSocksPort: Boolean = true,
        stopMobileBeforeTun: Boolean = false
    ) {
        val tunThread = tun2socksThread
        stopAuthenticatedSocksProxy()
        if (stopMobileBeforeTun) {
            stopMobile()
        }
        stopTun2socks()
        if (closeTun) cleanupVpnInterface()
        tunThread?.interrupt()
        if (closeTun) {
            waitForTun2socksStopped(tunThread)
        }
        if (tun2socksThread == tunThread) {
            tun2socksThread = null
        }
        if (waitForSocksPort) {
            if (stopMobileBeforeTun) {
                waitForSocksPortReleased()
            } else {
                stopMobileAndWait()
            }
        } else if (!stopMobileBeforeTun) {
            stopMobile()
        }
        if (closeTun) {
            unbindProcessFromNetwork()
        }
    }

    private fun stopTun2socks() {
        if (nativeLibrariesLoaded && tun2socksStarted && !tun2socksStopRequested) {
            tun2socksStopRequested = true
            runCatching { stopTun2socksNative() }
        }
    }

    private fun stopMobile() {
        // sing-box-роутер: для чистого VLESS и для olcRTC+split. Останавливаем, если активен.
        if (vlessActive || olcSplitActive) {
            runCatching { SingBoxTunnel.stop() }
            vlessActive = false
        }
        // Чистый VLESS (без split) движок olcRTC не использует — для него ниже Mobile не запущен,
        // поэтому Mobile.isRunning()==false и Mobile.stop() безвреден. Для olcRTC и olcRTC+split
        // обязательно останавливаем движок, иначе WebRTC-сессия утечёт.
        val wasSplit = olcSplitActive
        olcSplitActive = false
        val provider = lastMobileProvider
        val wasRunning = Mobile.isRunning()
        if (wasRunning || wasSplit) {
            runCatching { Mobile.stop() }
        }
        if (wasRunning && provider == LocationConfig.PROVIDER_JITSI) {
            lastJitsiStopCompletedAtMs = System.currentTimeMillis()
            lastJitsiStoppedRoom = lastMobileRoom
        }
    }

    private fun stopAuthenticatedSocksProxy() {
        socksProxy?.stop()
        socksProxy = null
    }

    private suspend fun stopMobileAndWait() {
        val socksPort = socksListenPort
        stopMobile()
        waitForSocksPortReleased(socksPort)
    }

    private suspend fun waitForSocksPortReleased(
        port: Int = socksListenPort,
        timeoutMs: Long = SOCKS_RELEASE_TIMEOUT_MS
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isLocalSocksPortOpen(port)) return
            delay(SOCKS_RELEASE_POLL_MS)
        }
        addLog("SOCKS port $port is still busy after stop")
    }

    private fun isLocalSocksPortOpen(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(socksConnectHost(), port),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
            }
        }.isSuccess
    }

    private fun socksConnectHost(): String {
        return AndroidSocksProxySettings.connectHost(socksListenHost)
    }

    private fun handleRtcLine(line: String) {
        val lowerLine = line.lowercase()

        if (lowerLine.contains("ice connection state changed: connected") ||
            lowerLine.contains("peer connection state changed: connected") ||
            lowerLine.contains("socks5 server listening")
        ) {
            markRtcConnected()
            return
        }

        if (lowerLine.contains("ice connection state changed: failed") ||
            lowerLine.contains("peer connection state changed: failed")
        ) {
            noteRtcFailure(
                reason = "RTC failed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_FAILED_RECOVERY_THRESHOLD
            )
            return
        }

        if (lowerLine.contains("ice connection state changed: closed") ||
            lowerLine.contains("peer connection state changed: closed")
        ) {
            noteRtcFailure(
                reason = "RTC closed",
                fullRestart = shouldRecreateTunnelOnRtcLoss(),
                threshold = RTC_CLOSED_RECOVERY_THRESHOLD
            )
            return
        }

        // ICE «disconnected» — РАНЬШЕ НЕ ОБРАБАТЫВАЛОСЬ: канал отваливался, новые соединения
        // висли с «remote not ready (timeout)», а авто-восстановление не запускалось → юзеру
        // приходилось перезапускать приложение. WebRTC «disconnected» часто оживает сам за
        // пару секунд, поэтому не дёргаем сразу: ждём grace, и если «connected» так и не
        // пришёл — пересобираем туннель.
        if (lowerLine.contains("ice connection state changed: disconnected") ||
            lowerLine.contains("peer connection state changed: disconnected")
        ) {
            noteRtcDisconnected()
            return
        }

        if (lowerLine.contains("network is unreachable") ||
            lowerLine.contains("use of closed network connection") ||
            lowerLine.contains("read/write on closed pipe")
        ) {
            noteRtcFailure(
                reason = "RTC network path is closed",
                fullRestart = false,
                threshold = RTC_IO_ERROR_RECOVERY_THRESHOLD
            )
        }
    }

    private fun markRtcConnected() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
        // Канал ожил сам — отменяем отложенную пересборку после «disconnected».
        rtcDisconnectRecoveryJob?.cancel()
        rtcDisconnectRecoveryJob = null
    }

    /**
     * ICE/peer ушёл в «disconnected». Это часто временно — даём WebRTC шанс восстановиться
     * сам в течение RTC_DISCONNECT_GRACE_MS. Если «connected» так и не пришёл (markRtcConnected
     * отменил бы этот job) — запускаем пересборку туннеля, чтобы юзеру не пришлось
     * перезапускать приложение вручную.
     */
    private fun noteRtcDisconnected() {
        if (OlcboxVpnState.status.value !is VpnStatus.Connected) return
        // Не реагируем на «disconnected» в первые мгновения после установления связи.
        if (System.currentTimeMillis() - lastRtcConnectedAtMs < RTC_RECOVERY_GRACE_MS) return
        if (rtcDisconnectRecoveryJob?.isActive == true) return

        rtcDisconnectRecoveryJob = scope.launch {
            delay(RTC_DISCONNECT_GRACE_MS)
            rtcDisconnectRecoveryJob = null
            if (OlcboxVpnState.status.value !is VpnStatus.Connected) return@launch
            addLog("RTC disconnected and did not recover; rebuilding tunnel")
            requestTransportRecovery(
                reason = "RTC disconnected",
                fullRestart = shouldRecreateTunnelOnRtcLoss()
            )
        }
    }

    private fun resetRtcHealthState() {
        lastRtcConnectedAtMs = System.currentTimeMillis()
        lastRtcFailureAtMs = 0L
        rtcFailureCount = 0
    }

    private fun noteRtcFailure(
        reason: String,
        fullRestart: Boolean,
        threshold: Int
    ) {
        if (OlcboxVpnState.status.value !is VpnStatus.Connected) return

        val now = System.currentTimeMillis()
        if (now - lastRtcConnectedAtMs < RTC_RECOVERY_GRACE_MS) return

        rtcFailureCount = if (now - lastRtcFailureAtMs <= RTC_FAILURE_WINDOW_MS) {
            rtcFailureCount + 1
        } else {
            1
        }
        lastRtcFailureAtMs = now

        if (rtcFailureCount >= threshold) {
            requestTransportRecovery(reason, fullRestart)
        }
    }

    private fun isTunTrafficStalled(): Boolean {
        val stats = readTun2SocksStats() ?: return false
        val previous = watchdogTunStats
        watchdogTunStats = stats

        if (previous == null) return false

        val txDelta = stats.txPackets - previous.txPackets
        val rxDelta = stats.rxPackets - previous.rxPackets
        if (txDelta >= WATCHDOG_STALLED_TX_PACKET_DELTA && rxDelta <= 0L && transportRunning()) {
            watchdogStalledSamples++
        } else if (rxDelta > 0L || txDelta <= 0L) {
            watchdogStalledSamples = 0
        }

        return watchdogStalledSamples >= WATCHDOG_STALLED_SAMPLE_LIMIT
    }

    private fun readTun2SocksStats(): Tun2SocksStats? {
        if (!nativeLibrariesLoaded || !tun2socksStarted) return null
        return runCatching {
            val values = getTun2socksStatsNative()
            if (values.size < 4) return null
            Tun2SocksStats(
                txPackets = values[0],
                txBytes = values[1],
                rxPackets = values[2],
                rxBytes = values[3]
            )
        }.getOrNull()
    }

    private fun requestTransportRecovery(
        reason: String,
        fullRestart: Boolean,
        delayMs: Long = 0L,
        setReconnectingImmediately: Boolean = true
    ) {
        val status = OlcboxVpnState.status.value
        if (status !is VpnStatus.Connected && status !is VpnStatus.Reconnecting) return

        val recoveryGeneration = generation
        if (delayMs <= 0L &&
            recoveryRequestedForGeneration == recoveryGeneration &&
            recoveryJob?.isActive == true
        ) {
            return
        }

        recoveryJob?.cancel()
        if (setReconnectingImmediately && status is VpnStatus.Connected) {
            setStatus(VpnStatus.Reconnecting)
            updateNotification("Reconnecting...")
        }

        recoveryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (generation != recoveryGeneration) return@launch
            val currentStatus = OlcboxVpnState.status.value
            if (currentStatus !is VpnStatus.Connected && currentStatus !is VpnStatus.Reconnecting) {
                return@launch
            }

            recoveryRequestedForGeneration = recoveryGeneration
            if (setReconnectingImmediately && currentStatus is VpnStatus.Connected) {
                setStatus(VpnStatus.Reconnecting)
                updateNotification("Reconnecting...")
            }

            addLog("$reason; reconnecting transport")
            recoveryJob = null
            startTunnel(isMigration = true, forceFullRestart = fullRestart)
        }
    }

    private fun refreshWakeLock(force: Boolean = false) {
        val lock = wakeLock ?: return
        val now = System.currentTimeMillis()
        if (!force &&
            lock.isHeld &&
            now - lastWakeLockRefreshAtMs < WAKE_LOCK_REFRESH_INTERVAL_MS
        ) {
            return
        }

        runCatching {
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            lastWakeLockRefreshAtMs = now
        }.onFailure {
            Log.w(TAG, "Failed to refresh VPN wake lock", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }.onFailure {
            Log.w(TAG, "Failed to release VPN wake lock", it)
        }
        lastWakeLockRefreshAtMs = 0L
    }

    private fun scheduleTransportRetry(
        requestedGeneration: Long,
        reason: String,
        baseDelayMs: Long = RECONNECT_RETRY_BASE_DELAY_MS
    ) {
        val delayMs = nextReconnectRetryDelay(baseDelayMs)
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            addLog("Retrying transport after $reason in ${delayMs / 1_000}s")
            delay(delayMs)
            if (generation != requestedGeneration) return@launch
            if (OlcboxVpnState.status.value !is VpnStatus.Reconnecting) return@launch

            recoveryJob = null
            startTunnel(isMigration = true)
        }
    }

    private fun nextReconnectRetryDelay(baseDelayMs: Long): Long {
        val multiplier = 1L shl reconnectAttempt.coerceAtMost(MAX_RECONNECT_BACKOFF_POWER)
        reconnectAttempt++
        return (baseDelayMs * multiplier).coerceAtMost(RECONNECT_RETRY_MAX_DELAY_MS)
    }

    private fun resetRecoveryState() {
        recoveryRequestedForGeneration = 0L
        reconnectAttempt = 0
        recoveryJob?.cancel()
        recoveryJob = null
        rtcDisconnectRecoveryJob?.cancel()
        rtcDisconnectRecoveryJob = null
    }

    private fun shouldRecreateTunnelOnRtcLoss(): Boolean {
        return connectionMode == AndroidConnectionMode.Tun
    }

    private fun cleanupVpnInterface() {
        runCatching { vpnInterface?.close() }
        vpnInterface = null
    }

    private fun canReconnectTransportInPlace(): Boolean {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> vpnInterface != null && tun2socksThread?.isAlive == true
            AndroidConnectionMode.Proxy -> transportRunning()
        }
    }

    /**
     * Подпись параметров, влияющих на сам TUN-интерфейс/маршрутизацию. Если она не
     * изменилась между запусками, смену сервера можно сделать бесшовно (переиспользовать
     * существующий TUN + tun2socks). При изменении режима/порта/split-tunnel нужен полный
     * перезапуск туннеля.
     */
    private fun tunBuildSignature(): String {
        return listOf(
            connectionMode.value,
            socksListenHost,
            socksListenPort.toString(),
            splitTunnelMode.value,
            splitTunnelProxyApps.sorted().joinToString(","),
            splitTunnelBypassApps.sorted().joinToString(",")
        ).joinToString("|")
    }

    private fun shouldRestartForStartCommand(): Boolean {
        return when (OlcboxVpnState.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting,
            VpnStatus.Stopping -> true
            VpnStatus.Disconnected,
            is VpnStatus.Error -> false
        } ||
            startupJob?.isActive == true ||
            cleanupJob?.isActive == true ||
            vpnInterface != null ||
            tun2socksThread != null ||
            socksProxy != null ||
            transportRunning()
    }

    private fun registerNetworkMonitor() {
        if (isCallbackRegistered) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isCallbackRegistered = true
            addLog("Network monitor registered")
        } catch (e: Exception) {
            Log.e(TAG, "Network monitor failed", e)
        }
    }

    private fun findActiveUpstreamNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val candidates = connectivityManager.allNetworks.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.isUsableUpstream()) return@mapNotNull null
            network to UpstreamCandidate(
                isActive = network == active,
                isValidated = caps.isValidatedUpstream(),
                transport = caps.upstreamTransport()
            )
        }
        val selectedIndex = UpstreamNetworkSelector.selectIndex(candidates.map { it.second }) ?: return null
        return candidates[selectedIndex].first
    }

    private fun NetworkCapabilities.isUsableUpstream(): Boolean {
        return !hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun NetworkCapabilities.isValidatedUpstream(): Boolean {
        return isUsableUpstream() &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun NetworkCapabilities.upstreamTransport(): UpstreamTransport {
        return when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> UpstreamTransport.Wifi
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> UpstreamTransport.Cellular
            else -> UpstreamTransport.Other
        }
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        currentNetwork = network
        currentNetworkTransport = network?.transportOrNull()
        if (connectionMode == AndroidConnectionMode.Tun || vpnInterface != null) {
            setUnderlyingNetworks(if (network != null) arrayOf(network) else null)
        }
    }

    private fun Network.transportOrNull(): UpstreamTransport? {
        val caps = connectivityManager.getNetworkCapabilities(this) ?: return null
        if (!caps.isUsableUpstream()) return null
        return caps.upstreamTransport()
    }

    private fun isBenignWifiRefresh(
        previousTransport: UpstreamTransport?,
        nextTransport: UpstreamTransport?
    ): Boolean {
        return previousTransport == UpstreamTransport.Wifi &&
            nextTransport == UpstreamTransport.Wifi
    }

    private fun bindProcessToNetwork(network: Network?, successLog: String? = null) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            if (successLog != null) addLog(successLog)
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed", e)
        }
    }

    private fun unbindProcessFromNetwork() {
        bindProcessToNetwork(null)
    }

    private fun getNetName(network: Network): String {
        val caps = connectivityManager.getNetworkCapabilities(network)
        return if (caps != null) getNetName(caps) else "Other"
    }

    private fun getNetName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Other"
    }

    private fun shouldKeepProcessBound(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun startForeground(statusText: String = "Protecting your connection") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Reed",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(statusText),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
    }

    private fun updateNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String) =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Reed")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(getAppPendingIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, OlcboxVpnService::class.java).apply { action = ACTION_STOP_VPN },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun getAppPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setStatus(status: VpnStatus) {
        OlcboxVpnState.setStatus(status)
    }

    private fun activeModeLabel(): String {
        return when (connectionMode) {
            AndroidConnectionMode.Tun -> "Reed"
            AndroidConnectionMode.Proxy -> "Proxy"
        }
    }

    private fun connectedNotificationText(): String = "Подключено"

    private class AuthenticatedSocksProxy(
        private val listenPort: Int,
        private val backendPort: Int,
        private val username: String,
        private val password: String,
        private val log: (String) -> Unit
    ) {
        @Volatile
        private var stopped = false
        @Volatile
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val sockets = mutableSetOf<Socket>()

        val isRunning: Boolean
            get() = !stopped && serverSocket?.isClosed == false && acceptThread?.isAlive == true

        fun start() {
            stopped = false
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, listenPort))
            }
            serverSocket = server
            acceptThread = thread(name = "OlcboxSocksProxy", isDaemon = true) {
                acceptLoop(server)
            }
            log("SOCKS proxy listening on ${AndroidSocksProxySettings.DEFAULT_HOST}:$listenPort")
        }

        fun stop() {
            stopped = true
            runCatching { serverSocket?.close() }
            synchronized(sockets) {
                sockets.forEach { socket -> runCatching { socket.close() } }
                sockets.clear()
            }
            acceptThread?.interrupt()
            acceptThread = null
            serverSocket = null
        }

        private fun acceptLoop(server: ServerSocket) {
            while (!stopped) {
                val client = runCatching { server.accept() }
                    .onFailure { if (!stopped) log("SOCKS proxy accept failed: ${it.message}") }
                    .getOrNull() ?: continue

                synchronized(sockets) { sockets.add(client) }
                thread(name = "OlcboxSocksProxyClient", isDaemon = true) {
                    try {
                        handleClient(client)
                    } finally {
                        synchronized(sockets) { sockets.remove(client) }
                        runCatching { client.close() }
                    }
                }
            }
        }

        private fun handleClient(client: Socket) {
            val clientIn = DataInputStream(client.getInputStream())
            val clientOut = DataOutputStream(client.getOutputStream())
            if (!authenticate(clientIn, clientOut)) return

            Socket().use { backend ->
                backend.connect(
                    InetSocketAddress(AndroidSocksProxySettings.DEFAULT_HOST, backendPort),
                    SOCKET_CONNECT_TIMEOUT_MS
                )
                val backendIn = DataInputStream(backend.getInputStream())
                val backendOut = DataOutputStream(backend.getOutputStream())

                backendOut.write(byteArrayOf(SOCKS_VERSION, 0x01, SOCKS_METHOD_USERNAME_PASSWORD))
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != SOCKS_METHOD_USERNAME_PASSWORD.toInt()) return

                val userBytes = username.toByteArray()
                val passBytes = password.toByteArray()

                backendOut.write(SOCKS_AUTH_VERSION.toInt())
                backendOut.write(userBytes.size)
                backendOut.write(userBytes)
                backendOut.write(passBytes.size)
                backendOut.write(passBytes)
                backendOut.flush()

                if (backendIn.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return
                if (backendIn.readUnsignedByte() != 0x00) return // 0x00 - успешно

                val c2b = relay(client, backend, "client-to-backend")
                val b2c = relay(backend, client, "backend-to-client")
                c2b.join()
                runCatching { backend.close() }
                runCatching { client.close() }
                b2c.join(RELAY_JOIN_TIMEOUT_MS)
            }
        }

        private fun authenticate(input: DataInputStream, output: DataOutputStream): Boolean {
            if (input.readUnsignedByte() != SOCKS_VERSION.toInt()) return false
            val methodCount = input.readUnsignedByte()
            var supportsPassword = false
            repeat(methodCount) {
                if (input.readUnsignedByte() == SOCKS_METHOD_USERNAME_PASSWORD.toInt()) {
                    supportsPassword = true
                }
            }
            if (!supportsPassword) {
                output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_NO_ACCEPTABLE))
                output.flush()
                return false
            }

            output.write(byteArrayOf(SOCKS_VERSION, SOCKS_METHOD_USERNAME_PASSWORD))
            output.flush()

            if (input.readUnsignedByte() != SOCKS_AUTH_VERSION.toInt()) return false
            val userBytes = ByteArray(input.readUnsignedByte())
            input.readFully(userBytes)
            val passwordBytes = ByteArray(input.readUnsignedByte())
            input.readFully(passwordBytes)

            val accepted = userBytes.decodeToString() == username &&
                passwordBytes.decodeToString() == password
            output.write(byteArrayOf(SOCKS_AUTH_VERSION, if (accepted) 0x00 else 0x01))
            output.flush()
            return accepted
        }

        private fun relay(from: Socket, to: Socket, name: String): Thread {
            return thread(name = "OlcboxSocksRelay-$name", isDaemon = true) {
                runCatching {
                    from.getInputStream().copyTo(to.getOutputStream(), RELAY_BUFFER_SIZE)
                }
                runCatching { to.shutdownOutput() }
                runCatching { from.shutdownInput() }
            }
        }

        private companion object {
            const val SOCKS_VERSION: Byte = 0x05
            const val SOCKS_AUTH_VERSION: Byte = 0x01
            const val SOCKS_METHOD_NO_AUTH: Byte = 0x00
            const val SOCKS_METHOD_USERNAME_PASSWORD: Byte = 0x02
            const val SOCKS_METHOD_NO_ACCEPTABLE: Byte = 0xFF.toByte()
            const val SOCKET_CONNECT_TIMEOUT_MS = 1_000
            const val RELAY_BUFFER_SIZE = 16 * 1024
            const val RELAY_JOIN_TIMEOUT_MS = 500L
        }
    }

    companion object {
        @Volatile
        private var nativeLibrariesLoaded = false
        private var nativeLibrariesLoadError: Throwable? = null
        private val nativeLibrariesLock = Any()

        private fun ensureNativeLibrariesLoaded(): Boolean {
            if (nativeLibrariesLoaded) return true
            nativeLibrariesLoadError?.let { return false }

            return synchronized(nativeLibrariesLock) {
                if (nativeLibrariesLoaded) {
                    true
                } else {
                    try {
                        System.loadLibrary("hev-socks5-tunnel")
                        System.loadLibrary("olcbox_tun2socks")
                        nativeLibrariesLoaded = true
                        true
                    } catch (e: UnsatisfiedLinkError) {
                        nativeLibrariesLoadError = e
                        Log.e(TAG, "Failed to load native tun2socks libraries", e)
                        false
                    }
                }
            }
        }

        const val ACTION_START_VPN = OlcboxVpnActions.ACTION_START_VPN
        const val ACTION_STOP_VPN = OlcboxVpnActions.ACTION_STOP_VPN

        // База серверного API Reed (для скачивания sing-box-конфига VLESS на лету).
        private const val REED_API_BASE = "https://reed-vpn.duckdns.org"

        private const val LOCAL_SOCKS_PORT_BASE = 10818
        private const val LOCAL_SOCKS_PORT_MAX = 10858
        // Внутренний SOCKS-порт движка olcRTC в split-режиме (выше диапазона основных портов,
        // чтобы не конфликтовать; sing-box-роутер шлёт сюда не-РФ трафик). Единственный
        // экземпляр движка за раз → фиксированный порт безопасен.
        private const val OLCRTC_INTERNAL_SOCKS_PORT = 10861
        private const val MOBILE_READY_TIMEOUT_MS = 25_000L
        private const val PREVIOUS_STOP_WAIT_MS = 12_000L
        private const val JITSI_RESTART_SETTLE_MS = 2_000L
        private const val TUN2SOCKS_STOP_WAIT_MS = 1_000L
        private const val TUNNEL_HANDOFF_DELAY_MS = 300L
        private const val NETWORK_LOSS_GRACE_MS = 2_500L
        private const val NETWORK_STABILITY_GRACE_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val WATCHDOG_STALLED_TX_PACKET_DELTA = 8L
        private const val WATCHDOG_STALLED_SAMPLE_LIMIT = 3
        private const val RTC_RECOVERY_GRACE_MS = 2_500L
        // Сколько ждать самовосстановления WebRTC после ICE «disconnected» до пересборки.
        private const val RTC_DISCONNECT_GRACE_MS = 7_000L
        private const val RTC_FAILURE_WINDOW_MS = 6_000L
        private const val RTC_FAILED_RECOVERY_THRESHOLD = 1
        private const val RTC_CLOSED_RECOVERY_THRESHOLD = 2
        private const val RTC_IO_ERROR_RECOVERY_THRESHOLD = 3
        private const val RECONNECT_RETRY_BASE_DELAY_MS = 2_000L
        private const val NETWORK_RETRY_BASE_DELAY_MS = 8_000L
        private const val RECONNECT_RETRY_MAX_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_BACKOFF_POWER = 3
        private const val SOCKS_RELEASE_TIMEOUT_MS = 2_500L
        private const val SOCKS_RELEASE_QUICK_TIMEOUT_MS = 500L
        private const val SOCKS_RELEASE_POLL_MS = 100L
        private const val SOCKET_CONNECT_TIMEOUT_MS = 150
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = 30_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 1000L
        // 1280 (IPv6 minimum), not 1500: on double-hop servers (bridge → exit, e.g.
        // BRIDGE-Нидерланды 2) the inner packet gets VLESS/Reality-encapsulated TWICE.
        // A 1500-byte TUN packet then overflows the real path MTU and, when PMTUD is
        // black-holed (typical on RU mobile), large packets (TLS handshakes, page
        // bodies) get dropped → sites won't load on double-hop while single-hop works.
        // 1280 leaves headroom for the double encapsulation so traffic flows everywhere.
        private const val TUN_MTU = 1500
        private const val TUN_IPV4_ADDRESS = "10.0.88.88"
        private const val IPV4_PREFIX_LENGTH = 24
        // ULA-адрес для IPv6-плеча TUN (захват v6, чтоб не утекал мимо туннеля). /128 — точечный
        // адрес интерфейса; маршрут ::/0 заворачивает весь v6-трафик в hev-socks5-tunnel.
        private const val TUN_IPV6_ADDRESS = "fdfe:dcba:9876::1"
        private const val IPV6_PREFIX_LENGTH = 128
        private const val MAPDNS_ADDRESS = "1.1.1.1"
        private const val MAPDNS_NETWORK = "100.64.0.0"
        private const val MAPDNS_NETMASK = "255.192.0.0"
        private const val NOTIFICATION_CHANNEL_ID = "olcbox_vpn"
        private const val NOTIFICATION_ID = 100
        private const val TAG = "OlcboxVpnService"

        private fun addLog(msg: String) {
            OlcboxVpnState.addLog(msg)
        }
    }
}
