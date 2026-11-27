package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCookieVaultTest {
    @Test fun validatesUserSessionAndIsolatesMismatchedDotcomUser() {
        val cookieA = "logged_in=yes; dotcom_user=alice; user_session=sessA123; _gh_sess=xyz"
        assertTrue(WebCookieVault.belongsTo(cookieA, "alice"))
        assertTrue(WebCookieVault.belongsTo(cookieA, "ALICE"))
        assertFalse(WebCookieVault.belongsTo(cookieA, "bob"))
        assertEquals("alice", WebCookieVault.extractOwner(cookieA))
    }

    @Test fun preservesLegacyFixtureCookiesWithoutDotcomUser() {
        assertTrue(WebCookieVault.belongsTo("user_session=abc; logged_in=yes", "alice"))
        assertFalse(WebCookieVault.belongsTo("user_session=; logged_in=no", "alice"))
        assertFalse(WebCookieVault.belongsTo("logged_in=yes; dotcom_user=alice", "alice"))
    }

    @Test fun buildsSecureSetCookieDirectivesIncludingHostPrefix() {
        val directives = WebCookieVault.toSetCookieDirectives(
            "user_session=s1; __Host-user_session_same_site=s1; dotcom_user=mona",
        )
        assertEquals(3, directives.size)
        assertTrue(directives[0].contains("Path=/; Secure; HttpOnly"))
        assertTrue(directives[1].startsWith("__Host-user_session_same_site=s1; Path=/; Secure; HttpOnly"))
        assertFalse(directives[1].contains("Domain=", ignoreCase = true))
        assertEquals("dotcom_user=mona; Path=/; Secure", directives[2])
    }
}
