package com.mobilegh.nav

import com.mobilegh.data.SearchType
import java.net.URI
import java.net.URLDecoder

/** 把 github.com / api.github.com / raw 链接转换为应用内页面，无法识别时返回 null（交给浏览器） */
object Links {
    private val reserved = setOf(
        "settings", "features", "marketplace", "explore", "topics", "trending", "notifications", "login", "logout",
        "new", "pulls", "issues", "sponsors", "about", "pricing", "enterprise", "collections", "events", "search",
        "codespaces", "dashboard", "account", "apps", "site", "security", "customer-stories", "readme", "team",
        "contact", "join", "signup", "password_reset", "copilot", "github-copilot", "resources", "solutions", "stars",
        "oauth", "sessions", "organizations", "orgs", "404",
    )

    /** 安全页面里应用支持的三类告警；其余子页（如 advisories）交给浏览器 */
    private val securityPages = setOf("dependabot", "code-scanning", "secret-scanning")

    private val ownerPattern = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
    private val repoPattern = Regex("[A-Za-z0-9_.-]{1,100}")
    private val bareHost = Regex("(?i)^(?:(?:www\\.)?github\\.com|api\\.github\\.com|raw\\.githubusercontent\\.com|gist\\.github\\.com)/.*$")
    private val cloneUrl = Regex("(?i)^(?:git@github\\.com:|ssh://git@github\\.com/)([^\\s]+)$")
    private val shortRepo = Regex("^[A-Za-z0-9-]+/[A-Za-z0-9_.-]+$")
    private val markdownLink = Regex("^\\[[^]\\r\\n]*]\\(([^\\s()]+)\\)$")
    private val lineFragment = Regex("L(\\d{1,7})(?:-L?(\\d{1,7}))?")
    /** 页面内定位锚点：Issue / PR 评论（issuecomment-）与 PR 审查（pullrequestreview-） */
    private val commentFragment = Regex("(?:issuecomment|pullrequestreview)-(\\d{1,19})")
    private val linksInText = Regex(
        "(?i)(?<![A-Za-z0-9_./:@?=&%#-])(?:https?://(?:(?:www\\.)?github\\.com|api\\.github\\.com|(?:raw\\.githubusercontent\\.com|gist\\.github\\.com))|(?:(?:www\\.)?github\\.com|api\\.github\\.com|(?:raw\\.githubusercontent\\.com|gist\\.github\\.com))|git@github\\.com:|ssh://git@github\\.com/)/?[^\\s<>\"'`()\\[\\]{}，。；！？、：]+",
    )

    data class RepositoryLink(val repository: Screen.Repo, val target: Screen, val url: String = "") {
        val label: String get() = "${repository.owner}/${repository.name}"

        /** 提示用的名称：Issue / PR / 讨论带编号，提交带短 SHA，让用户能看出具体打开哪一页。 */
        val targetLabel: String
            get() = when (val t = target) {
                is Screen.IssueDetail -> "$label#${t.number}"
                is Screen.DiscussionDetail -> "$label#${t.number}"
                is Screen.CommitDetail -> "$label@${t.sha.take(7)}"
                else -> label
            }
    }

    data class InputLink(val url: String, val target: Screen)

    fun inputLink(text: String): InputLink? {
        val raw = normalizeInput(text) ?: return null
        val full = raw.substringBefore('#')
        // 搜索链接的关键词在查询参数里，要保留；其余链接丢掉 ?tab= 之类的会话参数
        (route(full) as? Screen.Search)?.let { return InputLink(full, it) }
        val url = full.substringBefore('?')
        // 只保留决定定位的片段（评论锚点、代码行号、PR 审查评论），其余如 #top 之类的页内锚点仍然丢弃
        val positional = raw.contains('#') && positionalFragment(raw)
        val withFragment = if (positional) url + "#" + raw.substringAfter('#') else url
        // 返回的 url 会被交给 receive 再次解析（外部打开时就是这样），所以它必须带着片段
        return fromInput(withFragment)?.let { InputLink(withFragment, it) }
    }

    fun sharedLink(text: String): InputLink? =
        inputLink(text) ?: linksInText.findAll(text.take(65_536)).mapNotNull {
            inputLink(it.value.trimEnd('.', ',', ';', '!', '?', '”', '’', '）', '】'))
        }.firstOrNull()

    /** 搜索提交时识别完整链接、无协议地址和 Git 克隆地址；普通搜索语法返回 null。 */
    fun fromInput(input: String): Screen? {
        val url = normalizeInput(input) ?: return null
        return route(url) ?: repositoryLink(url)?.target
    }

