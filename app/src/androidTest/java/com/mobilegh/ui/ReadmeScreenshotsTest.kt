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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.Api
import com.mobilegh.data.GitHubStarList
import com.mobilegh.data.ListedRepository
import com.mobilegh.data.Net
import com.mobilegh.data.Repo
import com.mobilegh.data.Session
import com.mobilegh.data.StarListAccount
import com.mobilegh.data.StarLists
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
import com.mobilegh.ui.screens.AddToStarListsDialog
import com.mobilegh.ui.screens.GitHubListEditor
import com.mobilegh.ui.screens.ListEditor
import com.mobilegh.ui.screens.LocalOnlyStarListDialogForTest
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
import com.mobilegh.i18n.tr
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.mobilegh.ui.components.ReadmeHeading
import com.mobilegh.ui.screens.ReadmeTocSheet
import com.mobilegh.ui.screens.AchievementsScreen
import com.mobilegh.data.Web

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

    private val sampleCode = "package demo\n\nfun main() {\n    println(\"hello, commit\")\n}\n"
    private val sampleJobLog = listOf(
        "2026-10-31T03:00:00.1000000Z ##[group]Run ./gradlew assembleDebug",
        "2026-10-31T03:00:00.2000000Z ##[endgroup]",
        "2026-10-31T03:00:01.0000000Z [command]/usr/bin/bash -e gradlew assembleDebug",
        "2026-10-31T03:00:02.0000000Z > Task :app:compileDebugKotlin",
        "2026-10-31T03:00:05.0000000Z w: FilterBar.kt uses a deprecated API",
        "2026-10-31T03:00:06.0000000Z ##[warning]Gradle reports a deprecation",
        "2026-10-31T03:00:07.0000000Z e: ActionsScreens.kt: unresolved reference",
        "2026-10-31T03:00:07.5000000Z ##[error]Process completed with exit code 1.",
    ).joinToString("\n")
    private val sampleReadme = "# demo\n\nHello **world**, a [rendered](https://github.com) README.\n"

    private fun route(method: String, path: String, accept: String?, body: String): String {
        val p = path
        return when {
            // 文件内容：原始地址（raw 主机）与 contents 接口的 raw 形式都返回原文；README 的 html 形式返回渲染结果
            // 原始地址可能经镜像（如 jsDelivr 的 /gh/owner/repo@ref/ 路径），所以只按文件名结尾匹配
            p.endsWith("/src/Main.kt") -> sampleCode
            p == "/repos/mona/mobilegh/contents/README.md" ->
                if (accept?.contains("html") == true) "<h1><a id=\"user-content-demo\" class=\"anchor\" href=\"#demo\"></a>demo</h1><p>Hello <strong>world</strong>, a <a href=\"https://github.com\">rendered</a> README.</p><h2><a id=\"user-content-features\" class=\"anchor\" href=\"#features\"></a>Features</h2><p>Feature list</p><h2><a id=\"user-content-install\" class=\"anchor\" href=\"#install\"></a>Installation</h2><p>How to install</p>" else sampleReadme
            p.endsWith("/README.md") -> sampleReadme
            p == "/repos/mona/mobilegh/contents/src/Main.kt" -> sampleCode
            // 作业日志：带时间戳的原始文本，页面里按分组、命令、警告、错误分别着色
            p.contains("/actions/jobs/") && p.endsWith("/logs") -> sampleJobLog
            p == "/repos/mona/mobilegh/events" -> """[{"id":"1","type":"PushEvent","actor":{"login":"mona","avatar_url":"https://avatars.githubusercontent.com/u/1?v=4"},"repo":{"name":"mona/mobilegh"},"payload":{"ref":"refs/heads/feature/login","head":"abcdef1234567","size":3,"commits":[{"sha":"111","message":"older change"},{"sha":"abcdef1234567","message":"Fix login redirect"}]},"created_at":"${ago(3)}"},{"id":"2","type":"PushEvent","actor":{"login":"hubot","avatar_url":"https://avatars.githubusercontent.com/u/2?v=4"},"repo":{"name":"mona/mobilegh"},"payload":{"ref":"refs/heads/main","head":"fedcba9876543","size":1,"commits":[{"sha":"fedcba9876543","message":"Bump version"}]},"created_at":"${ago(9)}"}]"""
            p == "/user" -> user("mona", "Mona Lisa")
            p == "/graphql" -> when {
                body.contains("MobileGHRepositoryLists(") -> repoListsGraphql()
                body.contains("MobileGHLists(") -> listsGraphql()
                body.contains("pinnedItems") && body.contains("repositoryOwner") -> pinnedGraphql()
                else -> graphql()
            }
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
    private fun listNode(id: String, name: String, desc: String, priv: Boolean, count: Int, h: Int) =
        """{"id":"$id","name":"$name","description":"$desc","isPrivate":$priv,"slug":"$id","createdAt":"${ago(h + 500)}","updatedAt":"${ago(h)}","lastAddedAt":"${ago(h)}","user":{"login":"mona"},"items":{"totalCount":$count}}"""

    private fun listsGraphql(): String {
        val nodes = listOf(
            listNode("list-1", "Learning Kotlin", "Resources and samples I am following", false, 8, 5),
            listNode("list-2", "Private reading list", "", true, 128, 30),
            listNode("list-3", "Tools", "CLI tools I use every day", false, 23, 96),
            listNode("list-4", "Design inspiration", "Interfaces worth studying", false, 12, 400),
        ).joinToString(",")
        return """{"data":{"viewer":{"login":"mona"},"user":{"lists":{"totalCount":4,"pageInfo":{"hasNextPage":false,"endCursor":null,"hasPreviousPage":false,"startCursor":null},"nodes":[$nodes]}}}}"""
    }

    /** 仓库归属（长按 Star 的选择器读的）：仓库已 Star，并且已在「Learning Kotlin」与「Tools」两个列表里 */
    private fun repoListsGraphql(): String {
        val nodes = listOf(
            listNode("list-1", "Learning Kotlin", "Resources and samples I am following", false, 8, 5),
            listNode("list-3", "Tools", "CLI tools I use every day", false, 23, 96),
        ).joinToString(",")
        return """{"data":{"viewer":{"login":"mona"},"repository":{"id":"R_mobilegh","viewerHasStarred":true,"lists":{"nodes":[$nodes]}}}}"""
    }

    private fun pinnedGraphql(): String {
        val pins = listOf(
            """{"name":"mobilegh","owner":{"login":"mona"},"description":"A GitHub client for Android built with Jetpack Compose","stargazerCount":340,"forkCount":42,"isPrivate":false,"primaryLanguage":{"name":"Kotlin","color":"#A97BFF"}}""",
            """{"name":"octokit.rb","owner":{"login":"octokit"},"description":"Ruby toolkit for the GitHub API","stargazerCount":3800,"forkCount":960,"isPrivate":false,"primaryLanguage":{"name":"Ruby","color":"#701516"}}""",
            """{"name":"private-notes","owner":{"login":"mona"},"description":"Personal notes and snippets","stargazerCount":5,"forkCount":0,"isPrivate":true,"primaryLanguage":{"name":"Markdown","color":"#083fa1"}}"""
        ).joinToString(",")
        return """{"data":{"repositoryOwner":{"pinnedItems":{"nodes":[$pins]}}}}"""
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
            // 头像与成就徽章图都返回测试用的 PNG（徽章路径不是数字，用路径哈希作为颜色种子）
            val resp = if (url.host.contains("avatars") || url.encodedPath.endsWith(".png")) {
                val seed = url.pathSegments.lastOrNull()?.toIntOrNull() ?: url.encodedPath.hashCode()
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
            compose.activity.getSharedPreferences("permissions", android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("notification_requested", true)
                .putBoolean("background_requested", true)
                .apply()
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
        // 模拟器负载高时 takeScreenshot 会返回 null，短暂等待后重试
        var shot: Bitmap? = null
        repeat(5) {
            if (shot == null) shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (shot == null) Thread.sleep(500)
        }
        shot!!
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

    @Test fun homeProfilePopup() {
        val n = Navigator().also { nav = it }
        compose.setContent { MobileGhTheme { AppRoot(n) } }
        Thread.sleep(1500)
        compose.onNodeWithTag("home_avatar_btn").performClick()
        Thread.sleep(1000)
        saveScreen("01-home-profile-popup")
    }
    @Test fun repos() = shot("02-repos") { it.tab = Tab.Repos }
    @Test fun starTab() = shot("03-star-tab") { it.tab = Tab.Star }
    @Test fun reposFilter() {
        val n = Navigator().also { nav = it }
        n.tab = Tab.Repos
        compose.setContent { MobileGhTheme { AppRoot(n) } }
        Thread.sleep(2500)
        // 首次进入仓库页可能弹出「启用 GitHub 数字审批」提示，先关掉，否则会挡住筛选面板
        val cancel = compose.onAllNodesWithText(if (lang == "en") "Cancel" else "取消").fetchSemanticsNodes()
        if (cancel.isNotEmpty()) { compose.onAllNodesWithText(if (lang == "en") "Cancel" else "取消")[0].performClick(); Thread.sleep(800) }
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
    @Test fun readmeToc() {
        val headings = listOf(
            ReadmeHeading(1, "mobilegh", "mobilegh"),
            ReadmeHeading(2, "Features", "features"),
            ReadmeHeading(2, "Architecture", "architecture"),
            ReadmeHeading(3, "UI Layer", "ui-layer"),
            ReadmeHeading(3, "Data Layer", "data-layer"),
            ReadmeHeading(2, "Installation", "installation"),
            ReadmeHeading(2, "License", "license"),
        )
        compose.setContent {
            MobileGhTheme {
                ReadmeTocSheet(headings = headings, onDismiss = {}, onPick = {})
            }
        }
        Thread.sleep(1200)
        compose.waitForIdle()
        saveScreen("03c-readme-toc-sheet")
    }
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
    @Test fun notifications() = shot("07-notifications") { it.push(Screen.Notifications) }
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

    private val fixtureLists = listOf(
        GitHubStarList("list-1", "Learning Kotlin", "Resources and samples I am following", false, "learning", "mona", 8),
        GitHubStarList("list-2", "Private reading list", "", true, "reading", "mona", 128),
        GitHubStarList("list-3", "Tools", "CLI tools I use every day", false, "tools", "mona", 23),
        GitHubStarList("list-4", "Design inspiration", "Interfaces worth studying", false, "design", "mona", 12),
    )

    /** 长按仓库的 Star 按钮后弹出的列表选择器（底部弹层） */
    @Test fun starListPicker() {
        val n = Navigator().also { nav = it }
        val lists = fixtureLists
        n.root.bag["star-lists:mona:mona:${Session.generation}"] = Loader(n.root.scope) { lists }.also { it.accept(lists) }
        val repo = Repo(1, "mobilegh", "mona/mobilegh", User(login = "mona", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"), description = "A GitHub client for Android", language = "Kotlin", stargazersCount = 1280, pushedAt = ago(3))
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) { AddToStarListsDialog(repo) {} } } }
        Thread.sleep(2500)
        compose.waitForIdle()
        saveScreen("13-star-picker")
    }

    /** 新建 GitHub 列表的编辑弹窗 */
    @Test fun newStarList() {
        compose.setContent { MobileGhTheme { GitHubListEditor(null, false, null, {}) { _, _, _ -> } } }
        Thread.sleep(1500)
        compose.waitForIdle()
        saveScreen("14-new-list")
    }

    /** 新建本机列表的编辑弹窗（不带可见性选项，仅名称、描述和字数） */
    @Test fun newLocalList() {
        compose.setContent { MobileGhTheme { ListEditor(null, false, null, {}) { _, _ -> } } }
        Thread.sleep(1500)
        compose.waitForIdle()
        saveScreen("14b-new-local-list")
    }

    /** 长按 Star 后改用本机列表的选择器（底部弹层，带保存按钮） */
    @Test fun localStarListPicker() {
        val n = Navigator().also { nav = it }
        val repo = Repo(1, "mobilegh", "mona/mobilegh", User(login = "mona", avatarUrl = "https://avatars.githubusercontent.com/u/1?v=4"), description = "A GitHub client for Android", language = "Kotlin", stargazersCount = 1280, pushedAt = ago(3))
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) {
            val account = StarListAccount("mona", Session.generation)
            val store = StarLists.store(compose.activity)
            // 预置两个本机列表，第一个已包含这个仓库；上一次运行可能留过同名列表，先清掉
            runCatching { store.read(account).forEach { store.delete(account, it.id) } }
            val made = store.create(account, "Read later", "Articles worth finishing")
            store.create(account, "Machine learning", "")
            store.setRepositoryLists(account, ListedRepository.from(repo), setOf(made.first().id), emptySet())
            LocalOnlyStarListDialogForTest(repo, account)
        } } }
        Thread.sleep(2500)
        compose.waitForIdle()
        saveScreen("13b-local-star-picker")
    }

    @Test fun starListsOverview() {
        val n = Navigator().also { nav = it }
        compose.setContent { MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) { StarListsScreen("mona") } } }
        Thread.sleep(2500)
        save("12-star-overview")
    }

    /** 搜索页直接渲染（不经过 AppRoot），用于截图搜索栏、类型标签和空态 */
    /** 从提交详情点进代码文件：应该是语法高亮的源码，不是渲染后的 README */
    @Test fun fileCode() = shot("18-file-code") { it.push(Screen.FileView("mona", "mobilegh", "src/Main.kt", "abc123")) }
    /** 同一个提交里的 README：应该渲染成文档 */
    @Test fun fileReadme() = shot("19-file-readme") { it.push(Screen.FileView("mona", "mobilegh", "README.md", "abc123")) }
    /** Action 作业日志：终端样式（深色底、等宽、分组/命令/错误着色） */
    @Test fun jobLog() = shot("20-job-log") { it.push(Screen.JobLog("mona", "mobilegh", 1L, "build")) }
    /** 同一份日志的深色主题版本：终端配色应跟随应用的明暗主题 */
    @Test fun jobLogDark() {
        compose.runOnUiThread { Session.themeMode = 2 }
        shot("21-job-log-dark") { it.push(Screen.JobLog("mona", "mobilegh", 1L, "build")) }
    }
    /** Issue 列表：顶部的状态胶囊与生效条件 */
    @Test fun issues() = shot("22-issues") { it.push(Screen.Issues("mona", "mobilegh", false)) }
    /** 仓库最近推送：谁推到了哪个分支 */
    @Test fun pushes() = shot("23-pushes") { it.push(Screen.Pushes("mona", "mobilegh")) }
    /** 点开一条推送：看到这次推送的提交与文件改动 */
    @Test fun pushDetail() = shot("24-push-detail", after = {
        compose.onAllNodesWithText("mona 推送了 3 个提交到分支 feature/login")[0].performClick()
        Thread.sleep(1500)
        compose.waitForIdle()
    }) { it.push(Screen.Pushes("mona", "mobilegh")) }
    /** 逐页巡检：依次打开应用里的每个页面并截图，用于检查各页风格是否统一（输出 sweep/<序号-名称>.png） */
    @Test fun sweepAllScreens() {
        val screens = listOf<Pair<String, Screen>>(
            "repo-files" to Screen.Files("mona", "mobilegh", "", null),
            "file-finder" to Screen.FileFinder("mona", "mobilegh", null),
            "code-search" to Screen.CodeSearch("mona", "mobilegh"),
            "search" to Screen.Search(0, ""),
            "blame" to Screen.Blame("mona", "mobilegh", "src/Main.kt", null),
            "edit-file" to Screen.EditFile("mona", "mobilegh", null, "src/Main.kt"),
            "upload" to Screen.UploadFiles("mona", "mobilegh", null),
            "new-pull" to Screen.NewPull("mona", "mobilegh", "feature/login", "main"),
            "edit-release" to Screen.EditRelease("mona", "mobilegh"),
            "run-workflow" to Screen.RunWorkflow("mona", "mobilegh", 1L, ".github/workflows/ci.yml", "CI"),
            "recent-repos" to Screen.RecentRepositories,
            "search-history" to Screen.SearchHistory,
            "edit-profile" to Screen.EditProfile,
            "commit" to Screen.CommitDetail("mona", "mobilegh", "b7c9443a1f2e3d4c5b6a79880a1b2c3d4e5f6071"),
            "issues-pr" to Screen.Issues("mona", "mobilegh", true),
            "issue-new" to Screen.NewIssue("mona", "mobilegh"),
            "pr-checks" to Screen.PullChecks("mona", "mobilegh", 7, "abcdef1234567"),
            "pr-files" to Screen.PullFiles("mona", "mobilegh", 7),
            "actions" to Screen.Actions("mona", "mobilegh"),
            "run-detail" to Screen.RunDetail("mona", "mobilegh", 1L),
            "releases" to Screen.Releases("mona", "mobilegh"),
            "branches" to Screen.Branches("mona", "mobilegh"),
            "insights" to Screen.Insights("mona", "mobilegh"),
            "contributors" to Screen.Contributors("mona", "mobilegh"),
            "discussions" to Screen.Discussions("mona", "mobilegh"),
            "discussion" to Screen.DiscussionDetail("mona", "mobilegh", 3),
            "repo-settings" to Screen.RepoSettings("mona", "mobilegh"),
            "projects-user" to Screen.Projects(com.mobilegh.data.ProjectOwnerKind.User, "mona"),
            "project" to Screen.ProjectDetail("PVT_1", "Roadmap"),
            "collaborators" to Screen.Collaborators("mona", "mobilegh"),
            "profile" to Screen.Profile("mona"),
            "org" to Screen.Org("hubot"),
            "users" to Screen.Users(com.mobilegh.nav.UserKind.Followers, "mona"),
            "repos-owned" to Screen.Repos(com.mobilegh.nav.RepoKind.User, "mona"),
            "orgs" to Screen.Orgs,
            "invitations" to Screen.Invitations,
            "gists" to Screen.Gists("mona"),
            "gist-detail" to Screen.GistDetail("g1"),
            "gist-editor" to Screen.GistEditor(),
            "activity" to Screen.Activity("mona"),
            "star-lists" to Screen.StarLists("mona"),
            "achievements" to Screen.Achievements("mona"),
            "settings" to Screen.Settings,
            "logs" to Screen.Logs,
            "authenticator" to Screen.Authenticator,
            "org-diagnostics" to Screen.OrgDiagnostics,
            "create-repo" to Screen.CreateRepo,
            "login" to Screen.Login,
            "my-issues" to Screen.MyIssues(false),
            "home-customize" to Screen.HomeCustomize,
        )
        val n = Navigator().also { nav = it }
        compose.setContent { MobileGhTheme { AppRoot(n) } }
        Thread.sleep(2000)
        screens.forEachIndexed { i, (name, screen) ->
            compose.runOnUiThread { n.push(screen) }
            Thread.sleep(1800)
            compose.waitForIdle()
            val dir = File(compose.activity.cacheDir, "sweep").apply { mkdirs() }
            val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
            File(dir, "%02d-%s.png".format(i + 1, name)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.runOnUiThread { n.popToRoot() }
            Thread.sleep(600)
        }
    }

    /** 成就：假的个人主页与详情片段，截取成就列表、点开后的徽章动效（中途）与详情弹窗 */
    @Test fun achievementDetailFlow() {
        val profile = """<html><body><div>
            <a href="/mona?tab=achievements&achievement=pull-shark"><img data-hovercard-type="achievement" alt="Achievement: Pull Shark" src="https://github.githubassets.com/assets/pull-shark-bronze-abc123.png" width="64"><span class="Label achievement-tier-label">x2</span></a>
            <a href="/mona?tab=achievements&achievement=quickdraw"><img data-hovercard-type="achievement" alt="Achievement: Quickdraw" src="https://github.githubassets.com/assets/quickdraw-default-abc123.png" width="64"></a>
            <a href="/mona?tab=achievements&achievement=starstruck"><img data-hovercard-type="achievement" alt="Achievement: Starstruck" src="https://github.githubassets.com/assets/starstruck-silver-abc123.png" width="64"></a>
            </div></body></html>"""
        val detail = """<div class="d-flex flex-column"><div class="tmp-p-3">
            <div><div class="d-flex"><h3>Pull Shark</h3><span class="Label achievement-tier-label achievement-tier-label--bronze">x2</span></div>
            <div class="mt-1">@mona opened pull requests that have been merged.</div></div>
            <div class="border-top achievement-history"><h4>History</h4>
            <div class="TimelineItem"><div>Bronze unlocked · First unlocked 2026-01-17 01:43:49 UTC</div></div>
            <div class="TimelineItem"><div>2nd pull request merged octo/repo#1</div></div>
            </div></div></div>"""
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val path = req.url.encodedPath
            val (code, type, body) = when {
                path == "/mona" -> Triple(200, "text/html; charset=utf-8", profile)
                path == "/users/mona/achievements/pull-shark/detail" -> Triple(200, "text/html; charset=utf-8", detail)
                else -> Triple(404, "text/plain", "Not Found")
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("OK")
                .body(body.toResponseBody(type.toMediaType())).build()
        }.build()
        Web.transport = fake
        try {
            val n = Navigator().also { nav = it }
            compose.setContent {
                MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) {
                    AchievementsScreen("mona")
                } }
            }
            Thread.sleep(2000)
            compose.waitForIdle()
            saveScreen("25-achievements")
            compose.onNodeWithContentDescription("拉取鲨鱼，铜级，x2").performClick()
            // 动效进行中截一张，再截动效结束后的弹窗
            // 不能用 saveScreen：它会 waitForIdle，等于等动画结束。这里直接截屏，抓到转动中的画面
            // 连续抓取动效过程中的几帧（每帧约 80ms），保存为 flip-00..07
            val frameDir = File(compose.activity.cacheDir, "readme/$lang").apply { mkdirs() }
            // 等弹窗真正出现（徽章开始转动）后再抓帧
            compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("倍数 x2").fetchSemanticsNodes().isNotEmpty() }
            repeat(8) { i ->
                Thread.sleep(80)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    ?.let { frame -> File(frameDir, "26-flip-%02d.png".format(i)).outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
            Thread.sleep(1600)
            compose.waitForIdle()
            saveScreen("27-achievement-dialog")
        } finally {
            Web.transport = null
        }
    }
    @Test fun searchHome() {
        val n = Navigator().also { nav = it }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) {
                com.mobilegh.ui.screens.SearchScreen(0, "")
            } }
        }
        Thread.sleep(1500)
        saveScreen("15-search-home")
    }
    /** 搜索结果页：带关键词直接渲染，结果来自假 API */
    @Test fun searchResults() {
        val n = Navigator().also { nav = it }
        compose.setContent {
            MobileGhTheme { CompositionLocalProvider(LocalNav provides n, LocalEntry provides n.root) {
                com.mobilegh.ui.screens.SearchScreen(0, "compose")
            } }
        }
        Thread.sleep(2500)
        saveScreen("16-search-results")
    }
    /** 共享筛选组件的样张：筛选按钮（带数量）、生效条件胶囊、分段选择、普通胶囊 */
    @Test fun filterComponents() {
        compose.setContent {
            MobileGhTheme {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.padding(16.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
                ) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        com.mobilegh.ui.components.FilterEntry(0) {}
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(16.dp))
                        com.mobilegh.ui.components.FilterEntry(3) {}
                    }
                    com.mobilegh.ui.components.ActiveFilterChips(
                        listOf("stars" to "最少 Star 100", "lang" to "语言 Kotlin", "topic" to "Topic android"), {}, {},
                    )
                    com.mobilegh.ui.components.ChoicePicker(tr("状态"), listOf(tr("不限"), tr("开放"), tr("已关闭")), 1) {}
                    com.mobilegh.ui.components.ChoicePicker(tr("可见性"), listOf(tr("全部"), tr("公开"), tr("私有"), tr("隐藏 Fork")), 2) {}
                }
            }
        }
        Thread.sleep(800)
        saveScreen("17-filter-components")
    }
}
