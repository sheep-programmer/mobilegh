package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test

class IssueQueryTest {
    private val base = IssueQuery(repo = "mona/repo")

    @Test fun stateAndSortAloneUseTheListEndpoint() {
        assertFalse(base.needsSearch)
        assertFalse(base.copy(state = IssueStateFilter.Closed, sort = IssueSort.Updated).needsSearch)
        assertFalse(base.copy(sort = IssueSort.Comments).needsSearch)
        // PR 列表接口不支持按评论数排序
        assertTrue(base.copy(pulls = true, sort = IssueSort.Comments).needsSearch)
        assertTrue(base.copy(text = "crash").needsSearch)
        assertTrue(base.copy(labels = listOf("bug")).needsSearch)
        assertTrue(IssueQuery(involves = Involvement.Created).needsSearch)
    }

    @Test fun searchQueryContainsEveryQualifierInOrder() {
        val q = IssueQuery(
            pulls = true, repo = "mona/repo", state = IssueStateFilter.Closed,
            author = "octocat", assignee = "hubot", labels = listOf("bug", "good first issue"), milestone = "v1.0 beta",
            review = ReviewFilter.Approved, draft = false, text = "  crash on start ",
        )
        assertEquals(
            "repo:mona/repo is:pr state:closed author:octocat assignee:hubot label:\"bug\" label:\"good first issue\" " +
                "milestone:\"v1.0 beta\" review:approved draft:false crash on start",
            q.searchQuery(),
        )
    }

    @Test fun allStateOmitsTheStateQualifierAndIssueIgnoresPrOnlyFilters() {
        val q = base.copy(state = IssueStateFilter.All, review = ReviewFilter.Required, draft = true)
        assertEquals("repo:mona/repo is:issue", q.searchQuery())
        assertTrue(q.activeFilters().isEmpty())
    }

    @Test fun involvementAddsMeQualifiersAndSkipsArchivedAcrossRepositories() {
        assertEquals("is:issue state:open assignee:@me archived:false", IssueQuery(involves = Involvement.Assigned).searchQuery())
        assertEquals("is:pr state:open review-requested:@me archived:false", IssueQuery(pulls = true, involves = Involvement.ReviewRequested).searchQuery())
        // 评审请求只对 PR 有意义
        assertEquals("is:issue state:open archived:false", IssueQuery(involves = Involvement.ReviewRequested).searchQuery())
        assertNull(IssueQuery(involves = Involvement.ReviewRequested).normalized().involves)
    }

    @Test fun valuesCannotInjectExtraQualifiers() {
        val q = base.copy(author = "evil repo:other", labels = listOf("a\" repo:x"), milestone = "\"m\"")
        assertEquals("repo:mona/repo is:issue state:open author:evilrepoother label:\"a repo:x\" milestone:\"m\"", q.searchQuery())
        assertEquals("author:@me", "author:" + IssueQuery.login("@me"))
        assertEquals("app/dependabot", IssueQuery.login("app/dependabot"))
    }

    @Test fun removingAndClearingFiltersKeepsScopeStateAndSort() {
        val q = base.copy(pulls = true, state = IssueStateFilter.All, sort = IssueSort.Oldest, labels = listOf("a", "b"), author = "x", text = "t", draft = true)
        val label = q.activeFilters().first { it.kind == FilterKind.Label && it.value == "a" }
        assertEquals(listOf("b"), q.without(label).labels)
        val cleared = q.cleared()
        assertEquals(IssueQuery(pulls = true, repo = "mona/repo", state = IssueStateFilter.All, sort = IssueSort.Oldest), cleared)
        assertFalse(cleared.hasFilters)
        assertEquals(listOf("作者：x", "标签：a", "标签：b", "仅草稿", "“t”"), q.activeFilters().map { it.text })
    }

    @Test fun normalizationDropsBlanksAndBoundsInput() {
        val q = IssueQuery(repo = "not a repo", author = "  ", labels = listOf(" bug ", "", "bug") + List(20) { "l$it" }, text = "x".repeat(400)).normalized()
        assertNull(q.repo)
        assertNull(q.author)
        assertEquals("bug", q.labels.first())
        assertEquals(IssueQuery.MAX_LABELS, q.labels.size)
        assertEquals(IssueQuery.MAX_TEXT, q.text.length)
    }

    @Test fun keysDifferForDifferentQueriesAndSortMapsToApiParameters() {
        assertNotEquals(base.key(), base.copy(sort = IssueSort.Oldest).key())
        assertEquals(base.key(), base.copy().key())
        assertEquals("created" to "asc", IssueSort.Oldest.field to IssueSort.Oldest.order)
        assertEquals("comments", IssueSort.Comments.field)
    }
}
