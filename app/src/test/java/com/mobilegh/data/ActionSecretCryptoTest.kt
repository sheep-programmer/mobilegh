package com.mobilegh.data

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ActionSecretCryptoTest {
    @Test fun requestContainsOnlySealedValueAndPublicKeyId() {
        val plaintext = "unit-test-plaintext".toByteArray()
        val request = sealedActionSecretRequest(
            name = "TOKEN",
            plaintext = plaintext,
            publicKey = ActionPublicKey(keyId = "key-1", key = "cHVibGljLWtleQ=="),
            sealer = ActionSecretSealer { bytes, publicKeyBase64 ->
                assertTrue(bytes.contentEquals(plaintext))
                assertEquals("cHVibGljLWtleQ==", publicKeyBase64)
                "ZW5jcnlwdGVk"
            },
        )
        assertEquals(setOf("encrypted_value", "key_id"), request.body!!.keys)
        assertEquals("ZW5jcnlwdGVk", request.body["encrypted_value"]!!.jsonPrimitive.content)
        assertEquals("key-1", request.body["key_id"]!!.jsonPrimitive.content)
        assertFalse(request.body.toString().contains("unit-test-plaintext"))
    }

    @Test fun plaintextNeverAppearsInFailure() {
        val plaintext = "unit-test-plaintext"
        try {
            sealedActionSecretRequest(
                name = "TOKEN",
                plaintext = plaintext.toByteArray(),
                publicKey = ActionPublicKey(keyId = "key-1", key = "invalid"),
                sealer = ActionSecretSealer { _, _ -> throw IllegalStateException("failed: $plaintext") },
            )
            fail("encryption failure must be surfaced")
        } catch (e: ActionSecretCryptoException) {
            assertFalse(e.message.orEmpty().contains(plaintext))
            assertFalse(e.cause?.message.orEmpty().contains(plaintext))
        }
    }

    @Test fun invalidPublicKeyHasGenericFailure() {
        val plaintext = "unit-test-plaintext"
        try {
            LazySodiumActionSecretSealer().seal(plaintext.toByteArray(), "not-base64")
            fail("invalid public key must fail")
        } catch (e: ActionSecretCryptoException) {
            assertFalse(e.message.orEmpty().contains(plaintext))
            assertFalse(e.message.orEmpty().contains("not-base64"))
        }
    }
}
