package com.mobilegh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    @Test fun sharedUrlsKeepTheirNativeTargetAndDropSessionQueryParameters() {
        assertEquals(Screen.Profile("octocat"), Links.sharedLink("作者主页：https://github.com/octocat")?.target)
        assertEquals("https://github.com/octocat/Hello-World", Links.inputLink("https://github.com/octocat/Hello-World?tab=readme#top")?.url)
        assertNull(Links.sharedLink("https://evil.example/?redirect=https://github.com/octocat/Hello-World"))
    }
    private val repo = Screen.Repo("octocat", "Hello-World")

    @Test fun repositoryAddressesAndCloneUrlsOpenTheSameNativeRepository() {
        listOf(
            " https://github.com/octocat/Hello-World/ ",
            "http://www.github.com/octocat/Hello-World?tab=readme#readme",
            "github.com/octocat/Hello-World",
            "HTTPS://GITHUB.COM/octocat/Hello-World.git",
            "git@github.com:octocat/Hello-World.git",
            "ssh://git@github.com/octocat/Hello-World.git",
            "octocat/Hello-World",
            "<https://github.com/octocat/Hello-World>",
            "[仓库](https://github.com/octocat/Hello-World)",
        ).forEach { assertEquals(it, repo, Links.fromInput(it)) }
    }

    @Test fun deepLinksKeepTheirDestinationRatherThanOpeningTheRepositoryHome() {
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), Links.fromInput("github.com/octocat/Hello-World/issues/42#issuecomment-1"))
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 7, true), Links.fromInput("https://github.com/octocat/Hello-World/pull/7/files"))
        assertEquals(Screen.FileView("octocat", "Hello-World", "src/a+b.kt", "main"), Links.fromInput("https://github.com/octocat/Hello-World/blob/main/src/a+b.kt"))
        assertEquals(Screen.FileView("octocat", "Hello-World", "文档.md", "main"), Links.fromInput("https://raw.githubusercontent.com/octocat/Hello-World/main/%E6%96%87%E6%A1%A3.md"))
        assertEquals(Screen.Releases("octocat", "Hello-World"), Links.fromInput("https://github.com/octocat/Hello-World/releases/tag/v1"))
        assertEquals(Screen.Profile("octocat"), Links.fromInput("https://github.com/octocat"))
    }

    @Test fun searchQualifiersAndOrdinaryWordsRemainSearchQueries() {
        listOf("language:kotlin stars:>100", "repo:octocat/Hello-World crash", "hello world", "kotlin", "", "   ")
            .forEach { assertNull(it, Links.fromInput(it)) }
    }

    @Test fun clipboardExtractsRepositoryFromSharedTextMarkdownAndMultipleLines() {
        listOf(
            "看看这个：https://github.com/octocat/Hello-World，挺好用",
            "[仓库](https://github.com/octocat/Hello-World)",
            "Repository: <https://github.com/octocat/Hello-World>.",
            "clone: git@github.com:octocat/Hello-World.git",
            "https://github.com/octocat\nhttps://github.com/octocat/Hello-World",
            "https://github.com/settings/security\nhttps://github.com/octocat/Hello-World",
        ).forEach { assertEquals(it, repo, Links.repositoryInText(it)?.repository) }
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false), Links.repositoryInText("See https://github.com/octocat/Hello-World/issues/42.")?.target)
        assertEquals(repo, Links.repositoryInText("https://api.github.com/repos/octocat/Hello-World")?.repository)
        assertEquals(repo, Links.repositoryInText("https://github.com/octocat/Hello-World/wiki")?.target)
    }

    @Test fun unrelatedDomainsProfilesSettingsAndMalformedUrlsAreIgnored() {
        listOf(
            "https://github.com", "https://github.com/octocat", "https://github.com/settings/security",
            "https://github.com/login/oauth/authorize?client_id=123", "https://github.com/orgs/octocat",
            "https://gist.github.com/octocat/abcd", "https://github.com.evil.example/octocat/Hello-World",
            "https://evil.example/github.com/octocat/Hello-World", "https://evil.example/?url=https://github.com/octocat/Hello-World",
            "https://github.com@evil.example/octocat/Hello-World", "https://evil.example@github.com/octocat/Hello-World",
            "ftp://github.com/octocat/Hello-World", "https://github.com:123/octocat/Hello-World",
            "https://github.com/octocat/%", "https://github.com/octocat/%2Fbad", "https://github.com/octocat/..",
            "https://github.com//Hello-World", "mygithub.com/octocat/Hello-World",
        ).forEach { assertNull(it, Links.repositoryInText(it)) }
        assertNull(Links.route("https://github.com/octocat/%"))
    }
}
