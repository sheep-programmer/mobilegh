package com.mobilegh.data

import android.webkit.CookieManager
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

/** 没有网页登录会话（或会话已失效）：调用方应引导用户在应用内登录 github.com 后重试。 */
class WebSessionMissing : IllegalStateException(tr("需要先登录 GitHub 网页账号才能添加附件"))

/**
 * 评论附件上传，走 github.com 网页上拖拽附件的同一组接口（抓包确认的三步）：
 * 1. POST /upload/policies/assets 申请上传策略：需要网页会话 cookie 与页面的 fetch-nonce；
 * 2. POST 到返回的 S3 地址：multipart，策略字段按返回的顺序排列，文件放最后，这一步不带任何 cookie；
 * 3. PUT 到 asset_upload_url：用 asset_upload_authenticity_token 确认，返回附件地址。
 *
 * 只接受网页登录会话：用 Token 请求时实测返回 404 / 422，所以这里不使用 PAT。
 */
class WebAttachmentUploader(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://github.com",
    /** 返回 github.com 的 Cookie 请求头（没有则为 null）。只用于 github.com 的请求。 */
    private val cookieHeader: () -> String?,
) {
    suspend fun upload(repoId: Long, fileName: String, mime: String, bytes: ByteArray): String {
        val cookie = cookieHeader()
        if (!hasSession(cookie)) throw WebSessionMissing()
        val nonce = fetchNonce(cookie)

        val policy = client.newCall(
            Request.Builder().url("$baseUrl/upload/policies/assets")
                .post(
                    MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("repository_id", repoId.toString())
                        .addFormDataPart("name", fileName)
                        .addFormDataPart("size", bytes.size.toString())
                        .addFormDataPart("content_type", mime)
                        .build(),
                )
                .githubHeaders(cookie, nonce)
                .build(),
        ).await().use { resp -> resp.code to resp.body.string() }
        if (policy.first == 401 || policy.first == 403 || policy.first == 404) throw WebSessionMissing()
        if (policy.first !in 200..299) throw IOException(trf("附件上传失败（HTTP {0}）", policy.first))

        val json = Api.plain.parseToJsonElement(policy.second).jsonObject
        val s3Url = json.str("upload_url") ?: throw IllegalStateException(tr("上传策略缺少地址"))
        val form = (json["form"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }
            ?: throw IllegalStateException(tr("上传策略缺少表单字段"))
        val assetUrl = absolute(json.str("asset_upload_url") ?: throw IllegalStateException(tr("上传策略缺少确认地址")))
        val assetToken = json.str("asset_upload_authenticity_token") ?: throw IllegalStateException(tr("上传策略缺少确认令牌"))

        // 第二步：字段顺序必须与策略一致，文件放在最后；S3 不接受 GitHub 的 cookie
        val s3 = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
        }.build()
        client.newCall(Request.Builder().url(s3Url).post(s3).build()).await().use { resp ->
            if (!resp.isSuccessful) throw IOException(trf("附件上传失败（HTTP {0}）", resp.code))
        }

        // 第三步：确认，返回附件地址
        val confirm = Request.Builder().url(assetUrl)
            .header("Accept", "application/json")
            .githubHeaders(cookie, nonce)
            .put(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("authenticity_token", assetToken).build())
            .build()
        val href = client.newCall(confirm).await().use { resp ->
            if (!resp.isSuccessful) throw IOException(trf("附件上传失败（HTTP {0}）", resp.code))
            Api.plain.parseToJsonElement(resp.body.string()).str("href")
        }
        return href?.takeIf { it.isNotBlank() } ?: throw IllegalStateException(tr("上传成功但没有返回地址"))
    }

    /** 页面里的 fetch-nonce：github.com 网页发出的 fetch 请求都要带它。未登录的页面没有这个值。 */
    private suspend fun fetchNonce(cookie: String?): String {
        val req = Request.Builder().url("$baseUrl/").header("Accept", "text/html")
            .apply { cookie?.let { header("Cookie", it) } }
            .build()
        val html = client.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw WebSessionMissing()
            resp.body.string()
        }
        for (pattern in NONCE_PATTERNS) {
            pattern.find(html)?.let { return it.groupValues[1] }
        }
        throw WebSessionMissing()
    }

    private fun absolute(path: String): String = if (path.startsWith("http")) path else baseUrl + path

    private fun Request.Builder.githubHeaders(cookie: String?, nonce: String): Request.Builder = apply {
        header("X-Requested-With", "XMLHttpRequest")
        header("GitHub-Verified-Fetch", "true")
        header("X-Fetch-Nonce", nonce)
        cookie?.let { header("Cookie", it) }
    }

    companion object {
        private val NONCE_PATTERNS = listOf(
            Regex("<meta\\s+name=\"fetch-nonce\"\\s+content=\"([^\"]+)\""),
            Regex("<meta\\s+content=\"([^\"]+)\"\\s+name=\"fetch-nonce\""),
        )

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private var lastRenewTime = 0L

        /** GitHub 登录后写入的会话 cookie */
        fun hasSession(cookie: String?): Boolean = cookie?.contains("user_session=") == true

        /**
         * 自动保活 / 静默续期 Web Session：
         * 每天最多触发一次轻量心跳请求，更新 GitHub 服务端会话的活跃时间戳（Sliding Expiration），
         * 并将返回的最新的 Set-Cookie 实时写回 CookieManager 且 flush。
         */
        suspend fun renewSession(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && now - lastRenewTime < 24 * 3600 * 1000L) return@withContext true
            val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return@withContext false
            val cookie = runCatching { cookieManager.getCookie("https://github.com") }.getOrNull() ?: return@withContext false
            if (!hasSession(cookie)) return@withContext false

            val req = Request.Builder()
                .url("https://github.com/")
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            try {
                productionClient.routed().newCall(req).await().use { resp ->
                    if (resp.isSuccessful) {
                        lastRenewTime = now
                        val setCookies = resp.headers("Set-Cookie")
                        for (header in setCookies) {
                            cookieManager.setCookie("https://github.com", header)
                        }
                        cookieManager.flush()
                        true
                    } else false
                }
            } catch (_: Exception) {
                false
            }
        }

        fun renewSessionAsync(force: Boolean = false) {
            scope.launch { renewSession(force) }
        }

        private val productionClient: OkHttpClient by lazy {
            OkHttpClient.Builder().proxySelector(AppNetwork.proxySelector())
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        /** 应用里使用的实例：cookie 取自 WebView 的 Cookie 存储（应用内网页登录写入的） */
        fun forSession(): WebAttachmentUploader = WebAttachmentUploader(productionClient) {
            CookieManager.getInstance().getCookie("https://github.com")
        }
    }
}
