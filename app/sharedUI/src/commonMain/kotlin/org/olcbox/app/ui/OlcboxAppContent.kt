package org.olcbox.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.reed.ReedSession
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
    // Онбординг при первом запуске (до основного интерфейса).
    var showOnboarding by remember { mutableStateOf(!ReedSession.onboardingDone) }
    // Полный выход в экран входа: чистим сессию И возвращаем UI на онбординг. Раньше
    // выход/удаление только сбрасывали токен, но showOnboarding не переключался →
    // оставались на главном экране с «пустым»/странным меню. Теперь — назад на вход.
    val backToLogin: () -> Unit = {
        ReedSession.logout()
        // Серверы Reed-аккаунта уходят вместе с сессией; чужие подписки остаются.
        locationViewModel.deleteReedAccountLocations()
        showOnboarding = true
    }
    if (showOnboarding) {
        ReedOnboardingScreen(
            homeViewModel = homeViewModel,
            locationViewModel = locationViewModel,
            onToggleClick = onToggleClick,
            onDone = { showOnboarding = false },
        )
        return
    }

    // Нижняя навигация Reed: 4 вкладки с настоящими иконками (по дизайну).
    // Активный цвет чередуется лайм/оранжевый, как в макете. Переключение — плавное (Crossfade).
    var selectedTab by remember { mutableStateOf(0) }
    val lime = MaterialTheme.colorScheme.primary
    val orange = MaterialTheme.colorScheme.secondary
    data class TabDef(val icon: ImageVector, val label: String, val color: Color)
    val tabs = listOf(
        TabDef(Icons.Rounded.Shield, "Главная", lime),
        TabDef(Icons.Rounded.Settings, "Настройки", orange),
        TabDef(Icons.Rounded.Person, "Профиль", lime),
        TabDef(Icons.Rounded.ChatBubble, "Помощь", orange),
    )

    ReedMemberGate(onLogout = backToLogin) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = tab.color,
                            selectedTextColor = tab.color,
                            indicatorColor = tab.color.copy(alpha = 0.16f),
                        ),
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Crossfade(targetState = selectedTab, animationSpec = tween(280), label = "tab_switch") { tab ->
                when (tab) {
                    0 -> ReedHomeScreen(
                        homeViewModel = homeViewModel,
                        locationViewModel = locationViewModel,
                        onToggleClick = onToggleClick,
                    )
                    1 -> ReedSettingsScreen()
                    2 -> ReedAccountScreen(locationViewModel = locationViewModel, onLogout = backToLogin)
                    else -> ReedSupportScreen()
                }
            }
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
