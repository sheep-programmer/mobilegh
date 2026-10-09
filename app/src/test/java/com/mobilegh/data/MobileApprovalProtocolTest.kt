package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class MobileApprovalProtocolTest {
    private val request = MobileApprovalRequest(123, "AAH/gA==", true, "TWO_FACTOR_LOGIN")

    @Test fun signingBytesMatchPythonAndOfficialApkIncludingIntegerNormalization() {
        val expected = byteArrayOf(49, 124, 0, 1, -1, -128, 124, 55)
        assertArrayEquals(expected, MobileApprovalProtocol.signingMessage(request, "007"))
        assertArrayEquals(expected.copyOf(6), MobileApprovalProtocol.signingMessage(request.copy(challengeRequired = false), ""))
    }

    @Test fun missingUnicodeOrOversizedDigitsCannotBeSigned() {
        for (digits in listOf("", "１２", "-1", "1.0", "123456")) {
            assertThrows(IllegalArgumentException::class.java) { MobileApprovalProtocol.signingMessage(request, digits) }
        }
    }

    @Test fun requestBindingRejectsExpiryAndChangesToIdPayloadOrChallenge() {
        assertTrue(MobileApprovalProtocol.sameRequest(request, request))
        for (active in listOf(null, request.copy(id = 124), request.copy(payload = "Y2hhbmdlZA=="),
            request.copy(challengeRequired = false), request.copy(type = "TWO_FACTOR_SUDO_CHALLENGE"))) {
            assertFalse(MobileApprovalProtocol.sameRequest(request, active))
        }
    }

    @Test fun malformedChallengesAndIncompleteImportsAreRejected() {
        for (payload in listOf("", "bad!")) {
            assertThrows(IllegalArgumentException::class.java) { MobileApprovalProtocol.signingMessage(request.copy(payload = payload), "7") }
        }
        for (bundle in listOf("{}", """{"version":2}""", """{"version":1,"login":"alice"}""")) {
            assertThrows(IllegalArgumentException::class.java) { MobileApprovalProtocol.parseBundle(bundle) }
        }
    }

    @Test fun graphqlPendingAndEmptyStatesDecodeWithoutInventingRequests() {
        assertNull(MobileApprovalProtocol.parseRequest(Json.parseToJsonElement("""{"activeAuthRequest":null}""").jsonObject))
        assertEquals(request, MobileApprovalProtocol.parseRequest(Json.parseToJsonElement(
            """{"activeAuthRequest":{"id":123,"payload":"AAH/gA==","challengeRequired":true,"type":"TWO_FACTOR_LOGIN"}}""").jsonObject))
    }

    @Test fun derEcdsaSignatureVerifiesOnlyForTheExactNumberAndKey() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val message = MobileApprovalProtocol.signingMessage(request, "7")
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(message); sign() }
        val encoded = Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(signature) + "\n"
        fun verify(bytes: ByteArray): Boolean = Signature.getInstance("SHA256withECDSA").run {
            initVerify(key.public); update(bytes); this.verify(Base64.getMimeDecoder().decode(encoded))
        }
        assertTrue(verify(message))
        assertFalse(verify(MobileApprovalProtocol.signingMessage(request, "8")))
    }
}
