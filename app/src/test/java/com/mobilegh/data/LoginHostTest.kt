package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/** 登录地址：默认 github.com，可配置为自定义 https 站点；设备授权与网页授权的地址、校验随之变化 */
class LoginHostTest {
    private val custom = "https://login.example.workers.dev"

    private fun deviceAuthJson(verificationUri: String) = Json.parseToJsonElement(
        """{"device_code":"device-test","user_code":"ABCD-EFGH","verification_uri":"$verificationUri","expires_in":900,"interval":5}""",
    ).jsonObject

    private fun callback(iss: String?, base: String): String {
        val params = mutableListOf("code" to "test-code", "state" to "S")
        iss?.let { params += "iss" to it }
        val url = "github://com.github.android/oauth?" + params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return ApprovalOAuthProtocol.callback(url, "S", base)
    }

    // ---- 登录地址校验 ----

    @Test fun defaultLoginHostIsGitHubAndBlankInputMeansDefault() {
        assertEquals("https://github.com", Net.DEFAULT_LOGIN_BASE)
        assertEquals("https://github.com", Net.loginBase)
        assertNull(Net.loginProxyError(""))
        assertNull(Net.loginProxyError("   "))
    }

    @Test fun httpsHostsWithoutPathAreAccepted() {
        assertNull(Net.loginProxyError(custom))
        assertNull(Net.loginProxyError("  $custom/  "))
        assertNull(Net.loginProxyError("https://203.0.113.10:8443"))
        assertNull(Net.loginProxyError("https://github.com"))
    }

    @Test fun plaintextOrSchemelessHostsAreRejected() {
        assertNotNull(Net.loginProxyError("http://login.example.workers.dev"))
        assertNotNull(Net.loginProxyError("HTTP://login.example.workers.dev"))
        assertNotNull(Net.loginProxyError("login.example.workers.dev"))
        assertNotNull(Net.loginProxyError("ssh://login.example.workers.dev"))
    }

    @Test fun pathQueryFragmentCredentialsAndSpacesAreRejected() {
        assertNotNull(Net.loginProxyError("$custom/login"))
        assertNotNull(Net.loginProxyError("$custom/login/oauth"))
        assertNotNull(Net.loginProxyError("$custom?x=1"))
        assertNotNull(Net.loginProxyError("$custom/?x=1"))
        assertNotNull(Net.loginProxyError("$custom#top"))
        assertNotNull(Net.loginProxyError("https://user:" + "pw" + "@login.example.workers.dev"))
        assertNotNull(Net.loginProxyError("https://login example.com"))
        assertNotNull(Net.loginProxyError("https://"))
        assertNotNull(Net.loginProxyError("https:///path"))
    }

    @Test fun pathErrorIsAChineseSentence() {
        assertEquals("登录地址只填写域名，不能包含路径", Net.loginProxyError("$custom/login"))
    }

    // ---- 端点拼接：默认与自定义站点 ----

    @Test fun deviceFlowUrlsFollowTheLoginHost() {
        assertEquals("https://github.com/login/device/code", DeviceAuth.deviceCodeUrl("https://github.com"))
        assertEquals("https://github.com/login/oauth/access_token", DeviceAuth.accessTokenUrl("https://github.com"))
        assertEquals("https://login.example.workers.dev/login/device/code", DeviceAuth.deviceCodeUrl(custom))
        assertEquals("https://login.example.workers.dev/login/oauth/access_token", DeviceAuth.accessTokenUrl(custom))
        assertEquals("https://203.0.113.10:8443/login/device/code", DeviceAuth.deviceCodeUrl("https://203.0.113.10:8443"))
    }

    @Test fun webFlowUrlsFollowTheLoginHost() {
        assertEquals("https://github.com/login/oauth/authorize", ApprovalOAuthProtocol.authorizeUrl("https://github.com"))
        assertEquals("https://github.com/login/oauth/access_token", ApprovalOAuthProtocol.tokenUrl("https://github.com"))
        assertEquals("https://login.example.workers.dev/login/oauth/authorize", ApprovalOAuthProtocol.authorizeUrl(custom))
        assertEquals("https://login.example.workers.dev/login/oauth/access_token", ApprovalOAuthProtocol.tokenUrl(custom))
    }

