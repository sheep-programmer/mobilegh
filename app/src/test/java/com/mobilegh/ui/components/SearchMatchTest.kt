package com.mobilegh.ui.components

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatchTest {
    @Test
    fun countsEachIndividualOccurrenceSeparately() {
        val lines = listOf(
            AnnotatedString("alpha beta"),
            AnnotatedString("No hits here"),
            AnnotatedString("banana avocado"),
        )
        // line 1: alpha (2), beta (1) -> 3 'a's
        // line 2: 0 'a's
        // line 3: banana (3), avocado (2) -> 5 'a's
        val matches = findSearchMatches(lines, "a")
        assertEquals(8, matches.size)

        // Line 1 matches:
        assertEquals(1, matches[0].line)
        assertEquals(1, matches[1].line)
        assertEquals(1, matches[2].line)

        // Line 3 matches:
        assertEquals(3, matches[3].line)
        assertEquals(3, matches[7].line)
    }

    @Test
    fun returnsEmptyOnBlankQuery() {
        val lines = listOf(AnnotatedString("some text"))
        assertTrue(findSearchMatches(lines, "").isEmpty())
        assertTrue(findSearchMatches(lines, "   ").isEmpty())
    }

    @Test
    fun caseInsensitiveMatching() {
        val lines = listOf(AnnotatedString("A a a A"))
        val matches = findSearchMatches(lines, "a")
        assertEquals(4, matches.size)
        assertEquals(0, matches[0].start)
        assertEquals(2, matches[1].start)
        assertEquals(4, matches[2].start)
        assertEquals(6, matches[3].start)
    }
}
