package org.olcbox.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Диагностический перехватчик вылетов. При любом необработанном исключении
 * (в любом потоке) сохраняет полный стек + инфо об устройстве в файл
 * «Загрузки/reed-crash-<время>.txt», который пользователь легко найдёт в
 * проводнике и пришлёт нам. После записи передаёт управление стандартному
 * обработчику, поэтому приложение падает как обычно — мы лишь фиксируем причину.
 *
 * СПЕЦ-СБОРКА: предназначена для отлова краша запуска на устройствах (Redmi и др.),
 * где встроенная кнопка «Логи» недоступна (приложение падает до её показа).
 */
object CrashLogger {

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Перехватчик не должен сам падать — молча игнорируем ошибку записи.
            }
            // Отдаём управление штатному обработчику (приложение завершится как обычно).
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        val stack = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()

        val report = buildString {
            appendLine("=== REED VPN CRASH REPORT ===")
            appendLine("Время: $ts")
            appendLine("Поток: ${thread.name}")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Сборка: спец-версия с логом вылета")
            appendLine()
            appendLine("--- STACK TRACE ---")
            append(stack)
        }

        val fileName = "reed-crash-$fileStamp.txt"
        val bytes = report.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: пишем в общую папку «Загрузки» через MediaStore (без прав).
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
            }
        } else {
            // Android 9 и ниже: пишем в app-папку (видна как Android/data/<pkg>/files/Download).
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, fileName).writeBytes(bytes)
            }
        }

        // Дублируем копию в внутреннее хранилище приложения (на всякий случай).
        try {
            context.openFileOutput("last_crash.txt", Context.MODE_PRIVATE).use { it.write(bytes) }
        } catch (_: Throwable) {
        }
    }
}
