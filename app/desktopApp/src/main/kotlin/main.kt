import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.security.SecureRandom
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.olcbox.app.ui.reed2.DesktopReed2AppContent
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.flow.first
import org.olcbox.app.data.reed.ReedSession
import androidx.compose.ui.input.key.type
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.olcbox.app.CurrentAppInfo
import org.olcbox.app.data.datasource.JvmLocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.JvmLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.JvmConfigImporter
import org.olcbox.app.data.share.ConfigShareService
import org.olcbox.app.data.share.SubscriptionShareItem
import org.olcbox.app.ui.OlcboxAppContent
import org.olcbox.app.ui.components.ApplicationSocksProxySettings
import org.olcbox.app.ui.components.ApplicationSettingsSheet
import org.olcbox.app.ui.components.ApplicationUpdateOfferSheet
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateInfo
import org.olcbox.app.update.AppUpdateSettings
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.update.JvmUpdateInstaller
import org.olcbox.app.update.JvmUpdateSettingsStore
import org.olcbox.app.update.identity
import org.olcbox.app.update.isDownloaded
import org.olcbox.app.update.isUpdateCheckDue
import org.olcbox.app.update.shouldShowOffer
import org.olcbox.app.vpn.DesktopSocksProxySettings
import org.olcbox.app.vpn.DesktopVpnManager
import org.olcbox.app.vpn.JvmDesktopSocksProxySettingsStore

private class DesktopAppDependencies {
    private val locationsDataSource = JvmLocationsDataSourceImpl()
    val configImporter = JvmConfigImporter()

    val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
    val updateService = AppUpdateService(
        deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
    )
    val updateSettingsStore = JvmUpdateSettingsStore()
    val updateInstaller = JvmUpdateInstaller()
    val socksProxySettingsStore = JvmDesktopSocksProxySettingsStore()

    val vpnManager = DesktopVpnManager(locationsRepository)

    val homeViewModel = HomeScreenViewModel(
        vpnManager = vpnManager,
        locationsRepository = locationsRepository,
        configImporter = configImporter,
        logExporter = JvmLogExporter()
    )

    val locationViewModel = LocationViewModel(locationsRepository)

    fun close() {
        vpnManager.close()
    }
}

private const val WINDOWS_ELEVATED_START_ARGUMENT = "--olcbox-start-vpn-after-elevation"

// Трей раньше всегда показывал LinuxIcon.png (даже на Windows) — WindowsTrayIcon.png
// это PNG-кадр 32x32, извлечённый из appIcons/WindowsIcon.ico для корректного значка в трее.
private fun trayIconResourceName(): String {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return if ("win" in osName) "WindowsTrayIcon.png" else "LinuxIcon.png"
}

fun main(args: Array<String>) {
    if ("--selftest" in args) { runSelfTest(); return }
    reedApp(args)
}

