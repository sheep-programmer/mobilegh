package com.mobilegh.data

import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ApiErrorHandlingTest {
    @Test fun friendlyMapsRateLimitGatewayAndPatErrors() {
        val rateLimited = Api.friendly(429, "API rate limit exceeded", retryAfterSeconds = 45)
        assertTrue(rateLimited.contains("45"))

        val gateway = Api.friendly(502, "Bad Gateway")
        assertTrue(gateway.contains("502") && gateway.contains("代理"))

        val pat = Api.friendly(403, "Resource not accessible by personal access token")
        assertTrue(pat.contains("PAT"))

        val admin = Api.friendly(403, "Must have admin rights to Repository.")
        assertTrue(admin.contains("管理员"))
    }

    @Test fun ensureOkAttachesRetryAfterHeader() {
        val headers = Headers.headersOf("retry-after", "30")
        val err = runCatching { Api.ensureOk(Resp(429, """{"message":"Too many requests"}""", headers)) }.exceptionOrNull()
        assertTrue(err is ApiException)
        assertEquals(429, (err as ApiException).code)
        assertTrue(err.message.contains("30"))
    }

    @Test fun networkHintDistinguishesTimeoutAndConnectFailure() {
        assertTrue(SocketTimeoutException("timeout").networkHint()!!.contains("超时"))
        assertTrue(ConnectException("refused").networkHint()!!.contains("端口"))
    }
}
