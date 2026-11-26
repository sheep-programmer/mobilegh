package com.mobilegh.ui.components

import android.util.LruCache
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.Net
import com.mobilegh.data.OfflineTranslation
import com.mobilegh.data.Session
import com.mobilegh.i18n.I18n
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal suspend fun translateDocumentBlocks(
    blocks: List<HtmlBlock>,
    translate: suspend (String) -> String,
): List<HtmlBlock> {
    return mapDocumentInlines(blocks) { translateInlineRuns(it, translate) }
}

private suspend fun mapDocumentInlines(
    blocks: List<HtmlBlock>,
    inlines: suspend (List<Inline>) -> List<Inline>,
): List<HtmlBlock> = blocks.map { block ->
    when (block) {
        is HtmlBlock.Heading -> block.copy(inlines = inlines(block.inlines))
        is HtmlBlock.Paragraph -> block.copy(inlines = inlines(block.inlines))
        is HtmlBlock.Quote -> block.copy(blocks = mapDocumentInlines(block.blocks, inlines))
        is HtmlBlock.Aligned -> block.copy(blocks = mapDocumentInlines(block.blocks, inlines))
        is HtmlBlock.ListBlock -> block.copy(items = block.items.map { mapDocumentInlines(it, inlines) })
        is HtmlBlock.Details -> block.copy(summary = inlines(block.summary), blocks = mapDocumentInlines(block.blocks, inlines))
        is HtmlBlock.Table -> block.copy(header = block.header.map { inlines(it) }, rows = block.rows.map { row -> row.map { inlines(it) } })
        else -> block
    }
}

internal fun translationLanguageSample(parts: Sequence<String>): String = buildString {
    for (part in parts) {
        val remaining = 1200 - length
        if (remaining <= 1) break
        val text = preservedTokens.replace(part.take(1200), " ").trim()
        if (text.none(Char::isLetter)) continue
        if (isNotEmpty()) append(' ')
        append(text.take(1200 - length))
    }
}

internal fun normalizeTranslationLanguageTag(tag: String): String? {
    val clean = tag.trim().lowercase(java.util.Locale.ROOT)
    if (clean.isEmpty() || clean == "und" || clean.endsWith("-latn") || "-latn-" in clean) return null
    return when {
        clean == "zh" || clean.startsWith("zh-") -> "zh"
        clean == "en" || clean.startsWith("en-") -> "en"
        else -> clean.substringBefore('-')
    }
}

