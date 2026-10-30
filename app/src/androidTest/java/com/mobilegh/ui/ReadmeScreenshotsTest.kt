package com.mobilegh.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.Net
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.i18n.AppLanguage
import com.mobilegh.i18n.I18n
import com.mobilegh.nav.Loader
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.Pager
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.ui.screens.SearchFilterDialog
import com.mobilegh.ui.screens.StarListsScreen
import com.mobilegh.ui.screens.StarredRepoPage
import com.mobilegh.ui.theme.MobileGhTheme
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * 生成 README 用的截图。全部数据都是本机虚构的测试账号（mona / octocat / hubot），
 * 网络层被替换成本地假数据，不联网、不读取任何真实账号；头像是程序画的彩色字母头像。
 *
 * 只有传入 `-e readmeShots true` 时才执行，平常跑测试会跳过。用 tools/readme_screenshots.sh 一键生成。
 * 语言通过 `-e lang zh|en` 指定，图片写入应用缓存目录的 readme/<lang>/ 下。
 */
@RunWith(AndroidJUnit4::class)
class ReadmeScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var nav: Navigator? = null
    private val args = InstrumentationRegistry.getArguments()
    private val lang get() = args.getString("lang") ?: "zh"

    private fun ago(h: Int) = Instant.now().minusSeconds(h * 3600L).toString()

    private fun user(login: String, name: String = login, id: Int = 1, bio: String = "Octocat enthusiast. Building things on GitHub.") =
        """{"login":"$login","id":$id,"name":"$name","type":"User","avatar_url":"https://avatars.githubusercontent.com/u/$id?v=4","bio":"$bio","company":"@github","location":"San Francisco","followers":128,"following":42,"public_repos":31,"created_at":"2015-03-01T00:00:00Z"}"""

    private fun repo(owner: String, name: String, desc: String, lang: String?, stars: Int, forks: Int = 0, priv: Boolean = false, h: Int = 3, ownerId: Int = 1) =
        """{"id":${name.hashCode()},"name":"$name","full_name":"$owner/$name","private":$priv,"owner":${user(owner, id = ownerId)},"description":"$desc","language":${lang?.let { "\"$it\"" } ?: "null"},"stargazers_count":$stars,"watchers_count":$stars,"forks_count":$forks,"open_issues_count":7,"default_branch":"main","pushed_at":"${ago(h)}","updated_at":"${ago(h)}","html_url":"https://github.com/$owner/$name","permissions":{"admin":true,"push":true,"pull":true},"license":{"name":"MIT License","spdx_id":"MIT"},"topics":["android","kotlin","compose","github"],"has_discussions":true}"""

    private val repos by lazy {
        listOf(
            repo("mona", "mobilegh", "A GitHub client for Android built with Jetpack Compose", "Kotlin", 1280, 56),
            repo("mona", "secret-notes", "Private notes and drafts", "Python", 3, 0, true, 26),
            repo("octocat", "Hello-World", "My first repository on GitHub!", null, 2300, 1900, false, 80, 2),
            repo("mona", "dotfiles", "Config files for my machines", "Shell", 41, 4, false, 200),
            repo("hubot", "chatops", "A customizable life embetterment robot", "JavaScript", 17800, 3500, false, 40, 3),
        )
    }

    private fun issue(n: Int, title: String, state: String = "open", pr: Boolean = false, comments: Int = 2) =
        """{"id":$n,"number":$n,"title":"$title","state":"$state","user":${user("mona")},"comments":$comments,"created_at":"${ago(5)}","updated_at":"${ago(2)}","labels":[{"name":"bug","color":"d73a4a"},{"name":"good first issue","color":"7057ff"}],${if (pr) "\"pull_request\":{\"url\":\"x\"}," else ""}"html_url":"https://github.com/mona/mobilegh/issues/$n","body":"Steps to reproduce:\n\n1. Open the app\n2. Tap the list"}"""

    private fun notification(i: Int, title: String, type: String, reason: String, unread: Boolean) =
        """{"id":"$i","unread":$unread,"reason":"$reason","updated_at":"${ago(i)}","subject":{"title":"$title","type":"$type","url":"https://api.github.com/repos/mona/mobilegh/issues/$i"},"repository":${repo("mona", "mobilegh", "x", "Kotlin", 1)}}"""

    private fun event(i: Int, type: String, actor: String, repoName: String, payload: String) =
        """{"id":"$i","type":"$type","actor":{"login":"$actor","avatar_url":"https://avatars.githubusercontent.com/u/${i + 10}?v=4"},"repo":{"name":"$repoName"},"payload":$payload,"created_at":"${ago(i)}"}"""

    private val patch = """@@ -1,7 +1,9 @@\n package app\n \n-import old.Thing\n+import new.Thing\n+import new.Other\n \n fun main() {\n-    println(\"old value that is quite long and wraps around\")\n+    println(\"hello\")\n+    run()\n }"""

    private fun route(method: String, path: String, accept: String?, body: String): String {
        val p = path
        return when {
            p == "/user" -> user("mona", "Mona Lisa")
            p == "/graphql" -> if (body.contains("MobileGHLists(")) listsGraphql() else graphql()
            p.startsWith("/user/repos") || p.endsWith("/repos") -> "[${repos.joinToString(",")}]"
            p.startsWith("/user/starred") || p.endsWith("/starred") -> "[${repos.joinToString(",")}]"
            p.startsWith("/notifications") -> "[" + listOf(
                notification(1, "Crash when opening the repository list", "Issue", "mention", true),
                notification(2, "feat: upload files and folders as one commit", "PullRequest", "review_requested", true),
                notification(3, "Release v0.27.9", "Release", "subscribed", false),
                notification(4, "CI failed on main", "CheckSuite", "ci_activity", false),
            ).joinToString(",") + "]"
            p.endsWith("/received_events") || p.endsWith("/events") -> "[" + listOf(
                event(1, "WatchEvent", "octocat", "mona/mobilegh", "{}"),
                event(2, "PushEvent", "mona", "mona/mobilegh", """{"ref":"refs/heads/main","size":2,"commits":[{"message":"fix: harden file and folder uploads"},{"message":"docs: update"}]}"""),
                event(3, "IssuesEvent", "hubot", "mona/mobilegh", """{"action":"opened","issue":{"number":42,"title":"Crash when opening the repository list"}}"""),
                event(4, "ForkEvent", "octocat", "mona/dotfiles", """{"forkee":{"full_name":"octocat/dotfiles"}}"""),
                event(6, "ReleaseEvent", "mona", "mona/mobilegh", """{"release":{"tag_name":"v0.27.9","name":"MobileGH v0.27.9"}}"""),
            ).joinToString(",") + "]"
            p == "/search/issues" -> """{"total_count":3,"items":[${issue(42, "Crash when opening the repository list")},${issue(7, "feat: upload files and folders", pr = true)},${issue(5, "Add dark mode support", "closed", comments = 0)}]}"""
            p == "/search/repositories" -> """{"total_count":5,"items":[${repos.joinToString(",")}]}"""
            p.matches(Regex("/repos/[^/]+/[^/]+")) -> repos[0]
            p.matches(Regex("/repos/[^/]+/[^/]+/issues/\\d+")) -> issue(42, "Crash when opening the repository list").replace("\"comments\":2", "\"comments\":2,\"body_html\":\"<p>Steps to reproduce:</p><ol><li>Open the app</li><li>Tap the list</li></ol><p>Expected: no crash.</p>\",\"author_association\":\"OWNER\"")
            p.matches(Regex("/repos/[^/]+/[^/]+/issues/\\d+/comments")) -> """[{"id":1,"user":${user("octocat", "The Octocat", 2)},"body":"I can reproduce this on v0.27.8.","body_html":"<p>I can reproduce this on v0.27.8.</p>","created_at":"${ago(4)}","author_association":"CONTRIBUTOR"},{"id":2,"user":${user("mona")},"body":"Fixed in main.","body_html":"<p>Fixed in <code>main</code>.</p>","created_at":"${ago(1)}","author_association":"OWNER"}]"""
            p.matches(Regex("/repos/[^/]+/[^/]+/issues/\\d+/(timeline|events)")) -> "[]"
            p.endsWith("/issues") -> "[${issue(42, "Crash when opening the repository list")},${issue(41, "Add dark mode support", "closed", comments = 0)}]"
            p.endsWith("/pulls") -> "[${issue(7, "feat: upload files and folders", pr = true)}]"
            p.matches(Regex("/repos/[^/]+/[^/]+/compare/.+")) -> """{"status":"diverged","ahead_by":3,"behind_by":1,"total_commits":3,"commits":[{"sha":"b7c9443a1f2e3d4c5b6a79880a1b2c3d4e5f6071","commit":{"message":"fix: harden file and folder uploads","author":{"name":"mona","date":"${ago(3)}"}},"author":${user("mona")}}],"files":[{"filename":"app/src/Main.kt","status":"modified","additions":5,"deletions":3,"changes":8,"sha":"x1","patch":"$patch"},{"filename":"README.md","status":"added","additions":2,"deletions":0,"changes":2,"sha":"x2","patch":"@@ -0,0 +1,2 @@\n+# mobilegh\n+GitHub client"}]}"""
            p.matches(Regex("/repos/[^/]+/[^/]+/commits/[^/]+")) -> """{"sha":"b7c9443a1f2e3d4c5b6a79880a1b2c3d4e5f6071","commit":{"message":"fix: harden file and folder uploads","author":{"name":"mona","date":"${ago(3)}"},"committer":{"name":"mona","date":"${ago(3)}"}},"author":${user("mona")},"committer":${user("mona")},"stats":{"total":8,"additions":5,"deletions":3},"parents":[{"sha":"a1b2c3d"}],"files":[{"filename":"app/src/Main.kt","status":"modified","additions":5,"deletions":3,"changes":8,"sha":"x1","patch":"$patch"},{"filename":"README.md","status":"added","additions":2,"deletions":0,"changes":2,"sha":"x2","patch":"@@ -0,0 +1,2 @@\n+# mobilegh\n+GitHub client"}]}"""
            p.endsWith("/branches") -> """[{"name":"main","protected":true,"commit":{"sha":"b7c9443a1f2e3d4c5b6a79880a1b2c3d4e5f6071"}},{"name":"dev","protected":false,"commit":{"sha":"a1"}}]"""
            p.endsWith("/languages") -> """{"Kotlin":812345,"Java":12000,"Shell":3000}"""
            p.endsWith("/orgs") -> "[]"
            p.endsWith("/readme") -> if (accept?.contains("html") == true) "<h1>mobilegh</h1><p>A GitHub client for Android: fast, native and in GitHub's design language.</p><ul><li>Code, issues and pull requests</li><li>Side-by-side diffs</li><li>Git LFS uploads</li></ul>" else """{"name":"README.md","content":"IyBtb2JpbGVnaA==","encoding":"base64","html_url":"x"}"""
            p.endsWith("/traffic/views") -> traffic(1520, 340, 90)
            p.endsWith("/traffic/clones") -> traffic(430, 120, 25)
            p.endsWith("/traffic/popular/referrers") -> """[{"referrer":"github.com","count":120,"uniques":45},{"referrer":"google.com","count":64,"uniques":30}]"""
            p.endsWith("/traffic/popular/paths") -> """[{"path":"/mona/mobilegh","title":"mona/mobilegh","count":300,"uniques":90}]"""
            p.endsWith("/stats/commit_activity") -> "[" + (0 until 52).joinToString(",") { w -> """{"days":[0,1,2,3,1,0,0],"total":${(w * 37) % 23},"week":${1_700_000_000L + w * 604800L}}""" } + "]"
            p.endsWith("/commits") -> """[{"sha":"b7c9443a1f2e3d4c5b6a79880a1b2c3d4e5f6071","commit":{"message":"fix: harden file and folder uploads","author":{"name":"mona","date":"${ago(3)}"}},"author":${user("mona")}}]"""
            p.endsWith("/actions/runs") -> """{"total_count":2,"workflow_runs":[{"id":1,"name":"CI","display_title":"fix: harden file and folder uploads","head_branch":"main","head_sha":"b7c9443a1f2e3d4c5b6a79880a1b2c3d4e5f6071","event":"push","status":"completed","conclusion":"success","run_number":128,"created_at":"${ago(3)}","updated_at":"${ago(3)}","run_started_at":"${ago(3)}","actor":${user("mona")}}]}"""
            p.endsWith("/releases") -> """[{"id":1,"tag_name":"v0.27.9","name":"MobileGH v0.27.9","published_at":"${ago(30)}","author":${user("mona")},"body":"Upload support","assets":[{"id":1,"name":"app-arm64-v8a-release.apk","size":10485760,"download_count":120,"content_type":"application/vnd.android.package-archive"}]}]"""
            p.endsWith("/contents") || p.contains("/contents/") -> """[{"name":"app","path":"app","type":"dir","sha":"1","size":0},{"name":"docs","path":"docs","type":"dir","sha":"2","size":0},{"name":"README.md","path":"README.md","type":"file","sha":"3","size":1234}]"""
            p.endsWith("/contributors") -> "[${user("mona")},${user("octocat", id = 2)}]"
            p.matches(Regex("/users/[^/]+")) -> user("mona", "Mona Lisa")
            else -> if (p.matches(Regex(".*/(topics|tags|labels|milestones|assignees|collaborators|teams|stargazers|forks|subscribers|gists|following|followers)"))) "[]" else "{}"
        }
    }

    private fun traffic(count: Int, uniques: Int, per: Int): String {
        val days = (0 until 14).joinToString(",") { d ->
            val c = per / 3 + (d * 7919) % (per / 2 + 1)
            """{"timestamp":"2026-05-${(d + 1).toString().padStart(2, '0')}T00:00:00Z","count":$c,"uniques":${c / 3 + 1}}"""
        }
        return """{"count":$count,"uniques":$uniques,"views":[$days],"clones":[$days]}"""
    }

    /** 「Star 列表」目录页：三个测试列表，一个私有 */
    private fun listsGraphql(): String {
        fun node(id: String, name: String, desc: String, priv: Boolean, count: Int, h: Int) =
            """{"id":"$id","name":"$name","description":"$desc","isPrivate":$priv,"slug":"$id","createdAt":"${ago(h + 500)}","updatedAt":"${ago(h)}","lastAddedAt":"${ago(h)}","user":{"login":"mona"},"items":{"totalCount":$count}}"""
        val nodes = listOf(
            node("list-1", "Learning Kotlin", "Resources and samples I am following", false, 8, 5),
            node("list-2", "Private reading list", "", true, 128, 30),
            node("list-3", "Tools", "CLI tools I use every day", false, 23, 96),
            node("list-4", "Design inspiration", "Interfaces worth studying", false, 12, 400),
        ).joinToString(",")
        return """{"data":{"viewer":{"login":"mona"},"user":{"lists":{"totalCount":4,"pageInfo":{"hasNextPage":false,"endCursor":null,"hasPreviousPage":false,"startCursor":null},"nodes":[$nodes]}}}}"""
    }

    private fun graphql(): String {
        val weeks = (0 until 53).joinToString(",") { w ->
            "{\"contributionDays\":[" + (0 until 7).joinToString(",") { d ->
                val c = ((w * 7 + d) * 7919) % 9
                "{\"date\":\"2025-${(w / 5 % 12 + 1).toString().padStart(2, '0')}-${(d + 1).toString().padStart(2, '0')}\",\"contributionCount\":$c,\"contributionLevel\":\"${listOf("NONE", "FIRST_QUARTILE", "SECOND_QUARTILE", "THIRD_QUARTILE", "FOURTH_QUARTILE")[c / 2]}\",\"weekday\":$d}"
            } + "]}"
        }
        return """{"data":{"viewer":{"login":"mona","contributionsCollection":{"contributionCalendar":{"totalContributions":1234,"weeks":[$weeks]}},"pinnedItems":{"nodes":[]},"starredRepositories":{"totalCount":340,"nodes":[],"edges":[],"pageInfo":{"hasNextPage":false}}},"user":{"contributionsCollection":{"contributionCalendar":{"totalContributions":1234,"weeks":[$weeks]}},"pinnedItems":{"nodes":[]}}}}"""
    }

    /** 程序画的彩色字母头像：每个用户号固定一种颜色和一个字母 */
    private fun avatar(seed: Int): ByteArray {
        val colors = intArrayOf(0xFF8250DF.toInt(), 0xFF0969DA.toInt(), 0xFF1A7F37.toInt(), 0xFFBF3989.toInt(), 0xFFBC4C00.toInt())
        val letters = " MOHLDKRS" // 用户号 1 = mona(M)，2 = octocat(O)，3 = hubot(H)
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 160f, 160f, Paint().apply { color = colors[seed.mod(colors.size)] })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 90f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText(letters[seed.mod(letters.length)].toString(), 80f, 112f, paint)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Before fun install() {
        assumeTrue("只在 -e readmeShots true 时生成截图", args.getString("readmeShots") == "true")
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val url = req.url
            val resp = if (url.host.contains("avatars")) {
                val seed = url.pathSegments.lastOrNull()?.toIntOrNull() ?: 0
                Response.Builder().body(avatar(seed).toResponseBody("image/png".toMediaType()))
            } else {
                val buf = okio.Buffer().also { req.body?.writeTo(it) }.readUtf8()
                Response.Builder().body(route(req.method, url.encodedPath, req.header("Accept"), buf).toResponseBody("application/json".toMediaType()))
            }
            resp.request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        }.build()
        val f = Api::class.java.getDeclaredField("http\$delegate")
        f.isAccessible = true
        f.set(null, lazyOf(fake))
        Net.apiProxy = ""
        // 模拟器剪贴板里可能残留之前测试复制的链接，会让首页冒出「剪贴板快捷打开」提示，截图前先清空
        compose.runOnUiThread {
            (compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
        }
        I18n.select(if (lang == "en") AppLanguage.English else AppLanguage.Chinese)
        Session.themeMode = 1
        compose.runOnUiThread { Session.signIn("fixture-token", User(login = "mona", name = "Mona Lisa", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4")) }
    }

    @After fun cleanUp() {
        if (args.getString("readmeShots") != "true") return
        compose.runOnUiThread { nav?.dispose(); Session.signOut() }
        I18n.select(AppLanguage.Chinese)
    }

    private fun save(name: String) {
        compose.waitForIdle()
        val dir = File(compose.activity.cacheDir, "readme/$lang").apply { mkdirs() }
        val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 底部弹层是单独的窗口，compose 的根节点截不到；整屏截图再裁掉状态栏 */
    private fun saveScreen(name: String) {
        compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val top = (24 * compose.activity.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(shot, 0, top, shot.width, shot.height - top)
        val dir = File(compose.activity.cacheDir, "readme/$lang").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String, wait: Long = 2500, prepare: (Navigator) -> Unit = {}, after: () -> Unit = {}, setup: (Navigator) -> Unit) {
        val n = Navigator().also { nav = it }
        prepare(n)
        compose.setContent { MobileGhTheme { AppRoot(n) } }
        compose.runOnUiThread { setup(n) }
        Thread.sleep(wait)
        compose.waitForIdle()
        after()
        Thread.sleep(1000)
        save(name)
    }

    private fun tapCharts() {
        val charts = compose.onAllNodesWithTag("chart")
        for (i in charts.fetchSemanticsNodes().indices) {
            charts[i].performTouchInput { click(Offset(width * (0.45f + 0.1f * i), height * 0.75f)) }
        }
    }

    @Test fun home() = shot("01-home", prepare = { n ->
        // 首页的「收藏列表」卡片读取的是这份目录；直接放进首页标签的页面作用域，避免展示读取失败
        val lists = listOf(
            GitHubStarList("list-1", "Learning Kotlin", "Resources and samples I am following", false, "learning", "mona", 8),
            GitHubStarList("list-2", "Private reading list", "", true, "reading", "mona", 128),
            GitHubStarList("list-3", "Tools", "CLI tools I use every day", false, "tools", "mona", 23),
        )
        n.tabEntries.getValue(Tab.Home).bag["star-lists:mona:mona:${Session.generation}"] = Loader(n.root.scope) { lists }.also { it.accept(lists) }
    }) { }
    @Test fun repos() = shot("02-repos") { it.tab = Tab.Repos }
    @Test fun reposFilter() {
        val n = Navigator().also { nav = it }
        n.tab = Tab.Repos
        compose.setContent { MobileGhTheme { AppRoot(n) } }
        Thread.sleep(2500)
        compose.onNodeWithContentDescription(if (lang == "en") "Filter" else "筛选").performClick()
        Thread.sleep(1500)
        saveScreen("13-repos-filter")
    }
    @Test fun searchFilter() {
        val n = Navigator().also { nav = it }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) {
                SearchFilterDialog(0, com.mobilegh.data.SearchFilters(minStars = 100, repoLanguage = "Kotlin"), {}, {})
            } }
        }
        Thread.sleep(1500)
        saveScreen("14-search-filter")
    }
    @Test fun repo() = shot("03-repo") { it.push(Screen.Repo("mona", "mobilegh")) }
    /** 分支选择是底部弹层，单独截整屏（弹层是另一个窗口） */
    @Test fun branchPicker() = shot("03b-branch-picker", after = {
        compose.onAllNodesWithText("main")[0].performClick()
        Thread.sleep(1200)
        compose.waitForIdle()
        saveScreen("03b-branch-picker-sheet")
    }) { it.push(Screen.Repo("mona", "mobilegh")) }
    @Test fun issue() = shot("04-issue") { it.push(Screen.IssueDetail("mona", "mobilegh", 42, false)) }
    @Test fun diff() = shot("05-diff-split", after = {
        compose.onNodeWithText("app/src/Main.kt", substring = true).performClick()
        compose.onNodeWithText("README.md", substring = true).performClick()
    }) { it.push(Screen.CommitDetail("mona", "mobilegh", "b7c9443a1f2e3d4c5b6a79880a1b2c3d4e5f6071")) }
    @Test fun compareScreen() = shot("06-compare", after = {
        compose.onNodeWithText("app/src/Main.kt", substring = true).performClick()
    }) { it.push(Screen.Compare("mona", "mobilegh", "main", "dev")) }
    @Test fun notifications() = shot("07-notifications") { it.tab = Tab.Notifications }
    @Test fun insights() = shot("08-insights", wait = 3500, after = { tapCharts() }) { it.push(Screen.Insights("mona", "mobilegh")) }
    @Test fun settings() = shot("10-settings") { it.push(Screen.Settings) }
    @Test fun login() {
        compose.runOnUiThread { Session.signOut() }
        shot("11-login") { }
    }

    @Test fun starLists() {
        val n = Navigator().also { nav = it }
        val lists = listOf(
            GitHubStarList("list-1", "Learning Kotlin", "Resources and samples I am following", false, "learning", "mona", 8),
            GitHubStarList("list-2", "Private reading list", "", true, "reading", "mona", 128),
            GitHubStarList("list-3", "Tools", "CLI tools I use every day", false, "tools", "mona", 23),
        )
        val items = listOf(
            Repo(1, "mobilegh", "mona/mobilegh", User(login = "mona", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"), description = "A GitHub client for Android", language = "Kotlin", stargazersCount = 1280, forksCount = 56, pushedAt = ago(3)),
            Repo(2, "Hello-World", "octocat/Hello-World", User(login = "octocat", avatarUrl = "https://avatars.githubusercontent.com/u/2?v=4"), description = "My first repository on GitHub!", stargazersCount = 2300, pushedAt = ago(30)),
        )
        n.root.bag["star-lists:mona:mona:${Session.generation}"] = Loader(n.root.scope) { lists }.also { it.accept(lists) }
        n.root.bag["rl-starred:mona:0:true::true"] = Pager<Repo>(n.root.scope, fetch = { _, _ -> emptyList() }).also { it.end = true; it.loadedOnce = true }
        n.root.bag["rl-list:list-1:true"] = Pager<Repo>(n.root.scope, fetch = { _, _ -> items }).also { it.items += items; it.end = true; it.loadedOnce = true }
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) { StarredRepoPage("mona", "") } } }
        Thread.sleep(1500)
        compose.onNodeWithText(if (lang == "en") "Lists" else "列表").performClick()
        Thread.sleep(1000)
        compose.onNodeWithText("Learning Kotlin").performClick()
        Thread.sleep(1500)
        save("09-star-list")
    }

    @Test fun starListsOverview() {
        val n = Navigator().also { nav = it }
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) { StarListsScreen("mona") } } }
        Thread.sleep(2500)
        save("12-star-overview")
    }
}
