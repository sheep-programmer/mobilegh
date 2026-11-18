package com.mobilegh.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

internal object ArtifactApks {
    const val MAX_APK_BYTES = 1024L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 2 * MAX_APK_BYTES
    private const val MAX_ENTRIES = 20_000
    private const val MAX_CHOICES = 100

    data class Entry(val path: String, val size: Long) {
        val name get() = path.substringAfterLast('/')
    }

    private fun candidates(zip: ZipFile): List<Entry> {
        if (zip.size() > MAX_ENTRIES) throw IOException("Too many ZIP entries")
        val result = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (item in zip.entries()) {
            val path = item.name
            if (item.isDirectory || item.size !in 1..MAX_APK_BYTES || !path.endsWith(".apk", true) ||
                path.any { it == '\\' || it == ':' || Character.isISOControl(it) } ||
                path.split('/').any { it.isBlank() || it == ".." || it == "." }) continue
            if (!seen.add(path)) throw IOException("Duplicate APK entry")
            result += Entry(path, item.size)
            if (result.size > MAX_CHOICES) throw IOException("Too many APK entries")
        }
        return result.sortedBy { it.path.lowercase() }
    }

    fun list(file: File): List<Entry> = ZipFile(file).use(::candidates)

    suspend fun extract(file: File, selected: Entry, output: File) {
        ZipFile(file).use { zip ->
            if (selected !in candidates(zip)) throw IOException("APK entry changed")
            val entry = zip.getEntry(selected.path) ?: throw IOException("Missing APK entry")
            val size = zip.getInputStream(entry).use { copyBounded(it, output, selected.size) }
            if (size != selected.size) throw IOException("Incomplete APK entry")
        }
    }

    suspend fun copyBounded(input: InputStream, output: File, limit: Long): Long {
        var total = 0L
        val buffer = ByteArray(128 * 1024)
        output.outputStream().use { sink ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IOException("File exceeds size limit")
                sink.write(buffer, 0, count)
            }
        }
        return total
    }
}
