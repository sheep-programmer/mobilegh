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

private val platformExtensions = mapOf(
    ReleasePlatform.Android to setOf("apk", "aab", "apks", "xapk", "apkm"),
    ReleasePlatform.Windows to setOf("exe", "msi", "msix", "msixbundle", "appx", "appxbundle", "msu"),
    ReleasePlatform.Mac to setOf("dmg", "pkg", "mpkg", "app"),
    ReleasePlatform.Linux to setOf("deb", "rpm", "appimage", "flatpak", "flatpakref", "snap", "ipk"),
)
private val archiveExtensions = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz", "txz", "bz2", "tbz", "tbz2", "zst", "tzst", "bin", "run", "lz4", "lz")
private val compoundExtensions = listOf(".pkg.tar.zst", ".pkg.tar.xz", ".pkg.tar.gz", ".app.zip", ".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tar.lz4", ".tar.lz")
private val extensionPattern = Regex("[A-Za-z0-9]{1,16}")
private val markerSeparators = Regex("[^a-z0-9]+")
private val androidMarker = Regex("android[0-9]*")
private val windowsMarker = Regex("(?:windows|win|mingw|winnt)(?:[0-9]+|arm64|x64|x86)?")
private val macMarker = Regex("(?:macos|osx|darwin)[0-9]*")
private val linuxMarker = Regex("(?:linux|ubuntu|debian|fedora|centos|rhel|alpine|archlinux|opensuse|raspbian)[0-9]*")

/** Recognize compound suffixes for both filtering and filename display. */
internal fun releaseAssetSuffix(filename: String): String {
    val compound = compoundExtensions.firstOrNull { filename.length > it.length && filename.endsWith(it, ignoreCase = true) }
    if (compound != null) return filename.takeLast(compound.length)
    val dot = filename.lastIndexOf('.')
    return if (dot > 0 && extensionPattern.matches(filename.substring(dot + 1))) filename.substring(dot) else ""
}

internal fun ReleasePlatform.matchesAsset(filename: String): Boolean {
    if (this == ReleasePlatform.All) return true
    val name = filename.lowercase()
    val suffix = releaseAssetSuffix(name)
    val ext = suffix.removePrefix(".")
    if (suffix.startsWith(".pkg.tar.")) return this == ReleasePlatform.Linux
    if (suffix == ".app.zip") return this == ReleasePlatform.Mac
    platformExtensions.entries.firstOrNull { ext in it.value }?.let { return it.key == this }
    if (ext.isNotEmpty() && ext.substringAfterLast('.') !in archiveExtensions) return false

    val markers = name.removeSuffix(suffix).split(markerSeparators)
    return when (this) {
        ReleasePlatform.All -> true
        ReleasePlatform.Android -> markers.any(androidMarker::matches)
        ReleasePlatform.Windows -> markers.any(windowsMarker::matches)
        ReleasePlatform.Mac -> markers.any { macMarker.matches(it) || it == "mac" }
        ReleasePlatform.Linux -> markers.none(androidMarker::matches) && markers.any(linuxMarker::matches)
    }
}
