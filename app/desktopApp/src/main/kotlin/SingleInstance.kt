import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * Одна копия клиента на пользователя Windows. Первая копия держит замок
 * %USERPROFILE%\.reedvpn\instance.lock (ОС снимает его сама, если процесс упал) и слушает
 * 127.0.0.1 на случайном порту (порт — в instance.port). Повторный запуск шлёт ей «SHOW»
 * и выходит: пользователь видит уже открытое окно.
 * Если работает копия другой версии (пережила обновление в трее), она по «SHOW» сама
 * закрывается и уступает место новой.
 */
object SingleInstance {
    private val dir = File(System.getProperty("user.home"), ".reedvpn").apply { mkdirs() }
    private val lockFile = File(dir, "instance.lock")
    private val portFile = File(dir, "instance.port")
    private val version = System.getProperty("jpackage.app-version").orEmpty()

    @Volatile private var lock: FileLock? = null
    @Volatile var onShow: () -> Unit = {}
    @Volatile var onReplace: () -> Unit = {}

    /**
     * true — мы единственная копия, можно запускать окно. false — окно уже есть, надо выйти.
     * waitMs: перезапуск с правами администратора стартует, пока старая копия ещё закрывается.
     */
    fun acquire(waitMs: Long = 0): Boolean {
        val start = System.currentTimeMillis()
        var replacing = false
        while (true) {
            if (tryLock()) {
                startServer()
                return true
            }
            val elapsed = System.currentTimeMillis() - start
            if (!replacing && elapsed >= waitMs) {
                // Замок занят: зовём окно первой копии. Нет ответа (зависла) — вторую всё равно не плодим.
                if (notifyRunning() != "REPLACE") return false
                replacing = true
            }
            if (replacing && elapsed >= waitMs + REPLACE_WAIT_MS) return false
            Thread.sleep(250)
        }
    }

    private fun tryLock(): Boolean = runCatching {
        val channel = RandomAccessFile(lockFile, "rw").channel
        val l = channel.tryLock()
        if (l == null) { channel.close(); false } else { lock = l; true }
    }.getOrDefault(false)

    private fun notifyRunning(): String? = runCatching {
        val port = portFile.readText().trim().toInt()
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1500)
            s.soTimeout = 2000
            s.getOutputStream().write("SHOW $version\n".toByteArray())
            s.getOutputStream().flush()
            s.getInputStream().bufferedReader().readLine()?.trim()
        }
    }.getOrNull()

    private fun startServer() {
        val server = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        runCatching { portFile.writeText(server.localPort.toString()) }
        thread(isDaemon = true, name = "reed-single-instance") {
            while (true) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { s ->
                        s.soTimeout = 2000
                        val line = s.getInputStream().bufferedReader().readLine()?.trim().orEmpty()
                        if (!line.startsWith("SHOW")) return@use
                        val other = line.removePrefix("SHOW").trim()
                        val replace = other.isNotEmpty() && version.isNotEmpty() && other != version
                        s.getOutputStream().write((if (replace) "REPLACE\n" else "OK\n").toByteArray())
                        s.getOutputStream().flush()
                        if (replace) onReplace() else onShow()
                    }
                }
            }
        }
    }

    private const val REPLACE_WAIT_MS = 15_000L
}
