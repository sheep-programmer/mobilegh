package com.mobilegh.data

import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trc
import com.mobilegh.i18n.trf

/** GitHub REST / GraphQL 接口封装 */
object GitHub {
    const val PER = 30

    /** Star 页单独用 100 条一页：Star 数量可以上万，每页越小翻页越多 */
    const val STARRED_PER = 100

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
        Api.get<List<Repo>>(
            buildString {
                append("/user/repos?affiliation=$affiliation")
                // GitHub 对 affiliation 与 visibility 的组合在不同 API 版本行为不一致；
                // 组织成员仓库由下面的组织接口负责，避免筛选后出现空列表。
                if (affiliation == "owner") append("&visibility=$visibility")
                append("&sort=$sort&per_page=$PER&page=$page")
            },
            force,
        ).track()

    suspend fun userRepos(login: String, sort: String, page: Int, force: Boolean) =
        Api.get<List<Repo>>("/users/$login/repos?type=owner&sort=$sort&per_page=$PER&page=$page", force)

    suspend fun orgRepos(org: String, sort: String, page: Int, force: Boolean) =
        Api.get<List<Repo>>("/orgs/$org/repos?type=all&sort=$sort&per_page=$PER&page=$page", force).track()

    /** 一次读取一个组织可访问的全部仓库，专门用于移动端的组织筛选页。 */
    private suspend fun orgReposAll(org: String, sort: String, force: Boolean): List<Repo> {
        val result = ArrayList<Repo>()
        var page = 1
        while (page <= 20) {
            val list = Api.get<List<Repo>>("/orgs/${Api.encodePath(org)}/repos?type=all&sort=$sort&per_page=100&page=$page", force).track()
            result += list
            if (list.size < 100) break
            page++
        }
        return result
    }

    /**
     * 汇总用户加入的组织仓库，去重后按最近推送或名称排序。
     *
     * 先走用户维度的 /user/repos?affiliation=organization_member（对受限组织更健壮），
     * 再用 /orgs/{org}/repos 补充（可拿到用户没直接参与但可访问的仓库）。
     */
    suspend fun organizationRepos(orgs: List<String>, sort: String, force: Boolean): List<Repo> {
        if (orgs.isEmpty()) return emptyList()
        val wanted = orgs.map { it.lowercase() }.toSet()
        val result = LinkedHashMap<Long, Repo>()
        // 主路径：用户维度的组织成员仓库
        orgMemberRepos(force).filter { it.owner.login.lowercase() in wanted }.forEach { result[it.id] = it }
        // 补充路径：逐个组织读取，受限时忽略该组织的失败
        orgs.forEach { org ->
            try {
                orgReposAll(org, sort, force).forEach { result[it.id] = it }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: ApiException) {
                AppLog.warn("network", trf("组织仓库读取失败：{0} ({1})", org, e.code))
                if (orgs.size == 1 && result.isEmpty()) throw e
            }
        }
        return result.values.sortedWith(
            if (sort == "full_name") compareBy(String.CASE_INSENSITIVE_ORDER) { it.fullName }
            else compareByDescending<Repo> { it.pushedAt ?: it.updatedAt ?: it.createdAt ?: "" },
        )
    }

    /**
     * 已 Star 的仓库。[sort] 只能是 created（Star 时间）、updated（更新时间）或 stars（仓库 star 数）；
     * [direction] 为 asc / desc。REST 对不认识的 sort 会静默回退成默认顺序，不要传别的值。
     *
     * 每页用 100（[STARRED_PER]）：Star 可以达到上万条，30 条一页会有几百页，
     * 100 条一页既少翻页也少占额度。
     */
    suspend fun starred(login: String?, page: Int, force: Boolean, sort: String = "created", direction: String = "desc") =
        Api.get<List<Repo>>(starredUrl(login, page, sort, direction), force)

    /** [starred] 的 URL 拼装：单独抽出来便于测试（REST 会静默忽略不认识的 sort，必须确定传对了） */
    internal fun starredUrl(login: String?, page: Int, sort: String, direction: String) =
        (if (login == null) "/user/starred" else "/users/$login/starred") +
            "?sort=$sort&direction=$direction&per_page=$STARRED_PER&page=$page"

    suspend fun forks(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Repo>>("${r(o, n)}/forks?sort=newest&per_page=$PER&page=$page", force)

    // ---------------- 组织 ----------------
    suspend fun myOrgs(force: Boolean): List<Org> = orgAccess(force).orgs

    data class OrgAccess(
        val orgs: List<Org>,
        val restricted: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val scopes: String? = null,
    )

    suspend fun orgAccess(force: Boolean): OrgAccess = OrganizationAccess.read(force).let {
        OrgAccess(it.orgs, warnings = it.warnings, scopes = it.scopes)
    }

    /** All repositories actually visible to this token, including organization membership. */
    suspend fun orgMemberRepos(force: Boolean = false): List<Repo> {
        val result = linkedMapOf<Long, Repo>()
        for (page in 1..20) {
            val list = Api.get<List<Repo>>("/user/repos?affiliation=organization_member&sort=pushed&per_page=100&page=$page", force).track()
            list.forEach { result[it.id] = it }
            if (list.size < 100) break
        }
        return result.values.toList()
    }

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
    suspend fun repoLicense(o: String, n: String) = Api.get<LicenseFile>("${r(o, n)}/license", false)

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

    /**
     * 源码原文。公开仓库优先走加速节点（该节点的分支内容有几秒到几分钟的缓存），
     * 刚提交过文件时用 [force] 跳过它直接读 API，保证看到的就是最新内容。
     */
    suspend fun fileRaw(o: String, n: String, path: String, ref: String?, force: Boolean = false): String {
        if (!force) Net.freshRawUrl(o, n, ref ?: "HEAD", Api.encodePath(path))?.let { url ->
            runCatching {
                val resp = Api.call("GET", url)
                // 个别代理被拦截时会返回 200 的 HTML 页面，需排除
                val html = resp.headers["content-type"]?.startsWith("text/html") == true
                if (resp.code == 200 && (!html || path.endsWith(".html") || path.endsWith(".htm"))) return resp.body
            }
        }
        return Api.text("${r(o, n)}/contents/${Api.encodePath(path)}" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), Api.RAW, force)
    }

