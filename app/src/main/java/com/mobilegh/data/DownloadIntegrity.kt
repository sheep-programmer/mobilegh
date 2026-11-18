package com.mobilegh.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest

internal fun trustedSha256(digest: String?): String? = digest?.trim()?.lowercase()
    ?.takeIf { it.matches(Regex("sha256:[0-9a-f]{64}")) }
    ?.substringAfter(':')

internal suspend fun verifyDownload(file: File, expected: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    file.inputStream().use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) } == expected
}

internal enum class ReleasePlatform(val label: String) {
    All("全部平台"), Android("Android"), Windows("Windows"), Mac("macOS"), Linux("Linux"),
}

internal fun ReleasePlatform.matchesAsset(filename: String): Boolean {
    val name = filename.lowercase()
    val markers = name.split(Regex("[^a-z0-9]+"))
    return when (this) {
        ReleasePlatform.All -> true
        ReleasePlatform.Android -> name.endsWith(".apk") || name.endsWith(".aab") || "android" in markers
        ReleasePlatform.Windows -> name.endsWith(".exe") || name.endsWith(".msi") || name.endsWith(".msix") ||
            name.endsWith(".msixbundle") || markers.any { it in setOf("win", "windows", "win32", "win64") }
        ReleasePlatform.Mac -> name.endsWith(".dmg") || name.endsWith(".pkg") ||
            markers.any { it in setOf("macos", "mac", "osx", "darwin") }
        ReleasePlatform.Linux -> name.endsWith(".deb") || name.endsWith(".rpm") || name.endsWith(".appimage") || "linux" in markers
    }
}
