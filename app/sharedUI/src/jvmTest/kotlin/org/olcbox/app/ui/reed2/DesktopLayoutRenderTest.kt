package org.olcbox.app.ui.reed2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Рендер раскладки Windows в PNG: REED_RENDER_OUT=/path/dir. Без переменной тест ничего не делает. */
class DesktopLayoutRenderTest {
    @Test
    fun renderWindowsLayout() {
        val out = System.getenv("REED_RENDER_OUT") ?: return
        File(out).mkdirs()
        val servers = listOf(
            ServerUi("auto", "EU", "Автовыбор", "Самый быстрый сервер", 5),
            ServerUi("nl1", "NL", "Нидерланды", "Амстердам", 21),
            ServerUi("nl2", "NL", "Нидерланды", "Через Москву", 22),
            ServerUi("de", "DE", "Германия", "Франкфурт", 23),
            ServerUi("us", "US", "США", "Нью-Йорк", 118),
            ServerUi("ru", "RU", "Россия", "Российские сайты из-за границы", 31),
        )
        for ((w, h) in listOf(1100 to 720, 900 to 600, 1600 to 900)) {
            ImageComposeScene(width = w, height = h, density = Density(1f)).use { scene ->
                scene.setContent {
                    DesktopShell(
                        items = listOf(
                            SidebarItem("Главная", Icons.Rounded.Home, true) {},
                            SidebarItem("Семья", Icons.Rounded.Group, false) {},
                            SidebarItem("Профиль", Icons.Rounded.Person, false) {},
                            SidebarItem("Поддержка", DesktopIcons.support, false, badge = true) {},
                        ),
                        footerLabel = "Подписка",
                        footerTitle = "до 23 июля 2029",
                        footerVersion = "Reed Client 3.0.0",
                        servers = {
                            DesktopServersPanel(servers, "de", ReedConnState.On, {}, {}, {}, false, false, "Серверов пока нет")
                        },
                    ) {
                        DesktopHomeCenter(
                            connState = ReedConnState.On, statusWord = "Подключено", timer = "00:01:08",
                            locationSubtitle = "Германия · Франкфурт", topLine = "Автоподключение включено",
                            hasUnread = true, onBell = {}, onToggleConnect = {},
                            subscription = SubscriptionUi(true, "до 23 июля 2029", 201.9, 2048.0, "Осталось 1010 дней"),
                            subscriptionMessage = null, subscriptionActionLabel = null, onSubscriptionAction = {},
                            onSubscriptionClick = {}, errorText = null,
                            ruDirect = true, onRuDirect = {}, autoConnect = false, onAutoConnect = {},
                        )
                    }
                }
                // Два кадра: первый запускает анимации, второй — состояние после 2 с.
                scene.render(0L)
                val img = scene.render(2_000_000_000L)
                val png = img.encodeToData(EncodedImageFormat.PNG)!!.bytes
                File(out, "win-${w}x${h}.png").writeBytes(png)
            }
        }
    }
}
