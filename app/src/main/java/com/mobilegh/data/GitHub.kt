package com.mobilegh.data

import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** GitHub REST / GraphQL 接口封装 */
object GitHub {
    const val PER = 30

    private fun r(o: String, n: String) = "/repos/$o/$n"

    // ---------------- 用户 ----------------
    suspend fun viewer(force: Boolean = false) = Api.get<User>("/user", force)
    suspend fun user(login: String, force: Boolean = false) = Api.get<User>("/users/$login", force)
    suspend fun followers(login: String, page: Int, force: Boolean) = Api.get<List<User>>("/users/$login/followers?per_page=$PER&page=$page", force)
    suspend fun following(login: String, page: Int, force: Boolean) = Api.get<List<User>>("/users/$login/following?per_page=$PER&page=$page", force)
    suspend fun isFollowing(login: String) = Api.exists("/user/following/$login")
    suspend fun follow(login: String, on: Boolean) = Api.exec(if (on) "PUT" else "DELETE", "/user/following/$login")

    // ---------------- 仓库列表 ----------------
    /** affiliation: owner / collaborator / organization_member 的任意组合——官方 App 只显示 owner */
    suspend fun myRepos(affiliation: String, sort: String, visibility: String, page: Int, force: Boolean) =
        Api.get<List<Repo>>("/user/repos?affiliation=$affiliation&visibility=$visibility&sort=$sort&per_page=$PER&page=$page", force).track()

    suspend fun userRepos(login: String, sort: String, page: Int, force: Boolean) =
        Api.get<List<Repo>>("/users/$login/repos?type=owner&sort=$sort&per_page=$PER&page=$page", force)

    suspend fun orgRepos(org: String, sort: String, page: Int, force: Boolean) =
        Api.get<List<Repo>>("/orgs/$org/repos?type=all&sort=$sort&per_page=$PER&page=$page", force).track()

    suspend fun starred(login: String?, page: Int, force: Boolean) =
        Api.get<List<Repo>>((if (login == null) "/user/starred" else "/users/$login/starred") + "?sort=created&per_page=$PER&page=$page", force)

    suspend fun forks(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Repo>>("${r(o, n)}/forks?sort=newest&per_page=$PER&page=$page", force)

    // ---------------- 组织 ----------------
    suspend fun myOrgs(force: Boolean) = Api.get<List<Org>>("/user/orgs?per_page=100", force)
    suspend fun userOrgs(login: String, force: Boolean) = Api.get<List<Org>>("/users/$login/orgs?per_page=100", force)
    suspend fun org(login: String, force: Boolean = false) = Api.get<Org>("/orgs/$login", force)
    suspend fun orgMembers(org: String, page: Int, force: Boolean) = Api.get<List<User>>("/orgs/$org/members?per_page=$PER&page=$page", force)

    // ---------------- 邀请 ----------------
    suspend fun repoInvitations(force: Boolean) = Api.get<List<Invitation>>("/user/repository_invitations?per_page=100", force)
    suspend fun acceptInvitation(id: Long) = Api.exec("PATCH", "/user/repository_invitations/$id")
    suspend fun declineInvitation(id: Long) = Api.exec("DELETE", "/user/repository_invitations/$id")
    suspend fun orgInvitations(force: Boolean) = Api.get<List<OrgMembership>>("/user/memberships/orgs?state=pending&per_page=100", force)
    suspend fun acceptOrg(org: String) = Api.exec("PATCH", "/user/memberships/orgs/$org", buildJsonObject { put("state", "active") })

    // ---------------- 仓库 ----------------
    suspend fun repo(o: String, n: String, force: Boolean = false) = Api.get<Repo>(r(o, n), force).also { if (it.isPrivate) Net.markPrivate(it.fullName) }

    private fun List<Repo>.track() = onEach { if (it.isPrivate) Net.markPrivate(it.fullName) }

    suspend fun readme(o: String, n: String, ref: String?, force: Boolean = false): String? = try {
        Api.text("${r(o, n)}/readme" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), Api.HTML, force)
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    suspend fun contents(o: String, n: String, path: String, ref: String?, force: Boolean): List<Content> {
        val body = Api.text("${r(o, n)}/contents/${Api.encodePath(path)}" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), force = force)
        val el = Api.json.parseToJsonElement(body)
        return if (el is JsonArray) Api.json.decodeFromJsonElement(ListSerializer(Content.serializer()), el)
        else listOf(Api.json.decodeFromJsonElement(Content.serializer(), el))
    }

