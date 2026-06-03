package org.olcbox.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.ui.features.home.HomeScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationSettingsScreen
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.navigation.AppScreen

@Composable
fun OlcboxAppContent(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    onToggleClick: () -> Unit,
    onImportFileRequested: () -> Unit,
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit,
    onScanQrRequested: () -> Unit = {},
    onCopyConfigRequested: () -> Unit,
    onShareLocationRequested: (LocationConfig) -> Unit = {},
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    showAppSettingsButton: Boolean,
    showSplitTunnelingButton: Boolean = false,
    canScanQr: Boolean = false,
    onAppSettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit = {}
) {
    // Нижняя навигация Reed: 4 вкладки (Главная / Управление / Кабинет / Поддержка).
    // Вкладка 0 = существующий экран olcbox (подключение + серверы), 1-3 — пока заглушки
    // под перенос дизайна. Иконки — эмодзи (чтобы не тянуть material-icons зависимость).
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        "🏠" to "Главная",
        "⚙️" to "Настройки",
        "👤" to "Кабинет",
        "💬" to "Поддержка",
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(tab.first) },
                        label = { Text(tab.second) },
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                0 -> HomeTab(
                    homeViewModel = homeViewModel,
                    locationViewModel = locationViewModel,
                    currentScreen = currentScreen,
                    onNavigate = onNavigate,
                    onToggleClick = onToggleClick,
                    onImportFileRequested = onImportFileRequested,
                    onImportFromClipboardRequested = onImportFromClipboardRequested,
                    onScanQrRequested = onScanQrRequested,
                    onCopyConfigRequested = onCopyConfigRequested,
                    onShareLocationRequested = onShareLocationRequested,
                    onSaveLogsRequested = onSaveLogsRequested,
                    showAppSettingsButton = showAppSettingsButton,
                    showSplitTunnelingButton = showSplitTunnelingButton,
                    canScanQr = canScanQr,
                    onAppSettingsClick = onAppSettingsClick,
                    onSplitTunnelingClick = onSplitTunnelingClick,
                )
                1 -> ReedSettingsScreen()
                2 -> ReedAccountScreen()
                else -> ReedSupportScreen()
            }
        }
    }
}

@Composable
private fun ReedPlaceholder(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title)
        Text(subtitle)
    }
}

@Composable
private fun HomeTab(
    homeViewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    currentScreen: AppScreen,
    onNavigate: (AppScreen) -> Unit,
    onToggleClick: () -> Unit,
    onImportFileRequested: () -> Unit,
    onImportFromClipboardRequested: (onImported: () -> Unit, onError: (String) -> Unit) -> Unit,
    onScanQrRequested: () -> Unit,
    onCopyConfigRequested: () -> Unit,
    onShareLocationRequested: (LocationConfig) -> Unit,
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    showAppSettingsButton: Boolean,
    showSplitTunnelingButton: Boolean,
    canScanQr: Boolean,
    onAppSettingsClick: () -> Unit,
    onSplitTunnelingClick: () -> Unit,
) {
    val homeScrollState = rememberScrollState()

    AnimatedContent(
        targetState = currentScreen,
        label = "app_screen_transition",
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 240,
                        delayMillis = 30,
                        easing = LinearOutSlowInEasing
                    )
                ),
                initialContentExit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 160,
                        easing = LinearOutSlowInEasing
                    )
                ),
                sizeTransform = SizeTransform(
                    clip = false,
                    sizeAnimationSpec = { _, _ ->
                        tween(
                            durationMillis = 420,
                            easing = FastOutSlowInEasing
                        )
                    }
                )
            )
        }
    ) { screen ->
        when (screen) {
            AppScreen.Home -> {
                HomeScreen(
                    viewModel = homeViewModel,
                    locationViewModel = locationViewModel,
                    scrollState = homeScrollState,
                    onToggleClick = onToggleClick,
                    onImportFileRequested = onImportFileRequested,
                    onImportFromClipboardRequested = onImportFromClipboardRequested,
                    onScanQrRequested = onScanQrRequested,
                    onCopyConfigRequested = onCopyConfigRequested,
                    onSaveLogsRequested = onSaveLogsRequested,
                    showAppSettingsButton = showAppSettingsButton,
                    showSplitTunnelingButton = showSplitTunnelingButton,
                    canScanQr = canScanQr,
                    onAppSettingsClick = onAppSettingsClick,
                    onSplitTunnelingClick = onSplitTunnelingClick,
                    onOpenLocationSettings = { id ->
                        locationViewModel.startEditing(id)
                        onNavigate(AppScreen.LocationSettings(id))
                    },
                    onAddLocation = {
                        locationViewModel.startEditing(null)
                        onNavigate(AppScreen.LocationSettings(null))
                    }
                )
            }

            is AppScreen.LocationSettings -> {
                LocationSettingsScreen(
                    viewModel = locationViewModel,
                    homeViewModel = homeViewModel,
                    onShareLocationRequested = onShareLocationRequested,
                    onBack = {
                        homeViewModel.loadCurrentConfig()
                        onNavigate(AppScreen.Home)
                    }
                )
            }
        }
    }
}
