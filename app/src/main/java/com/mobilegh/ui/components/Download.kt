package com.mobilegh.ui.components

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.mobilegh.data.Net
import com.mobilegh.data.Session

private val GITHUB_HOSTS = setOf("github.com", "api.github.com", "raw.githubusercontent.com", "codeload.github.com", "objects.githubusercontent.com")

/**
 * 直接把文件下载到系统「下载」目录（走系统 DownloadManager，带进度通知，完成后可点开）。
 *
 * - 私有仓库内容：直连 github.com 并带上 Token（不经加速节点，避免 Token 外泄）。
 * - 公开内容：走加速节点，不带 Token。
 * - Android 9 及以下若没有存储权限，退回到浏览器下载。
 */
fun Context.downloadFile(rawUrl: String, filename: String? = null, isPrivate: Boolean = false) {
    val url = if (isPrivate) rawUrl else Net.download(rawUrl)
    val name = (filename?.takeIf { it.isNotBlank() } ?: Uri.parse(rawUrl).lastPathSegment ?: "download")
        .substringBefore('?').ifBlank { "download" }

    // Android 9-（API 28-）写公共目录需要存储权限，没有就退回浏览器
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        openBrowser(url)
        toast("已在浏览器中打开下载")
        return
    }

    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    if (dm == null) {
        openBrowser(url)
        return
    }
    runCatching {
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(name)
            setDescription("MobileGH 下载")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())?.let { setMimeType(it) }
            // 仅在直连 GitHub 时附带 Token；加速/第三方地址绝不带
            val token = Session.token
            if (token != null && Uri.parse(url).host in GITHUB_HOSTS) {
                addRequestHeader("Authorization", "Bearer $token")
            }
            addRequestHeader("User-Agent", "MobileGH/1.0 (Android)")
        }
        dm.enqueue(req)
        toast("开始下载：$name")
    }.onFailure {
        openBrowser(url)
        toast("已在浏览器中打开下载")
    }
}
