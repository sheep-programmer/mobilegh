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
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.act

/**
 * 评论附件的选择与上传：从系统选择器选一张图片或一段视频，传到 GitHub 的附件存储，
 * 把返回的地址交给调用方插入正文。上传期间 [uploading] 会变非空，界面可以显示进度。
 *
 * 有 GitHub Token 时直接上传。没有 Token 时再使用应用内网页登录，登录成功后继续上传。
 */
data class UploadedAttachment(
    val url: String,
    val fileName: String,
    val isVideo: Boolean,
    val localUri: String? = null,
)

class CommentAttachmentState(
    val uploading: String?,
    val items: List<UploadedAttachment>,
    val pick: () -> Unit,
    val remove: (UploadedAttachment) -> Unit,
)

@Composable
fun rememberCommentAttachments(
    repoId: Long?,
    onUploadingChange: (Boolean) -> Unit = {},
    onUploaded: (url: String, fileName: String) -> Unit,
): CommentAttachmentState {
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    var uploading by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<UploadedAttachment>>(emptyList()) }

    fun start(uri: Uri) {
        val resolver = ctx.contentResolver
        val (name, size) = describe(resolver, uri)
        if (!CommentAttachments.isAllowed(name)) {
            ctx.toast(tr("只支持图片和视频附件"))
            return
        }
        uploading = name
        onUploadingChange(true)
        entry.act({
            uploading = null
            onUploadingChange(false)
            ctx.toast(trf("附件上传失败：{0}", it))
        }) {
            require(repoId != null && repoId > 0) { tr("无法确定所属仓库，请稍后重试") }
            val url = CommentAttachments.upload(repoId, name, size) {
                resolver.openInputStream(uri) ?: throw IllegalStateException(tr("无法读取所选文件"))
            }
            val isVideo = CommentAttachments.VIDEO_EXT.contains(name.substringAfterLast('.', "").lowercase())
            items = (items.filterNot { it.url == url } + UploadedAttachment(url, name, isVideo, uri.toString()))
            uploading = null
            onUploadingChange(false)
            onUploaded(url, name)
            ctx.toast(tr("附件已插入"))
        }
    }

    // 系统选择器不按 MIME 过滤（部分文件管理器会把视频隐藏），选完后再按扩展名校验
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) start(uri)
    }
    // uploading 状态需要暴露给界面：包一层 state
    // Token 会话可以直接打开选择器，未登录时才先进入网页会话登录。
    val openPicker: () -> Unit = {
        pick.launch(arrayOf("image/*", "video/*"))
    }
    val removeItem: (UploadedAttachment) -> Unit = { target ->
        items = items.filterNot { it.url == target.url }
    }
    return remember(uploading, items) { CommentAttachmentState(uploading, items, openPicker, removeItem) }
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

private val MD_IMAGE_REGEX = Regex("""!\[([^\]]*)]\((https://[^)\s]+)\)""")
private val ASSET_URL_REGEX = Regex("""https://github\.com/user-attachments/assets/[A-Za-z0-9-]+""")

/** 从正文与已上传记录汇总附件列表，便于在编辑器底部展示与弹窗预览 */
fun parseEditorAttachments(text: String, uploaded: List<UploadedAttachment>): List<UploadedAttachment> {
    val byUrl = uploaded.associateBy { it.url }.toMutableMap()
    val ordered = mutableListOf<UploadedAttachment>()
    val seen = HashSet<String>()
    for (match in MD_IMAGE_REGEX.findAll(text)) {
        val alt = match.groupValues[1].ifBlank { tr("图片附件") }
        val url = match.groupValues[2]
        if (seen.add(url)) ordered += byUrl[url] ?: UploadedAttachment(url, alt, isVideo = false)
    }
    for (match in ASSET_URL_REGEX.findAll(text)) {
        val url = match.value
        if (seen.add(url)) ordered += byUrl[url] ?: UploadedAttachment(url, tr("视频/媒体附件"), isVideo = true)
    }
    for (item in uploaded) {
        if (seen.add(item.url)) ordered += item
    }
    return ordered
}

/** 从编辑器文本中移除指定附件对应的 Markdown 图片语法或裸链接 */
fun removeAttachmentSnippet(value: TextFieldValue, attachment: UploadedAttachment): TextFieldValue {
    val escaped = Regex.escape(attachment.url)
    val updated = value.text
        .replace(Regex("""\n?!\[[^\]]*]\($escaped\)\n?"""), "\n")
        .replace(Regex("""\n?$escaped\n?"""), "\n")
        .trim('\n')
    val cursor = value.selection.min.coerceIn(0, updated.length)
    return TextFieldValue(updated, TextRange(cursor))
}
