package org.olcbox.app.ui.reed2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.olcbox.app.data.reed.ReedApi
import org.olcbox.app.data.reed.ReedSession

/**
 * Reed 2.0 (ТЗ 4.5, приёмка §10): системное уведомление «Новое устройство» с кнопками
 * «Это я» и «Заблокировать». Проверку зовёт туннельный сервис раз в несколько минут, пока он
 * работает; кнопки обрабатывает [ReedDeviceAlertReceiver] без открытия приложения.
 */
object ReedDeviceAlerts {
    private const val CHANNEL_ID = "reed_devices"
    private const val NOTIFICATION_ID = 4101
    private const val PREFS = "reed2"
    private const val KEY_POSTED = "newdev_posted_max_id"

    const val ACTION_ITS_ME = "org.olcbox.app.reed.NEW_DEVICE_ITS_ME"
    const val ACTION_BLOCK = "org.olcbox.app.reed.NEW_DEVICE_BLOCK"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
    const val EXTRA_DEVICE_ID = "device_id"

    /** Одна проверка: показывает уведомление о самом свежем неподтверждённом устройстве. */
    suspend fun check(context: Context) {
        val token = ReedSession.token ?: return
        if (ReedSession.joinedViaCode) return            // участник семьи устройствами не управляет
        val list = try { ReedApi.notifications(token).notifications } catch (e: Throwable) { return }
        val n = list.filter { it.kind == "new_device" && it.id > ReedSession.newDeviceAckMaxId && it.deviceId != null }
            .maxByOrNull { it.id } ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (n.id <= prefs.getInt(KEY_POSTED, 0)) return  // уже показывали
        prefs.edit().putInt(KEY_POSTED, n.id).apply()
        post(context, n.id, n.deviceId ?: return, n.body.ifBlank { "К подписке подключилось новое устройство." })
    }

    private fun post(context: Context, notificationId: Int, deviceId: Int, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Новые устройства", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            Intent(context, ReedDeviceAlertReceiver::class.java).apply {
                action = name
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Новое устройство")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Это я", action(ACTION_ITS_ME, 1))
            .addAction(0, "Заблокировать", action(ACTION_BLOCK, 2))
            .build()
        try { nm.notify(NOTIFICATION_ID, notification) } catch (e: SecurityException) { /* нет разрешения */ }
    }

    internal fun dismiss(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }
}

/** Кнопки уведомления о новом устройстве. */
class ReedDeviceAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(ReedDeviceAlerts.EXTRA_NOTIFICATION_ID, 0)
        val deviceId = intent.getIntExtra(ReedDeviceAlerts.EXTRA_DEVICE_ID, 0)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == ReedDeviceAlerts.ACTION_BLOCK && deviceId > 0) {
                    ReedSession.token?.let { t ->
                        try { ReedApi.deviceAction(t, deviceId, "toggle_block") } catch (e: Throwable) { }
                    }
                }
                if (notificationId > ReedSession.newDeviceAckMaxId) ReedSession.newDeviceAckMaxId = notificationId
                ReedDeviceAlerts.dismiss(context)
            } finally {
                pending.finish()
            }
        }
    }
}
