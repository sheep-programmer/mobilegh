package com.mobilegh.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** A single page of public recommendations, stored in the app's disposable cache directory. */
internal class ExplorePageCache(private val file: File) {
    private companion object {
        const val FRESH_MS = 10 * 60_000L
        const val MAX_AGE_MS = 24 * 60 * 60_000L
        const val MAX_CACHE_BYTES = 512 * 1024L
    }

    @Serializable
    private data class Stored(val query: String, val at: Long, val result: SearchResult<Repo>)

    data class Page(val result: SearchResult<Repo>, val stale: Boolean)

    fun read(query: String, now: Long = System.currentTimeMillis()): Page? {
        if (file.length() !in 1..MAX_CACHE_BYTES) return null
        val stored = try {
            Api.plain.decodeFromString<Stored>(file.readText())
        } catch (_: IOException) {
            return null
        } catch (_: SerializationException) {
            return null
        }
        val age = now - stored.at
        if (stored.query != query || age !in 0..MAX_AGE_MS) return null
        return Page(stored.result, stale = age > FRESH_MS)
    }

    fun write(query: String, result: SearchResult<Repo>, now: Long = System.currentTimeMillis()) {
        val publicRepos = result.items.filterNot { it.isPrivate }.map { it.copy(permissions = null, parent = null) }
        if (publicRepos.isEmpty() || result.incompleteResults) return
        val bytes = Api.plain.encodeToString(Stored(query, now, result.copy(items = publicRepos))).encodeToByteArray()
        if (bytes.size > MAX_CACHE_BYTES) return
        try {
            val temp = File.createTempFile("explore-", ".tmp", file.parentFile)
            try {
                temp.writeBytes(bytes)
                Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: IOException) {
            // A cache write is optional. The repository search result is still available to the page.
        }
    }
}
