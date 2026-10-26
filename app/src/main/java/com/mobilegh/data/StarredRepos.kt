package com.mobilegh.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 星标页的一页结果。[truncated] 对应 GitHub 的 isOverLimit：Star 太多时服务端只返回一部分。 */
data class StarredPage(val repos: List<Repo>, val nextCursor: String?, val truncated: Boolean)

/** 服务端不接受 `starredRepositories(query:)` 时抛出；界面随即改为只过滤已加载的 Star。 */
class StarSearchUnavailable : Exception("当前账号无法在服务端搜索 Star")

/**
 * 官方客户端的星标查询：`starredRepositories(query:, orderBy: {field: STARRED_AT, direction:})`，游标分页。
 * 公开 schema 里这个连接只有 STARRED_AT 一种字段，方向可选 ASC/DESC；按更新时间排则由 REST 承担。
 */
class StarredReposClient internal constructor(
    private val activeAccount: () -> StarListAccount?,
    private val request: suspend (StarListAccount, String, JsonObject) -> JsonObject,
) {
    companion object {
        const val PAGE_SIZE = 100
        const val MAX_QUERY = 256
        internal const val NODE_FIELDS = "id databaseId name nameWithOwner isPrivate description stargazerCount forkCount " +
            "isFork isArchived primaryLanguage { name } pushedAt updatedAt owner { __typename login avatarUrl }"
        private val LOGIN = Regex("[A-Za-z0-9-]{1,39}")
        private val QUERY_ARG = Regex("""(unknown argument|doesn't accept argument|does not accept argument)[^\n]*query""", RegexOption.IGNORE_CASE)

        /** GraphQL 的校验错误长这样：Field 'starredRepositories' doesn't accept argument 'query'. */
        internal fun queryRejected(message: String?): Boolean {
            val text = message ?: return false
            if (!text.contains("starredRepositories", true)) return false
            return QUERY_ARG.containsMatchIn(text) || (text.contains("query", true) && text.contains("argument", true))
        }
    }

    private fun checkAccount(account: StarListAccount) {
        val active = activeAccount()
        check(active != null && active.generation == account.generation && active.login.equals(account.login, true)) {
            "账号已切换或退出，请重新打开 Star 页"
        }
    }

    /**
     * 一页星标仓库；[query] 为空表示浏览全部 Star，否则交给 GitHub 做服务端搜索。
     * [direction] 是 Star 时间的排序方向（ASC 最早在前 / DESC 最近在前），只影响顺序不影响过滤。
     */
    suspend fun page(
        account: StarListAccount,
        login: String,
        query: String = "",
        cursor: String? = null,
        direction: String = "DESC",
    ): StarredPage {
        require(LOGIN.matches(login)) { "无效的 GitHub 用户名" }
        require(direction == "ASC" || direction == "DESC") { "无效的排序方向" }
        val keyword = query.trim()
        require(keyword.length <= MAX_QUERY) { "搜索关键词最多 $MAX_QUERY 个字符" }
        checkAccount(account)
        val gql = "query MobileGHStarredRepositories(\$login:String!,\$query:String,\$first:Int!,\$after:String,\$order:StarOrder) {" +
            " viewer { login }" +
            " repositoryOwner(login:\$login) { __typename ... on User {" +
            " starredRepositories(first:\$first, after:\$after, query:\$query, orderBy:\$order) {" +
            " isOverLimit pageInfo { hasNextPage endCursor } nodes { $NODE_FIELDS } } } } }"
        val variables = buildJsonObject {
            put("login", login)
            put("query", if (keyword.isEmpty()) JsonNull else JsonPrimitive(keyword))
            put("first", PAGE_SIZE)
            put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
            put("order", buildJsonObject { put("field", "STARRED_AT"); put("direction", direction) })
        }
        val data = try {
            request(account, gql, variables)
        } catch (failure: ApiException) {
            if (keyword.isNotEmpty() && queryRejected(failure.message)) throw StarSearchUnavailable() else throw failure
        }
        checkAccount(account)
        val viewer = data.requiredObject("viewer").requiredString("login")
        require(viewer.equals(account.login, true)) { "Star 列表返回了其他账号的数据" }
        val owner = data["repositoryOwner"] as? JsonObject ?: error("GitHub 没有返回这个用户")
        // 组织不能 Star；返回空页而不是报错，页面仍能显示“没有 Star 任何仓库”
        if (owner.stringOrEmpty("__typename") != "User") return StarredPage(emptyList(), null, false)
        val connection = owner["starredRepositories"] as? JsonObject ?: error("GitHub 没有返回 Star 列表")
        val nodes = (connection["nodes"] as? JsonArray).orEmpty()
        val info = connection.requiredObject("pageInfo")
        val next = if (info.requiredBoolean("hasNextPage")) info.requiredString("endCursor") else null
        return StarredPage(
            repos = nodes.mapNotNull { node ->
                (node as? JsonObject)?.let { parseRepo(it) }
            },
            nextCursor = next,
            truncated = (connection["isOverLimit"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun parseRepo(node: JsonObject): Repo {
        val owner = node.requiredObject("owner")
        return Repo(
            id = (node["databaseId"] as? JsonPrimitive)?.longOrNull ?: 0L,
            name = node.requiredString("name"),
            fullName = node.requiredString("nameWithOwner"),
            owner = User(
                login = owner.requiredString("login"),
                avatarUrl = owner.stringOrEmpty("avatarUrl"),
                type = owner.stringOrEmpty("__typename"),
            ),
            isPrivate = node.requiredBoolean("isPrivate"),
            description = node.stringOrEmpty("description").takeIf { it.isNotEmpty() },
            fork = (node["isFork"] as? JsonPrimitive)?.booleanOrNull == true,
            archived = (node["isArchived"] as? JsonPrimitive)?.booleanOrNull == true,
            language = (node["primaryLanguage"] as? JsonObject)?.stringOrEmpty("name")?.takeIf { it.isNotEmpty() },
            stargazersCount = (node["stargazerCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            forksCount = (node["forkCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            pushedAt = node.stringOrEmpty("pushedAt").takeIf { it.isNotEmpty() },
            updatedAt = node.stringOrEmpty("updatedAt").takeIf { it.isNotEmpty() },
        )
    }
}

private fun JsonObject.requiredObject(name: String) = this[name] as? JsonObject ?: error("Star 列表字段 $name 不完整，请刷新")
private fun JsonObject.requiredString(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    ?: error("Star 列表字段 $name 不完整，请刷新")
private fun JsonObject.stringOrEmpty(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.requiredBoolean(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull
    ?: error("Star 列表字段 $name 不完整，请刷新")

/** 本地过滤：名称、坐标或描述命中即可；空白关键词视为全部命中。 */
internal fun starMatches(repo: Repo, query: String): Boolean {
    val keyword = query.trim()
    if (keyword.isEmpty()) return true
    return repo.fullName.contains(keyword, true) || repo.name.contains(keyword, true) ||
        repo.description?.contains(keyword, true) == true ||
        repo.owner.login.contains(keyword, true)
}

object StarredRepos {
    private val _searchSupported = MutableStateFlow(true)
    private var supportGeneration = -1

    /** 服务端拒绝 query 参数后为 false；Star 页改用本地过滤并提示。 */
    val searchSupported: StateFlow<Boolean> = _searchSupported

    internal var client = StarredReposClient({ currentAccount() }, { _, query, variables ->
        Api.graphql(query, variables)
    })

    private fun currentAccount() = Session.login?.takeIf { Session.token != null }?.let { StarListAccount(it, Session.generation) }

    suspend fun page(login: String, query: String = "", cursor: String? = null, direction: String = "DESC"): StarredPage {
        val account = currentAccount() ?: throw ApiException(401, "搜索全部 Star 需要先登录 GitHub")
        return try {
            client.page(account, login, query, cursor, direction)
        } catch (unavailable: StarSearchUnavailable) {
            markSearchUnsupported(account.generation)
            throw unavailable
        }
    }

    @Synchronized
    private fun markSearchUnsupported(generation: Int) {
        if (Session.generation == generation) _searchSupported.value = false
    }

    /** 每个账号只重置一次；页面保留后返回不能抹掉已确认的回退状态。 */
    @Synchronized
    fun resetSupport() {
        if (supportGeneration == Session.generation) return
        supportGeneration = Session.generation
        _searchSupported.value = true
    }
}
