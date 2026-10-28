package com.mobilegh.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 从系统文件选择器（SAF）读要上传的内容。
 *
 * 文件可以任意多选；文件夹用「选择目录」拿到并递归展开，[Group.Entry.relative] 始终带上顶层文件夹名，
 * 因此在仓库里的层级由「当前浏览目录 + 这份相对路径」拼出来。
 * 这里只回答「本机有什么」，仓库路径拼接与提交都由 [GitUpload] 和上传页处理。
 */
object UploadSource {
    /** 一个可上传的本机文件：[relative] 是相对于选择点的多级路径（单个文件时就是文件名） */
    class Entry(val uri: String, val relative: String, val size: Long)

    /** 一次选择的结果；[note] 说明被跳过或截断的部分，界面上不能吞掉 */
    class Group(val entries: List<Entry>, val note: String? = null)

    private const val MAX_DEPTH = GitUpload.MAX_DEPTH
    internal const val MAX_FOLDER_FILES = GitUpload.MAX_FOLDER_FILES
    private const val MAX_FILES = MAX_FOLDER_FILES

    internal fun shouldRecurseDirectory(name: String): Boolean = name != ".git"

    private val CHILD_COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    /** 读取用户多选的内容；个别设备的选择器也会给出目录，目录按文件夹递归展开 */
    suspend fun readFiles(ctx: Context, uris: List<Uri>): Group = withContext(Dispatchers.IO) {
        val out = ArrayList<Entry>()
        val notes = ArrayList<String>()
        var unreadable = 0
        var truncated = false
        uris.forEach { uri ->
            if (isDirectory(ctx, uri)) {
                val tree = treeOf(uri)
                val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                if (tree != null && docId != null) {
                    val name = safeName(displayName(ctx, uri) ?: docId.tail())
                    if (name == null) unreadable++ else walk(ctx, tree, docId, name, 0, out, notes) { truncated = true }
                } else unreadable++
            } else {
                val info = meta(ctx, uri)
                if (info == null) unreadable++ else out.add(Entry(uri.toString(), info.first, info.second))
            }
        }
        if (unreadable > 0) notes.add(trf("{0} 项读不到，已跳过", unreadable))
        Group(out.limit(notes, truncated), notes.joinToString(" · ").ifBlank { null })
    }

    /** 递归读取用户选中的文件夹；文件夹名成为顶层目录 */
    suspend fun readFolder(ctx: Context, root: Uri): Group = withContext(Dispatchers.IO) {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull()
        if (treeId.isNullOrBlank()) return@withContext Group(emptyList(), tr("无法读取该位置"))
        val name = safeName(displayName(ctx, DocumentsContract.buildDocumentUriUsingTree(root, treeId)) ?: treeId.tail())
            ?: return@withContext Group(emptyList(), tr("文件夹名称无法使用"))
        val out = ArrayList<Entry>()
        val notes = ArrayList<String>()
        var truncated = false
        walk(ctx, root, treeId, name, 0, out, notes) { truncated = true }
        Group(out.limit(notes, truncated), notes.joinToString(" · ").ifBlank { null })
    }

    internal fun List<Entry>.limit(notes: MutableList<String>, truncated: Boolean = false): List<Entry> =
        if (size > MAX_FILES || truncated) { notes.add(trf("文件超过 {0} 个，只保留前 {1} 个", MAX_FILES, MAX_FILES)); take(MAX_FILES) } else this

    /** "primary:Documents/sub" → "sub"；没有分隔符时原样返回 */
    private fun String.tail(): String = trimEnd('/').substringAfterLast('/').substringAfterLast(':').trim()

    private fun safeName(raw: String?): String? = GitUpload.sanitizeName(raw)

    /** 树地址（.../tree/<treeId>[/document/<docId>]）；不是 SAF 树地址时返回 null */
    private fun treeOf(uri: Uri): Uri? = runCatching {
        if (!DocumentsContract.isTreeUri(uri)) null else uri
    }.getOrNull()

    /** 目录判定：SAF 的目录 MIME 是 vnd.android.document/directory */
    private fun isDirectory(ctx: Context, uri: Uri): Boolean =
        column(ctx, uri, DocumentsContract.Document.COLUMN_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR

    /**
     * 展开一个目录：只带普通文件，仅跳过 Git 内部目录 `.git`；`.github` 等隐藏目录必须保留。
     * [prefix] 是已确定的相对路径，递归时逐级追加；读不下去的子目录记进 [notes]，不整体失败。
     */
    private fun walk(
        ctx: Context,
        tree: Uri,
        docId: String,
        prefix: String,
        depth: Int,
        out: MutableList<Entry>,
        notes: MutableList<String>,
        onTruncated: () -> Unit,
    ) {
        if (docId.isBlank()) return
        if (depth > MAX_DEPTH) { notes.add(trf("层级超过 {0} 层，未继续深入", MAX_DEPTH)); return }
        val children = runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId) }.getOrNull() ?: return
        val rows = runCatching {
            ctx.contentResolver.query(children, CHILD_COLUMNS, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val raw = c.getString(1)?.takeIf { it.isNotBlank() } ?: id.tail()
                        val mime = c.getString(2)
                        val size = runCatching { if (c.isNull(3)) 0L else c.getLong(3) }.getOrDefault(0L)
                        add(Triple(id, raw, mime to size))
                    }
                }
            }
        }.getOrNull()
        if (rows == null) { notes.add(trf("读不到「{0}」的内容，已跳过", prefix)); return }
        for ((id, raw, info) in rows) {
            if (out.size >= MAX_FILES) { onTruncated(); return }
            val (mime, size) = info
            val name = safeName(raw)
            if (name == null) { notes.add(trf("跳过异常名称「{0}」", raw)); continue }
            val rel = "$prefix/$name"
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (!shouldRecurseDirectory(name)) continue
                walk(ctx, tree, id, rel, depth + 1, out, notes, onTruncated)
            } else {
                out.add(Entry(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), rel, size.coerceAtLeast(0)))
            }
        }
    }

    /** 文件的显示名与大小；读不到返回 null（选择失效、文件被移动等） */
    private fun meta(ctx: Context, uri: Uri): Pair<String, Long>? {
        val row = runCatching {
            ctx.contentResolver.query(uri, arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else (if (c.isNull(0)) null else c.getString(0)) to
                    if (c.isNull(1)) 0L else runCatching { c.getLong(1) }.getOrDefault(0L)
            }
        }.getOrNull()
        val name = safeName(row?.first ?: uri.lastPathSegment?.substringAfterLast('/')) ?: return null
        return name to (row?.second ?: 0L).coerceAtLeast(0)
    }

    private fun column(ctx: Context, uri: Uri, key: String): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(key), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    private fun displayName(ctx: Context, uri: Uri): String? =
        column(ctx, uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME)

    /** 打开本机文件；授权失效或文件被删时返回 null，由调用方给出提示 */
    fun open(ctx: Context, uri: String): InputStream? =
        runCatching { ctx.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull()

    /** 尽量记住读取授权：多数选择器只给一次性授权，不记住的话离开本页后就读不到了 */
    fun persistRead(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
