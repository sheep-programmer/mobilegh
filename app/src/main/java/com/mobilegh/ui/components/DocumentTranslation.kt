package com.mobilegh.ui.components

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.Crypto
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.i18n.I18n
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun translateDocumentBlocks(
    blocks: List<HtmlBlock>,
    translate: suspend (String) -> String,
): List<HtmlBlock> {
    suspend fun inlines(parts: List<Inline>) = parts.map { part ->
        if (part is Inline.Text && !part.style.code) part.copy(text = translateProse(part.text, translate)) else part
    }
    return blocks.map { block ->
        when (block) {
            is HtmlBlock.Heading -> block.copy(inlines = inlines(block.inlines))
            is HtmlBlock.Paragraph -> block.copy(inlines = inlines(block.inlines))
            is HtmlBlock.Quote -> block.copy(blocks = translateDocumentBlocks(block.blocks, translate))
            is HtmlBlock.Aligned -> block.copy(blocks = translateDocumentBlocks(block.blocks, translate))
            is HtmlBlock.ListBlock -> block.copy(items = block.items.map { translateDocumentBlocks(it, translate) })
            is HtmlBlock.Details -> block.copy(summary = inlines(block.summary), blocks = translateDocumentBlocks(block.blocks, translate))
            is HtmlBlock.Table -> block.copy(header = block.header.map { inlines(it) }, rows = block.rows.map { row -> row.map { inlines(it) } })
            else -> block
        }
    }
}

private val preservedTokens = Regex("""(?i)https?://[^\s<>]+|(?<![\p{L}\p{N}])@[a-z\d_-]+|[a-z\d_.-]+/[a-z\d_.-]+|\bv?\d+(?:\.\d+)+\b""")

private fun documentText(blocks: List<HtmlBlock>): Sequence<String> = sequence {
    suspend fun SequenceScope<String>.prose(parts: List<Inline>) {
        for (part in parts) if (part is Inline.Text && !part.style.code) {
            yield(part.text)
        }
    }
    for (block in blocks) when (block) {
        is HtmlBlock.Heading -> prose(block.inlines)
        is HtmlBlock.Paragraph -> prose(block.inlines)
        is HtmlBlock.Quote -> yieldAll(documentText(block.blocks))
        is HtmlBlock.Aligned -> yieldAll(documentText(block.blocks))
        is HtmlBlock.ListBlock -> for (item in block.items) yieldAll(documentText(item))
        is HtmlBlock.Details -> {
            prose(block.summary)
            yieldAll(documentText(block.blocks))
        }
        is HtmlBlock.Table -> {
            for (cell in block.header) prose(cell)
            for (row in block.rows) for (cell in row) prose(cell)
        }
        else -> Unit
    }
}

private suspend fun translateProse(text: String, translate: suspend (String) -> String): String = buildString {
    suspend fun appendProse(segment: String) {
        val content = segment.trim()
        if (content.none(Char::isLetter)) {
            append(segment)
            return
        }
        val start = segment.indexOf(content)
        append(segment.substring(0, start))
        append(translate(content).ifBlank { content })
        append(segment.substring(start + content.length))
    }
    var offset = 0
    for (token in preservedTokens.findAll(text)) {
        appendProse(text.substring(offset, token.range.first))
        append(token.value)
        offset = token.range.last + 1
    }
    appendProse(text.substring(offset))
}

private class ModelDownloadTimeout : Exception()
private val translationJobs = Semaphore(2)

private class DocumentTranslation(private val parsed: ParsedHtml, private val target: String, private val cache: TranslationParagraphCache) {
    private val languageLock = Mutex()
    private var detected = false
    private var source: String? = null

    private suspend fun sourceLanguage(): String? = languageLock.withLock {
        if (!detected) {
            val sample = buildString {
                for (part in documentText(parsed.blocks)) {
                    if (length >= 1200) break
                    val text = preservedTokens.replace(part.take(1200), " ").trim()
                    if (text.none(Char::isLetter)) continue
                    if (isNotEmpty()) append(' ')
                    append(text.take(1200 - length))
                }
            }
            if (sample.isNotBlank()) {
                val identifier = LanguageIdentification.getClient()
                val tag = try { identifier.identifyLanguage(sample).awaitResult() } finally { identifier.close() }
                source = TranslateLanguage.fromLanguageTag(tag)
            }
            detected = true
        }
        source
    }

    suspend fun translate(block: HtmlBlock): HtmlBlock = withContext(Dispatchers.Default) {
        translationJobs.withPermit {
            val language = sourceLanguage() ?: return@withPermit block
            if (language == target) return@withPermit block
            val translator = lazy {
                Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(language).setTargetLanguage(target).build())
            }
            var modelReady = false
            try {
                translateDocumentBlocks(listOf(block)) { text ->
                    val cacheKey = cache.key(language, target, text)
                    cache.read(cacheKey) ?: run {
                        if (!modelReady) {
                            withTimeoutOrNull(45_000) {
                                translator.value.downloadModelIfNeeded().awaitResult()
                                true
                            } ?: throw ModelDownloadTimeout()
                            modelReady = true
                        }
                        translator.value.translate(text).awaitResult().also { result -> cache.write(cacheKey, result) }
                    }
                }.single()
            } finally {
                if (translator.isInitialized()) translator.value.close()
            }
        }
    }
}