private fun reedApp(args: Array<String>) = application {
    // Configure JNA to find native libraries in resources
    System.setProperty(
        "jna.library.path",
        System.getProperty("jna.library.path", "") +
                File.pathSeparator +
                File(System.getProperty("user.dir"), "native").absolutePath
    )

    val dependencies = remember { DesktopAppDependencies() }
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }
    var showDesktopSettings by remember { mutableStateOf(false) }
    var isWindowVisible by remember { mutableStateOf(true) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateSettings by remember { mutableStateOf(AppUpdateSettings()) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    var updateOffer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var sharePayload by remember { mutableStateOf<Pair<String, String>?>(null) }
    var desktopNotice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trayState = rememberTrayState()
    val trayHomeState by dependencies.homeViewModel.state.collectAsState()

    suspend fun saveUpdateSettings(settings: AppUpdateSettings) {
        val normalized = settings.normalized()
        updateSettings = normalized
        dependencies.updateSettingsStore.save(normalized)
    }

    fun checkUpdate(manual: Boolean) {
        scope.launch {
            val previousSettings = updateSettings
            val checkStartedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!manual && !previousSettings.isUpdateCheckDue(checkStartedAt)) return@launch

            updateMessage = "Checking ${previousSettings.channel.name.lowercase()}..."
            val result = dependencies.updateService.check(previousSettings.channel)
            val checkedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            val checkedSettings = previousSettings.copy(lastCheckAtEpochMs = checkedAt).normalized()
            saveUpdateSettings(checkedSettings)
            result.fold(
                onSuccess = { info ->
                    if (manual || info.shouldShowOffer(previousSettings, checkedAt)) {
                        if (info.isDownloaded(checkedSettings)) {
                            updateOffer = null
                            updateMessage = "Latest ${info.channel.name.lowercase()} is already downloaded"
                        } else if (info.isUpdateAvailable) {
                            updateOffer = info
                            updateMessage = "${info.channel.name} update found: ${info.version}"
                        } else {
                            updateOffer = null
                            updateMessage = "Reed Client is up to date"
                        }
                    } else {
                        updateOffer = null
                        updateMessage = null
                    }
                },
                onFailure = { error ->
                    updateMessage = error.message ?: "Update check failed"
                }
            )
        }
    }

    fun downloadUpdate(info: AppUpdateInfo) {
        scope.launch {
            updateProgress = 0f
            updateMessage = "Downloading ${info.asset.name}..."
            val result = dependencies.updateInstaller.downloadAndOpen(info.asset) { progress ->
                updateProgress = progress
            }
            updateMessage = result.getOrElse { error ->
                "Download failed: ${error.message ?: "unknown error"}"
            }
            if (result.isSuccess) {
                saveUpdateSettings(
                    updateSettings.copy(
                        lastSeenUpdateVersion = info.identity(),
                        lastDownloadedUpdateVersion = info.identity()
                    )
                )
                updateOffer = null
            }
            updateProgress = null
        }
    }

    fun postponeUpdate(info: AppUpdateInfo) {
        scope.launch {
            saveUpdateSettings(updateSettings.copy(lastSeenUpdateVersion = info.identity()))
            updateOffer = null
        }
    }

    LaunchedEffect(Unit) {
        val loaded = dependencies.updateSettingsStore.load()
        updateSettings = loaded
        dependencies.vpnManager.updateSocksProxySettings(dependencies.socksProxySettingsStore.load())
        checkUpdate(manual = false)
        if (WINDOWS_ELEVATED_START_ARGUMENT in args) {
            dependencies.homeViewModel.loadCurrentConfig {
                dependencies.homeViewModel.ToggleVpn()
            }
        }
    }

    LaunchedEffect(desktopNotice) {
        if (desktopNotice != null) {
            delay(1_800)
            desktopNotice = null
        }
    }

    Tray(
        state = trayState,
        icon = painterResource(trayIconResourceName()),
        tooltip = "Reed Client",
        menu = {
            Item("Открыть", onClick = { isWindowVisible = true })
            Item(
                if (trayHomeState.isVpnConnected || trayHomeState.isVpnLoading) "Отключить" else "Подключить",
                enabled = trayHomeState.isVpnConnected || trayHomeState.isVpnLoading || trayHomeState.canStartVpn,
                onClick = {
                    dependencies.homeViewModel.ToggleVpn()
                }
            )
            Separator()
            Item("Выход", onClick = {
                dependencies.close()
                exitApplication()
            })
        }
    )

    // Windows 3.0: раскладка под большой экран (боковая панель / центр / серверы).
    val windowState = rememberWindowState(width = 1100.dp, height = 720.dp)
    Window(
        title = "Reed Client",
        icon = painterResource(trayIconResourceName()),
        visible = isWindowVisible,
        state = windowState,
        onKeyEvent = { e ->
            // F11 — во весь экран и обратно, Esc — выйти из полноэкранного режима.
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
            else when (e.key) {
                androidx.compose.ui.input.key.Key.F11 -> {
                    windowState.placement = if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen)
                        androidx.compose.ui.window.WindowPlacement.Floating else androidx.compose.ui.window.WindowPlacement.Fullscreen
                    true
                }
                androidx.compose.ui.input.key.Key.Escape ->
                    if (windowState.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen) {
                        windowState.placement = androidx.compose.ui.window.WindowPlacement.Floating; true
                    } else false
                else -> false
            }
        },
        onCloseRequest = {
            isWindowVisible = false
        },
    ) {
        window.minimumSize = Dimension(900, 600)
        // Тёмная системная полоса заголовка (Windows 10 20H1+/11), чтобы не выбивалась из интерфейса.
        LaunchedEffect(Unit) { WindowsDarkTitleBar.apply(window) }

        DisposableEffect(Unit) {
            onDispose {
                dependencies.close()
            }
        }

        AppTheme {
            val logs by dependencies.homeViewModel.logs.collectAsState()
            val homeState by dependencies.homeViewModel.state.collectAsState()
            val socksProxySettings by dependencies.vpnManager.socksProxySettings.collectAsState()

            fun reloadLocationsAfterImport(onComplete: () -> Unit = {}) {
                dependencies.locationViewModel.loadLocations {
                    dependencies.homeViewModel.loadCurrentConfig(onComplete)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Reed 2.0: новый интерфейс (общие экраны с Android), только Wi-Fi-серверы.
                DesktopReed2AppContent(
                    homeViewModel = dependencies.homeViewModel,
                    locationViewModel = dependencies.locationViewModel,
                    onToggleClick = { dependencies.homeViewModel.ToggleVpn() },
                    onScanQr = { onResult -> readQrOrCodeFromDesktop(window)?.let(onResult) },
                )

                updateOffer?.let { info ->
                    ApplicationUpdateOfferSheet(
                        info = info,
                        downloadProgress = updateProgress,
                        onLater = { postponeUpdate(info) },
                        onDownload = { downloadUpdate(info) }
                    )
                }

                sharePayload?.let { (title, payload) ->
                    DesktopConfigShareOverlay(
                        title = title,
                        payload = payload,
                        onCopy = {
                            dependencies.configImporter.copyToClipboard(payload)
                            desktopNotice = "Copied"
                        },
                        onDismiss = {
                            sharePayload = null
                        }
                    )
                }

                desktopNotice?.let { notice ->
                    DesktopNotice(
                        text = notice,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopConfigShareOverlay(
    title: String,
    payload: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    var copied by remember(payload) { mutableStateOf(false) }
    val qrMatrix = remember(payload) {
        runCatching {
            MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 128, 128)
        }.getOrNull()
    }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            val noOpInteraction = remember { MutableInteractionSource() }

            Surface(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 440.dp)
                    .clickable(
                        interactionSource = noOpInteraction,
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (copied) "Copied to clipboard" else "Scan QR or copy the link",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }

                    if (qrMatrix != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(240.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            DesktopQrCode(
                                matrix = qrMatrix,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        SelectionContainer {
                            Text(
                                text = payload,
                                modifier = Modifier.padding(14.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onCopy()
                                copied = true
                            }
                        ) {
                            Text("Copy")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopQrCode(
    matrix: BitMatrix,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(Color.White)
        val cellSize = min(size.width / matrix.width, size.height / matrix.height)
        val qrWidth = cellSize * matrix.width
        val qrHeight = cellSize * matrix.height
        val left = (size.width - qrWidth) / 2f
        val top = (size.height - qrHeight) / 2f

        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(left + x * cellSize, top + y * cellSize),
                        size = Size(cellSize, cellSize)
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopNotice(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun DesktopSocksProxySettings.toApplicationSocksProxySettings(): ApplicationSocksProxySettings {
    return ApplicationSocksProxySettings(
        host = host,
        port = port,
        username = username,
        password = password
    )
}

private fun generateDesktopProxyPassword(length: Int = 24): String {
    val random = SecureRandom()
    return buildString(length) {
        repeat(length) {
            append(DESKTOP_PROXY_PASSWORD_ALPHABET[random.nextInt(DESKTOP_PROXY_PASSWORD_ALPHABET.length)])
        }
    }
}

private const val DESKTOP_PROXY_PASSWORD_ALPHABET =
    "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

private fun desktopSubscriptionItems(items: List<LocationItem>): List<SubscriptionShareItem> {
    return items
        .mapNotNull { item ->
            val url = item.subscriptionUrl
                ?.trim()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: return@mapNotNull null
            url to item
        }
        .groupBy({ it.first }, { it.second })
        .entries
        .sortedBy { it.key }
        .map { (url, subscriptionItems) ->
            val metadata = subscriptionItems.firstNotNullOfOrNull { it.metadata?.subscription }
            SubscriptionShareItem(
                url = url,
                name = metadata?.name?.takeIf { it.isNotBlank() }
                    ?: subscriptionItems.first().fullName,
                updateIntervalHours = metadata?.updateIntervalHours,
                lastRefreshAtEpochMs = metadata?.lastRefreshAtEpochMs,
                locationCount = subscriptionItems.size
            )
        }
}

private fun chooseConfigFile(owner: Frame): File? {
    val dialog = FileDialog(owner, "Import Olcbox Config", FileDialog.LOAD)
    dialog.isVisible = true

    return dialog.files.firstOrNull()
}

private fun chooseSaveFile(owner: Frame, defaultName: String): File? {
    val dialog = FileDialog(owner, "Save Reed VPN Logs", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true

    val fileName = dialog.file ?: return null
    val directory = dialog.directory ?: return File(fileName)

    return File(directory, fileName)
}

/**
 * «QR» на компьютере: камеры нет, поэтому берём код/ссылку из буфера обмена, иначе картинку
 * с QR из буфера (скриншот) или из файла и распознаём её.
 */
private fun readQrOrCodeFromDesktop(owner: Frame): String? {
    val cb = try { java.awt.Toolkit.getDefaultToolkit().systemClipboard } catch (e: Throwable) { null }
    try {
        val text = cb?.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        if (!text.isNullOrBlank()) return text.trim()
    } catch (e: Throwable) { }
    try {
        val img = cb?.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as? java.awt.Image
        if (img != null) decodeQr(img)?.let { return it }
    } catch (e: Throwable) { }
    val dialog = java.awt.FileDialog(owner, "Картинка с QR-кодом", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file?.let { File(dialog.directory, it) } ?: return null
    return try { javax.imageio.ImageIO.read(file)?.let { decodeQr(it) } } catch (e: Throwable) { null }
}

private fun decodeQr(image: java.awt.Image): String? {
    val w = image.getWidth(null); val h = image.getHeight(null)
    if (w <= 0 || h <= 0) return null
    val bi = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
    bi.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
    val px = IntArray(w * h); bi.getRGB(0, 0, w, h, px, 0, w)
    val src = com.google.zxing.RGBLuminanceSource(w, h, px)
    return try {
        com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(src)))
            .text?.trim()
    } catch (e: Throwable) { null }
}

/**
 * Самопроверка Windows-клиента (CI): вход по токену из REED_SELFTEST_TOKEN, импорт серверов аккаунта,
 * подключение к первому Wi-Fi-серверу через НАСТОЯЩИЙ TUN, запросы к российским и зарубежным сайтам
 * системной сетью (т.е. через туннель и сплит-роутинг), отчёт в REED_SELFTEST_REPORT. Окно не открывается.
 */
private fun runSelfTest() = kotlinx.coroutines.runBlocking {
    val out = StringBuilder()
    fun log(s: String) { println(s); out.appendLine(s) }
    val reportFile = File(System.getenv("REED_SELFTEST_REPORT") ?: "reed-selftest.txt")
    // Код проверяющих магазинов (REED-…) из секрета CI → sub_token. hwid постоянный, чтобы
    // каждая сборка занимала одно и то же место в «Устройствах», а не новое.
    val code = System.getenv("REED_SELFTEST_CODE").orEmpty().trim()
    val token = System.getenv("REED_SELFTEST_TOKEN").orEmpty().ifBlank {
        if (code.isBlank()) "" else try {
            val c = java.net.URL("https://reedapp.ru/app/code/login").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"code":"$code","hwid":"reed-ci-windows","name":"CI Windows","device_model":"GitHub Actions","device_os":"Windows"}""".toByteArray()) }
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            Regex("\"sub_token\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: run { println("вход по коду: ${body.take(200)}"); "" }
        } catch (e: Throwable) { println("вход по коду: ${e.javaClass.simpleName}"); "" }
    }
    val deps = DesktopAppDependencies()
    fun ipVia(url: String): String = try {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        val body = c.inputStream.bufferedReader().readText()
        body.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=") ?: body.trim().take(60)
    } catch (e: Throwable) { "ошибка: ${e.javaClass.simpleName}" }
    // Windows 3.0: адаптер «Reed» поднимает сам sing-box и должен исчезнуть вместе с ним.
    fun tunAdapter(): String = try {
        val pr = ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
            "(Get-NetAdapter -Name 'Reed' -ErrorAction SilentlyContinue | Measure-Object).Count")
            .redirectErrorStream(true).start()
        pr.inputStream.bufferedReader().readText().trim().ifBlank { "?" }
    } catch (e: Throwable) { "ошибка ${e.javaClass.simpleName}" }
    fun probe(url: String): String = try {
        val t0 = System.nanoTime()
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 12000; c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "Mozilla/5.0 ReedSelfTest")
        val code = c.responseCode; c.disconnect()
        "HTTP $code за ${(System.nanoTime() - t0) / 1_000_000} мс"
    } catch (e: Throwable) { "НЕ ОТКРЫЛСЯ (${e.javaClass.simpleName}: ${e.message?.take(80)})" }

    try {
        val before = ipVia("https://www.cloudflare.com/cdn-cgi/trace")
        log("IP до подключения: $before")
        // Без токена — «временный сервер» (работает без входа): проверяем TUN и путь «напрямую».
        if (token.isNotBlank()) ReedSession.token = token
        ReedSession.splitRouting = true
        ReedSession.onboardingDone = true; ReedSession.consentAccepted = true
        suspend fun import(url: String): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            deps.homeViewModel.onImportFullConfig(url, onComplete = { if (cont.isActive) cont.resume(null) {} },
                onError = { m -> if (cont.isActive) cont.resume(m) {} })
        }
        if (token.isNotBlank()) {
            val err = import("https://reedapp.ru/app/locations?token=$token")
            log("импорт серверов: ${err ?: "ок"}")
        } else log("режим: временный сервер (без входа)")
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c -> deps.locationViewModel.loadLocations { c.resume(Unit) {} } }
        val locs = deps.locationViewModel.locations.toList()
        val wifi = locs.filter { it.config?.isVless() == true &&
            org.olcbox.app.ui.reed2.ReedServerNaming.describe(it.fullName, isOlcRtc = false).network == "wifi" }
        log("серверов: ${locs.size}, Wi-Fi: ${wifi.size} (${wifi.take(6).joinToString { it.fullName }})")
        val target = (if (token.isBlank()) locs.firstOrNull { org.olcbox.app.data.datasource.ReedTempServer.isTemp(it.storageId) }
            else wifi.firstOrNull { it.fullName.contains("Автовыбор") } ?: wifi.firstOrNull()) ?: error("нет сервера для проверки")
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c -> deps.locationViewModel.selectLocation(target.storageId) { c.resume(Unit) {} } }
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c -> deps.homeViewModel.loadCurrentConfig { c.resume(Unit) {} } }
        log("подключаюсь к: ${target.fullName}")
        deps.homeViewModel.ToggleVpn()
        val ok = kotlinx.coroutines.withTimeoutOrNull(90_000) {
            deps.homeViewModel.state.first { it.isVpnConnected || it.connectionErrorMessage != null }
        }
        val st = deps.homeViewModel.state.value
        log("состояние: connected=${st.isVpnConnected} error=${st.connectionErrorMessage}")
        if (ok == null || !st.isVpnConnected) {
            deps.homeViewModel.logs.value.takeLast(40).forEach { log("  log: $it") }
            error("не подключилось")
        }
        kotlinx.coroutines.delay(3000)
        log("адаптер Reed во время подключения: ${tunAdapter()} шт")
        log("IP заграница (cloudflare) после подключения: ${ipVia("https://www.cloudflare.com/cdn-cgi/trace")}")
        log("IP Россия (yandex) после подключения: ${ipVia("https://ipv4-internet.yandex.net/api/v0/ip")}")
        for (u in listOf("https://www.gosuslugi.ru/", "https://www.nalog.gov.ru/", "https://online.sberbank.ru/",
                "https://www.tbank.ru/", "https://ya.ru/", "https://vk.com/", "https://www.ozon.ru/", "https://1c.ru/",
                "https://kontur.ru/", "https://www.wildberries.ru/",
                "https://www.google.com/", "https://www.youtube.com/", "https://github.com/", "https://www.instagram.com/",
                "https://chatgpt.com/", "https://discord.com/", "https://t.me/", "https://reedapp.ru/")) {
            log("$u → ${probe(u)}")
        }
        deps.homeViewModel.logs.value.takeLast(15).forEach { log("  log: $it") }

        // Смена сервера: отключиться → выбрать другой → подключиться (как при тапе по серверу).
        val other = wifi.firstOrNull { it.storageId != target.storageId && it.fullName.contains("Германия") }
            ?: wifi.firstOrNull { it.storageId != target.storageId }
        if (other != null) {
            deps.homeViewModel.ToggleVpn()
            kotlinx.coroutines.withTimeoutOrNull(20_000) { deps.homeViewModel.state.first { !it.isVpnConnected } }
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c -> deps.locationViewModel.selectLocation(other.storageId) { c.resume(Unit) {} } }
            kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c -> deps.homeViewModel.loadCurrentConfig { c.resume(Unit) {} } }
            log("смена сервера на: ${other.fullName}")
            deps.homeViewModel.ToggleVpn()
            kotlinx.coroutines.withTimeoutOrNull(90_000) {
                deps.homeViewModel.state.first { it.isVpnConnected || it.connectionErrorMessage != null }
            }
            val st2 = deps.homeViewModel.state.value
            log("состояние: connected=${st2.isVpnConnected} error=${st2.connectionErrorMessage}")
            kotlinx.coroutines.delay(3000)
            log("IP заграница (cloudflare): ${ipVia("https://www.cloudflare.com/cdn-cgi/trace")}")
            for (u in listOf("https://ya.ru/", "https://www.google.com/", "https://www.youtube.com/", "https://t.me/")) {
                log("$u → ${probe(u)}")
            }
        }
    } catch (e: Throwable) {
        log("FAIL ${e.javaClass.simpleName}: ${e.message}")
    } finally {
        try {
            if (deps.homeViewModel.state.value.isVpnConnected) { deps.homeViewModel.ToggleVpn(); kotlinx.coroutines.delay(5000) }
            log("адаптер Reed после отключения: ${tunAdapter()} шт")
            log("IP после отключения (интернет без туннеля): ${ipVia("https://www.cloudflare.com/cdn-cgi/trace")}")
            deps.close()
        } catch (e: Throwable) { }
        reportFile.writeText(out.toString())
        log("отчёт: ${reportFile.absolutePath}")
        kotlin.system.exitProcess(0)
    }
}
