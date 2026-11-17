package com.mobilegh.nav

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.mobilegh.data.CommentAttachments
import com.mobilegh.data.IssueShare
import com.mobilegh.data.PendingIssueShare
import com.mobilegh.data.SharedAttachment
import java.io.File
import java.util.UUID

internal data class ShareAttachmentMetadata(val mime: String?, val size: Long?)
private data class SharedSource(val metadata: ShareAttachmentMetadata, val name: String)

object ShareIntents {
    const val MAX_ATTACHMENTS = 10
    const val MAX_TOTAL_SIZE = 50 * 1024 * 1024L
    private val imageTypes = setOf("image/png", "image/jpeg", "image/gif")

    fun link(text: String?): Links.InputLink? = text?.let(Links::sharedLink)

    fun parse(type: String?, text: String?, attachments: List<SharedAttachment>): IssueShare? {
        val images = attachments.filter { it.mime in imageTypes }
        if (images.size != attachments.size) return null
        val supported = type == "text/plain" || type == "image/*" || type in imageTypes
        val body = text.orEmpty().trim()
        if (!supported || body.isEmpty() && images.isEmpty()) return null
        return IssueShare(body, images)
    }

    internal fun validateMetadata(items: List<ShareAttachmentMetadata>) {
        require(items.size <= MAX_ATTACHMENTS) { "每次最多分享 10 个附件" }
        items.forEach { item ->
            require(item.mime in imageTypes) { "只支持 PNG、JPEG 或 GIF 图片" }
            item.size?.let {
                require(it >= 0) { "无法读取附件大小" }
                require(it <= CommentAttachments.MAX_SIZE) { "附件不能超过 10 MB" }
            }
        }
        require(items.sumOf { it.size ?: 0L } <= MAX_TOTAL_SIZE) { "附件总大小不能超过 50 MB" }
    }

    /** 立即复制持有临时授权的 URI；导航状态中只保留缓存路径，不保存图片字节。 */
    fun import(context: Context, intent: Intent): String? {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.clipData?.let { clip ->
                (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).text?.toString() }
            }
        if (intent.type == "text/plain" && link(text) != null) return null
        require(intent.type == "text/plain" || intent.type == "image/*" || intent.type in imageTypes) { "分享内容无法用于创建 Issue" }
        val uris = buildList {
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(::add)
            val streams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            streams?.let(::addAll)
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add) }
        }.distinct()
        val resolver = context.contentResolver
        // 先读取并验证全部元数据，避免复制到一半才发现后续附件类型或大小不合法。
        val sources = uris.map { uri ->
            var name: String? = null
            var size: Long? = null
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0 && !it.isNull(nameColumn)) name = it.getString(nameColumn)
                    if (sizeColumn >= 0 && !it.isNull(sizeColumn)) size = it.getLong(sizeColumn)
                }
            }
            val mime = resolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: intent.type
            SharedSource(ShareAttachmentMetadata(mime, size), name.orEmpty())
        }
        validateMetadata(sources.map { it.metadata })
        val dir = File(context.cacheDir, "issue-share")
        check((dir.exists() || dir.mkdirs()) && dir.isDirectory) { "无法创建分享缓存目录" }
        val copied = mutableListOf<File>()
        try {
            var streamedTotal = 0L
            val attachments = sources.mapIndexed { index, source ->
                val attachment = copy(resolver, uris[index], source, dir) { copied += it }
                streamedTotal += File(attachment.path).length()
                require(streamedTotal <= MAX_TOTAL_SIZE) { "附件总大小不能超过 50 MB" }
                attachment
            }
            val share = parse(intent.type, text, attachments)
            if (share == null) {
                copied.forEach(File::delete)
                return null
            }
            return PendingIssueShare.save(context, share)
        } catch (t: Throwable) {
            copied.forEach(File::delete)
            throw t
        }
    }

    private fun copy(resolver: android.content.ContentResolver, uri: Uri, source: SharedSource, dir: File, onCreate: (File) -> Unit): SharedAttachment {
        val mime = source.metadata.mime!!
        val name = source.name.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "shared-image.${mime.substringAfter('/')}"
        val file = File(dir, UUID.randomUUID().toString() + "-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        onCreate(file)
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= CommentAttachments.MAX_SIZE) { "附件不能超过 10 MB" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法读取分享的图片")
        require(file.length() > 0) { "分享的图片为空" }
        return SharedAttachment(file.absolutePath, mime, name)
    }
}
