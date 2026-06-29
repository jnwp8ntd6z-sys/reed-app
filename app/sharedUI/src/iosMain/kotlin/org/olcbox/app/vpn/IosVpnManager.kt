package org.olcbox.app.vpn

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ios.IosBridgeResult
import org.olcbox.app.ios.IosLogWriter
import org.olcbox.app.ios.IosOlcRtcBridge
import org.olcbox.app.ios.IosOlcRtcCheckRequest
import org.olcbox.app.ios.IosOlcRtcStartRequest
import org.olcbox.app.ios.IosSingBoxBridge
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import platform.Foundation.NSUserDefaults

class IosVpnManager(
    private val locationsRepository: LocationsRepository,
    private val olcRtcBridge: IosOlcRtcBridge,
    private val singBoxBridge: IosSingBoxBridge
) : VpnManager {

    // Активен ли VLESS (sing-box) вместо olcRTC в текущей сессии.
    private var vlessActive = false
    private val httpClient by lazy { HttpClient(Darwin) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _socksProxySettings = MutableStateFlow(loadSocksProxySettings())
    val socksProxySettings: StateFlow<ApplicationSocksProxySettings> = _socksProxySettings.asStateFlow()

    private var operationJob: Job? = null
    private var generation = 0L

    init {
        olcRtcBridge.setLogWriter(object : IosLogWriter {
            override fun writeLog(message: String) {
                message
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { addLog("rtc: $it") }
            }
        })
        singBoxBridge.setLogWriter(object : IosLogWriter {
            override fun writeLog(message: String) {
                message
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { addLog("vless: $it") }
            }
        })
    }

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestedGeneration = ++generation
        operationJob = scope.launch {
            mutex.withLock {
                if (requestedGeneration != generation) return@withLock

                val shouldRestart = _status.value is VpnStatus.Connected ||
                    _status.value is VpnStatus.Connecting ||
                    _status.value is VpnStatus.Reconnecting ||
                    transportRunning()

                if (shouldRestart) {
                    setStatus(VpnStatus.Reconnecting)
                    addLog("Restarting iOS SOCKS connection")
                    stopTransport()
                    if (requestedGeneration != generation) return@withLock
                }

                startTransport(requestedGeneration, isRestart = shouldRestart)
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob = scope.launch {
            mutex.withLock {
                setStatus(VpnStatus.Stopping)
                stopTransport()
                setStatus(VpnStatus.Disconnected)
                addLog("iOS SOCKS stopped")
            }
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? {
        return runCheck(locationConfig) { request -> olcRtcBridge.ping(request) }
    }

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? {
        return runCheck(locationConfig) { request -> olcRtcBridge.check(request) }
    }

    fun updateSocksProxySettings(username: String, password: String, port: Int) {
        val settings = ApplicationSocksProxySettings(
            port = sanitizePort(port),
            username = username.trim().take(MAX_CREDENTIAL_LENGTH).ifBlank { generateCredential(USERNAME_LENGTH) },
            password = password.trim().take(MAX_CREDENTIAL_LENGTH).ifBlank { generateCredential(PASSWORD_LENGTH) }
        )
        _socksProxySettings.value = settings
        saveSocksProxySettings(settings)
    }

    fun regenerateSocksProxyPassword() {
        val current = _socksProxySettings.value
        updateSocksProxySettings(
            username = current.username,
            password = generateCredential(PASSWORD_LENGTH),
            port = current.port
        )
    }

    fun close() {
        generation++
        runCatching { olcRtcBridge.setLogWriter(null) }
        runCatching { olcRtcBridge.stop() }
        runCatching { singBoxBridge.setLogWriter(null) }
        runCatching { singBoxBridge.stop() }
        runCatching { httpClient.close() }
        scope.cancel()
    }

    private suspend fun startTransport(requestedGeneration: Long, isRestart: Boolean) {
        setStatus(if (isRestart) VpnStatus.Reconnecting else VpnStatus.Connecting)

        val active = locationsRepository.getActiveLocation()
        val location = active?.location?.normalized()

        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting iOS SOCKS")
            return
        }

        val socksSettings = _socksProxySettings.value

        val result = if (location.isVless()) {
            startVless(location, socksSettings.port)
        } else {
            val deviceId = locationsRepository.getDeviceIdentity()
            val request = location.startRequest(deviceId, socksSettings)
            addLog(
                "Starting iOS SOCKS provider=${location.bypassProvider}, " +
                    "transport=${location.transport}, room=${location.id}, port=${socksSettings.port}"
            )
            withContext(Dispatchers.Default) { olcRtcBridge.start(request) }
        }

        if (requestedGeneration != generation) return

        if (result.success) {
            setStatus(VpnStatus.Connected)
            addLog("iOS SOCKS ready on 127.0.0.1:${socksSettings.port}")
        } else {
            val message = result.message ?: "transport start failed"
            setStatus(VpnStatus.Error(message))
            addLog("iOS SOCKS start failed: $message")
            stopTransport()
        }
    }

    /** Запуск VLESS на iOS: качаем sing-box-конфиг и поднимаем ядро на локальном SOCKS5. */
    private suspend fun startVless(
        location: LocationConfig,
        socksPort: Int
    ): IosBridgeResult {
        addLog("Starting iOS VLESS server=${location.id}, port=$socksPort")
        val configJson = fetchSingboxConfig(location.key, location.id, socksPort)
            ?: return IosBridgeResult(success = false, message = "Failed to fetch VLESS config")
        val result = withContext(Dispatchers.Default) { singBoxBridge.start(configJson) }
        if (result.success) vlessActive = true
        return result
    }

    private suspend fun fetchSingboxConfig(token: String, server: String, socksPort: Int): String? {
        // «Любой» (чужой) ключ: token — это сам URI. Конфиг sing-box собираем локально.
        if (token.contains("://")) {
            val parsed = org.olcbox.app.data.datasource.ProxyKeyImport.parseUri(token)
            if (parsed != null) {
                return org.olcbox.app.data.datasource.ProxyKeyImport
                    .buildSingboxConfig(parsed.outbound, socksPort)
            }
            addLog("Imported key not recognized ($server)")
            return null
        }
        val fetched = runCatching {
            withContext(Dispatchers.Default) {
                // Временный VPN (id=reed-temp) — БЕЗ токена с /app/temp (работает до входа).
                if (server == "reed-temp") {
                    httpClient.get("$REED_API_BASE/app/temp") {
                        url { parameters.append("socks_port", socksPort.toString()) }
                    }.bodyAsText().takeIf { it.isNotBlank() }
                } else {
                    httpClient.get("$REED_API_BASE/app/singbox") {
                        url {
                            parameters.append("token", token)
                            parameters.append("socks_port", socksPort.toString())
                            parameters.append("server", server)
                            parameters.append("split", if (org.olcbox.app.data.reed.ReedSession.splitRouting) "1" else "0")
                        }
                    }.bodyAsText().takeIf { it.isNotBlank() }
                }
            }
        }.getOrElse {
            addLog("VLESS config fetch failed: ${it.message}")
            null
        }
        if (fetched != null) {
            // Кэшируем последний рабочий конфиг — подключение без интернета (как HAPP).
            NSUserDefaults.standardUserDefaults.setObject(fetched, singboxCacheKey(server, socksPort))
            return fetched
        }
        // API недоступен (нет сети / белые списки) — берём последний рабочий конфиг из кэша.
        val cached = NSUserDefaults.standardUserDefaults.stringForKey(singboxCacheKey(server, socksPort))
            ?.takeIf { it.isNotBlank() }
        if (cached != null) {
            addLog("VLESS config from offline cache (API unreachable)")
            return cached
        }
        // Временный VPN на свежей установке без доступа к API — вшитый конфиг для бутстрапа.
        if (server == "reed-temp") {
            val baked = org.olcbox.app.data.reed.bakedTempSingboxConfig(socksPort)
            NSUserDefaults.standardUserDefaults.setObject(baked, singboxCacheKey(server, socksPort))
            addLog("temp VLESS config from baked-in (API unreachable, fresh install)")
            return baked
        }
        addLog("VLESS config unavailable (no network, no cache)")
        return cached
    }

    private fun singboxCacheKey(server: String, socksPort: Int): String {
        val safe = server.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        return "singbox_cache_${safe}_$socksPort"
    }

    private fun transportRunning(): Boolean =
        if (vlessActive) singBoxBridge.isRunning() else olcRtcBridge.isRunning()

    private fun stopTransport() {
        if (vlessActive) {
            runCatching { singBoxBridge.stop() }
            vlessActive = false
        } else {
            runCatching { olcRtcBridge.stop() }
        }
    }

    private suspend fun runCheck(
        locationConfig: LocationConfig,
        block: (IosOlcRtcCheckRequest) -> org.olcbox.app.ios.IosLongResult
    ): Long? = withContext(Dispatchers.Default) {
        val config = locationConfig.normalized()
        if (!config.isComplete()) return@withContext null
        val request = IosOlcRtcCheckRequest(
            carrierName = config.bypassProvider,
            transportName = config.transport,
            roomId = config.id,
            clientId = locationsRepository.getDeviceIdentity(),
            keyHex = config.key,
            timeoutMillis = CHECK_TIMEOUT_MS,
            pingUrl = HTTP_PING_URL,
            vp8Fps = config.vp8Fps,
            vp8BatchSize = config.vp8Batch
        )
        val result = block(request)
        if (result.success && result.valueMillis >= 0L) result.valueMillis else null
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        _logs.value = (_logs.value + message).takeLast(MAX_LOG_LINES)
    }

    private fun LocationConfig.startRequest(
        deviceId: String,
        settings: ApplicationSocksProxySettings
    ): IosOlcRtcStartRequest {
        val config = normalized()
        return IosOlcRtcStartRequest(
            carrierName = config.bypassProvider,
            transportName = config.transport,
            roomId = config.id,
            clientId = deviceId,
            keyHex = config.key,
            socksPort = settings.port,
            socksUser = settings.username,
            socksPass = settings.password,
            vp8Fps = config.vp8Fps,
            vp8BatchSize = config.vp8Batch
        )
    }

    private fun loadSocksProxySettings(): ApplicationSocksProxySettings {
        val defaults = NSUserDefaults.standardUserDefaults
        val port = sanitizePort(defaults.integerForKey(KEY_SOCKS_PORT).toInt())
        val username = defaults.stringForKey(KEY_SOCKS_USERNAME)
            ?.takeIf { it.isNotBlank() }
            ?: generateCredential(USERNAME_LENGTH)
        val password = defaults.stringForKey(KEY_SOCKS_PASSWORD)
            ?.takeIf { it.isNotBlank() }
            ?: generateCredential(PASSWORD_LENGTH)
        return ApplicationSocksProxySettings(
            port = port,
            username = username,
            password = password
        ).also { saveSocksProxySettings(it) }
    }

    private fun saveSocksProxySettings(settings: ApplicationSocksProxySettings) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setInteger(settings.port.toLong(), KEY_SOCKS_PORT)
        defaults.setObject(settings.username, KEY_SOCKS_USERNAME)
        defaults.setObject(settings.password, KEY_SOCKS_PASSWORD)
    }

    private fun sanitizePort(port: Int): Int {
        return if (ApplicationSocksProxySettings.isValidPort(port)) {
            port
        } else {
            ApplicationSocksProxySettings.DEFAULT_PORT
        }
    }

    private fun generateCredential(length: Int): String {
        val boundedLength = min(max(length, 1), MAX_CREDENTIAL_LENGTH)
        return buildString(boundedLength) {
            repeat(boundedLength) {
                append(CREDENTIAL_ALPHABET[Random.nextInt(CREDENTIAL_ALPHABET.length)])
            }
        }
    }

    private companion object {
        const val KEY_SOCKS_PORT = "ios_socks_port"
        const val KEY_SOCKS_USERNAME = "ios_socks_username"
        const val KEY_SOCKS_PASSWORD = "ios_socks_password"
        const val USERNAME_LENGTH = 12
        const val PASSWORD_LENGTH = 24
        const val MAX_CREDENTIAL_LENGTH = 64
        const val MAX_LOG_LINES = 500
        const val CHECK_TIMEOUT_MS = 8_000L
        const val HTTP_PING_URL = "https://www.google.com/generate_204"
        const val CREDENTIAL_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        const val REED_API_BASE = "https://reed-vpn.duckdns.org"
    }
}
