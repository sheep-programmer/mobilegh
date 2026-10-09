package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** A small bounded protobuf codec for the Android check-in registration messages. */
internal object RegistrationProto {
    fun varint(value: Long): ByteArray {
        var n = value
        val out = ByteArrayOutputStream()
        do { out.write(((n and 127) or if (n ushr 7 != 0L) 128 else 0).toInt()); n = n ushr 7 } while (n != 0L)
        return out.toByteArray()
    }
    fun number(field: Int, value: Long) = varint((field shl 3).toLong()) + varint(value)
    fun bytes(field: Int, value: ByteArray) = varint(((field shl 3) or 2).toLong()) + varint(value.size.toLong()) + value
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun fixed64Fields(data: ByteArray): Map<Int, Long> {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun readVarint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(b.hasRemaining()) { "注册响应被截断" }
                val byte = b.get().toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            error("注册响应格式无效")
        }
        val fields = mutableMapOf<Int, Long>()
        while (b.hasRemaining()) {
            val tag = readVarint().toInt()
            require(tag ushr 3 > 0)
            when (tag and 7) {
                0 -> readVarint()
                1 -> { require(b.remaining() >= 8); fields[tag ushr 3] = b.long }
                2 -> { val n = readVarint(); require(n >= 0 && n <= b.remaining()); b.position(b.position() + n.toInt()) }
                5 -> { require(b.remaining() >= 4); b.position(b.position() + 4) }
                else -> error("注册响应格式无效")
            }
        }
        return fields
    }
}

/** Registers this installation automatically; account credentials never go to Google. */
internal object MobilePushRegistration {
    private const val SENDER = "890224420307"
    private const val PACKAGE = "com.github.android"
    private const val CERT = "1a6e215d9aed38ea2c31d4872021e599a0415fa6"
    private const val APP_ID = "1:890224420307:android:835ea94c9a536bb0"
    private const val API_KEY = "AIzaSyDCtGsFDYicuqE-6xaeiOImwjaP5cvgut8"
    private val random = SecureRandom()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()

