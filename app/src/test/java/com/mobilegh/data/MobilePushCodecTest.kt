package com.mobilegh.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MobilePushCodecTest {
    private val encryption = ByteArray(32) { it.toByte() }
    private val auth = ByteArray(32) { (it + 32).toByte() }
    private val credentials = buildJsonObject {
        put("encryptionKey", Base64.getEncoder().encodeToString(encryption))
        put("hmacKey", Base64.getEncoder().encodeToString(auth))
    }
    private fun fixture(): ByteArray {
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryption, "AES"), IvParameterSpec(iv))
            doFinal("{\"type\":\"mobile_device_auth\",\"thread_id\":\"123\"}".toByteArray())
        }
        val data = byteArrayOf(0x80.toByte()) + iv + cipher
        val mac = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(auth, "HmacSHA256")); doFinal(data) }
        return data + mac
    }

    @Test fun authenticatedPushDecryptsOnlyAfterMacValidation() {
        val data = fixture()
        val decoded = MobilePushCodec.decrypt(Base64.getEncoder().encodeToString(data), credentials)
        assertEquals("123", decoded.getValue("thread_id").jsonPrimitive.content)
        for (position in listOf(0, 1, 17, data.size - 1)) {
            val corrupt = data.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) {
                MobilePushCodec.decrypt(Base64.getEncoder().encodeToString(corrupt), credentials)
            }
        }
    }

    @Test fun repeatedMessageFieldsPreserveAppDataEntries() {
        val p = RegistrationProto
        val message = p.string(3, "890224420307") + p.string(5, "com.github.android") +
            p.bytes(7, p.string(1, "encrypted") + p.string(2, "fixture")) +
            p.bytes(7, p.string(1, "google.c.sender.id") + p.string(2, "890224420307"))
        val fields = MobilePushCodec.fields(message)
        assertEquals(2, fields.getValue(7).size)
        assertEquals("encrypted", MobilePushCodec.fields(fields.getValue(7)[0]).getValue(1).single().toString(Charsets.UTF_8))
    }

    @Test fun malformedFramesAreBoundedAndCannotAllocateUnboundedPayloads() {
        assertEquals(0, MobilePushCodec.number(ByteArrayInputStream(byteArrayOf(0))))
        for (data in listOf(byteArrayOf(10, 100, 1), byteArrayOf(0), ByteArray(11) { -128 })) {
            assertThrows(Exception::class.java) { MobilePushCodec.fields(data) }
        }
        assertThrows(IllegalArgumentException::class.java) { MobilePushCodec.exact(ByteArrayInputStream(byteArrayOf()), 1_048_577) }
    }
}
