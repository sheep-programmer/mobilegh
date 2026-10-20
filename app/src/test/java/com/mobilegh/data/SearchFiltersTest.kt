package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SearchFiltersTest {
    private fun since(days: Int) = LocalDate.now().minusDays(days.toLong()).toString()

    @Test fun repoQueryCombinesAllFilters() {
        val q = SearchQuery.build(
            SearchType.REPO, "compose ui",
            SearchFilters(repoOwner = "JetBrains", repoLanguage = "Kotlin", minStars = 100, minForks = 10, topic = "android", archived = 2),
        )
        assertEquals(
            "compose ui user:JetBrains language:\"Kotlin\" stars:>=100 forks:>=10 topic:android archived:false",
            q,
        )
    }

    @Test fun blankKeywordAndNoFilterProduceEmptyRepoQuery() {
        assertEquals("", SearchQuery.build(SearchType.REPO, "", SearchFilters()))
    }

    @Test fun userQueryTypeLocationFollowers() {
        assertEquals("octo type:org location:\"San Francisco\" language:\"Go\" followers:>=50",
            SearchQuery.build(SearchType.USER, "octo", SearchFilters(userType = 2, location = "San Francisco", userLanguage = "Go", minFollowers = 50)))
    }

    @Test fun issueAndPrQueriesAddStateAndMeta() {
        val f = SearchFilters(state = 1, author = "mona", labels = "bug,help wanted", minComments = 3, issueRepo = "cli/cli")
        val issue = SearchQuery.build(SearchType.ISSUE, "crash", f)
        assertTrue(issue.startsWith("crash is:issue is:open repo:cli/cli author:mona"))
        assertTrue(issue.contains("label:\"bug\""))
        assertTrue(issue.contains("label:\"help wanted\""))
        assertTrue(issue.contains("comments:>=3"))
        val pr = SearchQuery.build(SearchType.PR, "crash", f)
        assertTrue(pr.contains("is:pr"))
        assertFalse(pr.contains("is:issue"))
    }

    @Test fun issueStateMeansClosedWhenSelected() {
        assertTrue(SearchQuery.build(SearchType.ISSUE, "x", SearchFilters(state = 2)).contains("is:closed"))
        assertFalse(SearchQuery.build(SearchType.ISSUE, "x", SearchFilters()).contains("is:open"))
    }

    @Test fun issueUpdatedWithinDaysUsesDateQualifier() {
        assertTrue(SearchQuery.build(SearchType.ISSUE, "x", SearchFilters(updatedDays = 7)).contains("updated:>${since(7)}"))
    }

    @Test fun codeQueryScopesToRepoOwnerPathFilename() {
        assertEquals("fun main repo:sheep-programmer/mobilegh language:\"Kotlin\" path:\"app/src\" filename:\"Main\"",
            SearchQuery.build(SearchType.CODE, "fun main", SearchFilters(codeRepo = "sheep-programmer/mobilegh", codeLanguage = "Kotlin", path = "app/src", filename = "Main")))
    }

    @Test fun codeOwnerIsIgnoredWhenRepoIsGiven() {
        val q = SearchQuery.build(SearchType.CODE, "x", SearchFilters(codeRepo = "a/b", codeOwner = "c"))
        assertTrue(q.contains("repo:a/b"))
        assertFalse(q.contains("user:c"))
        // 没给仓库时 owner 才转成 user:
        assertTrue(SearchQuery.build(SearchType.CODE, "x", SearchFilters(codeOwner = "c")).contains("user:c"))
    }

    @Test fun quotesAndBackslashesCannotBreakQuerySyntax() {
        val q = SearchQuery.build(SearchType.REPO, "x", SearchFilters(repoLanguage = "K\"otlin\\"))
        assertEquals("x language:\"Kotlin\"", q)
    }

    @Test fun identifiersAreSanitized() {
        // 仓库名里的空格/引号/冒号被去掉，无法借机拼出额外的限定符
        val injected = SearchQuery.build(SearchType.CODE, "x", SearchFilters(codeRepo = "evil/injected language:ruby"))
        assertTrue(injected.startsWith("x repo:evil/injected"))
        assertFalse(injected.contains("language:"))
        assertEquals("x user:monaOR", SearchQuery.build(SearchType.REPO, "x", SearchFilters(repoOwner = "mona\" OR")))
    }

    @Test fun activeCountAndClearedArePerType() {
        val f = SearchFilters(
            repoLanguage = "Kotlin", minStars = 5,        // 仓库 2 项
            location = "Tokyo", userLanguage = "Go",      // 用户 2 项
            state = 1, issueRepo = "a/b",                 // Issue 2 项
            path = "src", codeLanguage = "Rust",          // 代码 2 项
        )
        assertEquals(2, f.activeCount(SearchType.REPO))
        assertEquals(2, f.activeCount(SearchType.USER))
        assertEquals(2, f.activeCount(SearchType.ISSUE))
        assertEquals(2, f.activeCount(SearchType.CODE))
        // 清除任一类型不影响其他类型
        // Issue 与 PR 共用同一组字段，清除其一等于两者都清除
        for (type in 0..4) {
            val cleared = f.cleared(type)
            val sameGroup = if (type == SearchType.ISSUE || type == SearchType.PR) setOf(SearchType.ISSUE, SearchType.PR) else setOf(type)
            for (other in 0..4) {
                val expected = if (other in sameGroup) 0 else 2
                assertEquals("type=$type other=$other", expected, cleared.activeCount(other))
            }
        }
    }

    @Test fun hasCriteriaRequiresKeywordOrFilter() {
        assertFalse(SearchQuery.hasCriteria(SearchType.CODE, "  ", SearchFilters()))
        assertTrue(SearchQuery.hasCriteria(SearchType.CODE, "x", SearchFilters()))
        assertTrue(SearchQuery.hasCriteria(SearchType.CODE, "", SearchFilters(codeLanguage = "Kotlin")))
    }
}
