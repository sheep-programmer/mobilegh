package com.mobilegh.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 不依赖 Android 的图片地址、格式及限读校验。 */
internal object ImageContent {
    /** 安全解析 URL 中的 host、path、query，避免 URI 遇空格/特殊字符时抛异常导致解析直接失败 */
    fun parseUrl(url: String): Triple<String, String, String?>? {
        val idx = url.indexOf("://")
        if (idx <= 0) return null
        val after = url.substring(idx + 3)
        var pathStart = after.length
        for (c in charArrayOf('/', '?', '#')) {
            val p = after.indexOf(c)
            if (p in 0 until pathStart) pathStart = p
        }
        val host = (if (pathStart < after.length) after.substring(0, pathStart) else after)
            .substringBefore(':')
            .trim()
            .lowercase()
        val rest = if (pathStart < after.length) after.substring(pathStart) else "/"
        val path = rest.substringBefore('?').substringBefore('#')
        val query = if (rest.contains('?')) rest.substringAfter('?').substringBefore('#') else null
        return Triple(host, path, query)
    }

    fun normalize(url: String): String {
        val (host, path, query) = parseUrl(url) ?: return url
        if (host != "github.com") return url
        val segs = path.split('/').filter { it.isNotEmpty() }
        return if (segs.size >= 5 && (segs[2] == "blob" || segs[2] == "raw")) {
            val q = if (!query.isNullOrEmpty()) "?$query" else ""
            "https://raw.githubusercontent.com/${segs[0]}/${segs[1]}/${segs.drop(3).joinToString("/")}$q"
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

    data class DataUri(
        val mime: String?,
        val isSvg: Boolean,
        val bytes: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DataUri) return false
            return mime == other.mime && isSvg == other.isSvg && bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = (mime?.hashCode() ?: 0) * 31 + bytes.contentHashCode()
    }

    /**
     * 解析 data: 协议的内联资源（如 Shields.io 徽章内的 SVG/PNG logo）。
     * 支持 base64 与 URL 编码两种形式，并判断是否为矢量 SVG。
     */
    fun parseDataUri(uri: String): DataUri? {
        if (!uri.startsWith("data:", ignoreCase = true)) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val header = uri.substring(5, comma).trim().lowercase()
        val dataPart = uri.substring(comma + 1)
        val isBase64 = header.contains(";base64") || header.endsWith("base64")
        val rawBytes = if (isBase64) {
            val clean = dataPart.filterNot { it.isWhitespace() }
            runCatching { java.util.Base64.getDecoder().decode(clean) }
                .recoverCatching { java.util.Base64.getMimeDecoder().decode(clean) }
                .getOrNull()
        } else {
            runCatching {
                java.net.URLDecoder.decode(dataPart, "UTF-8").toByteArray(Charsets.UTF_8)
            }.recoverCatching {
                dataPart.toByteArray(Charsets.UTF_8)
            }.getOrNull()
        } ?: return null

        if (rawBytes.isEmpty()) return null
        val declaredMime = header.substringBefore(';').trim().ifEmpty { null }
        val isSvg = declaredMime == "image/svg+xml" ||
            header.contains("image/svg") ||
            mimeOf("", rawBytes) == "image/svg+xml" ||
            rawBytes.toString(Charsets.UTF_8).trimStart().startsWith("<svg", ignoreCase = true)

        val resolvedMime = declaredMime ?: (if (isSvg) "image/svg+xml" else mimeOf("", rawBytes))
        return DataUri(mime = resolvedMime, isSvg = isSvg, bytes = rawBytes)
    }
}
