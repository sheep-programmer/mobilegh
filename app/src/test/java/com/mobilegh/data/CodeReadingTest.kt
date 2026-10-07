package com.mobilegh.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CodeReadingTest {
    private val sha = "a".repeat(40)
    private fun range(start: Int, end: Int) = """{"startingLine":$start,"endingLine":$end,"commit":{"oid":"$sha","messageHeadline":"Fix","committedDate":"2026-01-01T00:00:00Z","author":{"name":"Anonymous author","user":null}}}"""
    private fun data(ranges: String) = Api.plain.parseToJsonElement("""{"repository":{"object":{"oid":"$sha","blame":{"ranges":[$ranges]}}}}""").jsonObject
    @Test fun repositoryCodeQueriesRetainTheirScopeAndEmptyTermsAreRejected() {
        assertEquals("foo repo:mona/repo", CodeReading.searchQuery(" foo ", "mona", "repo"))
        assertEquals("foo", CodeReading.searchQuery("foo"))
        assertThrows(IllegalArgumentException::class.java) { CodeReading.searchQuery("") }
    }
    @Test fun fileNameMatchesRankBeforeDirectoryMatchesAndResultCountsAreNotTruncated() {
        val files = listOf(RepositoryTreeEntry("main/other.kt", "blob"), RepositoryTreeEntry("src/main.kt", "blob"), RepositoryTreeEntry("src/MainActivity.kt", "blob"))
        val result = CodeReading.filterFiles(files, "main", 2)
        assertEquals(3, result.count)
        assertEquals(listOf("src/main.kt", "src/MainActivity.kt"), result.entries.map { it.path })
        assertEquals("src/main.kt", CodeReading.filterFiles(files, "src main.kt").entries.single().path)
    }
    @Test fun unsafeAndUnopenableFilePathsAreRejected() {
        listOf("/root", "../readme", "src/../file", "src//file", "file\n").forEach { assertFalse(CodeReading.validPath(it)) }
        assertTrue(CodeReading.validPath("src/文件 + name.kt"))
    }
    @Test fun blameIncludesAnonymousAuthorsAndUsesInclusiveLineBounds() {
        val blame = CodeReading.parseBlame(data(range(1, 3) + "," + range(4, 8)))
        assertEquals("Anonymous author", blame.ranges.first().author)
        assertEquals(1, blame.rangeFor(3)?.start)
        assertEquals(4, blame.rangeFor(4)?.start)
        assertNull(blame.rangeFor(9))
    }
    @Test fun malformedAndOverlappingBlameRangesAreReported() {
        assertThrows(IllegalArgumentException::class.java) { CodeReading.parseBlame(data(range(0, 1))) }
        assertThrows(IllegalArgumentException::class.java) { CodeReading.parseBlame(data(range(1, 3) + "," + range(3, 4))) }
        assertThrows(ApiException::class.java) { CodeReading.parseBlame(Api.plain.parseToJsonElement("""{"repository":null}""").jsonObject) }
    }
    @Test fun codeSearchResponsesPreserveSnakeCaseFieldsAndSnippets() {
        val page = Api.json.decodeFromString<CodeSearchPage>("""{"total_count":2,"incomplete_results":true,"items":[{"path":"src/Foo.kt","html_url":"https://github.com/mona/repo/blob/main/src/Foo.kt","repository":{"name":"repo","owner":{"login":"mona"},"full_name":"mona/repo"},"text_matches":[{"fragment":"fun foo()"}]}]}""")
        assertEquals(2, page.totalCount)
        assertTrue(page.incompleteResults)
        assertEquals("fun foo()", page.items.single().textMatches.single().fragment)
    }
}
