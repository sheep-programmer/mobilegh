package com.mobilegh.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    @Test fun termsKeepPhrasesAndSkipQualifiers() {
        assertEquals(listOf("fun", "main"), SearchText.terms("fun main"))
        assertEquals(listOf("fun main"), SearchText.terms("\"fun main\""))
        assertEquals(listOf("crash"), SearchText.terms("repo:octocat/hello language:kotlin crash"))
        assertEquals(listOf("C++"), SearchText.terms("  C++  "))
        assertEquals(emptyList<String>(), SearchText.terms("repo:a/b language:kotlin"))
        assertEquals(emptyList<String>(), SearchText.terms("   "))
        // 未闭合的引号也当成短语处理
        assertEquals(listOf("fun main"), SearchText.terms("\"fun main"))
    }

    @Test fun rangesIgnoreCaseMergeOverlapsAndFindEveryOccurrence() {
        assertEquals(listOf(0..2, 8..10), SearchText.ranges("Foo bar foo", "foo"))
        // 相邻区间合并，重叠词只着色一次
        assertEquals(listOf(0..5), SearchText.ranges("abcdef", "abc cdef"))
        assertEquals(listOf(0..2, 4..7), SearchText.ranges("fun main", "fun fun main"))
        assertEquals(emptyList<IntRange>(), SearchText.ranges("fun main", "repo:a/b"))
        assertEquals(emptyList<IntRange>(), SearchText.ranges("", "fun"))
        assertEquals(emptyList<IntRange>(), SearchText.ranges("abc", "abcd"))
    }

    @Test fun annotatedHighlightsOnlyMatchedTerms() {
        val text = "Change the readme file"
        val styled = SearchText.annotated(text, "readme file", Color.Yellow)
        assertEquals(text, styled.text)
        val highlights = styled.spanStyles.filter { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals(listOf(11..16, 18..21), highlights.map { it.start until it.end })
        assertTrue(highlights.all { it.item.background == Color.Yellow })
        // 没有命中时原样返回，不加任何样式
        assertTrue(SearchText.annotated(text, "repo:a/b", Color.Yellow).spanStyles.isEmpty())
    }
}
