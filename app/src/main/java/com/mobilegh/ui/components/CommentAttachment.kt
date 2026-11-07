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
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilegh.data.CommentAttachments
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.act

/**
 * 评论附件的选择与上传：从系统选择器选一张图片或一段视频，传到 GitHub 的附件存储，
 * 把返回的地址交给调用方插入正文。上传期间 [uploading] 会变非空，界面可以显示进度。
 *
 * 需要应用内的网页登录：没有登录时先弹出登录页，登录成功后自动继续上传刚才选的文件。
 */
class CommentAttachmentState(val uploading: String?, val pick: () -> Unit)

@Composable
fun rememberCommentAttachments(repoId: Long?, onUploaded: (url: String, fileName: String) -> Unit): CommentAttachmentState {
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    var uploading by remember { mutableStateOf<String?>(null) }

    fun start(uri: Uri) {
        if (!GitHubWebLogin.hasSession()) {
            GitHubWebLogin.request { start(uri) }
            return
        }
        val resolver = ctx.contentResolver
        val (name, size) = describe(resolver, uri)
        if (!CommentAttachments.isAllowed(name)) {
            ctx.toast(tr("只支持图片和视频附件"))
            return
        }
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

    // 系统选择器不按 MIME 过滤（部分文件管理器会把视频隐藏），选完后再按扩展名校验
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri)
    }
    // uploading 状态需要暴露给界面：包一层 state
    // 先确认网页登录，再打开文件选择器，避免选完文件才发现要登录
    val openPicker: () -> Unit = {
        if (GitHubWebLogin.hasSession()) pick.launch(arrayOf("*/*"))
        else GitHubWebLogin.request { pick.launch(arrayOf("*/*")) }
    }
    return remember(uploading) { CommentAttachmentState(uploading, openPicker) }
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

/** 附件插入正文的 Markdown：图片用图语法，视频用裸链接（GitHub 会渲染成视频播放器） */
fun attachmentMarkdown(url: String, fileName: String): String =
    if (CommentAttachments.VIDEO_EXT.contains(fileName.substringAfterLast('.', "").lowercase())) url
    else "![${fileName}](${url})"

/** 在光标处插入一段内容：前面不是换行时先补一个换行，后面补一个换行；光标停在插入内容之后 */
fun insertAtCursor(value: TextFieldValue, snippet: String): TextFieldValue {
    val at = value.selection.min.coerceIn(0, value.text.length)
    val before = value.text.substring(0, at)
    val prefix = if (at == 0 || before.endsWith("\n")) "" else "\n"
    val inserted = prefix + snippet + "\n"
    return TextFieldValue(before + inserted + value.text.substring(at), TextRange(at + inserted.length))
}