private class TranslationParagraphCache(context: Context, private val scope: String) {
    private val directory = File(context.noBackupFilesDir, "translated-paragraphs")

    fun key(source: String, target: String, text: String): String = MessageDigest.getInstance("SHA-256")
        .digest("v1\u0000$scope\u0000$source\u0000$target\u0000$text".toByteArray())
        .joinToString("") { "%02x".format(it) }

    suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        synchronized(diskLock) {
            val file = File(directory, key)
            if (!file.isFile || file.length() !in 1..MAX_ENTRY_BYTES) return@synchronized null
            runCatching { Crypto.decrypt(file.readText()).also { file.setLastModified(System.currentTimeMillis()) } }.getOrNull()
        }
    }

    suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) {
        if (value.isBlank() || value.length > MAX_ENTRY_BYTES / 4) return@withContext
        synchronized(diskLock) {
            runCatching {
                directory.mkdirs()
                val encrypted = Crypto.encrypt(value)
                if (encrypted.length <= MAX_ENTRY_BYTES) File(directory, key).writeText(encrypted)
                if (++writesSinceTrim >= 32) {
                    writesSinceTrim = 0
                    val files = directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
                    var size = 0L
                    files.forEachIndexed { index, file ->
                        size += file.length()
                        if (index >= 2048 || size > MAX_CACHE_BYTES) file.delete()
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_ENTRY_BYTES = 48L * 1024
        private const val MAX_CACHE_BYTES = 8L * 1024 * 1024
        private val diskLock = Any()
        private var writesSinceTrim = 0
    }
}

private suspend fun <Result> Task<Result>.awaitResult(): Result = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        when {
            task.isCanceled -> continuation.cancel()
            task.isSuccessful -> continuation.resume(task.result)
            else -> continuation.resumeWithException(task.exception ?: IllegalStateException())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DocumentTranslationContent(
    parsed: ParsedHtml,
    allowed: Boolean,
    content: @Composable ((@Composable () -> Unit)?, @Composable (HtmlBlock, @Composable (HtmlBlock) -> Unit) -> Unit) -> Unit,
) {
    if (!allowed || !Session.translateDocuments) {
        content(null) { block, render -> render(block) }
        return
    }
    val target = if (I18n.isEnglish) TranslateLanguage.ENGLISH else TranslateLanguage.CHINESE
    val accountScope = "${Net.apiHost}:${Session.login.orEmpty()}"
    val context = rememberCtx().applicationContext
    val engine = remember(parsed, target, accountScope) {
        DocumentTranslation(parsed, target, TranslationParagraphCache(context, accountScope))
    }
    var failed by remember(engine) { mutableStateOf(false) }
    var translated by remember(engine) { mutableStateOf(false) }
    var showOriginal by remember(engine) { mutableStateOf(false) }
    var attempt by remember(engine) { mutableIntStateOf(0) }
    val header: (@Composable () -> Unit)? = when {
        failed || translated -> {{
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (translated) {
                    SectionActionChip(tr(if (showOriginal) "显示译文" else "查看原文")) { showOriginal = !showOriginal }
                    if (!showOriginal) Image(
                        painterResource(if (Gh.c.dark) R.drawable.google_translation_attribution_dark else R.drawable.google_translation_attribution),
                        contentDescription = tr("由 Google 自动翻译"),
                    )
                }
                if (failed) {
                    Text(tr("翻译失败，正在显示原文"), color = Gh.c.fgMuted, fontSize = 12.sp)
                    SectionActionChip(tr("重试")) { failed = false; attempt++ }
                }
            }
        }}
        else -> null
    }
    content(header) { block, render ->
        if (block is HtmlBlock.Code || block is HtmlBlock.Image || block is HtmlBlock.ImageRow ||
            block is HtmlBlock.Video || block is HtmlBlock.YouTube || block is HtmlBlock.Web || block == HtmlBlock.Rule) {
            render(block)
        } else {
            var visible by remember(engine, block) { mutableStateOf(false) }
            var result by remember(engine, block) { mutableStateOf<HtmlBlock?>(null) }
            LaunchedEffect(engine, block, visible, showOriginal, failed, attempt) {
                if (!visible || showOriginal || failed || result != null) return@LaunchedEffect
                try {
                    val updated = engine.translate(block)
                    result = updated
                    if (updated != block) translated = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    AppLog.warn("translation", error.javaClass.simpleName)
                    failed = true
                }
            }
            Box(Modifier.onVisibilityChanged(minDurationMs = 120, minFractionVisible = 0f) { visible = it }) {
                render(if (showOriginal) block else result ?: block)
            }
        }
    }
}