    @Test fun endpointsUseTheSavedLoginBaseByDefault() {
        // 测试环境未初始化 prefs，默认站点即 github.com
        assertEquals("https://github.com/login/device/code", DeviceAuth.deviceCodeUrl())
        assertEquals("https://github.com/login/oauth/access_token", DeviceAuth.accessTokenUrl())
        assertEquals("https://github.com/login/oauth/authorize", ApprovalOAuthProtocol.authorizeUrl())
        assertEquals("https://github.com/login/oauth/access_token", ApprovalOAuthProtocol.tokenUrl())
    }

    // ---- 同源判断 ----

    @Test fun originMatchesIgnoreHostCaseAndDefaultPortOnly() {
        val base = "https://github.com"
        assertTrue(Net.isLoginUrl("https://github.com/login/device", "/login/device", base))
        assertTrue(Net.isLoginUrl("https://GitHub.com/login/device", "/login/device", base))
        assertTrue(Net.isLoginUrl("https://github.com:443/login/device", "/login/device", base))
        assertFalse(Net.isLoginUrl("https://github.com:8443/login/device", "/login/device", base))
        assertFalse(Net.isLoginUrl("http://github.com/login/device", "/login/device", base))
        assertFalse(Net.isLoginUrl("https://github.com.evil.example/login/device", "/login/device", base))
        assertFalse(Net.isLoginUrl("https://user@github.com/login/device", "/login/device", base))
        assertFalse(Net.isLoginUrl("https://github.com/login/device?x=1", "/login/device", base))
        assertFalse(Net.isLoginUrl("https://github.com/login/device#x", "/login/device", base))
        assertFalse(Net.isLoginUrl("https://github.com/login/device/", "/login/device", base))
    }

    @Test fun originMatchesCustomHostWithItsOwnPort() {
        assertTrue(Net.isLoginUrl("https://203.0.113.10:8443/login/oauth", "/login/oauth", "https://203.0.113.10:8443"))
        assertFalse(Net.isLoginUrl("https://203.0.113.10/login/oauth", "/login/oauth", "https://203.0.113.10:8443"))
    }

    // ---- 设备授权：verification_uri ----

    @Test fun deviceVerificationUriIsCheckedAgainstTheDefaultHost() {
        assertEquals("https://github.com/login/device",
            DeviceAuth.decodeAuthorization(deviceAuthJson("https://github.com/login/device")).verificationUri)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceAuth.decodeAuthorization(deviceAuthJson("https://github.com/login/oauth"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceAuth.decodeAuthorization(deviceAuthJson("https://example.com/login/device"))
        }
    }

    @Test fun deviceVerificationUriIsCheckedAgainstTheCustomHost() {
        assertEquals("$custom/login/device",
            DeviceAuth.decodeAuthorization(deviceAuthJson("$custom/login/device"), custom).verificationUri)
        // 自定义站点后，GitHub 原站不再被接受
        assertThrows(IllegalArgumentException::class.java) {
            DeviceAuth.decodeAuthorization(deviceAuthJson("https://github.com/login/device"), custom)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceAuth.decodeAuthorization(deviceAuthJson("$custom/login/oauth"), custom)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceAuth.decodeAuthorization(deviceAuthJson("https://evil.example.com/login/device"), custom)
        }
    }

    // ---- 网页授权：iss ----

    @Test fun issuerIsCheckedAgainstTheDefaultHost() {
        assertEquals("test-code", callback(null, "https://github.com"))
        assertEquals("test-code", callback("https://github.com/login/oauth", "https://github.com"))
        assertThrows(IllegalArgumentException::class.java) {
            callback("https://github.com/login/device", "https://github.com")
        }
    }

    @Test fun issuerIsCheckedAgainstTheCustomHost() {
        assertEquals("test-code", callback("$custom/login/oauth", custom))
        assertThrows(IllegalArgumentException::class.java) { callback("https://github.com/login/oauth", custom) }
        assertThrows(IllegalArgumentException::class.java) { callback("$custom/login/device", custom) }
        assertThrows(IllegalArgumentException::class.java) { callback("https://evil.example.com/login/oauth", custom) }
        assertThrows(IllegalArgumentException::class.java) { callback("http://login.example.workers.dev/login/oauth", custom) }
    }
}
