package org.olcbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

internal class WindowsTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false
    // IP-адреса VPN-сервера, которые пускаем в обход TUN (см. start()).
    private var serverBypassIps: List<String> = emptyList()

    suspend fun start(
        tun2SocksBinary: Path,
        socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
        serverHost: String? = null
    ): Process {
        ensureAdministratorOrRequestRestart()

        // Резолвим адрес сервера ДО поднятия TUN (пока активен системный DNS), чтобы
        // добавить для него /32-маршрут в обход туннеля. Без этого соединение движка к
        // самому серверу заворачивается в TUN → петля → WSAENOBUFS на Windows.
        serverBypassIps = resolveServerIps(serverHost)

        val process = ProcessBuilder(tun2SocksCommand(tun2SocksBinary, socksPort))
            .directory(tun2SocksBinary.parent.toFile())
            .redirectErrorStream(true)
            .start()

        try {
            waitForAdapter(process)
            installRoutes()
            routesInstalled = true
            addLog("Windows TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            runCatching { removeRoutes() }
                .onFailure { addLog("Windows TUN partial route cleanup failed: ${it.message}") }
            routesInstalled = false
            stopProcess(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        if (routesInstalled) {
            runCatching { removeRoutes() }
                .onFailure { addLog("Windows TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
        }

        stopProcess(process)
    }

    suspend fun ensureAdministratorOrRequestRestart() {
        if (isAdministrator()) return

        addLog("Requesting Windows administrator privileges for TUN mode")
        requestAdministratorRestart()
        exitProcess(0)
    }

    private suspend fun isAdministrator(): Boolean {
        val isAdmin = runPowerShell(
            """
            ${'$'}principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
            if (${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { 'true' } else { 'false' }
            """.trimIndent()
        ).trim().equals("true", ignoreCase = true)

        return isAdmin
    }

    private suspend fun requestAdministratorRestart() {
        val processInfo = ProcessHandle.current().info()
        val currentCommand = processInfo.command().orElse(null)
            ?: error("Olcbox cannot resolve its Windows launcher for administrator restart")
        val currentArguments = processInfo.arguments().orElse(emptyArray()).toList()
        val restartArguments = if (ELEVATED_START_ARGUMENT in currentArguments) {
            currentArguments
        } else {
            currentArguments + ELEVATED_START_ARGUMENT
        }

        runPowerShell(
            restartAsAdministratorScript(
                command = currentCommand,
                arguments = restartArguments,
                workingDirectory = System.getProperty("user.dir").orEmpty()
            )
        )
    }

    private suspend fun waitForAdapter(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                error(
                    buildString {
                        append("tun2socks exited before $TUN_NAME was ready")
                        if (output.isNotBlank()) append(": ").append(output)
                    }
                )
            }

            if (adapterExists()) return
            delay(TUN_READY_POLL_MS)
        }

        error("$TUN_NAME adapter was not created")
    }

    private suspend fun adapterExists(): Boolean {
        return runCatching {
            runPowerShell(
                """
                ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
                if (${'$'}null -ne ${'$'}adapter) { 'true' } else { 'false' }
                """.trimIndent()
            ).trim().equals("true", ignoreCase = true)
        }.getOrDefault(false)
    }

    /** Резолвит host сервера в IPv4-адреса (или возвращает сам IP, если это литерал). */
    private fun resolveServerIps(host: String?): List<String> {
        val h = host?.trim().orEmpty()
        if (h.isEmpty()) return emptyList()
        return runCatching {
            java.net.InetAddress.getAllByName(h)
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress }
                .distinct()
        }.getOrElse {
            if (h.matches(Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}"""))) listOf(h) else emptyList()
        }
    }

    /** PowerShell-блок: /32-маршруты к серверу через физический шлюз (в обход TUN). */
    private fun serverBypassInstallScript(): String {
        if (serverBypassIps.isEmpty()) return ""
        val ipArray = serverBypassIps.joinToString(",") { "'$it'" }
        return """
            ${'$'}phys = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.InterfaceAlias -ne '$TUN_NAME' } |
              Sort-Object RouteMetric | Select-Object -First 1
            if (${'$'}phys) {
              foreach (${'$'}ip in @($ipArray)) {
                Get-NetRoute -DestinationPrefix "${'$'}ip/32" -ErrorAction SilentlyContinue |
                  Where-Object { ${'$'}_.InterfaceAlias -ne '$TUN_NAME' } |
                  Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
                New-NetRoute -DestinationPrefix "${'$'}ip/32" -NextHop ${'$'}phys.NextHop -InterfaceIndex ${'$'}phys.InterfaceIndex -RouteMetric 1 -ErrorAction SilentlyContinue | Out-Null
              }
            }
        """.trimIndent()
    }

    private suspend fun installRoutes() {
        runPowerShell(
            """
            ${'$'}ErrorActionPreference = 'Stop'
            ${serverBypassInstallScript()}
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction Stop
            ${'$'}ifIndex = ${'$'}adapter.ifIndex

            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetIPAddress -InterfaceIndex ${'$'}ifIndex -IPAddress '$TUN_IPV4_ADDRESS' -PrefixLength $TUN_IPV4_PREFIX_LENGTH -AddressFamily IPv4 | Out-Null

            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue

            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            New-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -NextHop '0.0.0.0' -RouteMetric 1 | Out-Null
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ServerAddresses '$MAPDNS_ADDRESS'
            """.trimIndent()
        )
    }

    /** PowerShell-блок: снять /32-маршруты обхода к серверу. */
    private fun serverBypassRemoveScript(): String {
        if (serverBypassIps.isEmpty()) return ""
        val ipArray = serverBypassIps.joinToString(",") { "'$it'" }
        return """
            foreach (${'$'}ip in @($ipArray)) {
              Get-NetRoute -DestinationPrefix "${'$'}ip/32" -ErrorAction SilentlyContinue |
                Where-Object { ${'$'}_.InterfaceAlias -ne '$TUN_NAME' } |
                Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            }
        """.trimIndent()
    }

    private suspend fun removeRoutes() {
        runPowerShell(
            """
            ${serverBypassRemoveScript()}
            ${'$'}adapter = Get-NetAdapter -Name '$TUN_NAME' -ErrorAction SilentlyContinue
            if (${'$'}null -eq ${'$'}adapter) { exit 0 }
            ${'$'}ifIndex = ${'$'}adapter.ifIndex
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '0.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Get-NetRoute -InterfaceIndex ${'$'}ifIndex -DestinationPrefix '128.0.0.0/1' -ErrorAction SilentlyContinue |
              Remove-NetRoute -Confirm:${'$'}false -ErrorAction SilentlyContinue
            Set-DnsClientServerAddress -InterfaceIndex ${'$'}ifIndex -ResetServerAddresses -ErrorAction SilentlyContinue
            Get-NetIPAddress -InterfaceIndex ${'$'}ifIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
              Where-Object { ${'$'}_.IPAddress -eq '$TUN_IPV4_ADDRESS' } |
              Remove-NetIPAddress -Confirm:${'$'}false -ErrorAction SilentlyContinue
            """.trimIndent()
        )
    }

    private suspend fun runPowerShell(script: String): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            script
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        // Жёсткий таймаут: без него зависший PowerShell-вызов держит подключение
        // в состоянии «подключаюсь» бесконечно (на Windows наблюдалось у пользователя).
        if (!process.waitFor(POWERSHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            runCatching { process.destroyForcibly() }
            error("PowerShell timed out after ${POWERSHELL_TIMEOUT_MS}ms")
        }
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            error("PowerShell failed with code $exitCode: $output")
        }
        output
    }

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "Olcbox"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val TUN_IPV4_PREFIX_LENGTH = 24
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val POWERSHELL_TIMEOUT_MS = 30_000L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
        const val ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

        fun tun2SocksCommand(
            tun2SocksBinary: Path,
            socksPort: Int = PacServer.LOCAL_SOCKS_PORT
        ): List<String> = listOf(
            tun2SocksBinary.toString(),
            "--device",
            TUN_NAME,
            "--proxy",
            "socks5://${PacServer.LOCAL_SOCKS_HOST}:$socksPort",
            "--mtu",
            TUN_MTU.toString(),
            "--loglevel",
            "warn"
        )

        fun restartAsAdministratorScript(
            command: String,
            arguments: List<String>,
            workingDirectory: String
        ): String {
            val quotedArguments = arguments
                .joinToString(separator = " ") { it.windowsCommandLineArgument() }
                .powershellLiteral()
            val workingDirectoryLine = workingDirectory
                .takeIf { it.isNotBlank() }
                ?.let { "  WorkingDirectory = ${it.powershellLiteral()}" }
                .orEmpty()

            return """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}startArgs = @{
                  FilePath = ${command.powershellLiteral()}
                  Verb = 'RunAs'
                  ArgumentList = $quotedArguments
                $workingDirectoryLine
                }
                Start-Process @startArgs | Out-Null
            """.trimIndent()
        }

        private fun String.powershellLiteral(): String = "'${replace("'", "''")}'"

        private fun String.windowsCommandLineArgument(): String {
            if (isEmpty()) return "\"\""
            if (none { it.isWhitespace() || it == '"' }) return this

            val quoted = StringBuilder("\"")
            var pendingBackslashes = 0
            for (char in this) {
                when (char) {
                    '\\' -> pendingBackslashes++
                    '"' -> {
                        repeat(pendingBackslashes * 2 + 1) { quoted.append('\\') }
                        quoted.append(char)
                        pendingBackslashes = 0
                    }
                    else -> {
                        repeat(pendingBackslashes) { quoted.append('\\') }
                        pendingBackslashes = 0
                        quoted.append(char)
                    }
                }
            }
            repeat(pendingBackslashes * 2) { quoted.append('\\') }
            return quoted.append('"').toString()
        }
    }
}
