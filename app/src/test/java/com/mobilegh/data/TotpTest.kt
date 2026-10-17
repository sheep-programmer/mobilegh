package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test

class TotpTest {
    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test fun rfc6238Sha1Vectors() {
        listOf(59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130").forEach { (seconds, expected) ->
            assertEquals(expected, Totp.now(rfcSecret, seconds * 1000, digits = 8))
        }
    }

    @Test fun rejectsCorruptKeysAndUnsupportedQrParameters() {
        assertFalse(Totp.isValid("JBSWY3DPEHPK3PXP!"))
        assertFalse(Totp.isValid("a"))
        assertFalse(Totp.isValid("otpauth://hotp/GitHub?secret=$rfcSecret"))
        assertFalse(Totp.isValid("otpauth://totp/GitHub?secret=$rfcSecret&algorithm=SHA256"))
        assertFalse(Totp.isValid("otpauth://totp/GitHub?secret=$rfcSecret&digits=8"))
        assertFalse(Totp.isValid("otpauth://totp/GitHub?secret=$rfcSecret&period=60"))
        assertTrue(Totp.isValid(" jbsw-y3dp ehpk3pxp "))
        assertEquals(rfcSecret, Totp.normalize("otpauth://totp/GitHub:user?secret=$rfcSecret&issuer=GitHub"))
    }

    @Test fun timeWindowBoundaryChangesCode() {
        assertEquals(Totp.now(rfcSecret, 30_000), Totp.now(rfcSecret, 59_999))
        assertNotEquals(Totp.now(rfcSecret, 59_999), Totp.now(rfcSecret, 60_000))
    }

    @Test fun verifyAcceptsAdjacentWindowsOnly() {
        // 当前码与前后各一个时间片都应通过（时钟漂移容错）
        val t = 1_234_567_890_000L
        val period = 30
        val current = Totp.now(rfcSecret, t)
        val before = Totp.now(rfcSecret, t - period * 1000L)
        val after = Totp.now(rfcSecret, t + period * 1000L)
        assertTrue(Totp.verify(rfcSecret, current, t))
        assertTrue(Totp.verify(rfcSecret, before, t))
        assertTrue(Totp.verify(rfcSecret, after, t))
        // 两个时间片之外不算有效
        val far = Totp.now(rfcSecret, t + 3 * period * 1000L)
        assertFalse(Totp.verify(rfcSecret, far, t))
        // 非六位数字或非数字输入直接拒绝
        assertFalse(Totp.verify(rfcSecret, "12345", t))
        assertFalse(Totp.verify(rfcSecret, "abcdef", t))
        assertFalse(Totp.verify(rfcSecret, "", t))
    }
}
