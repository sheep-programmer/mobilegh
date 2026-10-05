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
}
