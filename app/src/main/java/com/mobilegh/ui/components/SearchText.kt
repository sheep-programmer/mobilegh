package com.mobilegh.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight

/**
 * 代码搜索的查询词处理：从 `language:kt path:src foo bar` 里取出真正要匹配的词，
 * 并在结果片段里找出这些词的位置用于高亮。限定符（含冒号的 token）不参与高亮。
 */
internal object SearchText {

    /** 拆出自由文本词：支持用双引号包住带空格的短语，跳过 `key:value` 限定符。 */
    fun terms(query: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < query.length) {
            while (i < query.length && query[i].isWhitespace()) i++
            if (i >= query.length) break
            val quoted = query[i] == '"'
            if (quoted) i++
            val sb = StringBuilder()
            while (i < query.length && (if (quoted) query[i] != '"' else !query[i].isWhitespace())) {
                sb.append(query[i])
                i++
            }
            if (quoted && i < query.length) i++ // 跳过收尾引号
            val word = sb.toString()
            if (word.isBlank()) continue
            if (!quoted && word.contains(':')) continue
            out += word
        }
        return out
    }

    /** 所有匹配区间（忽略大小写），重叠或相邻的会合并，便于一次性着色。 */
    fun ranges(text: String, query: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val found = mutableListOf<IntRange>()
        terms(query).distinctBy { it.lowercase() }.forEach { term ->
            var from = 0
            while (from <= text.length - term.length) {
                val at = text.indexOf(term, from, ignoreCase = true)
                if (at < 0) break
                found += at until at + term.length
                from = at + term.length.coerceAtLeast(1)
            }
        }
        return merge(found)
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        var cur = sorted.first()
        sorted.drop(1).forEach { r ->
            cur = if (r.first <= cur.last + 1) {
                if (r.last > cur.last) cur.first..r.last else cur
            } else {
                out += cur
                r
            }
        }
        out += cur
        return out
    }

    /** 搜索结果片段：命中的词加底色和加粗，其余保持原样。 */
    fun annotated(text: String, query: String, background: Color): AnnotatedString {
        val hit = ranges(text, query)
        if (hit.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            hit.forEach { r ->
                addStyle(SpanStyle(background = background, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
            }
        }
    }
}
