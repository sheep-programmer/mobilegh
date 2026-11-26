package com.mobilegh.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Star 列表目录的磁盘缓存：冷启动后首页与 Star 列表可以立即显示上次的结果，不必从零开始请求。
 * 每个（当前账号，查看的用户）一个文件；文件内同时保存这两个登录名，读取时核对，防止串号。
 */
internal class StarListCatalogCache(private val directory: File) {
    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024L
    }

    @Serializable
    private data class Stored(val active: String, val login: String, val lists: List<GitHubStarList>)

    private fun file(active: String, login: String): File {
        val key = "${active.lowercase(Locale.ROOT)}\u0000${login.lowercase(Locale.ROOT)}"
        return File(directory, "star-lists-${key.hashCode().toUInt().toString(16)}.json")
    }

    fun read(active: String, login: String): List<GitHubStarList>? {
        val source = file(active, login)
        if (!source.isFile || source.length() !in 1..MAX_BYTES) return null
        val stored = try {
            Api.plain.decodeFromString<Stored>(source.readText())
        } catch (_: IOException) {
            return null
        } catch (_: SerializationException) {
            return null
        }
        if (!stored.active.equals(active, ignoreCase = true) || !stored.login.equals(login, ignoreCase = true)) return null
        return stored.lists
    }

    fun write(active: String, login: String, lists: List<GitHubStarList>) {
        val bytes = Api.plain.encodeToString(Stored(active, login, lists)).encodeToByteArray()
        if (bytes.size > MAX_BYTES) return
        try {
            directory.mkdirs()
            val temp = File.createTempFile("star-lists-", ".tmp", directory)
            try {
                temp.writeBytes(bytes)
                Files.move(temp.toPath(), file(active, login).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        } catch (_: IOException) {
            // 缓存写入是可选的：失败只是下次冷启动多一次请求。
        }
    }
}
