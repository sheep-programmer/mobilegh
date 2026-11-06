package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MediaTokenTest {
    private fun jwt(exp: Long): String {
        fun enc(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        return enc("""{"alg":"HS256"}""") + "." + enc("""{"exp":$exp,"sub":"x"}""") + "." + enc("sig")
    }

    @Test fun expiredTokenIsDetectedInReadmeHtml() {
        val html = """<video src="https://private-user-images.githubusercontent.com/a.mp4?jwt=${jwt(1_000)}">"""
        assertTrue(MediaProxy.hasExpiredToken(html, nowSeconds = 2_000))
        assertFalse(MediaProxy.hasExpiredToken(html, nowSeconds = 500))
    }

    @Test fun htmlWithoutTokensIsFine() {
        assertFalse(MediaProxy.hasExpiredToken("<p>no media</p>", 2_000))
        assertFalse(MediaProxy.hasExpiredToken("""<img src="https://github.com/a.png">""", 2_000))
    }

    @Test fun malformedTokensAreIgnoredInsteadOfCrashing() {
        assertNull(MediaProxy.tokenExpiry("not-a-jwt"))
        assertNull(MediaProxy.tokenExpiry("a.%%%.c"))
        assertFalse(MediaProxy.hasExpiredToken("""<img src="x?jwt=a.%%%.c">""", 2_000))
        assertEquals(1_700_000_000L, MediaProxy.tokenExpiry(jwt(1_700_000_000)))
    }
}
