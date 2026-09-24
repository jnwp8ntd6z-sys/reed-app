package org.olcbox.app.data.reed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeKindTest {
    @Test fun emptyIsNull() {
        assertNull(CodeKind.detect(""))
        assertNull(CodeKind.detect("   "))
    }

    @Test fun subscriptionLinks() {
        assertEquals(CodeKind.SubscriptionLink, CodeKind.detect("https://reedapp.ru/sub/abc"))
        assertEquals(CodeKind.SubscriptionLink, CodeKind.detect("vless://uuid@host:443?x=1"))
        assertEquals(CodeKind.SubscriptionLink, CodeKind.detect("VMESS://base64=="))
        assertEquals(CodeKind.SubscriptionLink, CodeKind.detect("trojan://p@h:443"))
        assertEquals(CodeKind.SubscriptionLink, CodeKind.detect("ss://method:pass@h:8388"))
    }

    @Test fun familyInvite() {
        assertEquals(CodeKind.FamilyInvite, CodeKind.detect("RDI-GHQMCV"))
        assertEquals(CodeKind.FamilyInvite, CodeKind.detect("rdi-ghqmcv"))
        assertEquals(CodeKind.FamilyInvite, CodeKind.detect("  RDI-ABC123  "))
    }

    @Test fun deviceCode() {
        assertEquals(CodeKind.DeviceCode, CodeKind.detect("RDX-ZNEFEN"))
        assertEquals(CodeKind.DeviceCode, CodeKind.detect("rdx-znefen"))
    }

    @Test fun accountCode() {
        assertEquals(CodeKind.AccountCode, CodeKind.detect("RD-JKSY4RU5"))
        assertEquals(CodeKind.AccountCode, CodeKind.detect("REED-JKSY4RU5"))
        assertEquals(CodeKind.AccountCode, CodeKind.detect("JKSY4RU5"))
    }

    @Test fun rdxNotConfusedWithRd() {
        // RDX-/RDI- НЕ должны попадать в AccountCode (RD-).
        assertTrue(CodeKind.detect("RDX-AAA111") == CodeKind.DeviceCode)
        assertTrue(CodeKind.detect("RDI-AAA111") == CodeKind.FamilyInvite)
    }
}
