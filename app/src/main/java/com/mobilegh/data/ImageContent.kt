package com.mobilegh.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

/** 不依赖 Android 的图片地址、格式及限读校验。 */
internal object ImageContent {
    fun normalize(url: String): String {
        val u = runCatching { URI(url) }.getOrNull() ?: return url
        if (u.host != "github.com") return url
        val segs = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        return if (segs.size >= 5 && (segs[2] == "blob" || segs[2] == "raw")) {
            "https://raw.githubusercontent.com/${segs[0]}/${segs[1]}/${segs.drop(3).joinToString("/")}" +
                (u.rawQuery?.let { "?$it" } ?: "")
        } else url
    }

    fun mimeOf(url: String, bytes: ByteArray? = null): String? {
        val b = bytes
        if (b != null && b.size >= 12) {
            if (b.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return "image/png"
            if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return "image/jpeg"
            if (String(b, 0, 6, Charsets.ISO_8859_1) in setOf("GIF87a", "GIF89a")) return "image/gif"
            if (String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP") return "image/webp"
            if (b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()) return "image/bmp"
            if (b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() && b[3] == 0.toByte()) return "image/x-icon"
            if (String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
                val brand = String(b, 8, minOf(b.size - 8, 48), Charsets.ISO_8859_1)
                if (brand.contains("avif") || brand.contains("avis")) return "image/avif"
                if (brand.contains("isom") || brand.contains("mp4")) return "video/mp4"
            }
        }
        if (b != null) {
            val head = String(b, 0, minOf(b.size, 4096), Charsets.UTF_8)
            return if (Regex("(?is)^(?:\\uFEFF|\\s|<\\?xml.*?\\?>|<!--.*?-->)*<svg(?:\\s|>)").containsMatchIn(head)) "image/svg+xml" else null
        }
        return when (url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "mp4" -> "video/mp4"
            else -> null
        }
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (output.size() + n > limit) return null
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
}