    /**
     * 剪贴板专用：只接受「完整 GitHub 链接 / 无协议地址 / owner/repo / 克隆地址」这几种明确形式，
     * 不扫描普通文本，避免复制一段话也弹出「打开仓库」。
     */
    fun clipboardLink(text: String): RepositoryLink? {
        val value = text.trim()
        if (value.length !in 1..8_192) return null
        // 整段必须是链接；带空白的普通句子直接忽略
        if (value.any(Char::isWhitespace)) return null
        val url = normalizeInput(value) ?: return null
        return repositoryLink(url)
    }

    /** 识别分享文本中的第一个仓库地址，不把个人主页或 GitHub 设置页当成仓库。 */
    fun repositoryInText(text: String): RepositoryLink? {
        val bounded = text.take(65_536)
        normalizeInput(bounded)?.let { repositoryLink(it)?.let { link -> return link } }
        return linksInText.findAll(bounded).mapNotNull { match ->
            normalizeInput(match.value.trimEnd('.', ',', ';', '!', '?', '”', '’', '）', '】'))?.let(::repositoryLink)
        }.firstOrNull()
    }

    private fun normalizeInput(input: String): String? {
        var value = input.trim()
        if (value.length !in 1..8_192) return null
        markdownLink.matchEntire(value)?.let { value = it.groupValues[1] }
        if (value.length >= 2 && (value.first() to value.last()) in setOf('<' to '>', '"' to '"', '\'' to '\'', '`' to '`')) {
            value = value.substring(1, value.lastIndex).trim()
        }
        if (value.any(Char::isWhitespace)) return null
        cloneUrl.matchEntire(value)?.let { return "https://github.com/${it.groupValues[1]}" }
        if (bareHost.matches(value) || shortRepo.matches(value)) return "https://${if (shortRepo.matches(value)) "github.com/" else ""}$value"
        return value
    }

