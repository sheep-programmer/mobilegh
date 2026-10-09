package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ApprovalSetupProtocolTest {
    @Test fun pkceMatchesRfc7636S256Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ApprovalOAuthProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun callbacksRequireExactRegisteredRouteAndMatchingState() {
        assertEquals("test-code", ApprovalOAuthProtocol.callback("github://com.github.android/oauth?code=test-code&state=expected", "expected"))
        for (url in listOf("github://com.github.android/oauth?code=test&state=wrong",
            "https://com.github.android/oauth?code=test&state=expected",
            "github://evil.example/oauth?code=test&state=expected",
            "github://com.github.android/other?code=test&state=expected",
            "github://user@com.github.android/oauth?code=test&state=expected",
            "github://com.github.android:443/oauth?code=test&state=expected",
            "github://com.github.android/oauth?code=test&state=expected&iss=https%3A%2F%2Fevil.example",
            "github://com.github.android/oauth?code=a&code=b&state=expected")) {
            assertThrows(IllegalArgumentException::class.java) { ApprovalOAuthProtocol.callback(url, "expected") }
        }
        assertThrows(IllegalStateException::class.java) {
            ApprovalOAuthProtocol.callback("github://com.github.android/oauth?error=access_denied&state=expected", "expected")
        }
    }

    @Test fun checkinResponseExtractsUnsignedDeviceAndSessionIdsWhileSkippingOtherFields() {
        val id = Long.MIN_VALUE + 17
        val token = -37L
        val response = RegistrationProto.number(1, 1) + byteArrayOf(57) +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(id).array() +
            RegistrationProto.string(11, "ignored") + byteArrayOf(65) +
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(token).array()
        assertEquals(mapOf(7 to id, 8 to token), RegistrationProto.fixed64Fields(response))
    }

    @Test fun malformedProtobufCannotOverrunBuffersOrLoopForever() {
        for (bytes in listOf(byteArrayOf(57, 1), byteArrayOf(10, 100, 1), byteArrayOf(0),
            ByteArray(12) { -128 }, byteArrayOf(15))) {
            assertThrows(Exception::class.java) { RegistrationProto.fixed64Fields(bytes) }
        }
        assertArrayEquals(byteArrayOf(0), RegistrationProto.varint(0))
        assertArrayEquals(byteArrayOf(-128, 1), RegistrationProto.varint(128))
    }
}
