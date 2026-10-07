package com.mobilegh.nav

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

    private val ownerPattern = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
    private val repoPattern = Regex("[A-Za-z0-9_.-]{1,100}")
    private val bareHost = Regex("(?i)^(?:(?:www\\.)?github\\.com|api\\.github\\.com|raw\\.githubusercontent\\.com|gist\\.github\\.com)/.*$")
    private val cloneUrl = Regex("(?i)^(?:git@github\\.com:|ssh://git@github\\.com/)([^\\s]+)$")
    private val shortRepo = Regex("^[A-Za-z0-9-]+/[A-Za-z0-9_.-]+$")
    private val markdownLink = Regex("^\\[[^]\\r\\n]*]\\(([^\\s()]+)\\)$")
    private val linksInText = Regex(
        "(?i)(?<![A-Za-z0-9_./:@?=&%#-])(?:https?://(?:(?:www\\.)?github\\.com|api\\.github\\.com|(?:raw\\.githubusercontent\\.com|gist\\.github\\.com))|(?:(?:www\\.)?github\\.com|api\\.github\\.com|(?:raw\\.githubusercontent\\.com|gist\\.github\\.com))|git@github\\.com:|ssh://git@github\\.com/)/?[^\\s<>\"'`()\\[\\]{}，。；！？、：]+",
    )

    data class RepositoryLink(val repository: Screen.Repo, val target: Screen, val url: String = "") {
        val label: String get() = "${repository.owner}/${repository.name}"
    }

    data class InputLink(val url: String, val target: Screen)

    fun inputLink(text: String): InputLink? {
        val url = normalizeInput(text)?.substringBefore('#')?.substringBefore('?') ?: return null
        return fromInput(url)?.let { InputLink(url, it) }
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
        return when (host) {
            "github.com", "www.github.com" -> web(segs)
            "api.github.com" -> api(segs)
            "raw.githubusercontent.com" ->
                if (segs.size >= 4 && repository(segs[0], segs[1]) != null) Screen.FileView(segs[0], segs[1], segs.drop(3).joinToString("/"), segs[2]) else null
            "gist.github.com" -> segs.getOrNull(1)?.let { Screen.GistDetail(it) } ?: segs.getOrNull(0)?.let { Screen.Gists(it) }
            else -> null
        }
    }

    private fun web(segs: List<String>): Screen? {
        if (segs.isEmpty()) return null
        if (segs[0] == "orgs" && segs.size >= 2) return segs[1].takeIf(ownerPattern::matches)?.let(Screen::Org)
        if (segs[0].lowercase() in reserved || !ownerPattern.matches(segs[0])) return null
        if (segs.size == 1) return Screen.Profile(segs[0])
        val repo = repository(segs[0], segs[1]) ?: return null
        val o = repo.owner
        val r = repo.name
        if (segs.size == 2) return Screen.Repo(o, r)
        val rest = segs.drop(4).joinToString("/")
        return when (segs[2]) {
            "issues" -> segs.getOrNull(3)?.toIntOrNull()?.let { Screen.IssueDetail(o, r, it, false) } ?: Screen.Issues(o, r, false)
            "pull" -> segs.getOrNull(3)?.toIntOrNull()?.let { Screen.IssueDetail(o, r, it, true) } ?: Screen.Issues(o, r, true)
            "pulls" -> Screen.Issues(o, r, true)
            "commit" -> segs.getOrNull(3)?.let { Screen.CommitDetail(o, r, it) }
            "commits" -> Screen.Commits(o, r, segs.getOrNull(3))
            "blob" -> if (segs.size >= 5) Screen.FileView(o, r, rest, segs[3]) else Screen.Repo(o, r)
            "tree" -> if (segs.size >= 4) Screen.Files(o, r, rest, segs[3]) else Screen.Repo(o, r)
            "releases", "tags" -> Screen.Releases(o, r)
            "actions" -> if (segs.getOrNull(3) == "runs") segs.getOrNull(4)?.toLongOrNull()?.let { Screen.RunDetail(o, r, it) } else Screen.Actions(o, r)
            "stargazers" -> Screen.Users(UserKind.Stargazers, o, r)
            "watchers" -> Screen.Users(UserKind.Watchers, o, r)
            "network", "forks" -> Screen.Repos(RepoKind.Forks, o, r)
            "branches" -> Screen.Branches(o, r)
            "graphs", "pulse" -> Screen.Insights(o, r)
            "discussions" -> segs.getOrNull(3)?.toIntOrNull()?.let { Screen.DiscussionDetail(o, r, it) } ?: Screen.Discussions(o, r)
            "compare" -> segs.getOrNull(3)?.split("...", limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { Screen.Compare(o, r, it[0], it[1]) } ?: Screen.Compare(o, r)
            "wiki", "security", "projects", "milestone", "milestones", "labels", "raw" -> null
            else -> Screen.Repo(o, r)
        }
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
            "releases" -> Screen.Releases(o, r)
            "actions" -> Screen.Actions(o, r)
            else -> Screen.Repo(o, r)
        }
    }
}
