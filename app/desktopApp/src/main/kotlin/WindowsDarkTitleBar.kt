import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window

/**
 * Тёмная полоса заголовка окна на Windows: DwmSetWindowAttribute(DWMWA_USE_IMMERSIVE_DARK_MODE).
 * На старых сборках Windows 10 атрибут имеет номер 19, с 20H1 — 20; пробуем оба.
 * Любая ошибка молча игнорируется — заголовок просто останется светлым.
 */
object WindowsDarkTitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attr: Int, value: Pointer, size: Int): Int
    }

    fun apply(window: Window) {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return
        runCatching {
            val dwm = Native.load("dwmapi", Dwm::class.java)
            val hwnd = Native.getComponentPointer(window) ?: return
            val on = Memory(4).apply { setInt(0, 1) }
            if (dwm.DwmSetWindowAttribute(hwnd, 20, on, 4) != 0) dwm.DwmSetWindowAttribute(hwnd, 19, on, 4)
        }
    }
}
