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

    @Test fun enterpriseRepoIssueAndPullLinksReuseNativeRoutes() {
        val enterprise = "https://corp.ghe.com/octocat/Hello-World"
        val apiHost = "api.corp.ghe.com"
        assertEquals(Screen.Repo("octocat", "Hello-World"), Links.route(enterprise, apiHost))
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), Links.route("$enterprise/issues/42", apiHost))
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 7, true), Links.route("$enterprise/pull/7", apiHost))
        assertEquals(Screen.PullFiles("octocat", "Hello-World", 7), Links.route("$enterprise/pull/7/files", apiHost))
    }

    @Test fun enterpriseApiFormUrlsReuseNativeApiRoutes() {
        val api = "https://api.corp.ghe.com"
        assertEquals(Screen.Repo("octocat", "Hello-World"), Links.route("$api/repos/octocat/Hello-World", "api.corp.ghe.com"))
        assertEquals(
            Screen.IssueDetail("octocat", "Hello-World", 42, false),
            Links.route("$api/repos/octocat/Hello-World/issues/42", "api.corp.ghe.com"),
        )
        assertEquals(
            Screen.IssueDetail("octocat", "Hello-World", 42, false),
            Links.route("$api/repos/octocat/Hello-World/issues/42", "corp.ghe.com"),
        )
        assertEquals(Screen.Profile("octocat"), Links.route("$api/users/octocat", "api.corp.ghe.com"))
    }

    @Test fun enterpriseApiHostMatchesAnAccountConfiguredWithTheWebHost() {
        val url = "https://api.corp.ghe.com/repos/octocat/Hello-World"
        org.junit.Assert.assertTrue(Links.enterpriseHostAllowed(url, "corp.ghe.com"))
        org.junit.Assert.assertFalse(Links.enterpriseHostAllowed(url, "api.other.ghe.com"))
        org.junit.Assert.assertFalse(Links.enterpriseHostAllowed(url, "api.github.com"))
    }

    @Test fun enterpriseWebHostStillUsesWebRoutesWhenTheAccountUsesTheWebHost() {
        // 账号的 API Host 被配置成 Web Host 时，Web 链接不能被当成 API 路径
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), Links.route("https://corp.ghe.com/octocat/Hello-World/issues/42", "corp.ghe.com"))
    }

    @Test fun enterpriseHostMatchingIgnoresCaseAndAcceptsTheApiPrefix() {
        val url = "HTTPS://CORP.GHE.COM:443/octocat/Hello-World"
        assertEquals("corp.ghe.com", Links.enterpriseHost(url))
        org.junit.Assert.assertTrue(Links.enterpriseHostAllowed(url, "CORP.GHE.COM"))
        org.junit.Assert.assertTrue(Links.enterpriseHostAllowed(url, "api.corp.ghe.com"))
        org.junit.Assert.assertFalse(Links.enterpriseHostAllowed(url, "api.other.ghe.com"))
        org.junit.Assert.assertFalse(Links.enterpriseHostAllowed(url, "api.github.com"))
    }

    @Test fun enterpriseRecognitionRejectsLookalikesInsecureSchemesAndPorts() {
        listOf(
            "https://ghe.com/octocat/Hello-World",
            "https://ghe.com.evil.test/octocat/Hello-World",
            "https://evilghe.com/octocat/Hello-World",
            "https://corp.ghe.com.evil.test/octocat/Hello-World",
            "http://corp.ghe.com/octocat/Hello-World",
            "https://corp.ghe.com:8443/octocat/Hello-World",
            "https://corp.ghe.com@evil.test/octocat/Hello-World",
        ).forEach {
            assertNull(it, Links.enterpriseHost(it))
            assertNull(it, Links.route(it, "api.corp.ghe.com"))
        }
    }

    @Test fun enterpriseRoutingRequiresTheActiveAccountHost() {
        val url = "https://corp.ghe.com/octocat/Hello-World/issues/42"
        assertNull(Links.route(url))
        assertNull(Links.route(url, "api.github.com"))
        assertNull(Links.route(url, "api.other.ghe.com"))
        assertNull(Links.fromInput(url, "api.other.ghe.com"))
        assertEquals(
            Screen.IssueDetail("octocat", "Hello-World", 42, false),
            Links.fromInput(url, "api.corp.ghe.com"),
        )
    }

    @Test fun enterpriseViewIntentsKeepTheUrlForTheAccountGuard() {
        val url = "https://corp.ghe.com/octocat/Hello-World/issues/42"
        assertEquals(url, IncomingLinks.enterpriseUrl("android.intent.action.VIEW", url))
        assertNull(IncomingLinks.enterpriseUrl("android.intent.action.SEND", url))
        assertNull(IncomingLinks.enterpriseUrl("android.intent.action.VIEW", repo))
        assertNull(IncomingLinks.enterpriseUrl("android.intent.action.VIEW", "https://ghe.com.evil.test/octocat/Hello-World"))
    }

    @Test fun configuredEnterpriseViewIntentUsesTheActiveAccountHost() {
        val url = "https://git.example.corp/octo/repo/issues/7"
        assertEquals(
            Screen.IssueDetail("octo", "repo", 7, false),
            IncomingLinks.view(url, "api.git.example.corp")?.target,
        )
        assertNull(IncomingLinks.view(url, "api.other.example.corp"))
    }

    @Test fun publicGithubAndGistRoutesDoNotDependOnTheEnterpriseHost() {
        assertEquals(Screen.Repo("octocat", "Hello-World"), Links.route(repo, "api.corp.ghe.com"))
        assertEquals(Screen.GistDetail("abcd"), Links.route("https://gist.github.com/octocat/abcd", "api.corp.ghe.com"))
    }

    @Test fun configuredNonGheEnterpriseHostsAcceptWebApiAndBareInputs() {
        val expected = Screen.IssueDetail("octo", "repo", 7, false)
        listOf("git.example.corp", "api.git.example.corp", "GIT.EXAMPLE.CORP", "https://git.example.corp:443").forEach { active ->
            assertEquals(active, expected, Links.fromInput("git.example.corp/octo/repo/issues/7", active))
            assertEquals(active, expected, Links.route("HTTPS://GIT.EXAMPLE.CORP:443/octo/repo/issues/7", active))
            assertEquals(active, expected, Links.route("https://api.git.example.corp/repos/octo/repo/issues/7", active))
        }
    }

    @Test fun publicApiRoutesRemainIndependentOfAnUnrelatedEnterpriseAccount() {
        val active = "api.git.example.corp"
        assertEquals(Screen.IssueDetail("o", "r", 1, false), Links.route("https://api.github.com/repos/o/r/issues/1", active))
        assertEquals(Screen.IssueDetail("o", "r", 2, true), Links.route("https://api.github.com/repos/o/r/pulls/2", active))
        assertEquals(Screen.Releases("o", "r", releaseId = 3), Links.route("https://api.github.com/repos/o/r/releases/3", active))
        assertEquals(Screen.Profile("o"), Links.route("https://api.github.com/users/o", active))
        assertEquals(Screen.IssueDetail("o", "r", 1, false), Links.fromInput("api.github.com/repos/o/r/issues/1", active))
        assertEquals(Screen.Repo("o", "r"), Links.fromInput("github.com/o/r", active))
    }

    @Test fun enterpriseTrustRejectsUnconfiguredMalformedAndConfusingHosts() {
        val active = "git.example.corp"
        listOf(
            "https://other.example.corp/octo/repo/issues/7",
            "https://evilgit.example.corp/octo/repo/issues/7",
            "http://git.example.corp/octo/repo/issues/7",
            "https://user@git.example.corp/octo/repo/issues/7",
            "https://git.example.corp:8443/octo/repo/issues/7",
            "https://git.example.corp.evil.test/octo/repo/issues/7",
            "https:///octo/repo/issues/7",
            "https://api.api.git.example.corp/repos/octo/repo/issues/7",
        ).forEach { url ->
            assertNull(url, Links.route(url, active))
            assertNull(url, Links.fromInput(url.removePrefix("https://"), active))
        }
        assertNull(Links.route("https://git.example.corp/octo/repo/issues/7", "https://user@git.example.corp"))
        assertNull(Links.route("https://git.example.corp/octo/repo/issues/7", "http://git.example.corp"))
        assertNull(Links.route("https://git.example.corp/octo/repo/issues/7", "git.example.corp:8443"))
    }
}
