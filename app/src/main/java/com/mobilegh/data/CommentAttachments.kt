package com.mobilegh.data

import com.mobilegh.i18n.tr

/**
 * Issue / PR 评论的图片与视频附件（GitHub 网页版同款能力）。
 *
 * 上传走 github.com 网页的附件接口，需要应用内登录的网页会话（见 [WebAttachmentUploader]）。
 * 服务器按**文件内容**校验类型，只接受图片与视频；这里也先按扩展名做本地预判。
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
     * 没有网页登录会话时抛出 [WebSessionMissing]，由界面先引导登录。
     */
    suspend fun upload(repoId: Long, fileName: String, size: Long, open: () -> java.io.InputStream): String {
        require(isAllowed(fileName)) { tr("只支持图片和视频附件（GitHub 的限制）") }
        require(size in 1..MAX_SIZE) { tr("附件不能超过 10 MB") }
        val bytes = open().use { it.readBytes() }
        return WebAttachmentUploader.forSession().upload(repoId, fileName, mimeOf(fileName), bytes)
    }
}
