package org.olcbox.app

import android.app.Application
import android.content.Context
import org.olcbox.app.data.reed.reedStoreInitAndroid

class App : Application() {
    companion object {
        lateinit var appContext: Context
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        // СПЕЦ-СБОРКА: ставим перехватчик вылетов ПЕРВЫМ делом, чтобы поймать краш
        // запуска и записать стек в «Загрузки/reed-crash-*.txt».
        CrashLogger.install(applicationContext)
        // Инициализируем хранилище сессии Reed на уровне процесса — нужно в т.ч.
        // для BootReceiver (автозапуск), который срабатывает без открытия Activity.
        reedStoreInitAndroid(applicationContext)
    }
}
