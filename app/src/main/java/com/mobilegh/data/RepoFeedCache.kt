package com.mobilegh.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal class RepoFeedCache(private val directory: File) {
    companion object {
        const val MAX_AGE_MS = 30 * 60_000L
        private const val MAX_BYTES = 2 * 1024 * 1024L
    }

    @Serializable
    private data class Stored(
        val items: List<Repo>,
        val pages: Int,
        val ended: Boolean,
        val savedAt: Long,
        val cursors: Map<Int, String?> = emptyMap(),
    )

    data class Snapshot(
        val items: List<Repo>,
        val pages: Int,
        val ended: Boolean,
        val stale: Boolean,
        val cursors: Map<Int, String?> = emptyMap(),
    )

    private fun file(key: String) = File(directory, "repo-feed-${key.hashCode().toUInt().toString(16)}.json")

    fun read(key: String, nowMs: Long = System.currentTimeMillis()): Snapshot? {
        val source = file(key)
        if (!source.isFile || source.length() > MAX_BYTES) return null
        val stored = try {
            Api.plain.decodeFromString<Stored>(source.readText())
        } catch (_: IOException) {
            return null
        } catch (_: SerializationException) {
            return null
        }
        val age = nowMs - stored.savedAt
        if (age < 0) return null
        return Snapshot(stored.items, stored.pages, stored.ended, age > MAX_AGE_MS, stored.cursors)
    }

    fun write(
        key: String,
        items: List<Repo>,
        pages: Int,
        ended: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        cursors: Map<Int, String?> = emptyMap(),
    ) {
        if (items.isEmpty()) return
        // 不截断条目：页数必须与条目数对应，否则恢复后续页会重复或跳页。超过上限则整份跳过。
        val bytes = Api.plain.encodeToString(Stored(items, pages, ended, nowMs, cursors)).encodeToByteArray()
        if (bytes.size > MAX_BYTES) return
        runCatching {
            directory.mkdirs()
            val target = file(key)
            val temp = File.createTempFile("repo-feed-", ".tmp", directory)
            try {
                temp.writeBytes(bytes)
                try {
                    Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: Throwable) {
                    if (target.exists()) target.delete()
                    check(temp.renameTo(target))
                }
            } finally {
                temp.delete()
            }
        }
    }
}
