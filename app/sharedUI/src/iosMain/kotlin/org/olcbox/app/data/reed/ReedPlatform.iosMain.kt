package org.olcbox.app.data.reed

import platform.UIKit.UIDevice

actual fun reedIsIOS(): Boolean = true

actual fun reedPlatformName(): String = "iOS"

actual fun reedDeviceModel(): String = UIDevice.currentDevice.name
