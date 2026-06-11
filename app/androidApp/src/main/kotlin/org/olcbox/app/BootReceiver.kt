package org.olcbox.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import org.olcbox.app.data.reed.ReedSession
import org.olcbox.app.data.reed.reedStoreInitAndroid
import org.olcbox.app.vpn.service.OlcboxVpnActions

/**
 * Автозапуск VPN при загрузке телефона (фишка Happ). Срабатывает ТОЛЬКО если:
 *  • в настройках включено авто-подключение (ReedSession.autoConnect),
 *  • пользователь вошёл (есть токен),
 *  • разрешение на VPN уже выдано ранее (VpnService.prepare == null).
 * Иначе ничего не делает — диалог разрешения из ресивера показать нельзя.
 * Активный сервер сервис читает с диска сам, опции подключения — из persisted-prefs.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        reedStoreInitAndroid(appContext)

        if (!ReedSession.autoConnect) return
        if (ReedSession.token.isNullOrBlank()) return
        if (VpnService.prepare(appContext) != null) return

        val svc = Intent().apply {
            setClassName(appContext.packageName, OlcboxVpnActions.SERVICE_CLASS_NAME)
            action = OlcboxVpnActions.ACTION_START_VPN
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(svc)
            } else {
                appContext.startService(svc)
            }
        } catch (e: Exception) {
            // На части прошивок старт foreground-сервиса из ресивера ограничен — тихо игнорируем.
        }
    }
}
