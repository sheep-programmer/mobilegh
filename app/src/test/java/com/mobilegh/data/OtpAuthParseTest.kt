package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** 添加验证器时从 otpauth 链接读取发行方、账号名和密钥 */
class OtpAuthParseTest {
    private val secret = "JBSWY3DPEHPK3PXP"

    @Test fun readsIssuerAndAccountFromLabel() {
        val parsed = Totp.parseOtpAuth("otpauth://totp/GitHub:alice?secret=$secret&issuer=GitHub")
        assertNotNull(parsed)
        assertEquals("GitHub", parsed!!.issuer)
        assertEquals("alice", parsed.account)
        assertEquals(secret, parsed.secret)
    }

    @Test fun percentEncodedLabelIsDecoded() {
        val parsed = Totp.parseOtpAuth("otpauth://totp/GitHub%3Abob?secret=$secret")!!
        assertEquals("GitHub", parsed.issuer)
        assertEquals("bob", parsed.account)
    }

    @Test fun issuerParameterWinsOverLabelPrefix() {
        val parsed = Totp.parseOtpAuth("otpauth://totp/Old:carol?secret=$secret&issuer=GitHub")!!
        assertEquals("GitHub", parsed.issuer)
        assertEquals("carol", parsed.account)
    }

    @Test fun labelWithoutIssuerIsAllAccount() {
        val parsed = Totp.parseOtpAuth("otpauth://totp/dave?secret=$secret")!!
        assertNull(parsed.issuer)
        assertEquals("dave", parsed.account)
    }

    @Test fun emptyLabelGivesNoAccount() {
        val parsed = Totp.parseOtpAuth("otpauth://totp/?secret=$secret")!!
        assertNull(parsed.account)
        assertNull(parsed.issuer)
    }

    @Test fun plainSecretIsNotALink() {
        assertNull(Totp.parseOtpAuth(" $secret "))
    }

    @Test fun unsupportedLinksAreRejected() {
        assertRejected { Totp.parseOtpAuth("otpauth://hotp/GitHub:alice?secret=$secret") }
        assertRejected { Totp.parseOtpAuth("otpauth://totp/GitHub:alice?secret=$secret&algorithm=SHA256") }
        assertRejected { Totp.parseOtpAuth("otpauth://totp/GitHub:alice?secret=$secret&digits=8") }
        assertRejected { Totp.parseOtpAuth("otpauth://totp/GitHub:alice?secret=$secret&period=60") }
    }

    @Test fun normalizeStripsDashesFromLinkSecret() {
        // 分组用连字符书写的密钥，规范化后应与原密钥一致
        assertEquals(secret, Totp.normalize("otpauth://totp/GitHub:alice?secret=" + secret.chunked(4).joinToString("-")))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        fail("expected IllegalArgumentException")
    }
}
