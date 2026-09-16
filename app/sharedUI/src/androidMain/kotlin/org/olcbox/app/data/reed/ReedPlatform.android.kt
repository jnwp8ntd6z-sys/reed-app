package org.olcbox.app.data.reed

import android.os.Build

actual fun reedIsIOS(): Boolean = false

actual fun reedPlatformName(): String = "Android"

actual fun reedDeviceModel(): String =
    listOf(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())
        .filter { it.isNotBlank() }
        .joinToString(" ")
