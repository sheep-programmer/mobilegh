package com.mobilegh.data

import android.content.Context
import com.mobilegh.BuildConfig
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit

data class MobileApprovalDevice(val login: String, val alias: String)
data class MobileApprovalRequest(val id: Int, val payload: String, val challengeRequired: Boolean, val type: String)

/** Shared with the proven Python implementation; no Android dependencies in the wire format. */
internal object MobileApprovalProtocol {
    fun signingMessage(request: MobileApprovalRequest, digits: String): ByteArray {
        val challenge = Base64.getDecoder().decode(request.payload)
        require(challenge.isNotEmpty()) { "GitHub 返回了空的验证请求" }
        val prefix = "1|".toByteArray(Charsets.UTF_8) + challenge
        if (!request.challengeRequired) return prefix
        require(digits.matches(Regex("[0-9]{1,5}"))) { "请输入浏览器显示的数字" }
        return prefix + "|${digits.toInt()}".toByteArray(Charsets.UTF_8)
    }

    fun sameRequest(expected: MobileApprovalRequest, active: MobileApprovalRequest?): Boolean =
        active != null && expected == active

    fun parseRequest(status: JsonObject): MobileApprovalRequest? {
        val request = status["activeAuthRequest"] as? JsonObject ?: return null
        return MobileApprovalRequest(request.getValue("id").jsonPrimitive.int,
            request.getValue("payload").jsonPrimitive.content,
            request.getValue("challengeRequired").jsonPrimitive.boolean,
            request.getValue("type").jsonPrimitive.content)
    }

    fun parseBundle(text: String): JsonObject {
        require(text.length <= 65536) { "设备配置文件过大" }
        val bundle = Json.parseToJsonElement(text).jsonObject
        require(bundle["version"]?.jsonPrimitive?.intOrNull == 1) { "设备配置版本不支持" }
        require(bundle["login"]?.jsonPrimitive?.content?.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) == true) { "设备配置的账号无效" }
        for (key in listOf("token", "privateKey", "certificate", "fcmToken", "encryptionKey", "hmacKey")) {
            require(!bundle[key]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "设备配置缺少 $key" }
        }
        for (key in listOf("encryptionKey", "hmacKey")) {
            require(Base64.getDecoder().decode(bundle.getValue(key).jsonPrimitive.content).size == 32) { "推送密钥无效" }
        }
        return bundle
    }
}

/** Uses a separately imported mobile-auth credential and never inherits API mirrors or the current login. */
object MobileApproval {
    private const val PREFS = "mobile_approval"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private const val STATUS_QUERY = """query MobileAuthRequests {
        viewer { login mobileAuthStatus { hasValidDeviceAuthKey hasExpiredAuthRequest
        activeAuthRequest { id payload challengeRequired type } } } }
    """
    private const val APPROVE_QUERY = """mutation ApproveMobileAuthDeviceRequest(
        ${'$'}requestId: Int!, ${'$'}signature: String!) {
        approveMobileAuthDeviceRequest(input: { requestId: ${'$'}requestId
        signature: ${'$'}signature signatureVersion: V1 }) { clientMutationId } }
    """
    private const val ROUTING_QUERY = """mutation AddMobileDeviceToken(
        ${'$'}deviceToken: String!, ${'$'}encryptionKey: String!, ${'$'}hmacKey: String!) {
        addMobileDeviceToken(input: { service: FCM deviceToken: ${'$'}deviceToken
        deviceName: "MobileGH" platform: 0 encryptionKey: ${'$'}encryptionKey
        hmacKey: ${'$'}hmacKey }) { success } }
    """
    private const val REGISTER_QUERY = """mutation RegisterDevice(${'$'}publicKey: String!, ${'$'}message: String!, ${'$'}signature: String!, ${'$'}name: String!, ${'$'}model: String!, ${'$'}hardware: Boolean!) {
        addMobileDevicePublicKey(input: { publicKey: ${'$'}publicKey verificationMessage: ${'$'}message
        verificationSignature: ${'$'}signature type: AUTH deviceName: ${'$'}name deviceModel: ${'$'}model
        deviceOs: ANDROID isHardwareBacked: ${'$'}hardware }) { expiresAt } }
    """

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    /** Generates this phone's signing key and registers it directly. No exported device bundle. */
    suspend fun activate(context: Context, token: String, expectedLogin: String? = null): MobileApprovalDevice = withContext(Dispatchers.IO) {
        val viewer = graph(token, "query { viewer { login mobileAuthStatus { hasValidDeviceAuthKey } } }").getValue("viewer").jsonObject
        val login = viewer.getValue("login").jsonPrimitive.content
        require(expectedLogin == null || expectedLogin.equals(login, true)) { "请授权当前账号 @$expectedLogin" }
        val suffix = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val alias = "mobilegh_2fa_${login.lowercase()}_$suffix"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val certificate = ks.getCertificate(alias)
        val pem = "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(certificate.publicKey.encoded)}\n-----END PUBLIC KEY-----\n"
        val verification = java.util.UUID.randomUUID().toString()
        val privateKey = ks.getKey(alias, null) as java.security.PrivateKey
        val hardware = runCatching {
            @Suppress("DEPRECATION")
            KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(privateKey, KeyInfo::class.java).isInsideSecureHardware
        }.getOrDefault(false)
        graph(token, REGISTER_QUERY, buildJsonObject {
            put("publicKey", Base64.getEncoder().encodeToString(pem.toByteArray()))
            put("message", verification); put("signature", sign(alias, verification.toByteArray()))
            put("name", "MobileGH · ${Build.MODEL}"); put("model", Build.MODEL); put("hardware", hardware)
        }).also { check(it["addMobileDevicePublicKey"] is JsonObject) { "GitHub 未接受验证设备注册" } }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = if (device(context)?.alias == alias) runCatching { credentials(context, MobileApprovalDevice(login, alias)) }.getOrNull() else null
        val push = previous?.takeIf { it["fcmToken"] != null } ?: MobilePushRegistration.register()
        refreshRouting(token, push)
        val stored = buildJsonObject { put("login", login); put("token", token)
            listOf("fcmToken", "encryptionKey", "hmacKey", "androidId", "securityToken").forEach { key -> push[key]?.let { put(key, it) } } }
        check(prefs.edit().putString("login", login).putString("alias", alias)
            .putString("credentials", Crypto.encrypt(stored.toString())).putBoolean("enabled", true).commit()) { "无法保存验证设备" }
        MobileApprovalDevice(login, alias)
    }

