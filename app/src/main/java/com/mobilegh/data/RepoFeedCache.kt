package com.mobilegh.data

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

internal class RepoFeedCache(private val directory: File) {
    companion object {
        const val MAX_AGE_MS = 30 * 60_000L
        private const val MAX_BYTES = 2 * 1024 * 1024L
    }

    @Serializable
    private data class Stored(val items: List<Repo>, val pages: Int, val ended: Boolean, val savedAt: Long)

    data class Snapshot(val items: List<Repo>, val pages: Int, val ended: Boolean, val stale: Boolean)

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
        if (age < 0 || age > MAX_AGE_MS * 4) return null
        return Snapshot(stored.items, stored.pages, stored.ended, age > MAX_AGE_MS)
    }

    fun write(key: String, items: List<Repo>, pages: Int, ended: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (items.isEmpty()) return
        runCatching {
            directory.mkdirs()
            val target = file(key)
            val temp = File.createTempFile("repo-feed-", ".tmp", directory)
            try {
                temp.writeText(Api.plain.encodeToString(Stored(items.take(200), pages, ended, nowMs)))
                check(temp.renameTo(target))
            } finally {
                temp.delete()
            }
        }
    }
}
