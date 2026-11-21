package com.mobilegh.translation

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Downloads the official ML Kit archive when Play services cannot complete its own download. */
internal object ChineseModelInstaller {
    private const val MODEL_PATH = "/translate/offline/v5/high/r29/en_zh.zip"
    val sourceUrls = listOf(
        "https://dl.google.com$MODEL_PATH",
        "https://redirector.gvt1.com/edgedl$MODEL_PATH",
    )
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val BUFFER_BYTES = 128 * 1024

    data class Record(val path: String, val size: Long, val sha256: String)

    fun readManifest(context: Context): List<Record> = context.assets.open("zh-model-sha256.tsv").bufferedReader().use { reader ->
        parseManifest(reader.readText())
    }

    fun parseManifest(text: String): List<Record> = text.lineSequence()
            .filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t', limit = 3)
                require(fields.size == 3) { "Invalid model manifest" }
                val digest = fields[0].lowercase()
                require(digest.length == 64 && digest.all { it in "0123456789abcdef" }) { "Invalid model digest" }
                val size = fields[1].toLongOrNull() ?: error("Invalid model size")
                require(size in 1..MAX_FILE_BYTES) { "Invalid model file size" }
                val path = safePath(fields[2])
                Record(path, size, digest)
            }.toList()

    fun archivePaths(names: List<String>): Set<String> {
        require(names.isNotEmpty()) { "Model archive is empty" }
        val safe = names.map(::safePath)
        val root = archiveRoot(safe)
        return safe.map { normalizeArchivePath(it, root) }.toSet()
    }

    fun validateArchivePaths(actual: Set<String>, expected: Set<String>) {
        require(actual == expected) { "Model archive contents do not match the trusted manifest" }
    }

    fun installArchive(archive: File, modelDir: File, manifest: List<Record>) {
        require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) { "Invalid model archive" }
        val expected = manifest.associateBy { it.path }
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            validateArchivePaths(archivePaths(names), expected.keys)
            val root = archiveRoot(names.map(::safePath))
            val entries = names.associateBy { normalizeArchivePath(safePath(it), root) }
            val staging = File(modelDir.parentFile, "${modelDir.name}.staging")
            val backup = File(modelDir.parentFile, "${modelDir.name}.backup")
            staging.deleteRecursively()
            backup.deleteRecursively()
            staging.mkdirs()
            try {
                for (record in manifest) {
                    val entry = entries[record.path]?.let(zip::getEntry) ?: error("Missing model file: ${record.path}")
                    val output = File(staging, record.path)
                    output.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        copyAndVerify(input, output, record)
                    }
                }
                modelDir.parentFile?.mkdirs()
                if (modelDir.exists() && !modelDir.renameTo(backup)) error("Cannot stage the current model")
                if (!staging.renameTo(modelDir)) error("Cannot install the model")
                backup.deleteRecursively()
            } catch (failure: Exception) {
                staging.deleteRecursively()
                if (!modelDir.exists() && backup.exists()) backup.renameTo(modelDir)
                throw failure
            }
        }
    }

    fun downloadAndInstall(proxy: Proxy?, cacheDir: File, modelDir: File, manifest: List<Record>) {
        val part = File(cacheDir, "en_zh.zip.part")
        val archive = File(cacheDir, "en_zh.zip")
        cacheDir.mkdirs()
        var last: Exception? = null
        repeat(3) {
            try {
                download(proxy, part, sourceUrls[it % sourceUrls.size])
                if (archive.exists()) archive.delete()
                if (!part.renameTo(archive)) throw IOException("Cannot prepare the model archive")
                installArchive(archive, modelDir, manifest)
                archive.delete()
                return
            } catch (failure: Exception) {
                last = failure
                archive.delete()
                if (it == 2) part.delete()
            }
        }
        throw IOException("Official model download failed", last)
    }

    private fun download(proxy: Proxy?, part: File, sourceUrl: String) {
        val offset = part.takeIf { it.isFile }?.length() ?: 0L
        val connection = (if (proxy == null) URL(sourceUrl).openConnection() else URL(sourceUrl).openConnection(proxy)) as HttpURLConnection
        connection.apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val append = offset > 0 && code == HttpURLConnection.HTTP_PARTIAL
            val start = if (append) offset else 0L
            if (!append && offset > 0) part.delete()
            val length = connection.contentLengthLong.takeIf { it >= 0 } ?: -1L
            require(start + length <= MAX_ARCHIVE_BYTES) { "Model archive exceeds size limit" }
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    copyBounded(input, output, MAX_ARCHIVE_BYTES - start)
                }
            }
            require(part.length() in 1..MAX_ARCHIVE_BYTES) { "Incomplete model archive" }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= limit) { "Model archive exceeds size limit" }
            output.write(buffer, 0, count)
        }
    }

    private fun copyAndVerify(input: InputStream, output: File, record: Record) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        output.outputStream().use { sink ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= record.size) { "Model file exceeds manifest size" }
                digest.update(buffer, 0, count)
                sink.write(buffer, 0, count)
            }
        }
        require(total == record.size) { "Model file size mismatch: ${record.path}" }
        require(digest.digest().joinToString("") { "%02x".format(it) } == record.sha256) {
            "Model file checksum mismatch: ${record.path}"
        }
    }

    private fun safePath(path: String): String {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\')) { "Invalid model archive path" }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || it.any { char -> char.isISOControl() } }) {
            "Invalid model archive path"
        }
        return parts.joinToString("/")
    }

    private fun archiveRoot(paths: List<String>): String? {
        val first = paths.map { it.substringBefore('/') }.distinct()
        return first.singleOrNull()?.takeIf { paths.all { path -> path.contains('/') } }
    }

    private fun normalizeArchivePath(path: String, root: String?): String =
        if (root != null) path.substringAfter('/') else path
}
