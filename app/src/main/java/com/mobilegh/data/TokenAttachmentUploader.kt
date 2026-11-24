package com.mobilegh.data

import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSink
import okio.source
import java.io.InputStream

/** Token based upload for GitHub's user-attachment endpoint. */
class TokenAttachmentRejected(val statusCode: Int) : IllegalStateException(
    trf("当前 Token 无法上传附件（HTTP {0}），请确认账号对仓库有写权限", statusCode),
)

/**
 * Uploads an image or video directly to GitHub's attachment storage.
 *
 * This is the same endpoint used by current GitHub CLI releases. It is separate
 * from the browser uploader because the two endpoints have different auth
 * contracts. The token is only accepted for the official uploads.github.com host.
 */
class TokenAttachmentUploader(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://uploads.github.com",
    private val token: () -> String? = { Session.token },
) {
    suspend fun upload(
        repositoryId: Long,
        fileName: String,
        mime: String,
        size: Long,
        open: () -> InputStream,
    ): String = withContext(Dispatchers.IO) {
        val accessToken = token()?.takeIf { it.isNotBlank() }
            ?: throw WebSessionMissing()
        require(repositoryId > 0) { tr("无法确定所属仓库，请稍后重试") }
        require(size > 0) { tr("无法读取附件大小") }

        val endpoint = (baseUrl.trimEnd('/') + "/user-attachments/assets" +
            "?name=${Api.q(fileName)}&content_type=${Api.q(mime)}&repository_id=$repositoryId").toHttpUrlOrNull()
        require(endpoint?.host == "uploads.github.com") {
            tr("当前 GitHub 站点不支持应用内附件上传")
        }

        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { sink.writeAll(it.source()) }
            }
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/vnd.github+json")
            .header("Content-Type", "application/octet-stream")
            .post(body)
            .build()
        client.routed().newCall(request).await().use { response ->
            val responseText = response.body.string()
            if (response.code == 401 || response.code == 403 || response.code == 404) {
                throw TokenAttachmentRejected(response.code)
            }
            if (!response.isSuccessful) {
                throw ApiException(response.code, trf("附件上传失败（HTTP {0}）", response.code))
            }
            val url = runCatching { Api.plain.parseToJsonElement(responseText).str("url") }.getOrNull()
                ?: throw IllegalStateException(tr("上传成功但没有返回地址"))
            url.takeIf { it.startsWith("https://github.com/user-attachments/assets/") }
                ?: throw IllegalStateException(tr("上传成功但返回地址无效"))
        }
    }
}
