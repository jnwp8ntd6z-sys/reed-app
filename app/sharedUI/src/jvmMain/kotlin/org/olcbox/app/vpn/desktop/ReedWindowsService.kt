package org.olcbox.app.vpn.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.olcbox.app.data.model.LocationConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Windows 3.0, этап 3 — служба «Reed Client Service» (как helper-служба Happ).
 *
 * Работает под LocalSystem (её ставит установщик через WinSW), держит sing-box с TUN, поэтому окно
 * приложения запускается без прав администратора и без UAC при каждом подключении.
 *
 * Управление — HTTP на 127.0.0.1:[PORT], три команды: /start, /stop, /status. Безопасность:
 *  - конфиг служба НЕ принимает от клиента: получает ключ/сервер и сама берёт конфиг с reedapp.ru
 *    (или строит из ключа «vless://…» тем же кодом, что приложение) → чужой процесс не подсунет
 *    sing-box под SYSTEM свой log.output / пути файлов;
 *  - нужен заголовок X-Reed-Client и Host 127.0.0.1 — страница в браузере такой запрос без
 *    preflight не пошлёт (CORS), а preflight служба не обслуживает.
 * Худшее, что может посторонняя локальная программа, — включить/выключить подключение.
 */
object ReedWindowsService {
    const val PORT = 47613
    const val HEADER = "X-Reed-Client"
    /** Порт mixed-входа sing-box (подписка при включённом туннеле, будущий режим прокси). */
    const val MIXED_PORT = 10810

    private const val READY_TIMEOUT_MS = 30_000L
    private const val LOG_LIMIT = 400

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class StartRequest(val location: LocationConfig, val split: Boolean = true)

    @Serializable
    data class Status(
        val state: String,          // off | connecting | on | error
        val error: String? = null,
        val serverId: String? = null,
        val generation: Long = 0,
        val logFrom: Int = 0,
        val logs: List<String> = emptyList(),
        val version: String = "",
    )

    // ── состояние службы ─────────────────────────────────────────────────
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "reed-service-worker").apply { isDaemon = true } }
    @Volatile private var state = "off"
    @Volatile private var error: String? = null
    @Volatile private var serverId: String? = null
    @Volatile private var generation = 0L
    @Volatile private var process: Process? = null
    private val logs = ArrayList<String>()
    private var logBase = 0 // номер первой строки в logs (кольцевой буфер)

    private fun log(line: String) = synchronized(logs) {
        logs.add(line)
        if (logs.size > LOG_LIMIT) { logs.removeAt(0); logBase++ }
    }

    /** Точка входа службы: `java -jar reed.jar --service`. Блокирует поток до остановки службы. */
    fun run() {
        log("Reed Client Service ${version()} запущена")
        Runtime.getRuntime().addShutdownHook(Thread { stopProcess() })
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 8)
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/status") { ex -> handle(ex, needPost = false) { statusJson(ex) } }
        server.createContext("/start") { ex -> handle(ex, needPost = true) { body -> start(body) } }
        server.createContext("/stop") { ex -> handle(ex, needPost = true) { _ -> stop(); statusJson(null) } }
        server.start()
        Thread.currentThread().join()
    }

    private fun handle(ex: HttpExchange, needPost: Boolean, action: (String) -> String) {
        try {
            val host = ex.requestHeaders.getFirst("Host").orEmpty()
            val okHost = host == "127.0.0.1:$PORT" || host == "localhost:$PORT"
            if (!okHost || ex.requestHeaders.getFirst(HEADER) != "1" ||
                (needPost && ex.requestMethod != "POST")) {
                reply(ex, 403, """{"error":"forbidden"}"""); return
            }
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            reply(ex, 200, action(body))
        } catch (e: Throwable) {
            runCatching { reply(ex, 500, """{"error":${kotlinx.serialization.json.JsonPrimitive(e.message ?: "error")}}""") }
        }
    }

    private fun reply(ex: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun statusJson(ex: HttpExchange?): String {
        val from = ex?.requestURI?.rawQuery?.split('&')?.firstOrNull { it.startsWith("from=") }
            ?.removePrefix("from=")?.toIntOrNull() ?: 0
        // logFrom — сквозной номер первой отданной строки; окно спрашивает дальше с from = logFrom + logs.size.
        val (firstAbs, tail) = synchronized(logs) {
            val start = (from - logBase).coerceIn(0, logs.size)
            (logBase + start) to logs.subList(start, logs.size).toList()
        }
        return json.encodeToString(Status.serializer(), Status(
            state = state, error = error, serverId = serverId, generation = generation,
            logFrom = firstAbs, logs = tail, version = version(),
        ))
    }

    // ── подключение ──────────────────────────────────────────────────────
    private fun start(body: String): String {
        val req = json.decodeFromString(StartRequest.serializer(), body)
        val location = req.location.normalized()
        require(location.isVless()) { "Этот сервер недоступен на компьютере" }
        require(location.isComplete()) { "Сервер без ключа" }
        val gen = synchronized(lock) {
            generation++
            state = "connecting"; error = null; serverId = location.id
            generation
        }
        worker.execute { connect(location, req.split, gen) }
        return statusJson(null)
    }

    private fun stop() {
        synchronized(lock) { generation++ }
        worker.execute {
            stopProcess()
            synchronized(lock) { state = "off"; error = null }
            log("Отключено")
        }
    }

    private fun connect(location: LocationConfig, split: Boolean, gen: Long) {
        fun superseded() = gen != generation
        try {
            stopProcess()
            if (superseded()) return
            val binary = singBoxBinary()
            WindowsSingBoxTun.killStaleProcesses(binary, ::log)
            val serverConfig = SingBoxDesktopRunner.fetchConfig(location, MIXED_PORT, splitRouting = split)
            if (superseded()) return
            val config = WindowsSingBoxTun.toTunConfig(serverConfig, MIXED_PORT)
            val dir = dataDir().also { Files.createDirectories(it) }
            val configPath = dir.resolve("singbox-tun.json")
            Files.writeString(configPath, config, StandardCharsets.UTF_8)

            log("Подключаю ${location.id} (РФ напрямую: ${if (split) "да" else "нет"})")
            val p = ProcessBuilder(binary.toString(), "run", "-c", configPath.toString())
                .directory(dir.toFile()).redirectErrorStream(true).start()
            synchronized(lock) { process = p }
            Thread({
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { log("vless: $it") } }
                // Движок завершился сам (не по команде stop) — сообщаем окну.
                synchronized(lock) {
                    if (process === p) {
                        process = null
                        if (state == "on" || state == "connecting") {
                            state = "error"; error = "sing-box завершился (код ${runCatching { p.exitValue() }.getOrNull()})"
                        }
                    }
                }
            }, "reed-singbox-log").apply { isDaemon = true }.start()

            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (superseded()) { stopProcess(); return }
                if (!p.isAlive) error("sing-box exited before start")
                if (portOpen(MIXED_PORT)) break
                Thread.sleep(200)
            }
            if (!portOpen(MIXED_PORT)) error("sing-box start timed out")
            Thread.sleep(700)
            if (!p.isAlive) error("sing-box exited after start (TUN)")
            synchronized(lock) { if (!superseded()) { state = "on"; error = null } }
            log("Подключено: TUN «${WindowsSingBoxTun.INTERFACE_NAME}»")
        } catch (e: Throwable) {
            stopProcess()
            synchronized(lock) { if (!superseded()) { state = "error"; error = e.message ?: e.javaClass.simpleName } }
            log("Ошибка подключения: ${e.message}")
        }
    }

    private fun stopProcess() {
        val p = synchronized(lock) { process.also { process = null } } ?: return
        if (!p.isAlive) return
        p.toHandle().descendants().forEach { it.destroy() }
        p.destroy()
        if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); p.waitFor(1, TimeUnit.SECONDS) }
    }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
    }.isSuccess

    // ── пути ─────────────────────────────────────────────────────────────
    /** Папка установки: …\Reed Client (jar лежит в …\Reed Client\app\reed.jar). */
    private fun installDir(): Path? = runCatching {
        val jar = Path(ReedWindowsService::class.java.protectionDomain.codeSource.location.toURI().path.removePrefix("/"))
        jar.parent?.parent
    }.getOrNull()

    /**
     * sing-box из папки установки (Program Files — пишет только администратор). Из jar во временную
     * папку НЕ распаковываем: исполняемый файл в папке, куда может писать пользователь, запущенный
     * от SYSTEM, = повышение прав.
     */
    private fun singBoxBinary(): Path {
        val fromInstall = installDir()?.resolve("bin")?.resolve(DesktopNativeAssets.singBoxFileName())
        if (fromInstall != null && fromInstall.exists()) return fromInstall
        return DesktopNativeAssets.resolveSingBoxBinary() // профиль SYSTEM — тоже недоступен пользователю
    }

    private fun dataDir(): Path = installDir()?.resolve("data")
        ?: Path(System.getProperty("user.home"), ".reedvpn", "service")

    private fun version(): String = System.getProperty("jpackage.app-version") ?: "dev"
}
