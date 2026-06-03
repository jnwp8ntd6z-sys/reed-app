package org.olcbox.app.data.reed

import android.content.Context
import android.content.SharedPreferences

// Контекст приходит из androidApp (AppActivity/App) через reedStoreInitAndroid().
// До инициализации операции — no-op (вернёт null), чтобы ничего не падало.
private var prefs: SharedPreferences? = null

fun reedStoreInitAndroid(context: Context) {
    if (prefs == null) {
        prefs = context.applicationContext
            .getSharedPreferences("reed_store", Context.MODE_PRIVATE)
    }
}

actual fun reedStoreGet(key: String): String? = prefs?.getString(key, null)

actual fun reedStorePut(key: String, value: String?) {
    val p = prefs ?: return
    p.edit().apply {
        if (value == null) remove(key) else putString(key, value)
    }.apply()
}
