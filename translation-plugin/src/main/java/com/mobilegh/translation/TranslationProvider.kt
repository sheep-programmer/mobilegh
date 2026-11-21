package com.mobilegh.translation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit
import java.io.File
import java.security.MessageDigest
import java.net.InetSocketAddress
import java.net.Proxy

/** Only the separately installed, same-signed MobileGH app can call this provider. */
class TranslationProvider : ContentProvider() {
    private val models get() = RemoteModelManager.getInstance()
    private class MissingModel(val language: String) : IllegalStateException()

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(callingPackage == "com.mobilegh")
        check(requireNotNull(context).packageManager.checkSignatures("com.mobilegh", "com.mobilegh.translation") ==
            android.content.pm.PackageManager.SIGNATURE_MATCH)
        val input = extras ?: Bundle.EMPTY
        return try {
            val value = when (method) {
                "protocol" -> "1"
                "prepare" -> {
                    val language = requireNotNull(arg).takeIf { it != "en" && TranslateLanguage.fromLanguageTag(it) != null }
                        ?: error("Unsupported model")
                    prepare(language, input)
                    "ready"
                }
                "identify" -> LanguageIdentification.getClient().use { identifier ->
                    Tasks.await(identifier.identifyLanguage(requireNotNull(arg).take(1200)), 15, TimeUnit.SECONDS)
                }
                "translate" -> translate(
                    requireNotNull(input.getString("source")), requireNotNull(input.getString("target")),
                    requireNotNull(arg).also { require(it.length <= 32_768) },
                )
                "languages" -> TranslateLanguage.getAllLanguages().filter { it != "en" }.joinToString(",")
                // 已经下载并可用的语言模型（不含内置的英语）。中文模型可能由兜底下载写入，不一定登记在 ML Kit 里，所以单独校验文件。
                "downloaded" -> downloadedLanguages().joinToString(",")
                else -> error("Unsupported translation operation")
            }
            Bundle().apply { putString("value", value) }
        } catch (missing: MissingModel) {
            Bundle().apply { putString("missingModel", missing.language) }
        } catch (failure: Exception) {
            Bundle().apply {
                putString("error", failure.javaClass.simpleName)
                putString("errorDetail", failure.message?.take(240))
            }
        }
    }

    private fun prepare(language: String, input: Bundle) {
        val download = input.getBoolean("download")
        val model = TranslateRemoteModel.Builder(language).build()
        if (download) {
            if (language == "zh") {
                var ready = runCatching {
                    Tasks.await(models.isModelDownloaded(model), 15, TimeUnit.SECONDS) && verifiedChineseFiles()
                }.getOrDefault(false)
                if (!ready) {
                    runCatching {
                        if (Tasks.await(models.isModelDownloaded(model), 15, TimeUnit.SECONDS)) {
                            Tasks.await(models.deleteDownloadedModel(model), 30, TimeUnit.SECONDS)
                        }
                        Tasks.await(models.download(model, DownloadConditions.Builder().build()), 4, TimeUnit.MINUTES)
                    }
                    ready = runCatching {
                        Tasks.await(models.isModelDownloaded(model), 15, TimeUnit.SECONDS) && verifiedChineseFiles()
                    }.getOrDefault(false)
                }
                if (!ready) {
                    AppFallback.download(input, requireNotNull(context), ::verifiedChineseFiles)
                }
            } else if (language != "zh") {
                Tasks.await(models.download(model, DownloadConditions.Builder().build()), 4, TimeUnit.MINUTES)
            }
        }
        check(if (language == "zh") isChineseModelReady(model) else Tasks.await(models.isModelDownloaded(model), 15, TimeUnit.SECONDS))
        val english = "Good morning. This is a translation test."
        val localized = translate("en", language, english)
        check(localized.isNotBlank() && localized != english)
        val back = translate(language, "en", localized)
        check(back.isNotBlank() && back != localized)
    }

    private object AppFallback {
        fun download(input: Bundle, context: android.content.Context, verified: () -> Boolean) {
            val proxy = when (input.getString("proxyMode")) {
                "Http" -> Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(input.getString("proxyHost").orEmpty(), input.getInt("proxyPort")))
                "Socks" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(input.getString("proxyHost").orEmpty(), input.getInt("proxyPort")))
                else -> null
            }
            val manifest = ChineseModelInstaller.readManifest(context)
            ChineseModelInstaller.downloadAndInstall(
                proxy,
                File(context.cacheDir, "translation-model"),
                File(context.noBackupFilesDir, "com.google.mlkit.translate.models/en_zh"),
                manifest,
            )
            check(verified()) { "Installed model did not pass verification" }
        }
    }

    private fun verifiedChineseFiles(): Boolean {
        val app = requireNotNull(context)
        val base = File(app.noBackupFilesDir, "com.google.mlkit.translate.models/en_zh")
        val buffer = ByteArray(128 * 1024)
        return app.assets.open("zh-model-sha256.tsv").bufferedReader().useLines { lines ->
            lines.all { record ->
                val (expected, size, path) = record.split('\t', limit = 3)
                val file = File(base, path)
                if (!file.isFile || file.length() != size.toLong()) return@all false
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    var count = input.read(buffer)
                    while (count != -1) {
                        digest.update(buffer, 0, count)
                        count = input.read(buffer)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) } == expected
            }
        }
    }

    private fun downloadedLanguages(): List<String> {
        val registered = runCatching {
            Tasks.await(models.getDownloadedModels(TranslateRemoteModel::class.java), 15, TimeUnit.SECONDS).map { it.language }
        }.getOrDefault(emptyList())
        val chinese = if (verifiedChineseFiles()) listOf("zh") else emptyList()
        return (registered.filter { it != "zh" && it != "en" } + chinese).distinct().sorted()
    }

    private fun isChineseModelReady(model: TranslateRemoteModel): Boolean =
        verifiedChineseFiles() || Tasks.await(models.isModelDownloaded(model), 15, TimeUnit.SECONDS)

    private fun translate(source: String, target: String, text: String): String {
        for (language in setOf(source, target) - "en") {
            val model = TranslateRemoteModel.Builder(language).build()
            val ready = if (language == "zh") isChineseModelReady(model)
            else Tasks.await(models.isModelDownloaded(model), 15, TimeUnit.SECONDS)
            if (!ready) throw MissingModel(language)
        }
        return Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build()).use {
            Tasks.await(it.translate(text), 45, TimeUnit.SECONDS)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
