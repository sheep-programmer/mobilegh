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
    /** 应用全部功能所需的权限范围。参考 gh CLI：repo 含私有库读写，delete_repo 才能删库，project 才能写看板 */
    const val SCOPES = "repo read:org notifications user gist workflow project delete_repo"
    val REQUIRED_SCOPES = SCOPES.split(" ")

    /** X-OAuth-Scopes 响应头 → 已授权集合；细粒度 PAT 不返回该头，返回 null 表示无法获知 */
    fun parseScopes(header: String?): Set<String>? =
        header?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()

    /** granted 为 null（无法获知）时不报缺失，避免误伤细粒度 Token 用户 */
    fun missingScopes(granted: Set<String>?): List<String> =
        if (granted == null) emptyList() else REQUIRED_SCOPES.filter { it !in granted }

    /** 缺少某权限时受影响的功能，提示用户为什么要补授权 */
    fun scopeFeature(scope: String): String = when (scope) {
        "repo" -> "私有仓库、Issues/PR 读写"
        "read:org" -> "组织与团队信息"
        "notifications" -> "通知已读与订阅"
        "user" -> "关注、列表与资料"
        "gist" -> "Gist"
        "workflow" -> "手动触发工作流"
        "project" -> "Projects 看板读写"
        "delete_repo" -> "删除仓库"
        else -> scope
    }

    /**
     * GitHub 的「权限不足」报错 → 中文指引。GraphQL 的 INSUFFICIENT_SCOPES 会写明缺的
     * scope（列表功能需要 user，而 2026-10 之前的旧授权没有它）；直接告诉用户去哪补，
     * 而不是把一段英文原文甩在对话框里。返回 null 表示这不是权限错误。
     */
    fun insufficientScopes(raw: String?): String? {
        if (raw == null || !raw.contains("required scopes", true) && !raw.contains("INSUFFICIENT_SCOPES", true)) return null
        val scope = Regex("""following scopes: \['"?([A-Za-z_:]+)"?'?\]""").find(raw)?.groupValues?.getOrNull(1)
        return buildString {
            append("当前登录授权权限不足")
            if (scope != null) append("：缺少 $scope（${scopeFeature(scope)}）")
            append("。请到「设置 → 重新登录，获取新授权」补齐权限后再试")
        }
    }
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    suspend fun start(clientId: String): DeviceAuthorization {
        return decodeAuthorization(post("/login/device/code", "client_id" to clientId, "scope" to SCOPES))
    }

    internal fun decodeAuthorization(r: JsonObject): DeviceAuthorization {
        val uri = r.str("verification_uri") ?: error("GitHub 未返回授权地址")
        require(uri == "https://github.com/login/device") { "GitHub 返回了未知的授权地址" }
        val expires = r.str("expires_in")?.toIntOrNull()?.takeIf { it > 0 }
            ?: error("GitHub 未返回有效的授权期限")
        return DeviceAuthorization(
            r.str("device_code") ?: error(r.str("error_description") ?: "无法获取设备码"),
            r.str("user_code") ?: error("无法获取授权码"), uri,
            expires.coerceAtMost(1800),
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
            client.routed().newCall(request).await().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, "授权请求失败 (${r.code})")
                val payload = Api.plain.parseToJsonElement(r.body.string()) as? JsonObject
                    ?: error("GitHub 未返回有效的授权信息")
                payload
            }
        }
    }
}
