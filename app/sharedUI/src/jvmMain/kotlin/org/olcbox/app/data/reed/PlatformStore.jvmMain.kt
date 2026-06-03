package org.olcbox.app.data.reed

import java.util.prefs.Preferences

private val prefs: Preferences = Preferences.userRoot().node("org/olcbox/reed")

actual fun reedStoreGet(key: String): String? = prefs.get(key, null)

actual fun reedStorePut(key: String, value: String?) {
    if (value == null) prefs.remove(key) else prefs.put(key, value)
}