internal suspend fun resolveTranslationLanguage(
    sample: String,
    identify: suspend (String) -> String,
    documentLanguage: suspend () -> String?,
): String? {
    if (sample.isBlank()) return null
    val normalized = normalizeTranslationLanguageTag(identify(sample))
    return normalized ?: documentLanguage()
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

// Treat styles as boundaries within a run, rather than translating each styled fragment separately.
private val translationMarker = Regex("""(?i)[\[［]\s*X\s*Q\s*([0-9]+)\s*X\s*[\]］]""")
private val plainStyle = InlineStyle()

private sealed interface TranslationMarker {
    data class Open(val style: InlineStyle) : TranslationMarker
    data object Close : TranslationMarker
    data class Literal(val text: Inline.Text) : TranslationMarker
}

private suspend fun translateInlineRuns(parts: List<Inline>, translate: suspend (String) -> String): List<Inline> {
    val output = mutableListOf<Inline>()
    val run = mutableListOf<Inline.Text>()
    suspend fun flush() {
        if (run.isEmpty()) return
        output += translateRun(run, translate)
        run.clear()
    }
    for (part in parts) {
        if (part is Inline.Text) run.add(part) else {
            // Images and explicit newlines stay in their original position.
            flush()
            output.add(part)
        }
    }
    flush()
    return output
}

private suspend fun translateRun(parts: List<Inline.Text>, translate: suspend (String) -> String): List<Inline.Text> {
    suspend fun fallback() = parts.map { part ->
        if (part.style.code) part else part.copy(text = translateProse(part.text, translate))
    }
    if (parts.any { translationMarker.containsMatchIn(it.text) } ||
        parts.sumOf { it.text.length.toLong() } > 12_000) return fallback()

    val markers = mutableListOf<TranslationMarker>()
    var hasProse = false
    val input = buildString {
        fun mark(marker: TranslationMarker) {
            append("[XQ").append(markers.size).append("X]")
            markers.add(marker)
        }
        for (part in parts) {
            if (part.text.isEmpty()) continue
            if (part.style.code) {
                mark(TranslationMarker.Literal(part))
                continue
            }
            if (part.style != plainStyle) mark(TranslationMarker.Open(part.style))
            var offset = 0
            fun prose(end: Int) {
                val text = part.text.substring(offset, end)
                hasProse = hasProse || text.any(Char::isLetter)
                append(text)
            }
            for (token in preservedTokens.findAll(part.text)) {
                prose(token.range.first)
                mark(TranslationMarker.Literal(part.copy(text = token.value)))
                offset = token.range.last + 1
            }
            prose(part.text.length)
            if (part.style != plainStyle) mark(TranslationMarker.Close)
        }
    }
    if (!hasProse) return parts
    val request = input.trim()
    if (request.length > 12_288) return fallback()
    val translated = translate(request)
    if (translated.isBlank() || translated == request) return parts

    val output = mutableListOf<Inline.Text>()
    fun add(text: String, style: InlineStyle) {
        if (text.isEmpty()) return
        val last = output.lastOrNull()
        if (last != null && last.style == style && !style.code) {
            output[output.lastIndex] = last.copy(text = last.text + text)
        } else output.add(Inline.Text(text, style))
    }

    val seen = mutableSetOf<Int>()
    var current: TranslationMarker.Open? = null
    var expectedClose = -1
    var offset = 0
    val text = input.takeWhile(Char::isWhitespace) + translated.trim() + input.takeLastWhile(Char::isWhitespace)
    for (match in translationMarker.findAll(text)) {
        val id = match.groupValues[1].toIntOrNull() ?: return fallback()
        val marker = markers.getOrNull(id) ?: return fallback()
        if (!seen.add(id)) return fallback()
        add(text.substring(offset, match.range.first), current?.style ?: plainStyle)
        when (marker) {
            is TranslationMarker.Open -> {
                if (current != null) return fallback()
                current = marker
                expectedClose = (id + 1 until markers.size).first { markers[it] == TranslationMarker.Close }
            }
            TranslationMarker.Close -> {
                if (id != expectedClose) return fallback()
                current = null
                expectedClose = -1
            }
            is TranslationMarker.Literal -> {
                if (marker.text.style != (current?.style ?: plainStyle) && !marker.text.style.code) return fallback()
                if (marker.text.style.code && current != null) return fallback()
                add(marker.text.text, marker.text.style)
            }
        }
        offset = match.range.last + 1
    }
    if (current != null || seen.size != markers.size) return fallback()
    add(text.substring(offset), plainStyle)
    return output
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

private val translationJobs = Semaphore(2)

internal class DocumentTranslation(private val parsed: ParsedHtml, private val target: String, private val cache: TranslationParagraphCache, private val scope: String) {
    private val languageLock = Mutex()
    private var detected = false
    private var source: String? = null

    private suspend fun documentLanguage(): String? = languageLock.withLock {
        if (!detected) {
            val filtered = documentText(parsed.blocks).filter { line ->
                // 跳过顶部的多语言切换栏（如 "English | Español | 简体中文 | ..."），避免整篇语言被误判
                line.count { it == '|' } < 3
            }
            val sample = translationLanguageSample(filtered).ifBlank {
                translationLanguageSample(documentText(parsed.blocks))
            }
            if (sample.isNotBlank()) source = normalizeTranslationLanguageTag(OfflineTranslation.identify(sample))
            detected = true
        }
        source
    }

    suspend fun translate(block: HtmlBlock): HtmlBlock = withContext(Dispatchers.Default) {
        translationJobs.withPermit {
            mapDocumentInlines(listOf(block)) { parts ->
                val sample = translationLanguageSample(parts.asSequence().filterIsInstance<Inline.Text>()
                    .filterNot { it.style.code }.map { it.text })
                val identified = resolveTranslationLanguage(sample, OfflineTranslation::identify, ::documentLanguage)
                    ?: return@mapDocumentInlines parts
                val docLang = if (identified != target && (sample.length < 24 || sample.split(Regex("\\s+")).size < 4)) {
                    documentLanguage()
                } else null
                val language = docLang ?: identified
                if (language == target) return@mapDocumentInlines parts
                translateInlineRuns(parts) { text ->
                    val cacheKey = TranslationParagraphCache.Key(scope, language, target, text)
                    cache.read(cacheKey) ?: run {
                        val result = try {
                            OfflineTranslation.translate(language, target, text)
                        } catch (missing: OfflineTranslation.ModelMissing) {
                            val fallbackLang = documentLanguage()?.takeIf { it != language && it != target } ?: throw missing
                            OfflineTranslation.translate(fallbackLang, target, text)
                        }
                        polishTranslation(text, result, language, target).also { cache.write(cacheKey, it) }
                    }
                }
            }.single()
        }
    }
}

/** No document text or translation is persisted. The UI session owns this cache. */
internal class TranslationParagraphCache {
    data class Key(val scope: String, val source: String, val target: String, val text: String)

    private val entries = object : LruCache<Key, String>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: String): Int =
            128 + 2 * (key.scope.length + key.source.length + key.target.length + key.text.length + value.length)
    }

    fun read(key: Key): String? = entries.get(key)

    fun write(key: Key, value: String) {
        if (value.isNotBlank() && key.text.length <= 12_288 && value.length <= 12_288) {
            entries.put(key, value)
        }
    }
}

