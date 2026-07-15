package org.olcbox.app.data.repository

import kotlinx.coroutines.flow.StateFlow
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.LocationEntry

interface LocationsRepository {
    val changes: StateFlow<Long>
    /** Понятная причина последнего неудачного importText (например «транспорт xhttp не поддерживается»), null если причины нет. */
    val lastImportError: String?
    suspend fun getBundle(): LocationBundleV4
    suspend fun saveBundle(bundle: LocationBundleV4)
    suspend fun exportBundle(): String
    suspend fun importText(text: String, subscriptionProxy: SubscriptionFetchProxy? = null): Boolean
    suspend fun refreshSubscriptions(subscriptionProxy: SubscriptionFetchProxy? = null): Int
    suspend fun refreshSubscription(
        subscriptionUrl: String,
        subscriptionProxy: SubscriptionFetchProxy? = null
    ): Int
    suspend fun refreshDueSubscriptions(subscriptionProxy: SubscriptionFetchProxy? = null): Int
    suspend fun setSubscriptionUpdateInterval(subscriptionUrl: String, hours: Int)
    suspend fun saveLocation(storageId: String, location: LocationConfig)
    suspend fun loadLocation(storageId: String): LocationConfig?
    suspend fun deleteLocation(storageId: String)
    /** Удаляет серверы Reed-аккаунта (импорт /app/locations и /app/olcconf) — при выходе из аккаунта. */
    suspend fun deleteReedAccountLocations()
    suspend fun getAllLocations(): List<LocationEntry>
    suspend fun getActiveLocationId(): String?
    suspend fun setActiveLocationId(storageId: String?)
    suspend fun getActiveLocation(): LocationEntry?
    suspend fun getDeviceIdentity(): String
}

data class SubscriptionFetchProxy(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)
