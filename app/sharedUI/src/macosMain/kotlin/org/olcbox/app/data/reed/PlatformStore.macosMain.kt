package org.olcbox.app.data.reed

import platform.Foundation.NSUserDefaults

private val defaults = NSUserDefaults.standardUserDefaults

actual fun reedStoreGet(key: String): String? =
    defaults.stringForKey(key)

actual fun reedStorePut(key: String, value: String?) {
    if (value == null) defaults.removeObjectForKey(key)
    else defaults.setObject(value, forKey = key)
}