    suspend fun register(): JsonObject {
        val p = RegistrationProto
        val now = System.currentTimeMillis()
        val build = p.string(1, "google/razor/flo:5.0.1/LRX22C/1602158:user/release-keys") +
            p.string(2, "flo") + p.string(3, "google") + p.string(6, "android-google") +
            p.number(7, now / 1000) + p.string(9, "flo") + p.number(10, 30) +
            p.string(11, "Nexus 7") + p.string(12, "asus") + p.string(13, "razor")
        val event = p.string(1, "event_log_start") + p.number(3, now)
        val checkin = p.bytes(1, build) + p.number(2, 0) + p.bytes(3, event) + p.number(9, 0)
        val message = p.number(2, 0) + p.bytes(4, checkin) + p.string(6, "en_US") +
            p.number(7, random.nextLong() and Long.MAX_VALUE) + p.string(12, "GMT") +
            p.number(14, 3) + p.number(20, 0) + p.number(22, 0)
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(message) } }.toByteArray()
        val response = execute(Request.Builder().url("https://android.clients.google.com/checkin")
            .header("Content-Encoding", "gzip").header("User-Agent", "Android-Checkin/2.0 (generic JLS36G); gzip")
            .post(gzip.toRequestBody("application/x-protobuffer".toMediaType())).build())
        val fields = p.fixed64Fields(response)
        val androidId = fields[7]?.takeIf { it != 0L }?.let(java.lang.Long::toUnsignedString) ?: error("无法注册验证接收设备")
        val securityToken = fields[8]?.let(java.lang.Long::toUnsignedString) ?: error("无法创建验证接收会话")
        val fidBytes = ByteArray(17).also(random::nextBytes).also { it[0] = ((it[0].toInt() and 15) or 112).toByte() }
        val fid = Base64.getUrlEncoder().withoutPadding().encodeToString(fidBytes).take(22)
        val firebase = buildJsonObject { put("appId", APP_ID); put("authVersion", "FIS_v2"); put("sdkVersion", "a:17.0.0"); put("fid", fid) }
        val install = Json.parseToJsonElement(execute(Request.Builder()
            .url("https://firebaseinstallations.googleapis.com/v1/projects/$SENDER/installations")
            .header("x-goog-api-key", API_KEY).header("X-Android-Package", PACKAGE).header("X-Android-Cert", CERT.uppercase())
            .header("X-Firebase-Client", Base64.getUrlEncoder().withoutPadding().encodeToString("{\"heartbeats\":[],\"version\":2}".toByteArray()))
            .post(firebase.toString().toRequestBody("application/json".toMediaType())).build()).toString(Charsets.UTF_8)).jsonObject
        val realFid = install.getValue("fid").jsonPrimitive.content
        val fisToken = install.getValue("authToken").jsonObject.getValue("token").jsonPrimitive.content
        val values = listOf("app" to PACKAGE, "sender" to SENDER, "device" to androidId, "cert" to CERT,
            "app_ver" to "946", "target_ver" to "37", "info" to "", "X-subtype" to SENDER,
            "X-subscription" to SENDER, "X-app_ver" to "946", "X-osv" to "30", "X-cliv" to "fiid-21.1.0",
            "X-gmsv" to "240913000", "X-scope" to "*", "X-appid" to realFid, "X-gmp_app_id" to APP_ID,
            "X-Goog-Firebase-Installations-Auth" to fisToken,
            "X-firebase-app-name-hash" to MessageDigest.getInstance("SHA-1").digest(PACKAGE.toByteArray()).joinToString("") { "%02x".format(it) })
        val form = FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()
        var token: String? = null
        for (attempt in 1..7) {
            val text = execute(Request.Builder().url("https://android.clients.google.com/c2dm/register3")
                .header("Authorization", "AidLogin $androidId:$securityToken").header("app", PACKAGE)
                .header("User-Agent", "Android-GCM/1.5 (generic LRX22C)").post(form).build()).toString(Charsets.UTF_8).trim()
            if (text.startsWith("token=")) { token = text.removePrefix("token="); break }
            if (text.contains("PHONE_REGISTRATION_ERROR") && attempt < 7) delay(attempt.coerceAtMost(5) * 1000L)
            else error("验证接收设备注册失败，请稍后重试")
        }
        check(!token.isNullOrBlank()) { "验证接收设备注册失败" }
        fun key() = Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))
        return buildJsonObject { put("fcmToken", token); put("encryptionKey", key()); put("hmacKey", key())
            put("androidId", androidId); put("securityToken", securityToken) }
    }

    suspend fun refreshSession(credentials: JsonObject): JsonObject {
        val p = RegistrationProto
        val checkin = p.bytes(1, byteArrayOf()) + p.number(2, System.currentTimeMillis()) + p.number(9, 0)
        val message = p.number(2, java.lang.Long.parseUnsignedLong(credentials.getValue("androidId").jsonPrimitive.content)) +
            p.bytes(4, checkin) + p.number(7, 0) + p.number(14, 3) + p.number(20, 0) + p.number(22, 0) +
            p.varint(105) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(java.lang.Long.parseUnsignedLong(credentials.getValue("securityToken").jsonPrimitive.content)).array()
        val gzip = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(message) } }.toByteArray()
        val data = execute(Request.Builder().url("https://android.clients.google.com/checkin")
            .header("Content-Encoding", "gzip").header("User-Agent", "Android-Checkin/2.0 (generic JLS36G); gzip")
            .post(gzip.toRequestBody("application/x-protobuffer".toMediaType())).build())
        val fields = p.fixed64Fields(data)
        check(fields[7] != null && fields[8] != null) { "无法刷新认证推送连接" }
        return buildJsonObject { credentials.forEach { (k, v) -> put(k, v) }
            put("androidId", java.lang.Long.toUnsignedString(fields.getValue(7)))
            put("securityToken", java.lang.Long.toUnsignedString(fields.getValue(8))) }
    }

    private suspend fun execute(request: Request): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { response ->
            check(response.isSuccessful) { "验证设备注册连接失败 (${response.code})" }
            response.body.bytes().also { require(it.size <= 65536) { "验证设备注册响应过大" } }
        }
    }
}
