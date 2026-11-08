package com.mobilegh.nav

import com.mobilegh.data.SearchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksRoutingTest {
    private val repo = "https://github.com/octocat/Hello-World"

    @Test fun blobTreeAndRawLinksKeepBranchAndPathForResolution() {
        assertEquals(Screen.Blob("octocat", "Hello-World", "feature/x/src/a.kt", 3..3), Links.route("$repo/blob/feature/x/src/a.kt#L3"))
        assertEquals(Screen.Blob("octocat", "Hello-World", "main/docs"), Links.route("$repo/tree/main/docs"))
        assertEquals(Screen.Blob("octocat", "Hello-World", "main"), Links.route("$repo/blob/main"))
        assertEquals(Screen.Blob("octocat", "Hello-World", "main/a.md"), Links.route("$repo/raw/main/a.md"))
        assertEquals(
            Screen.Blob("octocat", "Hello-World", "refs/heads/main/a.md"),
            Links.route("https://raw.githubusercontent.com/octocat/Hello-World/refs/heads/main/a.md"),
        )
        assertEquals(Screen.Repo("octocat", "Hello-World"), Links.route("$repo/blob"))
    }

    @Test fun issueListsAndNewIssueOpenTheRightPage() {
        assertEquals(Screen.NewIssue("octocat", "Hello-World"), Links.route("$repo/issues/new"))
        assertEquals(Screen.Issues("octocat", "Hello-World", false), Links.route("$repo/issues"))
        assertEquals(
            Screen.Search(SearchType.ISSUE, "repo:octocat/Hello-World is:open label:bug"),
            Links.route("$repo/issues?q=is%3Aopen+label%3Abug"),
        )
        assertEquals(Screen.Search(SearchType.PR, "repo:octocat/Hello-World is:open"), Links.route("$repo/pulls?q=is%3Aopen"))
    }

    @Test fun mergeQueuePageOpensInTheApp() {
        // 网页 /owner/repo/queue 是合并队列（不是 Actions 产物）
        assertEquals(Screen.MergeQueue("rust-lang", "rust-clippy"), Links.route("https://github.com/rust-lang/rust-clippy/queue"))
        assertNull(Links.route("$repo/queue/extra"))
    }

    @Test fun securityAndSettingsRootsOpenInTheApp() {
        assertEquals(Screen.SecurityAlerts("octocat", "Hello-World"), Links.route("$repo/security"))
        assertEquals(Screen.SecurityAlerts("octocat", "Hello-World"), Links.route("$repo/security/dependabot"))
        assertNull(Links.route("$repo/security/advisories"))
        assertEquals(Screen.RepoSettings("octocat", "Hello-World"), Links.route("$repo/settings"))
        assertNull(Links.route("$repo/settings/collaboration"))
    }

    @Test fun commitLinksIgnoreExtensionsAndReleaseTagsKeepTheirSlashes() {
        assertEquals(Screen.CommitDetail("octocat", "Hello-World", "abc1234"), Links.route("$repo/commit/abc1234.patch"))
        assertEquals(Screen.CommitDetail("octocat", "Hello-World", "abc1234"), Links.route("$repo/commit/abc1234#diff-0"))
        assertEquals(Screen.Releases("octocat", "Hello-World", "feature/x"), Links.route("$repo/releases/tag/feature/x"))
        assertEquals(Screen.Releases("octocat", "Hello-World", "release/1.0"), Links.route("$repo/releases/tag/release%2F1.0"))
    }

    @Test fun apiReleaseLinksOpenTheReleaseTheyName() {
        assertEquals(
            Screen.Releases("octocat", "Hello-World", releaseId = 42L),
            Links.fromInput("https://api.github.com/repos/octocat/Hello-World/releases/42"),
        )
        assertEquals(
            Screen.Releases("octocat", "Hello-World", "v2"),
            Links.fromInput("https://api.github.com/repos/octocat/Hello-World/releases/tags/v2"),
        )
    }

    @Test fun reviewAndCommentAnchorsSelectTheRightPlace() {
        // PR 的审查（pullrequestreview-N）与普通评论一样，记下 id 以便滚动到那一条
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 7, true, 99L), Links.route("$repo/pull/7#pullrequestreview-99"))
        // 行内审查评论在文件变更页里，直接进入那里
        assertEquals(Screen.PullFiles("octocat", "Hello-World", 7), Links.route("$repo/pull/7#discussion_r123"))
        // 无关的页内锚点不影响打开的页面
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 7, true, null), Links.route("$repo/pull/7#top"))
    }

    @Test fun userAttachmentsAndFileDownloadLinksDoNotRouteToRepo() {
        assertNull(Links.route("https://github.com/user-attachments/files/34540973/ios-unsigned.zip"))
        assertNull(Links.route("https://github.com/user-attachments/assets/12345"))
        assertNull(Links.route("https://github.com/octocat/Hello-World/files/34540973/test.zip"))
    }
}
