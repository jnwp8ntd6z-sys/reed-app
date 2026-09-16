package org.olcbox.app.data.reed

/** true на iOS — для платформенно-различного UI (экран входа Reed как «прокси-клиент»). */
expect fun reedIsIOS(): Boolean

/**
 * Имя платформы для привязки устройства по коду (поле device_os в /app/code/login).
 * Раньше во всех сборках жёстко слался "iOS", поэтому ноутбук в списке устройств
 * выглядел айфоном и владелец не мог отличить свои устройства друг от друга.
 */
expect fun reedPlatformName(): String

/** Модель/имя устройства для списка «Устройства» (поле device_model). */
expect fun reedDeviceModel(): String
