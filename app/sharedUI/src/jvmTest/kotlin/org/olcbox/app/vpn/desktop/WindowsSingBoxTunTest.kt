package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsSingBoxTunTest {
    private val sample = """
        {"inbounds":[{"type":"socks","tag":"socks-in","listen":"127.0.0.1","listen_port":10810}],
         "outbounds":[{"tag":"proxy","type":"selector"},{"tag":"direct","type":"direct","bind_interface":"Wi-Fi"}],
         "route":{"final":"proxy","auto_detect_interface":false,"rules":[
           {"action":"sniff"},
           {"protocol":"dns","action":"hijack-dns"},
           {"network":"udp","port":[443],"action":"reject","method":"drop"},
           {"ip_is_private":true,"outbound":"direct"}]}}
    """.trimIndent()

    @Test
    fun singBoxOwnsTunWithStrictRoute() {
        val out = Json.parseToJsonElement(WindowsSingBoxTun.toTunConfig(sample, 10810)).jsonObject
        val inbounds = out["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tun", "mixed"), inbounds.map { it["type"]!!.jsonPrimitive.content })
        val tun = inbounds[0]
        assertTrue(tun["auto_route"]!!.jsonPrimitive.boolean)
        assertTrue(tun["strict_route"]!!.jsonPrimitive.boolean)
        assertEquals("Reed", tun["interface_name"]!!.jsonPrimitive.content)
        assertEquals(10810, inbounds[1]["listen_port"]!!.jsonPrimitive.content.toInt())

        val route = out["route"]!!.jsonObject
        assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.boolean, "движок сам обходит свой TUN")
        val quic = route["rules"]!!.jsonArray.map { it.jsonObject }.first { it["action"]?.jsonPrimitive?.content == "reject" }
        assertFalse("method" in quic, "QUIC — отказом, а не молчаливым drop")

        val direct = out["outbounds"]!!.jsonArray.map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "direct" }
        assertFalse("bind_interface" in direct, "привязка к адаптеру из старой схемы не нужна")
    }

    /** Ручная проверка на живом конфиге: REED_SB_CONFIG=вход REED_SB_OUT=выход (потом `sing-box check`). */
    @Test
    fun convertRealConfigIfProvided() {
        val input = System.getenv("REED_SB_CONFIG") ?: return
        val output = System.getenv("REED_SB_OUT") ?: return
        File(output).writeText(WindowsSingBoxTun.toTunConfig(File(input).readText(), 10810))
    }
}
