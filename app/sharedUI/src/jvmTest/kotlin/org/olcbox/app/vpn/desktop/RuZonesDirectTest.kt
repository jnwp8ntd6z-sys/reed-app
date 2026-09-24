package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuZonesDirectTest {
    private val sample = """
        {"outbounds":[{"tag":"proxy","type":"selector"},{"tag":"direct","type":"direct"}],
         "route":{"final":"proxy","rules":[
           {"action":"sniff"},
           {"domain_suffix":["youtube.com"],"outbound":"proxy"},
           {"domain_suffix":["sber.ru"],"network":"udp","port":[443],"outbound":"direct"},
           {"ip_is_private":true,"outbound":"direct"},
           {"domain_suffix":["sber.ru","gosuslugi.ru"],"outbound":"direct"},
           {"ip_cidr":["2.56.24.0/22"],"outbound":"direct"}]}}
    """.trimIndent()

    @Test
    fun ruZonesGoDirectBeforeServerList() {
        val out = Json.parseToJsonElement(SingBoxDesktopRunner.addRuZonesDirect(sample)).jsonObject
        val rules = out["route"]!!.jsonObject["rules"]!!.jsonArray
        val i = rules.indexOfFirst { r ->
            r.jsonObject["domain_suffix"]?.jsonArray?.map { it.jsonPrimitive.content } == listOf("ru", "su", "xn--p1ai")
        }
        assertEquals(4, i, "зоны РФ — сразу перед серверным direct-списком, после YouTube-правил")
        assertEquals("direct", rules[i].jsonObject["outbound"]!!.jsonPrimitive.content)
        // Повторный вызов не дублирует правило.
        val twice = SingBoxDesktopRunner.addRuZonesDirect(SingBoxDesktopRunner.addRuZonesDirect(sample))
        assertEquals(1, Regex("xn--p1ai").findAll(twice).count())
    }

    /** Ручная проверка на живом конфиге: REED_SB_CONFIG=вход REED_SB_OUT=выход. */
    @Test
    fun patchRealConfigIfProvided() {
        val inPath = System.getenv("REED_SB_CONFIG") ?: return
        val patched = SingBoxDesktopRunner.addRuZonesDirect(File(inPath).readText())
        File(System.getenv("REED_SB_OUT") ?: "$inPath.patched").writeText(patched)
        assertTrue(patched.contains("xn--p1ai"))
    }
}
