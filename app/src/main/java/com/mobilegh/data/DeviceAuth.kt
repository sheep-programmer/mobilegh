package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class DeviceAuthorization(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresIn: Int, val interval: Int)

sealed interface DevicePoll {
    data class Authorized(val token: String, val refreshToken: String? = null, val expiresIn: Long? = null) : DevicePoll
    data object Pending : DevicePoll
    data class SlowDown(val interval: Int?) : DevicePoll
    data class Failed(val message: String) : DevicePoll
}

/** Public OAuth device flow. Never inherits API tokens, web cookies or mirror settings. */
object DeviceAuth {
    const val SCOPES = "repo read:org notifications user gist workflow"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun start(clientId: String): DeviceAuthorization {
        val r = post("/login/device/code", "client_id" to clientId, "scope" to SCOPES)
        val uri = r.str("verification_uri") ?: error("GitHub 未返回授权地址")
        require(uri == "https://github.com/login/device") { "GitHub 返回了未知的授权地址" }
        return DeviceAuthorization(
            r.str("device_code") ?: error(r.str("error_description") ?: "无法获取设备码"),
            r.str("user_code") ?: error("无法获取授权码"), uri,
            r.str("expires_in")?.toIntOrNull()?.coerceIn(30, 1800) ?: 900,
            r.str("interval")?.toIntOrNull()?.coerceAtLeast(5) ?: 5,
        )
    }

    suspend fun poll(clientId: String, code: String): DevicePoll = decodePoll(post(
        "/login/oauth/access_token", "client_id" to clientId, "device_code" to code,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
    ))

    /** GitHub documents the client-secret exception for tokens issued by the device flow. */
    suspend fun refresh(clientId: String, refreshToken: String): DevicePoll = decodePoll(post(
        "/login/oauth/access_token", "client_id" to clientId, "refresh_token" to refreshToken,
        "grant_type" to "refresh_token",
    ))

    internal fun decodePoll(r: JsonObject): DevicePoll {
        r.str("access_token")?.takeIf { it.isNotBlank() }?.let {
            return DevicePoll.Authorized(it, r.str("refresh_token"), r.str("expires_in")?.toLongOrNull()?.takeIf { seconds -> seconds > 0 })
        }
        return when (r.str("error")) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown(r.str("interval")?.toIntOrNull())
            "access_denied" -> DevicePoll.Failed("已取消授权")
            "expired_token" -> DevicePoll.Failed("授权码已过期，请重新开始")
            "incorrect_client_credentials" -> DevicePoll.Failed("OAuth 应用配置无效，请使用 Token 登录")
            "device_flow_disabled" -> DevicePoll.Failed("OAuth 应用尚未启用设备授权")
            else -> DevicePoll.Failed(r.str("error_description") ?: "GitHub 授权失败")
        }
    }

    private suspend fun post(path: String, vararg fields: Pair<String, String>): JsonObject {
        val body = FormBody.Builder().apply { fields.forEach { add(it.first, it.second) } }.build()
        val request = Request.Builder().url("https://github.com$path").post(body)
            .header("Accept", "application/json").header("User-Agent", "MobileGH").build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).await().use { r ->
                val payload = Api.plain.parseToJsonElement(r.body.string()) as? JsonObject
                    ?: error("GitHub 未返回有效的授权信息")
                if (!r.isSuccessful) throw ApiException(r.code, "授权请求失败 (${r.code})")
                payload
            }
        }
    }
}
