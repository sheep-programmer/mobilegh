package com.mobilegh.data

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SharedAttachment(val path: String, val mime: String, val name: String)

@Serializable
data class IssueShare(val text: String, val attachments: List<SharedAttachment> = emptyList())

/** 系统分享在进入仓库选择前暂存；文件元数据落盘，登录和进程回收不会把它丢掉。 */
object PendingIssueShare {
    private val json = Json { ignoreUnknownKeys = true }
    fun save(ctx: Context, share: IssueShare): String = Store(File(ctx.cacheDir, "issue-share")).save(share)
    fun peek(ctx: Context, id: String): IssueShare? = Store(File(ctx.cacheDir, "issue-share")).peek(id)
    /** 上传完成或不再需要时清理元数据与缓存附件。 */
    fun finish(ctx: Context, id: String): IssueShare? = Store(File(ctx.cacheDir, "issue-share")).finish(id)

    class Store(private val root: File, private val newId: () -> String = { UUID.randomUUID().toString() }) {
        private val transactionLock = locks.computeIfAbsent(root.canonicalPath) { Any() }

        fun save(share: IssueShare): String = synchronized(transactionLock) {
            root.mkdirs()
            val id = newId()
            val current = File(root, "$id.json")
            // 新元数据完整落盘前不碰旧草稿；复制或持久化失败时调用方仍能恢复旧分享。
            current.writeText(json.encodeToString(IssueShare.serializer(), share))
            root.listFiles { f -> f.extension == "json" && f != current }?.forEach { old ->
                runCatching { json.decodeFromString<IssueShare>(old.readText()) }.getOrNull()?.let { runCatching { removeFiles(it) } }
                old.delete()
            }
            id
        }

        fun peek(id: String): IssueShare? = synchronized(transactionLock) {
            if (!id.matches(Regex("[0-9a-fA-F-]{36}"))) return null
            val file = File(root, "$id.json")
            runCatching { json.decodeFromString<IssueShare>(file.readText()) }.getOrNull()
        }

        fun finish(id: String): IssueShare? = synchronized(transactionLock) {
            val share = peek(id) ?: return null
            File(root, "$id.json").delete()
            removeFiles(share)
            share
        }

        private fun removeFiles(share: IssueShare) {
            val base = root.canonicalFile
            share.attachments.forEach {
                val file = File(it.path).canonicalFile
                if (file.parentFile == base) file.delete()
            }
        }

        companion object {
            private val locks = ConcurrentHashMap<String, Any>()
        }
    }
}
