package com.mobilegh.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
import com.mobilegh.i18n.tr
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal object OfflineTranslation {
    private const val PACKAGE = "com.mobilegh.translation"
    internal const val ASSET_NAME = "mobilegh-offline-translation.apk"
    private const val MAX_APK_BYTES = 64L * 1024 * 1024
    private val provider = Uri.parse("content://com.mobilegh.translation.engine")
    private val http by lazy { OkHttpClient.Builder().proxySelector(AppNetwork.proxySelector()).callTimeout(3, TimeUnit.MINUTES).build() }
    private lateinit var app: Context
    internal class ModelMissing(val language: String) : IllegalStateException()

    fun init(context: Context) { app = context.applicationContext }

    fun installed(): Boolean = app.packageManager.checkSignatures(app.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH

    suspend fun prepare(language: String = "zh", download: Boolean) {
        check(call("protocol") == "1")
        check(call("prepare", language, Bundle().apply { putBoolean("download", download) }) == "ready")
    }

    suspend fun identify(sample: String): String = call("identify", sample)

    suspend fun languages(): List<String> = call("languages").split(',')

    suspend fun translate(source: String, target: String, text: String): String = call("translate", text, Bundle().apply {
        putString("source", source)
        putString("target", target)
    })

    private suspend fun call(method: String, arg: String? = null, extras: Bundle? = null): String = withContext(Dispatchers.IO) {
        check(installed())
        val result = app.contentResolver.call(provider, method, arg, extras)
        result?.getString("missingModel")?.let { throw ModelMissing(it) }
        result?.getString("value") ?: throw IllegalStateException(result?.getString("error") ?: "Translation component unavailable")
    }

    /** Use the official release asset and its digest. Android installs into a separate app sandbox. */
    suspend fun installerIntent(): Intent = withContext(Dispatchers.IO) {
        val release = Api.get<Release>("https://api.github.com/repos/sheep-programmer/mobilegh/releases/latest", force = true)
        val asset = release.assets.singleOrNull { it.name == ASSET_NAME }
            ?: throw IllegalStateException(tr("当前版本尚未提供离线翻译组件"))
        val sha = trustedSha256(asset.digest) ?: error("Missing translation component SHA-256")
        check(asset.size in 1..MAX_APK_BYTES)
        val url = "${Update.REPOSITORY_URL}/releases/download/${Api.q(release.tagName)}/$ASSET_NAME"
        val dir = File(app.cacheDir, "translation-install").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(1) }
            ?.forEach { it.delete() }
        val candidate = File.createTempFile("component-", ".apk", dir)
        try {
            http.routed().newCall(Request.Builder().url(url).build()).await().use { response ->
                check(response.isSuccessful && response.request.url.isHttps)
                check(ArtifactApks.copyBounded(response.body.byteStream(), candidate, asset.size) == asset.size)
            }
            check(verifyDownload(candidate, sha))
            check(trustedArchive(candidate))
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", candidate)
            @Suppress("DEPRECATION")
            Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (failure: Exception) {
            candidate.delete()
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun trustedArchive(file: File): Boolean {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        if (archive.packageName != PACKAGE) return false
        fun signatures(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners.orEmpty()
            else info.signatures.orEmpty()
        val own = signatures(pm.getPackageInfo(app.packageName, flags)).toSet()
        return own.isNotEmpty() && signatures(archive).toSet() == own
    }
}
