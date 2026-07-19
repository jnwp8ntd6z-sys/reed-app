package org.olcbox.app.vpn

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.SubscriptionFetchProxy

sealed class VpnStatus {
    object Disconnected : VpnStatus()
    object Connecting : VpnStatus()
    object Connected : VpnStatus()
    object Reconnecting : VpnStatus()
    object Stopping : VpnStatus()
    data class Error(val message: String) : VpnStatus()
}

interface VpnManager {
    val logs: StateFlow<List<String>>
    val status: StateFlow<VpnStatus>
    val isConnected: StateFlow<Boolean>
    fun needsPermission(): Boolean
    fun startVpn()
    fun stopVpn()
    suspend fun ping(locationConfig: LocationConfig): Long?
    suspend fun checkConnection(locationConfig: LocationConfig): Long?
    fun subscriptionFetchProxy(): SubscriptionFetchProxy? = null

    /**
     * Предзагрузка sing-box-конфигов для переданных VLESS-серверов в локальный кэш, пока
     * сеть доступна. Нужно для офлайн-подключения к ещё не использованным серверам: на
     * «зарезанном» мобильном наш API недоступен, и без кэша первое подключение к серверу
     * не проходит. Реализовано только на Android; на остальных платформах — no-op.
     */
    suspend fun prewarmConfigs(locations: List<LocationConfig>) {}

    /**
     * Реальное время подключения (epoch millis) от СИСТЕМЫ, если платформа его знает.
     * На iOS системный туннель живёт отдельно от процесса приложения (переживает закрытие,
     * включается/выключается из Пункта управления) — таймер сессии должен считать от этого
     * времени, а не от локальной метки процесса. null = платформа не знает, UI берёт
     * локальную метку.
     */
    fun connectedAtEpochMillis(): Long? = null
}
