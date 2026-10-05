package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DeviceAuthTest {
    private fun decode(text: String) = DeviceAuth.decodePoll(Json.parseToJsonElement(text).jsonObject)
    @Test fun deviceFlowHandlesPendingBackoffAndCompletion() {
        assertEquals(DevicePoll.Pending, decode("""{"error":"authorization_pending"}"""))
        assertEquals(DevicePoll.SlowDown(10), decode("""{"error":"slow_down","interval":10}"""))
        assertEquals(DevicePoll.SlowDown(null), decode("""{"error":"slow_down"}"""))
        assertEquals(DevicePoll.Authorized("test-only-token"), decode("""{"access_token":"test-only-token","token_type":"bearer"}"""))
    }
    @Test fun capturesRotatingCredentialsOnlyWhenGitHubReturnsThem() {
        assertEquals(DevicePoll.Authorized("test-access", "test-refresh", 28800),
            decode("""{"access_token":"test-access","refresh_token":"test-refresh","expires_in":28800}"""))
        assertEquals(DevicePoll.Authorized("test-access"), decode("""{"access_token":"test-access","expires_in":-1}"""))
    }
    @Test fun terminalErrorsNeverCountAsAuthorization() {
        assertTrue(decode("""{"error":"expired_token"}""") is DevicePoll.Failed)
        assertTrue(decode("""{"error":"access_denied"}""") is DevicePoll.Failed)
        assertTrue(decode("""{"error":"device_flow_disabled"}""") is DevicePoll.Failed)
        assertTrue(decode("""{"access_token":"","error":"unknown"}""") is DevicePoll.Failed)
        assertTrue(decode("{}") is DevicePoll.Failed)
    }
}
