package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** API 反向代理地址的校验：Token 会发往这个地址，必须是带域名的 https 地址 */
class ApiProxyValidationTest {
    @Test fun blankMeansOfficialApi() {
        assertNull(Net.apiProxyError(""))
        assertNull(Net.apiProxyError("   "))
    }

    @Test fun httpsAddressWithHostIsAccepted() {
        assertNull(Net.apiProxyError("https://gh-api.example.workers.dev"))
        assertNull(Net.apiProxyError("  https://gh-api.example.workers.dev/  "))
        assertNull(Net.apiProxyError("https://203.0.113.10:8443/github"))
    }

    @Test fun plaintextOrSchemelessAddressesAreRejected() {
        assertNotNull(Net.apiProxyError("http://gh-api.example.workers.dev"))
        assertNotNull(Net.apiProxyError("HTTP://gh-api.example.workers.dev"))
        assertNotNull(Net.apiProxyError("gh-api.example.workers.dev"))
        assertNotNull(Net.apiProxyError("ftp://example.com"))
    }

    @Test fun missingHostSpacesOrEmbeddedCredentialsAreRejected() {
        assertNotNull(Net.apiProxyError("https://"))
        assertNotNull(Net.apiProxyError("https:///path"))
        assertNotNull(Net.apiProxyError("https://user:" + "pw" + "@example.com"))
        assertNotNull(Net.apiProxyError("https://exa mple.com"))
    }

    @Test fun errorIsAChineseSentenceNotAnExceptionString() {
        assertEquals("地址必须以 https:// 开头", Net.apiProxyError("http://example.com"))
    }
}
