package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal object MobilePushCodec {
    fun number(input: InputStream): Long {
        var value = 0L
        for (shift in 0..63 step 7) {
            val b = input.read()
            if (b < 0) throw EOFException("推送数据被截断")
            require(shift != 63 || b and 254 == 0) { "推送整数溢出" }
            value = value or ((b and 127).toLong() shl shift)
            if (b and 128 == 0) return value
        }
        error("推送数据格式无效")
    }

    fun exact(input: InputStream, length: Int): ByteArray {
        require(length in 0..1_048_576) { "推送数据长度无效" }
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) throw EOFException("推送数据被截断")
            if (n == 0) throw EOFException("推送连接没有返回数据")
            offset += n
        }
        return bytes
    }

    fun fields(data: ByteArray): Map<Int, List<ByteArray>> {
        val input = ByteArrayInputStream(data)
        val fields = mutableMapOf<Int, MutableList<ByteArray>>()
        while (input.available() > 0) {
            val tag = number(input)
            require(tag in 1..Int.MAX_VALUE && tag ushr 3 > 0)
            val value = when ((tag and 7).toInt()) {
                0 -> RegistrationProto.varint(number(input))
                1 -> exact(input, 8)
                2 -> { val length = number(input); require(length in 0..1_048_576); exact(input, length.toInt()) }
                5 -> exact(input, 4)
                else -> error("推送数据类型无效")
            }
            fields.getOrPut((tag ushr 3).toInt()) { mutableListOf() }.add(value)
        }
        return fields
    }

    fun decrypt(encoded: String, credentials: JsonObject): JsonObject {
        val data = Base64.getDecoder().decode(encoded)
        require(data.size >= 65 && data[0] == 0x80.toByte() && (data.size - 49) % 16 == 0) { "认证推送格式无效" }
        val encryption = Base64.getDecoder().decode(credentials.getValue("encryptionKey").jsonPrimitive.content)
        val auth = Base64.getDecoder().decode(credentials.getValue("hmacKey").jsonPrimitive.content)
        require(encryption.size == 32 && auth.size == 32)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(auth, "HmacSHA256")); update(data, 0, data.size - 32) }.doFinal()
        require(MessageDigest.isEqual(mac, data.copyOfRange(data.size - 32, data.size))) { "认证推送校验失败" }
        val plain = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(encryption, "AES"), IvParameterSpec(data, 1, 16))
            doFinal(data, 17, data.size - 49)
        }
        return Json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonObject
    }
}

/** Authenticated FCM MCS connection, independent of Google Play services and the official APK. */
internal class MobileApprovalPush(private val credentials: JsonObject, private val receivedIds: List<String>) {
    @Volatile private var socket: Socket? = null
    fun close() { runCatching { socket?.close() } }

    suspend fun listen(onConnected: suspend (Boolean) -> Unit, onAuthRequest: suspend (Int, String?) -> Unit) = withContext(Dispatchers.IO) {
        val raw = Socket()
        socket = raw
        try {
            raw.connect(InetSocketAddress("mtalk.google.com", 5228), 15_000)
            raw.soTimeout = 60_000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, "mtalk.google.com", 5228, true) as SSLSocket).use { tls ->
                socket = tls
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.soTimeout = 60_000
                tls.startHandshake()
                val input = tls.inputStream
                val output = tls.outputStream
                val p = RegistrationProto
                val id = credentials.getValue("androidId").jsonPrimitive.content
                val login = p.string(1, "com.github.android-1.275.0") + p.string(2, "mcs.android.com") +
                    p.string(3, id) + p.string(4, id) + p.string(5, credentials.getValue("securityToken").jsonPrimitive.content) +
                    p.string(6, "android-${java.lang.Long.toHexString(java.lang.Long.parseUnsignedLong(id))}") +
                    p.bytes(8, p.string(1, "new_vc") + p.string(2, "1")) +
                    receivedIds.fold(byteArrayOf()) { bytes, value -> bytes + p.string(10, value) } +
                    p.number(12, 0) + p.number(14, 1) + p.number(16, 2) + p.number(17, 1)
                output.write(41)
                send(output, 2, login)
                val version = input.read()
                check(version == 41 || version == 38) { "推送协议版本不支持" }
                check(input.read() == 3) { "认证推送登录失败" }
                val loginResponse = MobilePushCodec.fields(packet(input))
                check(loginResponse[3] == null) { "认证推送登录被拒绝" }
                onConnected(true)
                var lastInbound = System.currentTimeMillis()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val tag = try { input.read() } catch (_: SocketTimeoutException) {
                        check(System.currentTimeMillis() - lastInbound < 180_000) { "认证推送连接已超时" }
                        send(output, 0, byteArrayOf())
                        continue
                    }
                    if (tag < 0) throw EOFException("认证推送连接已关闭")
                    val data = packet(input)
                    lastInbound = System.currentTimeMillis()
                    if (tag == 0) { send(output, 1, byteArrayOf()); continue }
                    check(tag != 4 && tag != 10) { "认证推送连接已断开" }
                    if (tag != 8) continue
                    val fields = MobilePushCodec.fields(data)
                    fun text(field: Int) = fields[field]?.firstOrNull()?.toString(Charsets.UTF_8)
                    if (text(5) != "com.github.android" || text(3) != "890224420307") continue
                    val values = fields[7].orEmpty().associate { entry ->
                        val kv = MobilePushCodec.fields(entry)
                        kv[1]?.firstOrNull()?.toString(Charsets.UTF_8).orEmpty() to kv[2]?.firstOrNull()?.toString(Charsets.UTF_8).orEmpty()
                    }
                    val encoded = values["encrypted"] ?: continue
                    val notification = runCatching { MobilePushCodec.decrypt(encoded, credentials) }.getOrNull() ?: continue
                    if (notification["type"]?.jsonPrimitive?.content != "mobile_device_auth") continue
                    val requestId = notification["thread_id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                    onAuthRequest(requestId, text(9))
                }
            }
        } finally {
            close(); raw.close(); socket = null
            onConnected(false)
        }
    }

    private fun packet(input: InputStream): ByteArray {
        val length = MobilePushCodec.number(input)
        require(length in 0..1_048_576)
        return MobilePushCodec.exact(input, length.toInt())
    }

    private fun send(output: OutputStream, tag: Int, data: ByteArray) {
        output.write(tag); output.write(RegistrationProto.varint(data.size.toLong())); output.write(data); output.flush()
    }
}
