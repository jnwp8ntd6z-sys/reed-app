package org.olcbox.app.data.reed

// Простое key-value хранилище для мелких настроек (токен входа, флаг онбординга).
// Платформенная реализация: Android — SharedPreferences, iOS/macOS — NSUserDefaults,
// Desktop(JVM) — java.util.prefs.Preferences.
expect fun reedStoreGet(key: String): String?
expect fun reedStorePut(key: String, value: String?)
