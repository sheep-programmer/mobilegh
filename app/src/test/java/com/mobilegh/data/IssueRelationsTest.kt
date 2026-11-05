package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IssueRelationsTest {
    private val issue = Issue(number = 31, title = "Crash on start", repositoryUrl = "https://api.github.com/repos/octo/hello")

    @Test fun repositoryComesFromTheIssueItself() {
        assertEquals("octo" to "hello", relatedIssueRepo(issue, "other", "repo"))
    }

    @Test fun sameRepositoryShowsTheShortNumber() {
        // 仓库名大小写不影响判断（GitHub 的 owner/name 不区分大小写）
        assertEquals("#31", relatedIssueLabel(issue, "Octo", "Hello"))
    }

    @Test fun otherRepositoryShowsOwnerNameAndNumber() {
        assertEquals("octo/hello#31", relatedIssueLabel(issue, "mobilegh", "app"))
    }

    @Test fun missingOrBrokenRepositoryUrlFallsBackToTheCurrentRepository() {
        // 回退到当前仓库，按同仓库显示短编号
        assertEquals("#31", relatedIssueLabel(issue.copy(repositoryUrl = null), "current", "repo"))
        assertEquals("#31", relatedIssueLabel(issue.copy(repositoryUrl = ""), "current", "repo"))
        assertEquals("#31", relatedIssueLabel(issue.copy(repositoryUrl = "https://example.com/octo"), "current", "repo"))
        assertEquals("#31", relatedIssueLabel(issue.copy(repositoryUrl = "https://api.github.com/repos/octo/"), "current", "repo"))
    }

    @Test fun trailingSlashIsIgnored() {
        assertEquals("octo/hello", relatedIssueRepo(issue.copy(repositoryUrl = "https://api.github.com/repos/octo/hello/"), "x", "y").let { "${it.first}/${it.second}" })
    }
}