    private fun parse(url: String): Pair<URI, List<String>>? = runCatching {
        val u = URI(url)
        val scheme = u.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || u.userInfo != null ||
            u.port !in setOf(-1, if (scheme == "https") 443 else 80)) return null
        val path = u.rawPath.orEmpty().trim('/')
        val segs = if (path.isEmpty()) emptyList() else path.split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        u to segs
    }.getOrNull()

    private fun repository(owner: String, name: String): Screen.Repo? {
        val repo = name.removeSuffix(".git")
        if (!ownerPattern.matches(owner) || owner.lowercase() in reserved ||
            !repoPattern.matches(repo) || repo in setOf(".", "..")) return null
        return Screen.Repo(owner, repo)
    }

    private fun repositoryLink(url: String): RepositoryLink? {
        val (u, segs) = parse(url) ?: return null
        val repo = when (u.host?.lowercase()) {
            "github.com", "www.github.com" -> if (segs.size >= 2) repository(segs[0], segs[1]) else null
            "api.github.com" -> if (segs.size >= 3 && segs[0] == "repos") repository(segs[1], segs[2]) else null
            "raw.githubusercontent.com" -> if (segs.size >= 4) repository(segs[0], segs[1]) else null
            else -> null
        } ?: return null
        return RepositoryLink(repo, route(url) ?: repo, url.substringBefore('#').substringBefore('?'))
    }

    fun route(url: String): Screen? {
        val (u, segs) = parse(url) ?: return null
        val host = u.host?.lowercase() ?: return null
        val screen = when (host) {
            "github.com", "www.github.com" -> search(u, segs) ?: web(u, segs)
            "api.github.com" -> api(segs)
            // raw 地址的第三段是分支 / 标签 / 提交，之后是路径；由 BlobScreen 去确认分界
            "raw.githubusercontent.com" ->
                if (segs.size >= 4 && repository(segs[0], segs[1]) != null) Screen.Blob(segs[0], segs[1], segs.drop(2).joinToString("/")) else null
            "gist.github.com" -> segs.getOrNull(1)?.let { Screen.GistDetail(it) } ?: segs.getOrNull(0)?.let { Screen.Gists(it) }
            else -> null
        }
        // 片段只在这里解析：路径解析本身不关心片段
        return when (screen) {
            is Screen.Blob -> screen.copy(lines = lineRange(url))
            is Screen.IssueDetail ->
                // PR 的行内审查评论（#discussion_rN）在文件变更页里，直接进入那里
                if (screen.isPull && fragmentOf(url)?.startsWith("discussion_r") == true) Screen.PullFiles(screen.owner, screen.name, screen.number)
                else screen.copy(commentId = commentAnchor(url))
            else -> screen
        }
    }

    private fun fragmentOf(url: String): String? = runCatching { URI(url.trim()).rawFragment }.getOrNull()

    /** 决定定位位置的片段：评论锚点、代码行号、PR 行内审查评论 */
    private fun positionalFragment(url: String): Boolean =
        commentAnchor(url) != null || lineRange(url) != null || fragmentOf(url)?.startsWith("discussion_r") == true

    /** `#issuecomment-123` 或 `#pullrequestreview-123` 里的 id（两者都是 Issue 讨论串里的一条）；没有或格式不对时返回 null */
    fun commentAnchor(url: String): Long? {
        val fragment = fragmentOf(url) ?: return null
        return commentFragment.matchEntire(fragment)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * 文件链接的行号锚点：`#L42` 单行，`#L42-L50` 区间（也接受 `#L42-50`）。
     * 没有行号或格式不对时返回 null；返回的是闭区间，起始行至少为 1。
     */
    fun lineRange(url: String): IntRange? {
        val fragment = fragmentOf(url) ?: return null
        return parseLineFragment(fragment)
    }

    internal fun parseLineFragment(fragment: String): IntRange? {
        val m = lineFragment.matchEntire(fragment) ?: return null
        val start = m.groupValues[1].toIntOrNull() ?: return null
        val end = m.groupValues[2].toIntOrNull() ?: start
        return if (start >= 1 && end >= start) start..end else null
    }

    private fun queryParams(u: URI): Map<String, String> =
        u.rawQuery.orEmpty().split('&').mapNotNull {
            val (k, v) = it.split('=', limit = 2).takeIf { p -> p.size == 2 } ?: return@mapNotNull null
            runCatching { k to URLDecoder.decode(v, "UTF-8") }.getOrNull()
        }.toMap()

    /** github.com/search?q=…&type=… 打开应用内搜索；没有关键词时交给浏览器 */
    private fun search(u: URI, segs: List<String>): Screen? {
        if (segs != listOf("search")) return null
        val params = queryParams(u)
        val q = params["q"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = when (params["type"]?.lowercase()) {
            "users" -> SearchType.USER
            "issues" -> SearchType.ISSUE
            "pullrequests" -> SearchType.PR
            "code" -> SearchType.CODE
            else -> SearchType.REPO
        }
        return Screen.Search(type, q)
    }

    /**
     * Issues / PR 列表页。带 q 筛选时交给应用内搜索（保留搜索语法，如 is:open label:bug）；
     * 没有筛选时打开普通列表。
     */
    private fun listScreen(u: URI, owner: String, name: String, pulls: Boolean): Screen {
        val q = queryParams(u)["q"]?.trim()?.takeIf { it.isNotEmpty() } ?: return Screen.Issues(owner, name, pulls)
        return Screen.Search(if (pulls) SearchType.PR else SearchType.ISSUE, "repo:$owner/$name $q")
    }

    private fun web(u: URI, segs: List<String>): Screen? {
        if (segs.isEmpty()) return null
        if (segs[0] == "orgs" && segs.size >= 2) return segs[1].takeIf(ownerPattern::matches)?.let(Screen::Org)
        if (segs[0].lowercase() in reserved || !ownerPattern.matches(segs[0])) return null
        if (segs.size == 1) return Screen.Profile(segs[0])
        val repo = repository(segs[0], segs[1]) ?: return null
        val o = repo.owner
        val r = repo.name
        if (segs.size == 2) return Screen.Repo(o, r)
        return when (segs[2]) {
            "issues" -> when (val seg = segs.getOrNull(3)) {
                "new" -> Screen.NewIssue(o, r)
                null -> listScreen(u, o, r, pulls = false)
                else -> seg.toIntOrNull()?.let { Screen.IssueDetail(o, r, it, false) } ?: listScreen(u, o, r, pulls = false)
            }
            "pull" -> segs.getOrNull(3)?.toIntOrNull()?.let { num ->
                // pull/N/files 直接进入文件变更页，而不是讨论串
                if (segs.getOrNull(4) == "files") Screen.PullFiles(o, r, num) else Screen.IssueDetail(o, r, num, true)
            } ?: listScreen(u, o, r, pulls = true)
            "pulls" -> listScreen(u, o, r, pulls = true)
            // 提交链接可能带 .patch / .diff 后缀，SHA 本身不含点
            "commit" -> segs.getOrNull(3)?.substringBefore('.')?.takeIf { it.isNotEmpty() }?.let { Screen.CommitDetail(o, r, it) }
            "commits" -> Screen.Commits(o, r, segs.getOrNull(3))
            // blob / tree / raw 的「分支 + 路径」由 BlobScreen 解析（分支名可能含 /）
            "blob", "raw" -> if (segs.size >= 4) Screen.Blob(o, r, segs.drop(3).joinToString("/")) else Screen.Repo(o, r)
            "tree" -> if (segs.size >= 4) Screen.Blob(o, r, segs.drop(3).joinToString("/")) else Screen.Repo(o, r)
            // 版本链接必须带上版本：/releases/tag/X 与 /releases/download/X/… 定位到 X，/releases/latest 定位到最新版
            "releases" -> when (segs.getOrNull(3)) {
                // 标签名未编码时可能含 /，取剩下的全部路径
                "tag" -> segs.drop(4).joinToString("/").takeIf { it.isNotEmpty() }?.let { Screen.Releases(o, r, it) } ?: Screen.Releases(o, r)
                "download" -> segs.getOrNull(4)?.let { Screen.Releases(o, r, it) } ?: Screen.Releases(o, r)
                "latest" -> Screen.Releases(o, r, "latest")
                else -> Screen.Releases(o, r)
            }
            "tags" -> Screen.Releases(o, r)
            "actions" -> if (segs.getOrNull(3) == "runs") segs.getOrNull(4)?.toLongOrNull()?.let { Screen.RunDetail(o, r, it) } else Screen.Actions(o, r)
            "stargazers" -> Screen.Users(UserKind.Stargazers, o, r)
            "watchers" -> Screen.Users(UserKind.Watchers, o, r)
            "network", "forks" -> Screen.Repos(RepoKind.Forks, o, r)
            "branches" -> Screen.Branches(o, r)
            "graphs", "pulse" -> Screen.Insights(o, r)
            "discussions" -> segs.getOrNull(3)?.toIntOrNull()?.let { Screen.DiscussionDetail(o, r, it) } ?: Screen.Discussions(o, r)
            "compare" -> segs.getOrNull(3)?.split("...", limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { Screen.Compare(o, r, it[0], it[1]) } ?: Screen.Compare(o, r)
            "wiki" -> wiki(o, r, segs.drop(3))
            // 安全页面的告警列表在应用内；仓库设置只有根页面在应用内，子页面交给浏览器
            "security" -> if (segs.size == 3 || segs[3] in securityPages) Screen.SecurityAlerts(o, r) else null
            "settings" -> if (segs.size == 3) Screen.RepoSettings(o, r) else null
            // 合并队列页（/owner/repo/queue）：应用内列出队列中的 PR，不再落到仓库首页
            "queue" -> if (segs.size == 3) Screen.MergeQueue(o, r) else null
            "projects", "milestone", "milestones", "labels" -> null
            else -> Screen.Repo(o, r)
        }
    }

    /** Wiki 页面在应用内打开；新建、编辑、历史等操作页仍交给浏览器 */
    private fun wiki(owner: String, name: String, rest: List<String>): Screen? {
        if (rest.isEmpty()) return Screen.Wiki(owner, name)
        val first = rest.first()
        if (first.startsWith("_") || first.equals("_new", true) || first.equals("_edit", true)) return null
        return Screen.Wiki(owner, name, rest.joinToString("/"))
    }

    private fun api(segs: List<String>): Screen? {
        if (segs.firstOrNull() == "users" && segs.size >= 2) return segs[1].takeIf(ownerPattern::matches)?.let(Screen::Profile)
        if (segs.firstOrNull() != "repos" || segs.size < 3) return null
        val repo = repository(segs[1], segs[2]) ?: return null
        val o = repo.owner
        val r = repo.name
        return when (segs.getOrNull(3)) {
            null -> Screen.Repo(o, r)
            "issues" -> segs.getOrNull(4)?.toIntOrNull()?.let { Screen.IssueDetail(o, r, it, false) }
            "pulls" -> segs.getOrNull(4)?.toIntOrNull()?.let { Screen.IssueDetail(o, r, it, true) }
            "commits" -> segs.getOrNull(4)?.let { Screen.CommitDetail(o, r, it) }
            "releases" -> when (segs.getOrNull(4)) {
                "tags" -> segs.drop(5).joinToString("/").takeIf { it.isNotEmpty() }?.let { Screen.Releases(o, r, it) } ?: Screen.Releases(o, r)
                "latest" -> Screen.Releases(o, r, "latest")
                // /releases/{id}：通知里的 Release 使用数字 id
                else -> segs.getOrNull(4)?.toLongOrNull()?.let { Screen.Releases(o, r, releaseId = it) } ?: Screen.Releases(o, r)
            }
            "actions" -> Screen.Actions(o, r)
            else -> Screen.Repo(o, r)
        }
    }
}
