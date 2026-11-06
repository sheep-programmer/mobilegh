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

    @Test fun githubSearchLinksOpenNativeSearchWithTheirTypeAndQuery() {
        assertEquals(Screen.Search(com.mobilegh.data.SearchType.REPO, "okhttp"), Links.route("https://github.com/search?q=okhttp&type=repositories"))
        assertEquals(Screen.Search(com.mobilegh.data.SearchType.CODE, "fun main"), Links.route("https://github.com/search?q=fun+main&type=code"))
        assertEquals(Screen.Search(com.mobilegh.data.SearchType.PR, "is:open author:me"), Links.route("https://github.com/search?type=pullrequests&q=is%3Aopen%20author%3Ame"))
        assertEquals(Screen.Search(com.mobilegh.data.SearchType.REPO, "kotlin"), Links.route("https://github.com/search?q=kotlin"))
        // 没有关键词的搜索页交给浏览器
        assertNull(Links.route("https://github.com/search"))
        assertNull(Links.route("https://github.com/search?q=+&type=code"))
        // 从外部打开（Intent / 分享）时也要保留关键词
        assertEquals(Screen.Search(com.mobilegh.data.SearchType.USER, "retrofit"), Links.inputLink("https://github.com/search?q=retrofit&type=users")?.target)
    }

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
        assertEquals(Screen.IssueDetail("octocat", "Hello-World", 42, false, 1L), Links.fromInput("github.com/octocat/Hello-World/issues/42#issuecomment-1"))
        assertEquals(Screen.PullFiles("octocat", "Hello-World", 7), Links.fromInput("https://github.com/octocat/Hello-World/pull/7/files"))
        assertEquals(Screen.Blob("octocat", "Hello-World", "main/src/a+b.kt"), Links.fromInput("https://github.com/octocat/Hello-World/blob/main/src/a+b.kt"))
        assertEquals(Screen.Blob("octocat", "Hello-World", "main/文档.md"), Links.fromInput("https://raw.githubusercontent.com/octocat/Hello-World/main/%E6%96%87%E6%A1%A3.md"))
        assertEquals(Screen.Profile("octocat"), Links.fromInput("https://github.com/octocat"))
    }

    @Test fun releaseLinksOpenTheVersionTheyNameNotTheLatestOne() {
        val repo = "https://github.com/CKopoer/BiliSail"
        assertEquals(Screen.Releases("CKopoer", "BiliSail", "v1.2.4+1"), Links.fromInput("$repo/releases/tag/v1.2.4%2B1"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail", "v1.2.4+1"), Links.fromInput("$repo/releases/tag/v1.2.4+1"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail", "release/1.0"), Links.fromInput("$repo/releases/tag/release%2F1.0"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail", "v1.2.4+1"), Links.fromInput("$repo/releases/download/v1.2.4%2B1/app.apk"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail", "latest"), Links.fromInput("$repo/releases/latest"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail"), Links.fromInput("$repo/releases"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail"), Links.fromInput("$repo/tags"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail", "v2"), Links.fromInput("https://api.github.com/repos/CKopoer/BiliSail/releases/tags/v2"))
        assertEquals(Screen.Releases("CKopoer", "BiliSail", "latest"), Links.fromInput("https://api.github.com/repos/CKopoer/BiliSail/releases/latest"))
    }

    @Test fun issueCommentAnchorsSurvivePastingAndRouting() {
        val expected = Screen.IssueDetail("CKopoer", "BiliSail", 69, false, 6663984777L)
        // 粘贴的链接（剪贴板、分享、Markdown）与直接路由都要保留评论位置
        assertEquals(expected, Links.route("https://github.com/CKopoer/BiliSail/issues/69#issuecomment-6663984777"))
        assertEquals(expected, Links.fromInput("https://github.com/CKopoer/BiliSail/issues/69#issuecomment-6663984777"))
        assertEquals(expected, Links.inputLink("https://github.com/CKopoer/BiliSail/issues/69?notification_referrer_id=1#issuecomment-6663984777")?.target)
        // 外部打开时 InputLink.url 会被再次解析：解析结果必须与第一次一致，锚点不能丢
        Links.inputLink("https://github.com/CKopoer/BiliSail/issues/69#issuecomment-6663984777")!!.let { link ->
            assertEquals(link.target, Links.fromInput(link.url))
        }
        Links.inputLink("https://github.com/octocat/Hello-World/blob/main/a.kt#L10-L20")!!.let { link ->
            assertEquals(Screen.Blob("octocat", "Hello-World", "main/a.kt", 10..20), Links.fromInput(link.url))
        }
        assertEquals(expected, Links.sharedLink("看这条 https://github.com/CKopoer/BiliSail/issues/69#issuecomment-6663984777 ：")?.target)
        assertEquals(expected.copy(isPull = true), Links.route("https://github.com/CKopoer/BiliSail/pull/69#issuecomment-6663984777"))
        // 没有评论锚点的 Issue 链接不带评论 id
        assertNull(Links.route("https://github.com/CKopoer/BiliSail/issues/69")?.let { (it as Screen.IssueDetail).commentId })
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
        assertEquals(repo, Links.repositoryInText("https://github.com/octocat/Hello-World/wiki")?.repository)
        assertEquals(Screen.Wiki("octocat", "Hello-World"), Links.repositoryInText("https://github.com/octocat/Hello-World/wiki")?.target)
    }

    @Test fun wikiPagesStayInTheAppButEditingPagesOpenInTheBrowser() {
        assertEquals(Screen.Wiki("octocat", "Hello-World"), Links.route("https://github.com/octocat/Hello-World/wiki"))
        assertEquals(Screen.Wiki("octocat", "Hello-World"), Links.route("https://github.com/octocat/Hello-World/wiki/"))
        assertEquals(Screen.Wiki("octocat", "Hello-World", "Home"), Links.route("https://github.com/octocat/Hello-World/wiki/Home"))
        assertEquals(Screen.Wiki("octocat", "Hello-World", "Guide/Install"), Links.route("https://github.com/octocat/Hello-World/wiki/Guide/Install"))
        assertEquals(Screen.Wiki("octocat", "Hello-World", "中文 页面"), Links.route("https://github.com/octocat/Hello-World/wiki/%E4%B8%AD%E6%96%87%20%E9%A1%B5%E9%9D%A2"))
        // 新建、编辑、历史等操作页在应用外完成
        assertNull(Links.route("https://github.com/octocat/Hello-World/wiki/_new"))
        assertNull(Links.route("https://github.com/octocat/Hello-World/wiki/_edit/Home"))
        assertNull(Links.route("https://github.com/octocat/Hello-World/wiki/_history"))
        // 从剪贴板 / 分享进入时同样解析到 wiki 页面
        assertEquals(Screen.Wiki("octocat", "Hello-World", "Home"), Links.inputLink("https://github.com/octocat/Hello-World/wiki/Home")?.target)
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