internal val LocalTranslationCache = staticCompositionLocalOf<TranslationParagraphCache?> { null }
internal class PageTranslation {
    var showOriginal by mutableStateOf(false)
}
internal val LocalPageTranslation = staticCompositionLocalOf<PageTranslation?> { null }

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
    val target = if (I18n.isEnglish) "en" else "zh"
    val accountScope = "${Net.apiHost}:${Session.login.orEmpty()}"
    val cache = LocalTranslationCache.current ?: remember { TranslationParagraphCache() }
    val page = LocalPageTranslation.current
    val engine = remember(parsed, target, accountScope, cache) {
        DocumentTranslation(parsed, target, cache, accountScope)
    }
    var failed by remember(engine) { mutableStateOf(false) }
    var missingLanguage by remember(engine) { mutableStateOf<String?>(null) }
    var translated by remember(engine) { mutableStateOf(false) }
    var inlineOriginal by remember(engine) { mutableStateOf(false) }
    val showOriginal = page?.showOriginal ?: inlineOriginal
    var attempt by remember(engine) { mutableIntStateOf(0) }
    val header: (@Composable () -> Unit)? = when {
        (failed && !showOriginal) || (translated && (!showOriginal || page == null)) -> {{
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (translated) {
                    if (page == null) {
                        SectionActionChip(tr(if (showOriginal) "显示译文" else "显示原文")) { inlineOriginal = !inlineOriginal }
                    }
                    if (!showOriginal) Image(
                        painterResource(if (Gh.c.dark) R.drawable.google_translation_attribution_dark else R.drawable.google_translation_attribution),
                        contentDescription = tr("由 Google 自动翻译"),
                    )
                }
                if (failed && !showOriginal) {
                    val message = missingLanguage?.let {
                        com.mobilegh.i18n.trf("请在离线翻译设置下载{0}模型，当前显示原文", OfflineTranslation.languageName(it))
                    } ?: tr("翻译失败，正在显示原文")
                    Text(message, color = Gh.c.fgMuted, fontSize = 12.sp)
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
                    missingLanguage = (error as? OfflineTranslation.ModelMissing)?.language
                    failed = true
                }
            }
            Box(Modifier.onVisibilityChanged(minDurationMs = 120, minFractionVisible = 0f) { visible = it }) {
                render(if (showOriginal) block else result ?: block)
            }
        }
    }
}
