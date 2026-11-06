package com.mobilegh.ui.components

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mobilegh.data.CommentAttachments
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.act

/**
 * 评论附件的选择与上传：从系统选择器选一张图片或一段视频，传到 GitHub 的 user-attachments，
 * 把返回的地址交给调用方插入评论正文。上传期间 [uploading] 会变非空，界面可以显示进度。
 */
class CommentAttachmentState(val uploading: String?, val pick: () -> Unit)

@Composable
fun rememberCommentAttachments(repoId: Long?, onUploaded: (url: String, fileName: String) -> Unit): CommentAttachmentState {
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf<String?>(null) }
    // OpenDocument 支持传多个 MIME 类型；GetContent 只接受一个，传 "image/* video/*" 会立刻返回
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = ctx.contentResolver
        val (name, size) = describe(resolver, uri)
        uploading = name
        entry.act({
            uploading = null
            ctx.toast(trf("附件上传失败：{0}", it))
        }) {
            require(repoId != null && repoId > 0) { tr("无法确定所属仓库，请稍后重试") }
            val url = CommentAttachments.upload(repoId, name, size) {
                resolver.openInputStream(uri) ?: throw IllegalStateException(tr("无法读取所选文件"))
            }
            uploading = null
            onUploaded(url, name)
            ctx.toast(tr("附件已插入"))
        }
    }
    // uploading 状态需要暴露给界面：包一层 state
    return remember(uploading) { CommentAttachmentState(uploading) { pick.launch(arrayOf("image/*", "video/*")) } }
}

/** 读取所选文件的显示名与大小；大小未知时按 0 处理，由服务端拒绝 */
private fun describe(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
    var name: String? = null
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0)
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    return fileName to size
}
