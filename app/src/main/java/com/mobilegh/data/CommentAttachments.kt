package com.mobilegh.data

import com.mobilegh.i18n.tr

/**
 * Issue / PR 评论的图片与视频附件（GitHub 网页版同款能力）。
 *
 * 优先走 GitHub 的 Token 附件接口。当前 Token 不具备上传资格时，如果已有网页会话，
 * 再回退到网页附件接口。服务器按**文件内容**校验类型，这里也先按扩展名做本地预判。
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

    /**
     * 上传一个附件，返回 `https://github.com/user-attachments/assets/<uuid>` 形式的地址。
     * 有普通 GitHub Token 时无需网页登录。Token 被服务端拒绝且没有网页会话时，返回可操作的权限错误。
     */
    suspend fun upload(repoId: Long, fileName: String, size: Long, open: () -> java.io.InputStream): String {
        require(isAllowed(fileName)) { tr("只支持图片和视频附件（GitHub 的限制）") }
        require(size in 1..MAX_SIZE) { tr("附件不能超过 10 MB") }
        val mime = mimeOf(fileName)
        if (Session.token != null && Net.loginBase == Net.DEFAULT_LOGIN_BASE) {
            try {
                return TokenAttachmentUploader(Api.http, token = { Session.token }).upload(repoId, fileName, mime, size, open)
            } catch (rejected: TokenAttachmentRejected) {
                // Fine-grained tokens and some OAuth apps can read the repository but
                // cannot use the attachment endpoint. A valid web session remains a
                // compatible fallback, and the stream can be opened again here.
                val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie("https://github.com") }.getOrNull()
                if (!WebAttachmentUploader.hasSession(cookie)) throw rejected
                WebAttachmentUploader.renewSession(force = true)
            }
        }
        val bytes = open().use { it.readBytes() }
        return WebAttachmentUploader.forSession().upload(repoId, fileName, mime, bytes)
    }
}
