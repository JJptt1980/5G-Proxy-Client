package com.tokyoxpa3.socksclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GwIdentityTest {
    @Test fun hostFormat() {
        assertEquals("ab12cd34.5632313236.gw-id.invalid", GwIdentity.host("ab12cd34", "V2126"))
    }

    @Test fun longChineseNameTruncatedSafely() {
        val h = GwIdentity.host("ab12cd34", "我的超級長名字的平板電腦一號機")
        val hex = h.removePrefix("ab12cd34.").removeSuffix(GwIdentity.SUFFIX)
        assertTrue(hex.length <= 48)
        assertEquals(0, (hex.length / 2) % 3)   // 每個中文字 3 bytes，沒被切壞
    }

    @Test fun connectRequestBytes() {
        val r = GwIdentity.connectRequest("a.gw-id.invalid")
        assertEquals(5, r[0].toInt()); assertEquals(1, r[1].toInt()); assertEquals(3, r[3].toInt())
        assertEquals(15, r[4].toInt())
        assertEquals(0, r[r.size - 2].toInt()); assertEquals(1, r[r.size - 1].toInt())
    }
}
