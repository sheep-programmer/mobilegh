package com.mobilegh.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** 一页 Star 记录。[nextCursor] 为空表示已经到底。 */
data class StarFeedPage(val items: List<StarFeedItem>, val nextCursor: String?, val truncated: Boolean)

/** 仓库和它被 Star 的时间：官方星标边自带 starredAt，不需要自己推算。 */
data class StarFeedItem(val repo: Repo, val starredAt: String)

/**
 * 官方星标连接的读取：`starredRepositories(orderBy: {field: STARRED_AT, direction: ASC|DESC})`。
 *
 * 这正是官方 App 的 StarredRepositories 查询所用的连接（官方把 direction 写死成 DESC）。
 * 正序（最早 Star 在前）/ 倒序（最近 Star 在前）都由服务端排好，客户端只按游标懒加载，
 * 不需要先扫全表。edges 还带回 `starredAt`，可直接显示每个仓库的 Star 时间。
 */
class StarFeedClient internal constructor(
    private val activeAccount: () -> StarListAccount?,
    private val request: suspend (StarListAccount, String, JsonObject) -> JsonObject,
) {
    companion object {
        const val PAGE_SIZE = 100

        internal const val NODE_FIELDS = "id databaseId name nameWithOwner isPrivate description stargazerCount forkCount " +
            "isFork isArchived primaryLanguage { name } pushedAt updatedAt owner { __typename login avatarUrl }"

        private const val QUERY_PREFIX = "query MobileGHStarFeed(\$login:String!,\$first:Int!,\$after:String,\$order:StarOrder) {" +
            " viewer { login }" +
            " repositoryOwner(login:\$login) { __typename ... on User { login starredRepositories(first:\$first, after:\$after, orderBy:\$order) {" +
            " isOverLimit pageInfo { hasNextPage endCursor }" +
            " edges { starredAt node { "

        internal fun query(): String = QUERY_PREFIX + NODE_FIELDS + " } } } } } }"

        private val LOGIN = Regex("[A-Za-z0-9-]{1,39}")
    }

    private fun checkAccount(account: StarListAccount) {
        val active = activeAccount()
        check(active != null && active.generation == account.generation && active.login.equals(account.login, true)) {
            "账号已切换或退出，请重新打开 Star 页"
        }
    }

    /** [direction] 只能是 ASC（最早 Star 在前）或 DESC（最近 Star 在前）。 */
    suspend fun page(
        account: StarListAccount,
        login: String,
        cursor: String? = null,
        direction: String = "DESC",
    ): StarFeedPage {
        require(LOGIN.matches(login)) { "无效的 GitHub 用户名" }
        require(direction == "ASC" || direction == "DESC") { "无效的排序方向" }
        checkAccount(account)
        val data = request(account, query(), buildJsonObject {
            put("login", login)
            put("first", PAGE_SIZE)
            put("after", cursor?.let(::JsonPrimitive) ?: JsonNull)
            put("order", buildJsonObject { put("field", "STARRED_AT"); put("direction", direction) })
        })
        checkAccount(account)
        val viewer = data["viewer"] as? JsonObject ?: error("GitHub 没有返回当前账号")
        require((viewer["login"] as? JsonPrimitive)?.contentOrNull?.equals(account.login, true) == true) {
            "Star 列表返回了其他账号的数据"
        }
        val owner = data["repositoryOwner"] as? JsonObject ?: error("GitHub 没有返回 $login")
        // 组织不能 Star；返回空页而不是报错，页面仍能显示「没有 Star 任何仓库」
        if ((owner["__typename"] as? JsonPrimitive)?.contentOrNull != "User") {
            return StarFeedPage(emptyList(), null, false)
        }
        require((owner["login"] as? JsonPrimitive)?.contentOrNull?.equals(login, true) == true) {
            "Star 列表返回了其他账号的数据"
        }
        val connection = owner["starredRepositories"] as? JsonObject ?: error("GitHub 没有返回 Star 列表")
        val edges = (connection["edges"] as? JsonArray).orEmpty()
        val items = edges.mapNotNull { edge ->
            val node = edge as? JsonObject ?: return@mapNotNull null
            val repo = (node["node"] as? JsonObject)?.let(::parseRepo) ?: return@mapNotNull null
            StarFeedItem(repo, (node["starredAt"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        val info = connection["pageInfo"] as? JsonObject
        val hasNext = (info?.get("hasNextPage") as? JsonPrimitive)?.booleanOrNull == true
        val next = if (hasNext) {
            (info["endCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: error("Star 列表分页缺少游标")
        } else null
        return StarFeedPage(
            items = items,
            nextCursor = next,
            truncated = (connection["isOverLimit"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun parseRepo(node: JsonObject): Repo {
        val owner = node["owner"] as? JsonObject ?: error("Star 列表字段 owner 不完整，请刷新")
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

private fun JsonObject.requiredString(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    ?: error("Star 列表字段 $name 不完整，请刷新")
private fun JsonObject.stringOrEmpty(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.requiredBoolean(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull
    ?: error("Star 列表字段 $name 不完整，请刷新")

object StarFeed {
    private val client = StarFeedClient({ currentAccount() }, { _, query, variables -> Api.graphql(query, variables) })

    private fun currentAccount() = Session.login?.takeIf { Session.token != null }?.let { StarListAccount(it, Session.generation) }

    suspend fun page(login: String, cursor: String? = null, direction: String = "DESC"): StarFeedPage {
        val account = currentAccount() ?: throw ApiException(401, "按 Star 时间排序需要先登录 GitHub")
        return client.page(account, login, cursor, direction)
    }
}