    /** 源码原文：公开仓库优先走加速节点，失败或私有时回退到 API */
    suspend fun fileRaw(o: String, n: String, path: String, ref: String?): String {
        Net.freshRawUrl(o, n, ref ?: "HEAD", Api.encodePath(path))?.let { url ->
            runCatching {
                val resp = Api.call("GET", url)
                // 个别代理被拦截时会返回 200 的 HTML 页面，需排除
                val html = resp.headers["content-type"]?.startsWith("text/html") == true
                if (resp.code == 200 && (!html || path.endsWith(".html") || path.endsWith(".htm"))) return resp.body
            }
        }
        return Api.text("${r(o, n)}/contents/${Api.encodePath(path)}" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), Api.RAW)
    }

    suspend fun fileHtml(o: String, n: String, path: String, ref: String?) =
        Api.text("${r(o, n)}/contents/${Api.encodePath(path)}" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), Api.HTML)

    suspend fun branches(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Branch>>("${r(o, n)}/branches?per_page=100&page=$page", force)
    suspend fun tags(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Tag>>("${r(o, n)}/tags?per_page=100&page=$page", force)
    suspend fun languages(o: String, n: String) = Api.get<Map<String, Long>>("${r(o, n)}/languages")
    suspend fun contributors(o: String, n: String, page: Int, force: Boolean) = Api.get<List<User>>("${r(o, n)}/contributors?per_page=$PER&page=$page", force)
    suspend fun stargazers(o: String, n: String, page: Int, force: Boolean) = Api.get<List<User>>("${r(o, n)}/stargazers?per_page=$PER&page=$page", force)
    suspend fun watchers(o: String, n: String, page: Int, force: Boolean) = Api.get<List<User>>("${r(o, n)}/subscribers?per_page=$PER&page=$page", force)

    suspend fun isStarred(o: String, n: String) = Api.exists("/user/starred/$o/$n")
    suspend fun star(o: String, n: String, on: Boolean) = Api.exec(if (on) "PUT" else "DELETE", "/user/starred/$o/$n")
    suspend fun isWatching(o: String, n: String): Boolean = try {
        Api.get<JsonObject>("${r(o, n)}/subscription", force = true)["subscribed"]?.let { (it as JsonPrimitive).content == "true" } ?: false
    } catch (e: ApiException) {
        if (e.code == 404) false else throw e
    }
    suspend fun watch(o: String, n: String, on: Boolean) =
        if (on) Api.exec("PUT", "${r(o, n)}/subscription", buildJsonObject { put("subscribed", true) })
        else Api.exec("DELETE", "${r(o, n)}/subscription")
    suspend fun fork(o: String, n: String) = Api.send<Repo>("POST", "${r(o, n)}/forks")

    // ---------------- 提交 ----------------
    suspend fun commits(o: String, n: String, sha: String?, path: String?, page: Int, force: Boolean) =
        Api.get<List<Commit>>("${r(o, n)}/commits?per_page=$PER&page=$page" + (sha?.let { "&sha=${Api.q(it)}" } ?: "") + (path?.let { "&path=${Api.q(it)}" } ?: ""), force)
    suspend fun commit(o: String, n: String, sha: String) = Api.get<Commit>("${r(o, n)}/commits/$sha")

    // ---------------- Issue / PR ----------------
    suspend fun issues(o: String, n: String, state: String, page: Int, force: Boolean) =
        Api.get<List<Issue>>("${r(o, n)}/issues?state=$state&per_page=$PER&page=$page", force).filter { it.pullRequest == null }
    suspend fun pulls(o: String, n: String, state: String, page: Int, force: Boolean) =
        Api.get<List<Pull>>("${r(o, n)}/pulls?state=$state&sort=updated&direction=desc&per_page=$PER&page=$page", force)
    suspend fun issue(o: String, n: String, num: Int, force: Boolean) = Api.get<Issue>("${r(o, n)}/issues/$num", force, Api.FULL)
    suspend fun issueComments(o: String, n: String, num: Int, force: Boolean) =
        Api.get<List<Comment>>("${r(o, n)}/issues/$num/comments?per_page=100", force, Api.FULL)
    suspend fun pull(o: String, n: String, num: Int, force: Boolean) = Api.get<Pull>("${r(o, n)}/pulls/$num", force, Api.FULL)
    suspend fun pullFiles(o: String, n: String, num: Int, page: Int) = Api.get<List<CommitFile>>("${r(o, n)}/pulls/$num/files?per_page=50&page=$page")
    suspend fun pullCommits(o: String, n: String, num: Int) = Api.get<List<Commit>>("${r(o, n)}/pulls/$num/commits?per_page=100")
    suspend fun pullReviews(o: String, n: String, num: Int, force: Boolean) = Api.get<List<Review>>("${r(o, n)}/pulls/$num/reviews?per_page=100", force, Api.FULL)
    suspend fun addComment(o: String, n: String, num: Int, body: String) =
        Api.send<Comment>("POST", "${r(o, n)}/issues/$num/comments", buildJsonObject { put("body", body) })
    suspend fun setIssueState(o: String, n: String, num: Int, open: Boolean, reason: String? = null) =
        Api.send<Issue>("PATCH", "${r(o, n)}/issues/$num", buildJsonObject {
            put("state", if (open) "open" else "closed")
            reason?.let { put("state_reason", it) }
        })
    suspend fun setPullState(o: String, n: String, num: Int, open: Boolean) =
        Api.send<Pull>("PATCH", "${r(o, n)}/pulls/$num", buildJsonObject { put("state", if (open) "open" else "closed") })
    suspend fun merge(o: String, n: String, num: Int, method: String) =
        Api.exec("PUT", "${r(o, n)}/pulls/$num/merge", buildJsonObject { put("merge_method", method) })
    suspend fun review(o: String, n: String, num: Int, event: String, body: String) =
        Api.exec("POST", "${r(o, n)}/pulls/$num/reviews", buildJsonObject {
            put("event", event)
            if (body.isNotBlank()) put("body", body)
        })
    suspend fun createIssue(o: String, n: String, title: String, body: String) =
        Api.send<Issue>("POST", "${r(o, n)}/issues", buildJsonObject { put("title", title); put("body", body) })

    // ---------------- Releases ----------------
    suspend fun releases(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Release>>("${r(o, n)}/releases?per_page=10&page=$page", force, Api.FULL)

    // ---------------- Actions ----------------
    suspend fun runs(o: String, n: String, workflowId: Long?, page: Int, force: Boolean) =
        Api.get<Runs>((if (workflowId != null) "${r(o, n)}/actions/workflows/$workflowId/runs" else "${r(o, n)}/actions/runs") + "?per_page=$PER&page=$page", force).workflowRuns
    suspend fun run(o: String, n: String, id: Long, force: Boolean) = Api.get<Run>("${r(o, n)}/actions/runs/$id", force)
    suspend fun jobs(o: String, n: String, runId: Long, force: Boolean) = Api.get<Jobs>("${r(o, n)}/actions/runs/$runId/jobs?per_page=100", force).jobs
    suspend fun artifacts(o: String, n: String, runId: Long, force: Boolean) = Api.get<Artifacts>("${r(o, n)}/actions/runs/$runId/artifacts", force).artifacts
    suspend fun rerun(o: String, n: String, runId: Long, failedOnly: Boolean) =
        Api.exec("POST", "${r(o, n)}/actions/runs/$runId/" + if (failedOnly) "rerun-failed-jobs" else "rerun")
    suspend fun cancelRun(o: String, n: String, runId: Long) = Api.exec("POST", "${r(o, n)}/actions/runs/$runId/cancel")
    suspend fun jobLog(o: String, n: String, jobId: Long) = Api.text("${r(o, n)}/actions/jobs/$jobId/logs", force = true)
    suspend fun workflows(o: String, n: String, force: Boolean) = Api.get<Workflows>("${r(o, n)}/actions/workflows?per_page=100", force).workflows
    suspend fun dispatch(o: String, n: String, workflowId: Long, ref: String) =
        Api.exec("POST", "${r(o, n)}/actions/workflows/$workflowId/dispatches", buildJsonObject { put("ref", ref) })
    suspend fun setWorkflowEnabled(o: String, n: String, workflowId: Long, on: Boolean) =
        Api.exec("PUT", "${r(o, n)}/actions/workflows/$workflowId/" + if (on) "enable" else "disable")

    // ---------------- 洞察 Insights（官方 App 不提供） ----------------
    suspend fun views(o: String, n: String, force: Boolean) = Api.get<Traffic>("${r(o, n)}/traffic/views", force)
    suspend fun clones(o: String, n: String, force: Boolean) = Api.get<Traffic>("${r(o, n)}/traffic/clones", force)
    suspend fun referrers(o: String, n: String, force: Boolean) = Api.get<List<Referrer>>("${r(o, n)}/traffic/popular/referrers", force)
    suspend fun popularPaths(o: String, n: String, force: Boolean) = Api.get<List<PopularPath>>("${r(o, n)}/traffic/popular/paths", force)

    /** 统计接口在首次计算时返回 202，需要稍后重试 */
    suspend fun commitActivity(o: String, n: String, force: Boolean): List<WeekActivity> {
        repeat(4) { attempt ->
            val resp = Api.call("GET", "${r(o, n)}/stats/commit_activity", force = force || attempt > 0)
            if (resp.code == 200) return Api.json.decodeFromString(ListSerializer(WeekActivity.serializer()), resp.body)
            if (resp.code != 202) Api.ensureOk(resp)
            delay(1500L * (attempt + 1))
        }
        return emptyList()
    }

    // ---------------- 仓库管理 ----------------
    suspend fun collaborators(o: String, n: String, force: Boolean) = Api.get<List<User>>("${r(o, n)}/collaborators?per_page=100", force)
    suspend fun addCollaborator(o: String, n: String, login: String, permission: String) =
        Api.exec("PUT", "${r(o, n)}/collaborators/$login", buildJsonObject { put("permission", permission) })
    suspend fun removeCollaborator(o: String, n: String, login: String) = Api.exec("DELETE", "${r(o, n)}/collaborators/$login")
    suspend fun pendingInvites(o: String, n: String, force: Boolean) = Api.get<List<Invitation>>("${r(o, n)}/invitations", force)
    suspend fun cancelInvite(o: String, n: String, id: Long) = Api.exec("DELETE", "${r(o, n)}/invitations/$id")
    suspend fun updateRepo(o: String, n: String, patch: JsonObject) = Api.send<Repo>("PATCH", r(o, n), patch)
    suspend fun setTopics(o: String, n: String, topics: List<String>) =
        Api.exec("PUT", "${r(o, n)}/topics", JsonObject(mapOf("names" to JsonArray(topics.map { JsonPrimitive(it) }))))
    suspend fun deleteRepo(o: String, n: String) = Api.exec("DELETE", r(o, n))
    suspend fun createRepo(org: String?, name: String, desc: String, private: Boolean, autoInit: Boolean) =
        Api.send<Repo>("POST", if (org == null) "/user/repos" else "/orgs/$org/repos", buildJsonObject {
            put("name", name)
            put("description", desc)
            put("private", private)
            put("auto_init", autoInit)
        })

    // ---------------- 通知 ----------------
    suspend fun notifications(all: Boolean, participating: Boolean, page: Int, force: Boolean) =
        Api.get<List<Notification>>("/notifications?all=$all&participating=$participating&per_page=$PER&page=$page", force)
    suspend fun markRead(id: String) = Api.exec("PATCH", "/notifications/threads/$id")
    suspend fun markDone(id: String) = Api.exec("DELETE", "/notifications/threads/$id")
    suspend fun markAllRead() = Api.exec("PUT", "/notifications", buildJsonObject { put("read", true) })

    // ---------------- 动态 ----------------
    suspend fun received(login: String, page: Int, force: Boolean) = Api.get<List<Event>>("/users/$login/received_events?per_page=$PER&page=$page", force)
    suspend fun userEvents(login: String, page: Int, force: Boolean) = Api.get<List<Event>>("/users/$login/events?per_page=$PER&page=$page", force)

    // ---------------- 搜索 ----------------
    suspend fun searchRepos(q: String, sort: String?, page: Int) = Api.get<SearchResult<Repo>>("/search/repositories?q=${Api.q(q)}&per_page=$PER&page=$page" + (sort?.let { "&sort=$it" } ?: ""))
    suspend fun searchUsers(q: String, page: Int) = Api.get<SearchResult<User>>("/search/users?q=${Api.q(q)}&per_page=$PER&page=$page")
    suspend fun searchIssues(q: String, page: Int) = Api.get<SearchResult<Issue>>("/search/issues?q=${Api.q(q)}&per_page=$PER&page=$page&advanced_search=true")

    // ---------------- Gist ----------------
    suspend fun gists(login: String?, page: Int, force: Boolean) = Api.get<List<Gist>>((if (login == null) "/gists" else "/users/$login/gists") + "?per_page=$PER&page=$page", force)
    suspend fun starredGists(page: Int, force: Boolean) = Api.get<List<Gist>>("/gists/starred?per_page=$PER&page=$page", force)
    suspend fun gist(id: String, force: Boolean) = Api.get<Gist>("/gists/$id", force)

    suspend fun rateLimit() = Api.get<RateLimit>("/rate_limit", force = true)

    // ---------------- GraphQL：贡献热力图 / 置顶仓库 ----------------
    private const val CONTRIB_Q = """
        query(${'$'}login: String!, ${'$'}from: DateTime, ${'$'}to: DateTime) {
          user(login: ${'$'}login) {
            contributionsCollection(from: ${'$'}from, to: ${'$'}to) {
              contributionYears
              totalCommitContributions
              totalIssueContributions
              totalPullRequestContributions
              totalPullRequestReviewContributions
              totalRepositoryContributions
              restrictedContributionsCount
              contributionCalendar {
                totalContributions
                weeks { contributionDays { date contributionCount contributionLevel weekday } }
              }
              commitContributionsByRepository(maxRepositories: 10) {
                repository { nameWithOwner isPrivate }
                contributions { totalCount }
              }
            }
          }
        }"""

    /** year == null 表示最近一年（与 GitHub 个人主页一致） */
    suspend fun contributions(login: String, year: Int?): Contributions {
        val vars = if (year == null) mapOf("login" to login)
        else mapOf("login" to login, "from" to "$year-01-01T00:00:00Z", "to" to "$year-12-31T23:59:59Z")
        val data = Api.graphql(CONTRIB_Q, vars)
        val c = data.obj("user")?.get("contributionsCollection") ?: throw ApiException(404, "用户不存在")
        return Api.plain.decodeFromJsonElement(c)
    }

    private const val PINNED_Q = """
        query(${'$'}login: String!) {
          repositoryOwner(login: ${'$'}login) {
            ... on ProfileOwner {
              pinnedItems(first: 6, types: [REPOSITORY]) {
                nodes { ... on Repository { name owner { login } description stargazerCount forkCount isPrivate primaryLanguage { name color } } }
              }
            }
          }
        }"""

    suspend fun pinned(login: String): List<PinnedRepo> {
        val nodes = Api.graphql(PINNED_Q, mapOf("login" to login)).obj("repositoryOwner").obj("pinnedItems").arr("nodes") ?: return emptyList()
        return nodes.objects().map { Api.plain.decodeFromJsonElement<PinnedRepo>(it) }
    }

    /** 与官方 App 不同，这里可以直接看到 Token 所拥有的全部 scope */
    suspend fun tokenScopes(): String? = Api.call("GET", "/user", force = true).headers["x-oauth-scopes"]
}
