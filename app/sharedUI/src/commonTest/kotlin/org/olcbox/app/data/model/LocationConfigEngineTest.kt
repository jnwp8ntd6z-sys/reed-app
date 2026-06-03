package org.olcbox.app.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки поля `engine` у LocationConfig (olcrtc по умолчанию + round-trip vless через
 * storage LocationEntry). Гарантируют, что добавление VLESS не ломает обратную
 * совместимость olcRTC-локаций.
 */
class LocationConfigEngineTest {

    @Test
    fun defaultsToOlcrtc() {
        val config = LocationConfig(id = "room", key = "secret")
        assertEquals(LocationConfig.ENGINE_OLCRTC, config.normalized().engine)
        assertFalse(config.isVless())
    }

    @Test
    fun normalizeEngineAliases() {
        assertEquals(LocationConfig.ENGINE_VLESS, LocationConfig.normalizeEngine("singbox"))
        assertEquals(LocationConfig.ENGINE_VLESS, LocationConfig.normalizeEngine("VLESS"))
        assertEquals(LocationConfig.ENGINE_OLCRTC, LocationConfig.normalizeEngine("whatever"))
    }

    @Test
    fun vlessRoundTripsThroughStorage() {
        // Для vless: id = tag сервера, key = sub_token.
        val vless = LocationConfig(
            name = "🇩🇪 SMART-Германия",
            id = "🇩🇪 SMART-Германия",
            key = "tok123",
            engine = LocationConfig.ENGINE_VLESS
        ).normalized()
        assertTrue(vless.isVless())
        assertTrue(vless.isComplete())

        val entry = LocationEntry.from(storageId = "s1", location = vless)
        val restored = entry.location
        assertEquals(LocationConfig.ENGINE_VLESS, restored.engine)
        assertEquals("🇩🇪 SMART-Германия", restored.id)
        assertEquals("tok123", restored.key)
    }
}
