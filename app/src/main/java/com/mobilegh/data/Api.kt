package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, override val message: String) : Exception(message)

class Resp(val code: Int, val body: String, val headers: Headers)

object Api {
    /** 官方地址或用户自建的 API 反向代理 */
    val BASE: String get() = Net.apiBase
    const val HTML = "application/vnd.github.html"
    const val RAW = "application/vnd.github.raw"
    const val FULL = "application/vnd.github.full+json"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val AUTH_HOSTS = setOf("api.github.com", "raw.githubusercontent.com", "uploads.github.com")

    lateinit var cacheDir: File

    /** REST：GitHub 使用 snake_case */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    /** GraphQL：camelCase */
    val plain = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    private val cache by lazy { Cache(File(cacheDir, "http"), 64L * 1024 * 1024) }

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder().header("User-Agent", "MobileGH/1.0 (Android)")
                val token = Session.token
                // 只向 GitHub 自己的域名发送 Token，避免泄露给第三方图片/CDN
                val apiHost = Net.apiHost
                if (token != null && req.header("Authorization") == null && (req.url.host in AUTH_HOSTS || req.url.host == apiHost)) {
                    b.header("Authorization", "Bearer $token")
                }
                if (req.url.host == apiHost || req.url.host == "api.github.com") {
                    b.header("X-GitHub-Api-Version", "2022-11-28")
                    if (req.header("Accept") == null) b.header("Accept", "application/vnd.github+json")
                }
                val authenticatedReq = b.build()
                val resp = chain.proceed(authenticatedReq)
                // 验证其他 Token、或切换账号前发出的旧请求，不应使当前账号失效。
                if (resp.code == 401 && token != null && Session.token == token &&
                    authenticatedReq.header("Authorization") == "Bearer $token" &&
                    (req.url.host in AUTH_HOSTS || req.url.host == apiHost)
                ) {
                    Session.authExpired = true
                }
                resp.header("x-ratelimit-remaining")?.let { rem ->
                    Session.rateRemaining = "$rem/${resp.header("x-ratelimit-limit") ?: "?"}"
                }
                resp
            }
            .build()
    }

    fun clearCache() = runCatching { cache.evictAll() }

    fun url(path: String) = if (path.startsWith("http")) path else BASE + path

    suspend fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        accept: String? = null,
        force: Boolean = false,
    ): Resp {
        val rb = Request.Builder().url(url(path))
        accept?.let { rb.header("Accept", it) }
        if (force) rb.cacheControl(CacheControl.Builder().noCache().build())
        val reqBody = when {
            body != null -> body.toString().toRequestBody(JSON_TYPE)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        rb.method(method, reqBody)
        val response = try {
            http.newCall(rb.build()).await()
        } catch (e: IOException) {
            throw ApiException(-1, "网络连接失败：${e.message ?: e.javaClass.simpleName}")
        }
        return withContext(Dispatchers.IO) {
            response.use { Resp(it.code, it.body.string(), it.headers) }
        }
    }

    fun ensureOk(r: Resp): Resp {
        if (r.code in 200..299) return r
        val msg = runCatching {
            json.parseToJsonElement(r.body).jsonObject["message"]?.jsonPrimitive?.content
        }.getOrNull()
        throw ApiException(r.code, friendly(r.code, msg))
    }

    private fun friendly(code: Int, msg: String?): String = when {
        code == 401 -> "认证失败：Token 无效或已过期"
        code == 403 && msg?.contains("rate limit", true) == true -> "API 调用次数已达上限，请稍后再试"
        code == 403 && msg?.contains("SAML", true) == true -> "该组织启用了 SAML SSO，请在 GitHub 网页上为 Token 授权 SSO"
        code == 404 -> "未找到内容（可能不存在或 Token 没有权限）"
        code == 409 && msg != null -> msg
        code == 422 -> "请求无效：${msg ?: ""}"
        msg != null -> "$msg ($code)"
        else -> "请求失败 ($code)"
    }

    suspend inline fun <reified T> get(path: String, force: Boolean = false, accept: String? = null): T {
        val body = ensureOk(call("GET", path, accept = accept, force = force)).body
        return withContext(Dispatchers.Default) { json.decodeFromString(body) }
    }

    suspend fun text(path: String, accept: String? = null, force: Boolean = false): String =
        ensureOk(call("GET", path, accept = accept, force = force)).body

    suspend inline fun <reified T> send(method: String, path: String, body: JsonElement? = null): T {
        val text = ensureOk(call(method, path, body)).body
        return withContext(Dispatchers.Default) { json.decodeFromString(text) }
    }

    suspend fun exec(method: String, path: String, body: JsonElement? = null) {
        ensureOk(call(method, path, body))
    }

    /** 204 = true, 404 = false（用于 star/follow 状态检查） */
    suspend fun exists(path: String): Boolean {
        val r = call("GET", path, force = true)
        return when (r.code) {
            204, 200 -> true
            404 -> false
            else -> ensureOk(r).let { true }
        }
    }

    suspend fun graphql(query: String, vars: Map<String, Any?> = emptyMap()): JsonObject {
        val body = buildJsonObject {
            put("query", JsonPrimitive(query))
            put("variables", JsonObject(vars.mapValues { (_, v) ->
                when (v) {
                    null -> JsonNull
                    is Number -> JsonPrimitive(v)
                    is Boolean -> JsonPrimitive(v)
                    is JsonElement -> v
                    else -> JsonPrimitive(v.toString())
                }
            }))
        }
        val r = ensureOk(call("POST", "/graphql", body))
        val root = plain.parseToJsonElement(r.body).jsonObject
        val data = root["data"]
        val errors = root["errors"] as? JsonArray
        if ((data == null || data is JsonNull) && errors != null) {
            val msg = errors.firstOrNull()?.jsonObject?.get("message")?.jsonPrimitive?.content
            throw ApiException(400, msg ?: "GraphQL 错误")
        }
        return data!!.jsonObject
    }

    fun encodePath(path: String) = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun q(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
}

fun JsonElement?.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? = ((this as? JsonObject)?.get(key) as? JsonArray)

fun JsonElement?.int(key: String): Int? = str(key)?.toIntOrNull()

fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }

@Suppress("unused")
private fun JsonElement.asArr() = jsonArray