    internal suspend fun preparePush(context: Context, device: MobileApprovalDevice): JsonObject {
        val original = credentials(context, device)
        val configured = if (original["androidId"] == null || original["securityToken"] == null) {
            val registration = MobilePushRegistration.register()
            buildJsonObject { original.forEach { (k, v) -> put(k, v) }; registration.forEach { (k, v) -> put(k, v) } }
        } else original
        val refreshed = MobilePushRegistration.refreshSession(configured)
        credentials(context, device)
        refreshRouting(refreshed.getValue("token").jsonPrimitive.content, refreshed)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("credentials", Crypto.encrypt(refreshed.toString())).commit()) { "无法保存推送连接" }
        return refreshed
    }

    suspend fun user(token: String): User {
        val v = graph(token, "query { viewer { login avatarUrl } }").getValue("viewer").jsonObject
        return User(login = v.getValue("login").jsonPrimitive.content, avatarUrl = v.getValue("avatarUrl").jsonPrimitive.content)
    }

    fun device(context: Context): MobileApprovalDevice? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val login = prefs.getString("login", null) ?: return null
        val alias = prefs.getString("alias", null) ?: return null
        return MobileApprovalDevice(login, alias)
    }

    private fun credentials(context: Context, expected: MobileApprovalDevice): JsonObject {
        check(device(context) == expected) { "设备账号已更换，请重新读取请求" }
        val encrypted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("credentials", null)
            ?: error("请先导入数字批准设备")
        return Api.plain.parseToJsonElement(Crypto.decrypt(encrypted)).jsonObject
    }

    suspend fun importDevice(context: Context, text: String): MobileApprovalDevice = withContext(Dispatchers.IO) {
        val bundle = MobileApprovalProtocol.parseBundle(text)
        val login = bundle.getValue("login").jsonPrimitive.content
        val token = bundle.getValue("token").jsonPrimitive.content
        val viewer = graph(token, STATUS_QUERY).getValue("viewer").jsonObject
        require(viewer.getValue("login").jsonPrimitive.content.equals(login, true)) { "配置令牌与设备账号不一致" }
        val status = viewer.getValue("mobileAuthStatus").jsonObject
        check(status.getValue("hasValidDeviceAuthKey").jsonPrimitive.boolean) { "请先在脚本中注册数字批准设备" }
        val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(
            Base64.getDecoder().decode(bundle.getValue("privateKey").jsonPrimitive.content))) as ECPrivateKey
        require(key.params.curve.field.fieldSize == 256) { "设备密钥必须是 P-256" }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(
            Base64.getDecoder().decode(bundle.getValue("certificate").jsonPrimitive.content)))
        // Prove that the certificate and private key belong to the same imported device.
        val probe = "MobileGH device import".toByteArray(Charsets.UTF_8)
        val proof = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(probe); sign() }
        require(Signature.getInstance("SHA256withECDSA").run { initVerify(certificate); update(probe); verify(proof) }) {
            "设备证书与私钥不匹配"
        }
        val suffix = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(12)
            .joinToString("") { "%02x".format(it) }
        val alias = "mobilegh_2fa_${login.lowercase()}_$suffix"
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) {
            require(ks.getCertificate(alias).publicKey.encoded.contentEquals(certificate.publicKey.encoded)) { "此设备已有不同的批准密钥" }
        } else {
            ks.setEntry(alias, KeyStore.PrivateKeyEntry(key, arrayOf(certificate)),
                KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build())
        }
        refreshRouting(token, bundle)
        val stored = JsonObject(bundle.filterKeys { it != "privateKey" && it != "certificate" })
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("login", login).putString("alias", alias)
            .putString("credentials", Crypto.encrypt(stored.toString())).commit()) { "无法保存设备配置" }
        MobileApprovalDevice(login, alias)
    }

    suspend fun refreshRouting(context: Context, device: MobileApprovalDevice) {
        val c = credentials(context, device)
        refreshRouting(c.getValue("token").jsonPrimitive.content, c)
    }

    private suspend fun refreshRouting(token: String, c: JsonObject) {
        val result = graph(token, ROUTING_QUERY, buildJsonObject {
            put("deviceToken", c.getValue("fcmToken"))
            put("encryptionKey", c.getValue("encryptionKey"))
            put("hmacKey", c.getValue("hmacKey"))
        })
        check(result["addMobileDeviceToken"]?.jsonObject?.get("success")?.jsonPrimitive?.booleanOrNull == true) {
            "GitHub 未接受验证请求接收配置"
        }
    }

    suspend fun poll(context: Context, device: MobileApprovalDevice): MobileApprovalRequest? {
        val c = credentials(context, device)
        val request = pollToken(c.getValue("token").jsonPrimitive.content, device.login)
        credentials(context, device)
        return request
    }

    private suspend fun pollToken(token: String, login: String): MobileApprovalRequest? {
        val viewer = graph(token, STATUS_QUERY).getValue("viewer").jsonObject
        check(viewer.getValue("login").jsonPrimitive.content.equals(login, true)) { "验证账号不一致" }
        val status = viewer.getValue("mobileAuthStatus").jsonObject
        check(status.getValue("hasValidDeviceAuthKey").jsonPrimitive.boolean) { "批准设备已失效，请重新注册并导入" }
        return MobileApprovalProtocol.parseRequest(status)
    }

    suspend fun approve(context: Context, device: MobileApprovalDevice, expected: MobileApprovalRequest, digits: String) {
        val message = MobileApprovalProtocol.signingMessage(expected, digits)
        val c = credentials(context, device)
        val token = c.getValue("token").jsonPrimitive.content
        check(MobileApprovalProtocol.sameRequest(expected, pollToken(token, device.login))) {
            "请求已过期或被替换，请在浏览器重新发起验证"
        }
        val signature = withContext(Dispatchers.IO) { sign(device.alias, message) }
        credentials(context, device) // The account must still be the one the user confirmed.
        val result = graph(token, APPROVE_QUERY, buildJsonObject { put("requestId", expected.id); put("signature", signature) })
        check(result["approveMobileAuthDeviceRequest"] is JsonObject) { "GitHub 未返回批准结果" }
    }

    internal fun sign(alias: String, message: ByteArray): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = ks.getKey(alias, null) as? java.security.PrivateKey
            ?: error("设备密钥不可用，请重新导入")
        val signed = Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(message); sign() }
        return Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(signed) + "\n"
    }

    private suspend fun graph(token: String, query: String, variables: JsonObject = buildJsonObject {}): JsonObject {
        val body = buildJsonObject { put("query", query); put("variables", variables) }
        val request = Request.Builder().url("https://api.github.com/graphql")
            .header("Authorization", "Bearer $token").header("User-Agent", "MobileGH/${BuildConfig.VERSION_NAME} (Android)")
            .header("Accept", "application/vnd.github.v4+json").header("Cache-Control", "no-store")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                check(response.isSuccessful) { "GitHub 验证请求失败 (${response.code})" }
                val data = Api.plain.parseToJsonElement(response.body.string()).jsonObject
                val errors = data["errors"] as? JsonArray
                if (!errors.isNullOrEmpty()) {
                    val message = errors.first().jsonObject["message"]?.jsonPrimitive?.content.orEmpty()
                    error(if (message.contains("number you entered did not match")) "数字不匹配，请重新发起验证并输入新数字"
                          else if (message.contains("doesn't exist") || message.contains("not defined")) "此授权令牌不支持数字批准，请导入脚本导出的设备配置"
                          else message.take(180).ifBlank { "GitHub 未接受验证请求" })
                }
                data.getValue("data").jsonObject
            }
        }
    }
}
