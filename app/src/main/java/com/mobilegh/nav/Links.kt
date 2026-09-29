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
    )

    fun route(url: String): Screen? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        val segs = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }.map { URLDecoder.decode(it, "UTF-8") }
        return when (host) {
            "github.com", "www.github.com" -> web(segs)
            "api.github.com" -> api(segs)
            "raw.githubusercontent.com" ->
                if (segs.size >= 4) Screen.FileView(segs[0], segs[1], segs.drop(3).joinToString("/"), segs[2]) else null
            "gist.github.com" -> segs.getOrNull(1)?.let { Screen.GistDetail(it) } ?: segs.getOrNull(0)?.let { Screen.Gists(it) }
            else -> null
        }
    }

    private fun web(segs: List<String>): Screen? {
        if (segs.isEmpty()) return null
        if (segs[0] == "orgs" && segs.size >= 2) return Screen.Org(segs[1])
        if (segs[0] in reserved || segs[0].startsWith("_")) return null
        if (segs.size == 1) return Screen.Profile(segs[0])
        val o = segs[0]
        val r = segs[1].removeSuffix(".git")
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
            "discussions", "wiki", "security", "projects", "compare", "milestone", "milestones", "labels", "raw" -> null
            else -> Screen.Repo(o, r)
        }
    }

    private fun api(segs: List<String>): Screen? {
        if (segs.firstOrNull() == "users" && segs.size >= 2) return Screen.Profile(segs[1])
        if (segs.firstOrNull() != "repos" || segs.size < 3) return null
        val o = segs[1]
        val r = segs[2]
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
