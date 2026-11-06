package com.mobilegh.data

import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Issue / PR 评论的图片与视频附件（GitHub 网页版同款能力）。
 *
 * 上传走 uploads.github.com 的 user-attachments 接口，服务器按**文件内容**校验类型，
 * 只接受图片与视频——文本、压缩包、PDF 等一律 422（与网页上拖拽附件的限制一致）。
 * 返回的地址在插入评论正文后才会真正关联到 Issue。
 */
object CommentAttachments {
    /** 上传前在本地预判的允许类型；服务器仍会做最终校验（magic bytes） */
    val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
    internal val VIDEO_EXT = setOf("mp4", "mov", "webm")

    /** 单个附件上限（网页端同款 10 MB） */
    const val MAX_SIZE = 10L * 1024 * 1024

    fun isAllowed(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXT || ext in VIDEO_EXT
    }

    fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    @Serializable
    private data class Uploaded(val url: String = "")

    /**
     * 上传一个附件，返回 `https://github.com/user-attachments/assets/<uuid>` 形式的地址。
     * [open] 每次调用给出新输入流（重试时也要能重新读）。
     */
    suspend fun upload(repoId: Long, fileName: String, size: Long, open: () -> java.io.InputStream): String {
        require(isAllowed(fileName)) { tr("只支持图片和视频附件（GitHub 的限制）") }
        require(size in 1..MAX_SIZE) { tr("附件不能超过 10 MB") }
        val target = "https://uploads.github.com/user-attachments/assets" +
            "?name=${Api.q(fileName)}&content_type=${Api.q(mimeOf(fileName))}&repository_id=$repoId"
        // 服务器按 multipart 接收；一次性写进内存（上限 10 MB，可接受）
        val bytes = open().use { it.readBytes() }
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mimeOf(fileName).toMediaTypeOrNull()))
            .build()
        val resp = Api.uploadRaw(target, mimeOf(fileName), bytes.size.toLong(), { java.io.ByteArrayInputStream(bytes) }, multipart)
        Api.ensureOk(resp)
        val url = Api.plain.decodeFromString(Uploaded.serializer(), resp.body).url
        require(url.isNotBlank()) { tr("上传成功但没有返回地址") }
        return url
    }
}