    suspend fun fileHtml(o: String, n: String, path: String, ref: String?, force: Boolean = false) =
        Api.text("${r(o, n)}/contents/${Api.encodePath(path)}" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), Api.HTML, force)

    // ---------------- 文件写入 ----------------
    /** 单个文件的元数据 + Base64 内容（编辑前读取，拿到当前 blob sha） */
    suspend fun fileContent(o: String, n: String, path: String, ref: String?, force: Boolean = true) =
        Api.get<Content>("${r(o, n)}/contents/${Api.encodePath(path)}" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), force)

    /** 新建或更新文件：sha 为空表示新建；GitHub 会在该分支上生成一个提交 */
    suspend fun putFile(o: String, n: String, path: String, text: String, message: String, sha: String?, branch: String) =
        Api.send<FileCommit>("PUT", "${r(o, n)}/contents/${Api.encodePath(path)}", buildJsonObject {
            put("message", message)
            put("content", RepoWrite.encode(text))
            if (sha != null) put("sha", sha)
            put("branch", branch)
        })

    suspend fun deleteFile(o: String, n: String, path: String, sha: String, message: String, branch: String) =
        Api.send<FileCommit>("DELETE", "${r(o, n)}/contents/${Api.encodePath(path)}", buildJsonObject {
            put("message", message)
            put("sha", sha)
            put("branch", branch)
        })

    // ---------------- Git 数据接口（配合 GitPack：对象走 git pack 传，树与提交走这里） ----------------
    /** 某个提交对应的文件树 sha */
    suspend fun commitTreeSha(o: String, n: String, sha: String): String =
        Api.get<Commit>("${r(o, n)}/commits/$sha", force = true).commit.tree?.sha?.takeIf { it.length >= 40 }
            ?: throw ApiException(500, tr("GitHub 没有返回该提交的文件树，请稍后重试"))

    /**
     * 递归读取一棵树，得到「仓库里已经有哪些文件」。
     * 树太大时 GitHub 会返回 truncated，这份表就不完整，调用方应当作全部重写处理，否则会漏文件。
     */
    suspend fun treeIndex(o: String, n: String, tree: String): TreeIndex {
        val t = Api.get<RepositoryTree>("${r(o, n)}/git/trees/$tree?recursive=1", force = true)
        val blobs = HashMap<String, RepoBlob>()
        t.tree.forEach { if (it.type == "blob") blobs[it.path] = RepoBlob(it.sha, it.mode.ifEmpty { "100644" }) }
        return TreeIndex(blobs, t.truncated)
    }

    /** 在 [base] 之上叠加 [entries]，返回新树 sha；基准树里没被列出的文件原样保留 */
    suspend fun createTree(o: String, n: String, base: String, entries: List<JsonObject>): String {
        val body = buildJsonObject {
            put("base_tree", base)
            put("tree", JsonArray(entries))
        }
        return shaOf("POST", "${r(o, n)}/git/trees", body = body, what = tr("文件树"))
    }

    /** 用现成的树生成一个提交（作者与提交者由 GitHub 按 Token 归属，和网页提交一致） */
    suspend fun createCommit(o: String, n: String, message: String, tree: String, parents: List<String>): String {
        val body = buildJsonObject {
            put("message", message)
            put("tree", tree)
            put("parents", JsonArray(parents.map { JsonPrimitive(it) }))
        }
        return shaOf("POST", "${r(o, n)}/git/commits", body = body, what = trc("提交", "noun"))
    }

    /** 把分支快进到 [sha]，等价于 `git push`；受保护分支或非快进会被 GitHub 拒绝 */
    suspend fun updateBranch(o: String, n: String, branch: String, sha: String) =
        Api.exec("PATCH", "${r(o, n)}/git/refs/${Api.encodePath("heads/$branch")}", buildJsonObject { put("sha", sha) })

    /** 新分支直接指向 [sha] */
    suspend fun createRef(o: String, n: String, branch: String, sha: String) =
        Api.send<GitRef>("POST", "${r(o, n)}/git/refs", buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", sha)
        })

    /** 发出写入请求并取回响应里的 sha；[what] 只用于出错时把话说清楚 */
    private suspend fun shaOf(method: String, path: String, body: JsonElement? = null, what: String): String {
        val resp = Api.ensureOk(Api.call(method, path, body, force = true))
        val sha = runCatching { (Api.json.parseToJsonElement(resp.body) as? JsonObject)?.get("sha") as? JsonPrimitive }
            .getOrNull()?.takeIf { it !is JsonNull }?.contentOrNull
        return sha ?: throw ApiException(500, trf("GitHub 没有返回新的{0}，请先打开提交列表确认写入结果，再决定是否重试", what))
    }

    // ---------------- 分支 ----------------
    /** 分支、标签或提交 SHA 对应的提交 SHA */
    suspend fun resolveSha(o: String, n: String, ref: String) =
        Api.text("${r(o, n)}/commits/${Api.encodePath(ref)}", "application/vnd.github.sha", force = true).trim()

    suspend fun branch(o: String, n: String, branch: String, force: Boolean = true) = Api.get<Branch>("${r(o, n)}/branches/${Api.encodePath(branch)}", force)

    /** 该名称是否为现有分支（标签或 SHA 返回 false） */
    suspend fun isBranch(o: String, n: String, ref: String): Boolean = try {
        branch(o, n, ref); true
    } catch (e: ApiException) {
        if (e.code == 404) false else throw e
    }

    suspend fun createBranch(o: String, n: String, branch: String, sha: String) =
        Api.send<GitRef>("POST", "${r(o, n)}/git/refs", buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", sha)
        })


    suspend fun createPull(o: String, n: String, title: String, body: String, head: String, base: String, draft: Boolean = false) =
        Api.send<Pull>("POST", "${r(o, n)}/pulls", buildJsonObject {
            put("title", title)
            put("body", body)
            put("head", head)
            put("base", base)
            if (draft) put("draft", true)
        }).also { RepoWrite.changed(o, n, RepoWrite.ISSUES) }

    suspend fun branches(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Branch>>("${r(o, n)}/branches?per_page=100&page=$page", force)
    suspend fun tags(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Tag>>("${r(o, n)}/tags?per_page=100&page=$page", force)
    suspend fun languages(o: String, n: String) = Api.get<Map<String, Long>>("${r(o, n)}/languages")
    suspend fun contributors(o: String, n: String, page: Int, force: Boolean) = Api.get<List<User>>("${r(o, n)}/contributors?per_page=$PER&page=$page", force)
    suspend fun stargazers(o: String, n: String, page: Int, force: Boolean) =
        ownerOnlyList("Star") { Api.get<List<User>>("${r(o, n)}/stargazers?per_page=$PER&page=$page", force) }
    suspend fun watchers(o: String, n: String, page: Int, force: Boolean) =
        ownerOnlyList("Watch") { Api.get<List<User>>("${r(o, n)}/subscribers?per_page=$PER&page=$page", force) }

    /** GitHub 现在只向仓库所有者返回 Star / Watch 用户列表，其他人一律 404；换成说明真实原因的提示 */
    private suspend fun <T> ownerOnlyList(kind: String, block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.code == 404) throw ApiException(404, trf("GitHub 只向仓库所有者公开 {0} 用户列表", kind)) else throw e
    }

    suspend fun isStarred(o: String, n: String) = Api.exists("/user/starred/$o/$n")
    suspend fun star(o: String, n: String, on: Boolean) =
        Api.exec(if (on) "PUT" else "DELETE", "/user/starred/$o/$n").also { StarListCache.onStarChanged() }
    suspend fun isWatching(o: String, n: String): Boolean = try {
        Api.get<JsonObject>("${r(o, n)}/subscription", force = true)["subscribed"]?.let { (it as JsonPrimitive).content == "true" } ?: false
    } catch (e: ApiException) {
        if (e.code == 404) false else throw e
    }
    suspend fun watch(o: String, n: String, on: Boolean) =
        if (on) Api.exec("PUT", "${r(o, n)}/subscription", buildJsonObject { put("subscribed", true) })
        else Api.exec("DELETE", "${r(o, n)}/subscription")
    /**
     * Fork 仓库。
     *
     * `POST /repos/{o}/{n}/forks` 只接受 organization / name / default_branch_only 三个参数，
     * 没有 description；GitHub 网页端是在 fork 完成后用 PATCH 补写描述的，所以描述拆到
     * [setDescription] 单独调用——描述写失败时已经建好的 fork 不会丢。
     */
    suspend fun fork(
        o: String,
        n: String,
        organization: String? = null,
        newName: String? = null,
        defaultBranchOnly: Boolean = true,
    ): Repo = Api.send<Repo>("POST", "${r(o, n)}/forks", forkBody(organization, newName, defaultBranchOnly))
        .also { RepoWrite.reposChanged() }

    /**
     * fork 请求体。单独抽成纯函数便于测试：GitHub 会静默忽略不认识的字段，
     * 只靠返回结果看不出参数是否发对了。
     */
    internal fun forkBody(organization: String?, newName: String?, defaultBranchOnly: Boolean) = buildJsonObject {
        organization?.takeIf { it.isNotBlank() }?.let { put("organization", JsonPrimitive(it)) }
        newName?.takeIf { it.isNotBlank() }?.let { put("name", JsonPrimitive(it)) }
        // 显式传值：省略时行为依赖服务端默认，写明避免以后默认值变化
        put("default_branch_only", JsonPrimitive(defaultBranchOnly))
    }

    /** fork 之后补写描述（fork 接口本身不接受 description） */
    suspend fun setDescription(o: String, n: String, description: String) =
        Api.send<Repo>("PATCH", r(o, n), buildJsonObject { put("description", JsonPrimitive(description)) })

    // ---------------- 提交 ----------------
    suspend fun commits(o: String, n: String, sha: String?, path: String?, page: Int, force: Boolean,
        author: String? = null, since: String? = null, until: String? = null) =
        Api.get<List<Commit>>("${r(o, n)}/commits?per_page=$PER&page=$page" +
            (sha?.let { "&sha=${Api.q(it)}" } ?: "") + (path?.let { "&path=${Api.q(it)}" } ?: "") +
            (author?.let { "&author=${Api.q(it)}" } ?: "") + (since?.let { "&since=${Api.q(it)}" } ?: "") +
            (until?.let { "&until=${Api.q(it)}" } ?: ""), force)
    suspend fun commit(o: String, n: String, sha: String) = Api.get<Commit>("${r(o, n)}/commits/$sha")

    // ---------------- Issue / PR ----------------
    /**
     * sort: created / updated / comments。列表接口会混入 PR；这里不做过滤，
     * 以免一页过滤后不足 PER 条被分页器误判为最后一页，由界面按 pullRequest 过滤。
     */
    suspend fun issues(o: String, n: String, state: String, page: Int, force: Boolean, sort: String = "created", direction: String = "desc") =
        Api.get<List<Issue>>("${r(o, n)}/issues?state=$state&sort=$sort&direction=$direction&per_page=$PER&page=$page", force)
    /**
     * 开放中的 Issue 数（不含 PR）。用搜索接口统计，与网页上 Issues 页的 Open 计数一致。
     * 仓库的 open_issues_count 与此常差 1，所以不用它直接减 PR 得出 Issue 数。
     */
    /**
     * 开放中的 Issue 数：仓库的 open_issues_count（包含 PR）减去开放 PR 数。
     * 两个值都是实时的；搜索接口的 total_count 要等索引更新，新建后马上刷新可能还是旧数。
     */
    suspend fun openIssueCount(o: String, n: String, force: Boolean = false): Int = coroutineScope {
        val all = async { repo(o, n, force).openIssuesCount }
        val pulls = async { openPullCount(o, n, force) }
        (all.await() - pulls.await()).coerceAtLeast(0)
    }

    /**
     * 开放中的 PR 数量。GitHub 的 open_issues_count 把 PR 也算在内，真正的 Issue 数要减掉它。
     * per_page=1 时 Link 头里 rel="last" 的页码就是总数；没有 Link 头时只可能是 0 或 1 条。
     */
    suspend fun openPullCount(o: String, n: String, force: Boolean = false): Int =
        countList("${r(o, n)}/pulls?state=open&per_page=1&page=1", force)

    /** 发布数（已发布的 Release）。 */
    suspend fun releaseCount(o: String, n: String, force: Boolean = false): Int =
        countList("${r(o, n)}/releases?per_page=1&page=1", force)

    /** 贡献者数（含匿名贡献者，与网页 Contributors 计数一致）。 */
    suspend fun contributorCount(o: String, n: String, force: Boolean = false): Int =
        countList("${r(o, n)}/contributors?anon=1&per_page=1&page=1", force)

    /** 讨论功能是否开启及开放中的讨论数。未开启讨论或未授权时返回 false 与 0。 */
    suspend fun discussionInfo(o: String, n: String, force: Boolean = false): Pair<Boolean, Int> {
        return try {
            val data = Api.graphql(
                "query(\$o: String!, \$n: String!) { repository(owner: \$o, name: \$n) { hasDiscussionsEnabled discussions(states: [OPEN], first: 1) { totalCount } } }",
                mapOf("o" to o, "n" to n),
            )
            val repo = (data["repository"] as? JsonObject)
            val enabled = repo?.get("hasDiscussionsEnabled")?.jsonPrimitive?.booleanOrNull ?: false
            val count = (repo?.get("discussions") as? JsonObject)?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0
            Pair(enabled, count)
        } catch (_: Exception) {
            Pair(false, 0)
        }
    }

    /** 开放中的讨论数。讨论只能用 GraphQL 统计；未开启讨论时返回 0。 */
    suspend fun discussionCount(o: String, n: String, force: Boolean = false): Int =
        discussionInfo(o, n, force).second

    /** 列表接口发一次 per_page=1 的请求，取总数（见 [listTotal]）。 */
    private suspend fun countList(path: String, force: Boolean): Int {
        val resp = Api.call("GET", path, force = force)
        Api.ensureOk(resp)
        return listTotal(resp.headers["Link"], resp.body)
    }

    /** sort: created / updated / popularity / long-running */
    /** 每页读完后在后台补充这一页 PR 的审查与 CI 状态（一页一个 GraphQL 请求，失败不影响列表） */
    suspend fun pulls(o: String, n: String, state: String, page: Int, force: Boolean, sort: String = "updated", direction: String = "desc") =
        Api.get<List<Pull>>("${r(o, n)}/pulls?state=$state&sort=$sort&direction=$direction&per_page=$PER&page=$page", force)
            .also { PullRequestStatuses.loadPage(o, n, it) }
    suspend fun issue(o: String, n: String, num: Int, force: Boolean) = Api.get<Issue>("${r(o, n)}/issues/$num", force, Api.FULL)
    /** 评论每页 100 条，最多连续读取的页数：超长讨论不会无限拉取，但也不再丢掉第 100 条之后的评论 */
    const val MAX_COMMENT_PAGES = 10
    const val COMMENT_PAGE_SIZE = 100

    /** 一页评论（按时间先后）。详情页先显示第一页，后续页在后台逐页追加。 */
    suspend fun issueCommentsPage(o: String, n: String, num: Int, page: Int, force: Boolean): List<Comment> =
        Api.get<List<Comment>>("${r(o, n)}/issues/$num/comments?per_page=$COMMENT_PAGE_SIZE&page=$page", force, Api.FULL)

    /** 一次读完全部评论（最多 [MAX_COMMENT_PAGES] 页）：从 #issuecomment-N 链接进来需要定位到具体评论时使用 */
    suspend fun issueComments(o: String, n: String, num: Int, force: Boolean): List<Comment> {
        val all = mutableListOf<Comment>()
        for (page in 1..MAX_COMMENT_PAGES) {
            val part = issueCommentsPage(o, n, num, page, force && page == 1)
            all += part
            if (part.size < COMMENT_PAGE_SIZE) break
        }
        return all
    }
    suspend fun pull(o: String, n: String, num: Int, force: Boolean) = Api.get<Pull>("${r(o, n)}/pulls/$num", force, Api.FULL)

    /**
     * 相关议题（网页版 Issue 侧栏的 Relationships → Relates to）。该接口要求比 2022-11-28 更新的
     * REST 版本，所以只给这三个请求单独指定版本头，其他接口维持原版本。返回的是标准 Issue 列表，
     * 可能包含其他仓库的内容（[Issue.repositoryUrl] 指向来源仓库）。
     */
    private val RELATED_API = mapOf("X-GitHub-Api-Version" to "2026-03-10")

    suspend fun relatedIssues(o: String, n: String, num: Int, force: Boolean) =
        Api.get<List<Issue>>("${r(o, n)}/issues/$num/relates_to?per_page=100", force, headers = RELATED_API)

    /** [issueId] 是议题的内部 id（[Issue.id]），不是 number */
    suspend fun addRelatedIssue(o: String, n: String, num: Int, issueId: Long) =
        Api.exec("POST", "${r(o, n)}/issues/$num/relates_to", buildJsonObject { put("issue_id", issueId) }, RELATED_API)

    suspend fun removeRelatedIssue(o: String, n: String, num: Int, issueId: Long) =
        Api.exec("DELETE", "${r(o, n)}/issues/$num/relates_to/$issueId", headers = RELATED_API)
    suspend fun pullFiles(o: String, n: String, num: Int, page: Int) = Api.get<List<CommitFile>>("${r(o, n)}/pulls/$num/files?per_page=50&page=$page")
    suspend fun pullCommits(o: String, n: String, num: Int) = Api.get<List<Commit>>("${r(o, n)}/pulls/$num/commits?per_page=100")
    suspend fun pullReviews(o: String, n: String, num: Int, force: Boolean) = Api.get<List<Review>>("${r(o, n)}/pulls/$num/reviews?per_page=100", force, Api.FULL)
    suspend fun addComment(o: String, n: String, num: Int, body: String) =
        Api.send<Comment>("POST", "${r(o, n)}/issues/$num/comments", buildJsonObject { put("body", body) })
            .also { RepoWrite.changed(o, n, RepoWrite.ISSUES) }
    suspend fun setIssueState(o: String, n: String, num: Int, open: Boolean, reason: String? = null) =
        Api.send<Issue>("PATCH", "${r(o, n)}/issues/$num", buildJsonObject {
            put("state", if (open) "open" else "closed")
            reason?.let { put("state_reason", it) }
        }).also { RepoWrite.changed(o, n, RepoWrite.ISSUES) }
    suspend fun setPullState(o: String, n: String, num: Int, open: Boolean) =
        Api.send<Pull>("PATCH", "${r(o, n)}/pulls/$num", buildJsonObject { put("state", if (open) "open" else "closed") })
            .also { RepoWrite.changed(o, n, RepoWrite.ISSUES) }
    /** title/message 为空时由 GitHub 生成默认提交信息；sha 用于防止合并时头部已被更新 */
    suspend fun merge(o: String, n: String, num: Int, method: String, title: String? = null, message: String? = null, sha: String? = null) =
        Api.exec("PUT", "${r(o, n)}/pulls/$num/merge", buildJsonObject {
            put("merge_method", method)
            title?.takeIf { it.isNotBlank() }?.let { put("commit_title", it) }
            message?.takeIf { it.isNotBlank() }?.let { put("commit_message", it) }
            sha?.takeIf { it.isNotBlank() }?.let { put("sha", it) }
        })
    suspend fun deleteBranch(o: String, n: String, branch: String) = Api.exec("DELETE", "${r(o, n)}/git/refs/heads/${Api.encodePath(branch)}")
    /**
     * 提交审查：当前用户有待提交审查时提交它（其中的行内评论随之发布）；没有则直接提交一条新审查。
     */
    suspend fun submitReview(o: String, n: String, num: Int, event: String, body: String) {
        val payload = ReviewApi.submitBody(event, body)
        val pending = findPendingReview(o, n, num, currentLogin())
        if (pending != null) Api.exec("POST", "${r(o, n)}/pulls/$num/reviews/${pending.id}/events", payload)
        else Api.exec("POST", "${r(o, n)}/pulls/$num/reviews", payload)
    }

    // ---------------- 待提交审查（PENDING） ----------------
    private const val MAX_REVIEW_PAGES = 10

    private suspend fun currentLogin(): String = Session.login ?: viewer(true).login

    /** 当前用户在此 PR 上的 PENDING 审查。GitHub 每人每 PR 只允许一个，所以按登录名找到即可。 */
    private suspend fun findPendingReview(o: String, n: String, num: Int, login: String): Review? {
        for (page in 1..MAX_REVIEW_PAGES) {
            val part = Api.get<List<Review>>("${r(o, n)}/pulls/$num/reviews?per_page=100&page=$page", true, Api.FULL)
            ReviewApi.findPending(part, login)?.let { return it }
            if (part.size < 100) break
        }
        return null
    }

    private suspend fun pendingCommentCount(o: String, n: String, num: Int, reviewId: Long): Int {
        var total = 0
        for (page in 1..MAX_REVIEW_PAGES) {
            val part = Api.get<List<ReviewComment>>("${r(o, n)}/pulls/$num/reviews/$reviewId/comments?per_page=100&page=$page", true, Api.FULL)
            total += part.size
            if (part.size < 100) break
        }
        return total
    }

    /** 当前用户的待提交审查（含行内评论数）；没有则返回 null */
    suspend fun pendingReview(o: String, n: String, num: Int): PendingReview? {
        val review = findPendingReview(o, n, num, currentLogin()) ?: return null
        return PendingReview(review.id, pendingCommentCount(o, n, num, review.id))
    }

    /**
     * 把行内评论加入当前用户的待提交审查；没有待提交审查时先创建一个（不带 event）。
     * commitId 为用户正在查看的 PR head 提交。回复、编辑、删除仍是即时操作。
     */
    suspend fun addPendingReviewComment(
        o: String, n: String, num: Int, body: String, commitId: String, path: String,
        line: Int?, side: String = "RIGHT", startLine: Int? = null, startSide: String? = null,
    ) {
        val reviewId = findPendingReview(o, n, num, currentLogin())?.id
            ?: Api.send<Review>("POST", "${r(o, n)}/pulls/$num/reviews", ReviewApi.createBody(commitId)).id
        Api.send<ReviewComment>(
            "POST", "${r(o, n)}/pulls/$num/reviews/$reviewId/comments",
            ReviewApi.commentBody(body, commitId, path, line, side, startLine, startSide),
        )
    }

    /** 放弃当前用户的待提交审查，连同其中未发布的行内评论一并删除；没有待提交审查时不做任何事 */
    suspend fun discardPendingReview(o: String, n: String, num: Int) {
        val review = findPendingReview(o, n, num, currentLogin()) ?: return
        Api.exec("DELETE", "${r(o, n)}/pulls/$num/reviews/${review.id}")
    }

    // ---------------- PR 审查意见（行内评论） ----------------
    suspend fun reviewComments(o: String, n: String, num: Int, force: Boolean): List<ReviewComment> {
        val all = ArrayList<ReviewComment>()
        for (page in 1..10) {
            val l = Api.get<List<ReviewComment>>("${r(o, n)}/pulls/$num/comments?per_page=100&page=$page", force, Api.FULL)
            all += l
            if (l.size < 100) break
        }
        // 「已解决」与精确的「已过时」状态只有 GraphQL 提供；拿不到时退回 REST 的行号判断
        val threads = try { reviewThreads(o, n, num) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            AppLog.warn("network", trf("审查会话状态读取失败：{0}", e.message))
            emptyMap()
        }
        return all.map { c -> threads[c.id]?.let { t -> c.copy(resolved = t.resolved, isOutdated = t.outdated, threadId = t.id) } ?: c }
    }

    private class ThreadState(val id: String, val resolved: Boolean, val outdated: Boolean)

    private const val THREADS_Q = """
        query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}cursor: String) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            pullRequest(number: ${'$'}number) {
              reviewThreads(first: 100, after: ${'$'}cursor) {
                pageInfo { hasNextPage endCursor }
                nodes { id isResolved isOutdated comments(first: 100) { nodes { databaseId } } }
              }
            }
          }
        }"""

    /** 评论 databaseId → 所属会话的状态 */
    private suspend fun reviewThreads(o: String, n: String, num: Int): Map<Long, ThreadState> {
        val map = HashMap<Long, ThreadState>()
        var cursor: String? = null
        repeat(5) {
            val conn = Api.graphql(THREADS_Q, mapOf("owner" to o, "name" to n, "number" to num, "cursor" to cursor))
                .obj("repository").obj("pullRequest").obj("reviewThreads") ?: return map
            conn.arr("nodes")?.objects()?.forEach { t ->
                val st = ThreadState(t.str("id").orEmpty(), t.str("isResolved") == "true", t.str("isOutdated") == "true")
                t.obj("comments").arr("nodes")?.objects()?.forEach { c -> c.str("databaseId")?.toLongOrNull()?.let { map[it] = st } }
            }
            val page = conn.obj("pageInfo")
            if (page.str("hasNextPage") != "true") return map
            cursor = page.str("endCursor") ?: return map
        }
        return map
    }

    suspend fun replyReviewComment(o: String, n: String, num: Int, commentId: Long, body: String) =
        Api.send<ReviewComment>("POST", "${r(o, n)}/pulls/$num/comments/$commentId/replies", buildJsonObject { put("body", body) })
    suspend fun editReviewComment(o: String, n: String, commentId: Long, body: String) =
        Api.send<ReviewComment>("PATCH", "${r(o, n)}/pulls/comments/$commentId", buildJsonObject { put("body", body) })
    suspend fun deleteReviewComment(o: String, n: String, commentId: Long) = Api.exec("DELETE", "${r(o, n)}/pulls/comments/$commentId")

    /** GraphQL 变更：出错时 data 中对应字段为 null，需要单独判断 */
    private suspend fun mutate(field: String, query: String, vars: Map<String, Any?>) {
        val data = Api.graphql(query, vars)
        if (data.obj(field) == null) throw ApiException(403, tr("操作失败（可能没有权限）"))
    }

    /** 置顶到仓库 Issue 列表顶部的 Issue（网页版的 Pin issue，每个仓库最多 3 个） */
    data class PinnedIssue(val number: Int, val title: String, val open: Boolean)

    suspend fun pinnedIssues(o: String, n: String): List<PinnedIssue> {
        val data = Api.graphql(
            "query(${'$'}o: String!, ${'$'}n: String!) { repository(owner: ${'$'}o, name: ${'$'}n) { pinnedIssues(first: 3) { nodes { issue { number title state } } } } }",
            mapOf("o" to o, "n" to n),
        )
        return data.obj("repository").obj("pinnedIssues").arr("nodes").orEmpty().mapNotNull { node ->
            val issue = node.obj("issue") ?: return@mapNotNull null
            val number = issue.int("number") ?: return@mapNotNull null
            PinnedIssue(number, issue.str("title").orEmpty(), issue.str("state") == "OPEN")
        }
    }

    /** 这个 Issue 是否已置顶 */
    suspend fun issuePinned(o: String, n: String, num: Int): Boolean {
        val data = Api.graphql(
            "query(${'$'}o: String!, ${'$'}n: String!, ${'$'}num: Int!) { repository(owner: ${'$'}o, name: ${'$'}n) { issue(number: ${'$'}num) { isPinned } } }",
            mapOf("o" to o, "n" to n, "num" to num),
        )
        return data.obj("repository").obj("issue").str("isPinned") == "true"
    }

    /** 置顶 / 取消置顶 Issue（需要仓库写权限）。REST 没有对应接口，只能走 GraphQL */
    suspend fun setIssuePinned(o: String, n: String, nodeId: String, pinned: Boolean) {
        val m = if (pinned) "pinIssue" else "unpinIssue"
        mutate(m, "mutation(${'$'}id: ID!) { $m(input: { issueId: ${'$'}id }) { issue { isPinned } } }", mapOf("id" to nodeId))
        RepoWrite.changed(o, n, RepoWrite.ISSUES)
    }

    suspend fun resolveThread(threadId: String, resolve: Boolean) {
        val m = if (resolve) "resolveReviewThread" else "unresolveReviewThread"
        mutate(m, "mutation(${'$'}id: ID!) { $m(input: { threadId: ${'$'}id }) { thread { isResolved } } }", mapOf("id" to threadId))
    }

    /** 草稿 ↔ 可评审：REST 没有对应接口，只能走 GraphQL */
    suspend fun setPullDraft(nodeId: String, draft: Boolean) {
        val m = if (draft) "convertPullRequestToDraft" else "markPullRequestReadyForReview"
        mutate(m, "mutation(${'$'}id: ID!) { $m(input: { pullRequestId: ${'$'}id }) { pullRequest { isDraft } } }", mapOf("id" to nodeId))
    }

    // ---------------- PR 审查者 ----------------
    /** 可请求审查的人：协作者列表需要推送权限，读不到时退回可指派人列表 */
    suspend fun reviewerCandidates(o: String, n: String): List<Assignable> = try {
        collectPages(CANDIDATE_PAGE, CANDIDATE_MAX_PAGES) { p -> Api.get<List<Assignable>>("${r(o, n)}/collaborators?per_page=$CANDIDATE_PAGE&page=$p", true) }
    } catch (e: ApiException) {
        if (e.code == 403 || e.code == 404) repoAssignees(o, n) else throw e
    }
    suspend fun requestReviewers(o: String, n: String, num: Int, logins: List<String>) =
        Api.exec("POST", "${r(o, n)}/pulls/$num/requested_reviewers", buildJsonObject { put("reviewers", JsonArray(logins.map { JsonPrimitive(it) })) })
    suspend fun removeReviewers(o: String, n: String, num: Int, logins: List<String>) =
        Api.exec("DELETE", "${r(o, n)}/pulls/$num/requested_reviewers", buildJsonObject { put("reviewers", JsonArray(logins.map { JsonPrimitive(it) })) })

    // ---------------- 检查（check runs + commit status） ----------------
    suspend fun checkRuns(o: String, n: String, sha: String, force: Boolean) =
        Api.get<CheckRuns>("${r(o, n)}/commits/$sha/check-runs?per_page=100", force).checkRuns
    suspend fun combinedStatus(o: String, n: String, sha: String, force: Boolean) =
        Api.get<CombinedStatus>("${r(o, n)}/commits/$sha/status?per_page=100", force)

    /** 两类检查各自读取；只有都失败时才报错（细粒度 Token 可能只授权了其中一种） */
    suspend fun checks(o: String, n: String, sha: String, force: Boolean): Checks = coroutineScope {
        val runs = async { runCatching { checkRuns(o, n, sha, force) } }
        val statuses = async { runCatching { combinedStatus(o, n, sha, force).statuses } }
        val a = runs.await()
        val b = statuses.await()
        if (a.isFailure && b.isFailure) throw a.exceptionOrNull()!!
        Checks(a.getOrDefault(emptyList()), b.getOrDefault(emptyList()))
    }

    /**
     * 没有推送权限时，GitHub 会静默忽略 labels / assignees / milestone。
     * 返回 null 表示 Issue 已创建但响应无法解析，调用方应按「已提交」处理，不要重试（否则会重复创建）。
     */
    suspend fun createIssue(
        o: String, n: String, title: String, body: String,
        labels: List<String> = emptyList(), assignees: List<String> = emptyList(), milestone: Int? = null,
    ): Issue? = Api.sendWrite<Issue>("POST", "${r(o, n)}/issues", buildJsonObject {
        put("title", title)
        put("body", body)
        if (labels.isNotEmpty()) put("labels", JsonArray(labels.map { JsonPrimitive(it) }))
        if (assignees.isNotEmpty()) put("assignees", JsonArray(assignees.map { JsonPrimitive(it) }))
        milestone?.let { put("milestone", it) }
    }).also { RepoWrite.changed(o, n, RepoWrite.ISSUES) }

    /**
     * 读取 .github/ISSUE_TEMPLATE/ 下的模板与 config.yml；目录不存在时返回空集合。
     * 单个文件读取失败只跳过该文件。
     */
    suspend fun issueTemplates(o: String, n: String, force: Boolean): IssueTemplateSet {
        val files = try {
            contents(o, n, IssueTemplates.DIR, null, force)
        } catch (e: ApiException) {
            if (e.code == 404) return IssueTemplateSet() else throw e
        }
        val wanted = files.filter { it.type == "file" && (IssueTemplates.isConfig(it.name) || IssueTemplates.isForm(it.name) || IssueTemplates.isMarkdown(it.name)) }
            .take(IssueTemplates.MAX_FILES)
        val texts = coroutineScope {
            wanted.map { f ->
                async {
                    try {
                        f.name to Api.text("${r(o, n)}/contents/${Api.encodePath(f.path)}", Api.RAW, force)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLog.warn("network", trf("Issue 模板读取失败：{0}", f.path))
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return IssueTemplates.build(texts)
    }

    // ---------------- 评论 / 表情 / 元数据 ----------------
    suspend fun editComment(o: String, n: String, commentId: Long, body: String) =
        Api.send<Comment>("PATCH", "${r(o, n)}/issues/comments/$commentId", buildJsonObject { put("body", body) })
    suspend fun deleteComment(o: String, n: String, commentId: Long) = Api.exec("DELETE", "${r(o, n)}/issues/comments/$commentId")
    suspend fun editIssue(o: String, n: String, num: Int, title: String, body: String) =
        Api.send<Issue>("PATCH", "${r(o, n)}/issues/$num", buildJsonObject { put("title", title); put("body", body) })
            .also { RepoWrite.changed(o, n, RepoWrite.ISSUES) }

    /**
     * 切换表情：POST 创建，已存在时 GitHub 返回 200（不是 201）并带回已有那条的 id，此时再 DELETE 即取消。
     * commentId 为 null 表示对 Issue/PR 正文本身表态。返回 true=现在已表态，false=已取消。
     */
    suspend fun toggleReaction(o: String, n: String, num: Int, commentId: Long?, content: String): Boolean {
        val base = if (commentId == null) "${r(o, n)}/issues/$num/reactions" else "${r(o, n)}/issues/comments/$commentId/reactions"
        val resp = Api.ensureOk(Api.call("POST", base, buildJsonObject { put("content", content) }))
        if (resp.code == 201) return true
        val id = Api.json.decodeFromString(ReactionResp.serializer(), resp.body).id
        Api.exec("DELETE", "$base/$id")
        return false
    }

    /** 候选项每页 100 条，最多读 [CANDIDATE_MAX_PAGES] 页；超出部分由界面上的筛选框找到 */
    private const val CANDIDATE_PAGE = 100
    private const val CANDIDATE_MAX_PAGES = 10

    /** 按页读取直到最后一页（不满一页）或达到 [maxPages] 页 */
    internal suspend fun <T> collectPages(pageSize: Int, maxPages: Int, load: suspend (page: Int) -> List<T>): List<T> {
        val all = ArrayList<T>()
        for (page in 1..maxPages) {
            val part = load(page)
            all += part
            if (part.size < pageSize) break
        }
        return all
    }

    suspend fun repoLabels(o: String, n: String) = collectPages(CANDIDATE_PAGE, CANDIDATE_MAX_PAGES) { p ->
        Api.get<List<Label>>("${r(o, n)}/labels?per_page=$CANDIDATE_PAGE&page=$p", true)
    }
    suspend fun repoAssignees(o: String, n: String) = collectPages(CANDIDATE_PAGE, CANDIDATE_MAX_PAGES) { p ->
        Api.get<List<Assignable>>("${r(o, n)}/assignees?per_page=$CANDIDATE_PAGE&page=$p", true)
    }
    suspend fun repoMilestones(o: String, n: String) = Api.get<List<Milestone>>("${r(o, n)}/milestones?state=open&per_page=100", true)
    suspend fun setLabels(o: String, n: String, num: Int, names: List<String>) =
        Api.exec("PUT", "${r(o, n)}/issues/$num/labels", buildJsonObject { put("labels", JsonArray(names.map { JsonPrimitive(it) })) })
    suspend fun setAssignees(o: String, n: String, num: Int, logins: List<String>) =
        Api.exec("PATCH", "${r(o, n)}/issues/$num", buildJsonObject { put("assignees", JsonArray(logins.map { JsonPrimitive(it) })) })
    suspend fun setMilestone(o: String, n: String, num: Int, milestone: Int?) =
        Api.exec("PATCH", "${r(o, n)}/issues/$num", buildJsonObject {
            put("milestone", milestone?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        })

    /** 用 GitHub 官方接口把 Markdown 渲染成 HTML（GFM，带仓库上下文，可解析 #123、@user、任务列表等）。用于编辑器预览。 */
    suspend fun renderMarkdown(text: String, context: String?): String =
        Api.ensureOk(Api.call("POST", "/markdown", buildJsonObject {
            put("text", text)
            put("mode", "gfm")
            if (context != null) put("context", context)
        }, accept = "text/html")).body

    // ---------------- Releases ----------------
    suspend fun releases(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Release>>("${r(o, n)}/releases?per_page=10&page=$page", force, Api.FULL)

    /** 最新正式版本；404 表示仓库还没有发布任何 Release */
    suspend fun latestRelease(o: String, n: String, force: Boolean = false): Release? = try {
        Api.get<Release>("${r(o, n)}/releases/latest", force, Api.FULL)
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    suspend fun release(o: String, n: String, id: Long, force: Boolean = true) = Api.get<Release>("${r(o, n)}/releases/$id", force)

    /** 按标签名取指定版本（不受列表分页影响）；标签不存在或无权查看时返回 null */
    suspend fun releaseByTag(o: String, n: String, tag: String, force: Boolean = false): Release? = try {
        Api.get<Release>("${r(o, n)}/releases/tags/${Api.q(tag)}", force, Api.FULL)
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    /** 新建 / 编辑 Release；target 只在标签尚不存在时用于创建标签 */
    private fun releaseBody(form: ReleaseEdit) = buildJsonObject {
        put("tag_name", form.tag)
        form.target?.let { put("target_commitish", it) }
        put("name", form.title)
        put("body", form.notes)
        put("draft", form.draft)
        put("prerelease", form.prerelease)
        // 草稿与预发布不能设为最新；GitHub 只接受字符串 "true" / "false" / "legacy"
        if (!form.draft) put("make_latest", if (form.latest && !form.prerelease) "true" else "false")
    }

    // 写入后响应解析失败时返回 null：Release 已生效，调用方不要再次提交（否则会因标签已存在而失败，或产生重复内容）
    suspend fun createRelease(o: String, n: String, form: ReleaseEdit): Release? = Api.sendWrite<Release>("POST", "${r(o, n)}/releases", releaseBody(form))
    suspend fun updateRelease(o: String, n: String, id: Long, form: ReleaseEdit): Release? = Api.sendWrite<Release>("PATCH", "${r(o, n)}/releases/$id", releaseBody(form))
    suspend fun deleteRelease(o: String, n: String, id: Long) = Api.exec("DELETE", "${r(o, n)}/releases/$id")

    /** 让 GitHub 根据两次发布之间合并的 PR 生成说明 */
    suspend fun generateNotes(o: String, n: String, tag: String, target: String?, previousTag: String?) =
        Api.send<ReleaseNotes>("POST", "${r(o, n)}/releases/generate-notes", buildJsonObject {
            put("tag_name", tag)
            target?.let { put("target_commitish", it) }
            previousTag?.let { put("previous_tag_name", it) }
        })

    // ---------------- Actions ----------------
    suspend fun runs(o: String, n: String, workflowId: Long?, page: Int, force: Boolean) =
        Api.get<Runs>((if (workflowId != null) "${r(o, n)}/actions/workflows/$workflowId/runs" else "${r(o, n)}/actions/runs") + "?per_page=$PER&page=$page", force).workflowRuns
    suspend fun run(o: String, n: String, id: Long, force: Boolean) = Api.get<Run>("${r(o, n)}/actions/runs/$id", force)
    suspend fun jobs(o: String, n: String, runId: Long, force: Boolean) = Api.get<Jobs>("${r(o, n)}/actions/runs/$runId/jobs?per_page=100", force).jobs
    suspend fun artifacts(o: String, n: String, runId: Long, force: Boolean) = Api.get<Artifacts>("${r(o, n)}/actions/runs/$runId/artifacts", force).artifacts
    suspend fun rerun(o: String, n: String, runId: Long, failedOnly: Boolean) =
        Api.exec("POST", "${r(o, n)}/actions/runs/$runId/" + if (failedOnly) "rerun-failed-jobs" else "rerun")
    suspend fun cancelRun(o: String, n: String, runId: Long) = Api.exec("POST", "${r(o, n)}/actions/runs/$runId/cancel")
    suspend fun job(o: String, n: String, jobId: Long, force: Boolean = false) = Api.get<Job>("${r(o, n)}/actions/jobs/$jobId", force)
    suspend fun jobLog(o: String, n: String, jobId: Long) = Api.text("${r(o, n)}/actions/jobs/$jobId/logs", force = true)
    suspend fun workflows(o: String, n: String, force: Boolean) = Api.get<Workflows>("${r(o, n)}/actions/workflows?per_page=100", force).workflows
    suspend fun dispatch(o: String, n: String, workflowId: Long, ref: String, inputs: Map<String, String> = emptyMap()) =
        Api.exec("POST", "${r(o, n)}/actions/workflows/$workflowId/dispatches", buildJsonObject {
            put("ref", ref)
            if (inputs.isNotEmpty()) put("inputs", JsonObject(inputs.mapValues { JsonPrimitive(it.value) }))
        })

    /** 工作流 YAML 原文（解析 workflow_dispatch 的输入参数） */
    suspend fun workflowFile(o: String, n: String, path: String, ref: String?) =
        Api.text("${r(o, n)}/contents/${Api.encodePath(path)}" + (ref?.let { "?ref=${Api.q(it)}" } ?: ""), Api.RAW, force = true)

    suspend fun environments(o: String, n: String) = Api.get<Environments>("${r(o, n)}/environments?per_page=100").environments.map { it.name }
    suspend fun setWorkflowEnabled(o: String, n: String, workflowId: Long, on: Boolean) =
        Api.exec("PUT", "${r(o, n)}/actions/workflows/$workflowId/" + if (on) "enable" else "disable")

    // ---------------- 洞察 Insights（官方 App 不提供） ----------------
    suspend fun views(o: String, n: String, force: Boolean) = Api.get<Traffic>("${r(o, n)}/traffic/views", force)
    suspend fun clones(o: String, n: String, force: Boolean) = Api.get<Traffic>("${r(o, n)}/traffic/clones", force)
    suspend fun referrers(o: String, n: String, force: Boolean) = Api.get<List<Referrer>>("${r(o, n)}/traffic/popular/referrers", force)
    suspend fun popularPaths(o: String, n: String, force: Boolean) = Api.get<List<PopularPath>>("${r(o, n)}/traffic/popular/paths", force)

    /**
     * 贡献者统计（官方 App 只给一个头像列表，这里给出每人的提交数与增删行数）。
     * 与 commit_activity 一样，首次计算会返回 202，需要轮询重试；空仓库返回 204。
     */
    suspend fun contributorStats(o: String, n: String, force: Boolean): List<ContributorStat> {
        repeat(5) { attempt ->
            val resp = Api.call("GET", "${r(o, n)}/stats/contributors?per_page=100", force = force || attempt > 0)
            when {
                resp.code == 200 -> return Api.json.decodeFromString(ListSerializer(ContributorStat.serializer()), resp.body)
                resp.code == 204 -> return emptyList()
                resp.code != 202 -> Api.ensureOk(resp)
            }
            delay(1500L * (attempt + 1))
        }
        throw ApiException(202, tr("GitHub 正在计算贡献者统计，请稍后刷新"))
    }

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
    /**
     * 新建仓库。[gitignore] 为 .gitignore 模板名（如 Android），[license] 为许可证 key（如 mit）；
     * 选了任一模板时 GitHub 会用它们生成第一个提交。
     */
    suspend fun createRepo(
        org: String?, name: String, desc: String, private: Boolean, autoInit: Boolean,
        gitignore: String? = null, license: String? = null,
    ) =
        Api.send<Repo>("POST", if (org == null) "/user/repos" else "/orgs/$org/repos", buildJsonObject {
            put("name", name)
            put("description", desc)
            put("private", private)
            put("auto_init", autoInit)
            gitignore?.takeIf { it.isNotBlank() }?.let { put("gitignore_template", it) }
            license?.takeIf { it.isNotBlank() }?.let { put("license_template", it) }
        }).also { RepoWrite.reposChanged() }

    /** 新建仓库可选的 .gitignore 模板名 */
    suspend fun gitignoreTemplates(force: Boolean = false): List<String> = Api.get("/gitignore/templates", force)

    /** 新建仓库可选的许可证（常用的开源许可证） */
    suspend fun licenses(force: Boolean = false): List<License> = Api.get("/licenses", force)

    // ---------------- 通知 ----------------
    suspend fun notifications(all: Boolean, participating: Boolean, page: Int, force: Boolean) =
        Api.get<List<Notification>>("/notifications?all=$all&participating=$participating&per_page=$PER&page=$page", force)
    suspend fun markRead(id: String) = Api.exec("PATCH", "/notifications/threads/$id")
    suspend fun markDone(id: String) = Api.exec("DELETE", "/notifications/threads/$id")
    suspend fun markAllRead() = Api.exec("PUT", "/notifications", buildJsonObject { put("read", true) })
    /** 取消订阅线程：之后不再收到该会话的通知，直到再次评论或被 @ */
    suspend fun unsubscribeThread(id: String) = Api.exec("DELETE", "/notifications/threads/$id/subscription")

    // ---------------- 动态 ----------------
    suspend fun received(login: String, page: Int, force: Boolean) = Api.get<List<Event>>("/users/$login/received_events?per_page=$PER&page=$page", force)
    /** 仓库事件（含 PushEvent），用于查看最近推送到哪些分支、由谁推送。 */
    suspend fun repoEvents(o: String, n: String, page: Int, force: Boolean) = Api.get<List<Event>>("${r(o, n)}/events?per_page=$PER&page=$page", force)

    /**
     * 最近推送（按提交历史还原）：事件接口 /repos/{o}/{n}/events 对大仓库不完整，最近推送经常不显示；
     * 提交历史接口一直可用。这里按分支读提交，把同一作者、时间相近的连续提交当成一次推送。
     * [branches] 传上一次的结果即可复用分支列表，不必每页都重新拉。
     */
    suspend fun commitPushes(
        o: String, n: String, force: Boolean,
        branches: List<Branch>? = null,
        perBranch: Int = MAX_COMMITS_PER_BRANCH,
        limit: Int = PUSH_PAGE,
    ): Pair<List<CommitPush>, List<Branch>> {
        val list = branches ?: branchesOnce(o, n, force)
        val out = mutableListOf<CommitPush>()
        for (b in list) {
            if (out.size >= limit) break
            val commits = runCatching {
                Api.get<List<Commit>>("${r(o, n)}/commits?sha=${Api.q(b.name)}&per_page=$perBranch&page=1", force)
            }.getOrDefault(emptyList())
            out += splitPushes("refs/heads/${b.name}", commits)
        }
        return out.sortedByDescending { it.at }.take(limit) to list
    }

    /** 分支列表，按最近提交排序；拉取失败时退回只含默认分支的一项，保证页面仍能显示提交记录 */
    private suspend fun branchesOnce(o: String, n: String, force: Boolean): List<Branch> {
        val all = runCatching { branches(o, n, 1, force) }.getOrDefault(emptyList())
        if (all.isNotEmpty()) return all.take(MAX_BRANCHES)
        val repo = runCatching { repo(o, n, force) }.getOrNull() ?: return emptyList()
        return listOf(Branch(repo.defaultBranch))
    }

    suspend fun userEvents(login: String, page: Int, force: Boolean) = Api.get<List<Event>>("/users/$login/events?per_page=$PER&page=$page", force)

    /** 组织活动流：比用户 received_events 更准确地覆盖组织仓库的 push、Issue、PR 和 release。 */
    suspend fun orgEvents(login: String, org: String, page: Int, force: Boolean) =
        Api.get<List<Event>>("/users/${Api.encodePath(login)}/events/orgs/${Api.encodePath(org)}?per_page=$PER&page=$page", force)

    suspend fun organizationEvents(login: String, orgs: List<String>, force: Boolean): List<Event> {
        if (orgs.isEmpty()) return emptyList()
        return orgs.flatMap { org -> orgEvents(login, org, 1, force) }
            .sortedByDescending { it.createdAt ?: "" }
    }

    // ---------------- 搜索 ----------------
    suspend fun searchRepos(q: String, sort: String?, page: Int, force: Boolean = false) = Api.get<SearchResult<Repo>>(
        "/search/repositories?q=${Api.q(q)}&per_page=$PER&page=$page" + (sort?.let { "&sort=$it" } ?: ""),
        force,
    )
    suspend fun searchUsers(q: String, page: Int) = Api.get<SearchResult<User>>("/search/users?q=${Api.q(q)}&per_page=$PER&page=$page")
    suspend fun searchIssues(q: String, page: Int, sort: String? = null, order: String? = null, force: Boolean = false) =
        Api.get<SearchResult<Issue>>(
            "/search/issues?q=${Api.q(q)}&per_page=$PER&page=$page&advanced_search=true" +
                (sort?.let { "&sort=$it" } ?: "") + (order?.let { "&order=$it" } ?: ""),
            force,
        )

    // ---------------- Gist ----------------
    suspend fun gists(login: String?, page: Int, force: Boolean) = Api.get<List<Gist>>((if (login == null) "/gists" else "/users/$login/gists") + "?per_page=$PER&page=$page", force)
    suspend fun starredGists(page: Int, force: Boolean) = Api.get<List<Gist>>("/gists/starred?per_page=$PER&page=$page", force)
    suspend fun gist(id: String, force: Boolean) = Api.get<Gist>("/gists/$id", force)

    /** 新建 / 编辑 Gist；请求体由 [gistCreatePayload] / [gistPatchPayload] 构造 */
    suspend fun createGist(payload: JsonObject) = Api.send<Gist>("POST", "/gists", payload)
    suspend fun editGist(id: String, payload: JsonObject) = Api.send<Gist>("PATCH", "/gists/$id", payload)
    suspend fun deleteGist(id: String) = Api.exec("DELETE", "/gists/$id")

    suspend fun isGistStarred(id: String) = Api.exists("/gists/$id/star")
    suspend fun starGist(id: String, on: Boolean) = Api.exec(if (on) "PUT" else "DELETE", "/gists/$id/star")

    suspend fun forkGist(id: String) = Api.send<Gist>("POST", "/gists/$id/forks")

    /** full+json 带回 body_html；个别情况下缺失时由调用方用 /markdown 补渲染 */
    suspend fun gistComments(id: String, force: Boolean): List<Comment> {
        val all = mutableListOf<Comment>()
        for (page in 1..MAX_COMMENT_PAGES) {
            val part = Api.get<List<Comment>>("/gists/$id/comments?per_page=100&page=$page", force && page == 1, Api.FULL)
            all += part
            if (part.size < 100) break
        }
        return all
    }

    suspend fun addGistComment(id: String, body: String) =
        Api.send<Comment>("POST", "/gists/$id/comments", buildJsonObject { put("body", body) })

    suspend fun editGistComment(id: String, commentId: Long, body: String) =
        Api.send<Comment>("PATCH", "/gists/$id/comments/$commentId", buildJsonObject { put("body", body) })

    suspend fun deleteGistComment(id: String, commentId: Long) = Api.exec("DELETE", "/gists/$id/comments/$commentId")

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
        val c = data.obj("user")?.get("contributionsCollection") ?: throw ApiException(404, tr("用户不存在"))
        val result: Contributions = Api.plain.decodeFromJsonElement(c)
        // 自己最近一年的数据同步给桌面贡献图小组件
        if (year == null && login.equals(Session.login, true)) WidgetStore.saveContributions(login, result.contributionCalendar)
        return result
    }

    /** 查询指定时间范围内的贡献详情（如单日、单周），返回具体的提交、PR、Review、Issue 等分类构成和仓库分布 */
    suspend fun contributionsRange(login: String, fromDate: String, toDate: String): Contributions {
        val vars = mapOf(
            "login" to login,
            "from" to "${fromDate}T00:00:00Z",
            "to" to "${toDate}T23:59:59Z",
        )
        val data = Api.graphql(CONTRIB_Q, vars)
        val c = data.obj("user")?.get("contributionsCollection") ?: throw ApiException(404, tr("用户不存在"))
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

/** 列表接口的总数（per_page=1 时）：Link 头里 rel="last" 的页码就是总数；没有 Link 头时只可能是 0 或 1 条。纯函数，便于单测。 */
internal fun listTotal(linkHeader: String?, body: String): Int {
    val last = linkHeader?.let { Regex("[?&]page=(\\d+)[^>]*>;\\s*rel=\"last\"").find(it) }
    return last?.groupValues?.get(1)?.toIntOrNull() ?: if (body.isBlank() || body.trim() == "[]") 0 else 1
}
