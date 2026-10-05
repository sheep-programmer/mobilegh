package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/** Public GitHub GraphQL UserList API, not GitHub's private web endpoints. */
data class GitHubStarList(
    val id: String,
    val name: String,
    val description: String,
    val isPrivate: Boolean,
    val slug: String,
    val owner: String,
    val count: Int,
)

data class UserListPage<T>(val items: List<T>, val totalCount: Int, val nextCursor: String?)
data class UserListRepository(val nodeId: String, val repo: Repo)
data class RepositoryListMemberships(
    val repositoryId: String,
    val starred: Boolean,
    val lists: List<GitHubStarList>,
    val memberships: Set<String>,
)

/** Injectable client so pagination, account changes, and replacement writes can be tested offline. */
class UserListsClient internal constructor(
    private val activeAccount: () -> StarListAccount?,
    private val request: suspend (StarListAccount, String, JsonObject) -> JsonObject,
) {
    companion object {
        const val PAGE_SIZE = 100
        const val MAX_LISTS = 100
        const val MAX_SCAN_ITEMS = 10_000
        const val MAX_SCAN_PAGES = 100
        const val MAX_DETAIL_ITEMS = 5000
        const val MAX_BATCH_ADD = 10
        const val MAX_NAME = 80
        const val MAX_DESCRIPTION = 280
        internal const val LIST_FIELDS = "id name description isPrivate slug user { login } items { totalCount }"
        private const val PAGE_FIELDS = "totalCount pageInfo { hasNextPage endCursor }"
        private const val REPO_FIELDS = "id databaseId name nameWithOwner isPrivate description stargazerCount forkCount primaryLanguage { name } pushedAt updatedAt owner { __typename login avatarUrl }"
        private val LOGIN = Regex("[A-Za-z0-9-]{1,39}")
        private val NAME = Regex("[A-Za-z0-9_.-]{1,100}")
    }
    private val mutationLock = Mutex()

    private fun checkAccount(account: StarListAccount) {
        val active = activeAccount()
        check(active != null && active.generation == account.generation && active.login.equals(account.login, true)) {
            "账号已切换或退出，请重新打开 GitHub 列表"
        }
    }

    private suspend fun call(account: StarListAccount, query: String, vars: JsonObject): JsonObject {
        currentCoroutineContext().ensureActive()
        checkAccount(account)
        val data = request(account, query, vars)
        currentCoroutineContext().ensureActive()
        checkAccount(account)
        // viewer is a Query field. Mutations validate the owner in their own payload below.
        if (query.startsWith("query ")) require(data.requiredObject("viewer").requiredString("login").equals(account.login, true)) { "GitHub 列表返回了其他账号的数据" }
        return data
    }

    suspend fun lists(account: StarListAccount, login: String, cursor: String? = null): UserListPage<GitHubStarList> {
        require(LOGIN.matches(login)) { "无效的 GitHub 用户名" }
        val query = "query MobileGHLists(\$login:String!,\$after:String) { viewer { login } user(login:\$login) { lists(first:$PAGE_SIZE,after:\$after) { $PAGE_FIELDS nodes { $LIST_FIELDS } } } }"
        val data = call(account, query, buildJsonObject { put("login", login); putCursor(cursor) })
        return listPage(data.requiredObject("user").requiredObject("lists"), login)
    }

    suspend fun allLists(account: StarListAccount, login: String): List<GitHubStarList> {
        val result = ArrayList<GitHubStarList>()
        var cursor: String? = null
        val seen = HashSet<String>()
        do {
            val page = lists(account, login, cursor)
            require(page.totalCount <= MAX_LISTS) { "GitHub 列表超过 $MAX_LISTS 个读取上限，请在网页中查看" }
            result += page.items
            require(result.size <= MAX_LISTS && result.map { it.id }.distinct().size == result.size) { "GitHub 列表分页重复或超过上限，请刷新" }
            cursor = page.nextCursor
            if (cursor != null) require(seen.add(cursor)) { "GitHub 列表分页游标重复，请刷新" }
            else require(result.size == page.totalCount) { "GitHub 列表读取不完整，请刷新" }
        } while (cursor != null)
        return result
    }

    suspend fun items(account: StarListAccount, list: GitHubStarList, cursor: String? = null): UserListPage<UserListRepository> {
        val query = "query MobileGHListItems(\$id:ID!,\$after:String) { viewer { login } node(id:\$id) { ... on UserList { id user { login } items(first:$PAGE_SIZE,after:\$after) { $PAGE_FIELDS nodes { ... on Repository { $REPO_FIELDS } } } } } }"
        val data = call(account, query, buildJsonObject { put("id", list.id); putCursor(cursor) })
        val node = data.requiredObject("node")
        require(node.requiredString("id") == list.id && node.requiredObject("user").requiredString("login").equals(list.owner, true)) { "GitHub 列表已变化，请刷新" }
        return page(node.requiredObject("items")) { value ->
            val fullName = value.requiredString("nameWithOwner")
            val owner = value.requiredObject("owner")
            val id = (value["databaseId"] as? JsonPrimitive)?.longOrNull ?: 0L
            UserListRepository(value.requiredString("id"), Repo(
                id = id, name = value.requiredString("name"), fullName = fullName,
                owner = User(login = owner.requiredString("login"), avatarUrl = owner.stringOrEmpty("avatarUrl"), type = owner.requiredString("__typename")),
                isPrivate = value.requiredBoolean("isPrivate"), description = value.stringOrEmpty("description").takeIf { it.isNotEmpty() },
                stargazersCount = value.requiredInt("stargazerCount"), forksCount = value.requiredInt("forkCount"),
                language = (value["primaryLanguage"] as? JsonObject)?.requiredString("name"),
                pushedAt = value.stringOrEmpty("pushedAt").takeIf { it.isNotEmpty() },
                updatedAt = value.stringOrEmpty("updatedAt").takeIf { it.isNotEmpty() },
            ))
        }
    }

    suspend fun create(account: StarListAccount, name: String, description: String, isPrivate: Boolean): GitHubStarList = mutationLock.withLock {
        val input = metadata(name, description, isPrivate)
        val data = call(account, "mutation MobileGHCreateList(\$input:CreateUserListInput!) { createUserList(input:\$input) { list { $LIST_FIELDS } } }", buildJsonObject { put("input", input) })
        parseList(data.requiredObject("createUserList").requiredObject("list"), account.login)
    }

    suspend fun update(account: StarListAccount, list: GitHubStarList, name: String, description: String, isPrivate: Boolean): GitHubStarList = mutationLock.withLock {
        require(list.owner.equals(account.login, true)) { "只能管理本人 GitHub 列表" }
        val input = JsonObject(metadata(name, description, isPrivate) + ("listId" to JsonPrimitive(list.id)))
        val data = call(account, "mutation MobileGHUpdateList(\$input:UpdateUserListInput!) { updateUserList(input:\$input) { list { $LIST_FIELDS } } }", buildJsonObject { put("input", input) })
        parseList(data.requiredObject("updateUserList").requiredObject("list"), account.login).also { require(it.id == list.id) { "GitHub 返回了其他列表" } }
    }

    suspend fun delete(account: StarListAccount, list: GitHubStarList) = mutationLock.withLock {
        require(list.owner.equals(account.login, true)) { "只能管理本人 GitHub 列表" }
        val data = call(account, "mutation MobileGHDeleteList(\$input:DeleteUserListInput!) { deleteUserList(input:\$input) { user { login } } }", buildJsonObject { put("input", buildJsonObject { put("listId", list.id) }) })
        require(data.requiredObject("deleteUserList").requiredObject("user").requiredString("login").equals(account.login, true)) { "GitHub 未确认列表删除，请刷新后查看" }
    }

    /** There is no public Repository.lists field. Scan every visible viewer list and all its items. */
    suspend fun memberships(account: StarListAccount, owner: String, name: String): RepositoryListMemberships {
        require(LOGIN.matches(owner) && NAME.matches(name) && name !in setOf(".", "..")) { "无效的仓库信息" }
        val query = "query MobileGHListMemberships(\$owner:String!,\$name:String!,\$after:String) { viewer { login lists(first:$PAGE_SIZE,after:\$after) { $PAGE_FIELDS nodes { $LIST_FIELDS scanned:items(first:$PAGE_SIZE) { $PAGE_FIELDS nodes { ... on Repository { id } } } } } } repository(owner:\$owner,name:\$name) { id viewerHasStarred } }"
        val lists = ArrayList<GitHubStarList>()
        val memberships = LinkedHashSet<String>()
        val seen = HashSet<String>()
        var cursor: String? = null
        var repositoryId: String? = null
        var starred = false
        var scannedItems = 0
        var scannedPages = 0
        do {
            require(++scannedPages <= MAX_SCAN_PAGES) { incompleteMessage() }
            val data = call(account, query, buildJsonObject { put("owner", owner); put("name", name); putCursor(cursor) })
            val repo = data.requiredObject("repository")
            val id = repo.requiredString("id")
            require(repositoryId == null || repositoryId == id) { "仓库已变化，请重新打开列表" }
            repositoryId = id
            starred = repo.requiredBoolean("viewerHasStarred")
            val connection = data.requiredObject("viewer").requiredObject("lists")
            val listPage = page(connection) { it }
            require(listPage.totalCount <= MAX_LISTS) { incompleteMessage() }
            for (node in listPage.items) {
                val list = parseList(node, account.login)
                require(lists.none { it.id == list.id }) { "GitHub 列表分页重复；未更改任何分类" }
                lists += list
                require(lists.size <= MAX_LISTS) { incompleteMessage() }
                var itemPage = page(node.requiredObject("scanned")) { it.requiredString("id") }
                require(itemPage.totalCount <= MAX_SCAN_ITEMS) { incompleteMessage() }
                val itemIds = HashSet<String>()
                val itemCursors = HashSet<String>()
                do {
                    scannedItems += itemPage.items.size
                    require(scannedItems <= MAX_SCAN_ITEMS) { incompleteMessage() }
                    itemPage.items.forEach { itemId -> require(itemIds.add(itemId)) { "GitHub 仓库分页重复；未更改任何分类" } }
                    val after = itemPage.nextCursor
                    if (after == null) {
                        require(itemIds.size == itemPage.totalCount) { "GitHub 列表仓库读取不完整；未更改任何分类" }
                        break
                    }
                    require(itemCursors.add(after) && ++scannedPages <= MAX_SCAN_PAGES) { incompleteMessage() }
                    val detail = call(account,
                        "query MobileGHMembershipItems(\$id:ID!,\$after:String) { viewer { login } node(id:\$id) { ... on UserList { id user { login } items(first:$PAGE_SIZE,after:\$after) { $PAGE_FIELDS nodes { ... on Repository { id } } } } } }",
                        buildJsonObject { put("id", list.id); putCursor(after) })
                    val listNode = detail.requiredObject("node")
                    require(listNode.requiredString("id") == list.id && listNode.requiredObject("user").requiredString("login").equals(account.login, true)) { "GitHub 列表已变化；未更改任何分类" }
                    itemPage = page(listNode.requiredObject("items")) { it.requiredString("id") }
                } while (true)
                if (id in itemIds) memberships += list.id
            }
            cursor = listPage.nextCursor
            if (cursor != null) require(seen.add(cursor)) { incompleteMessage() }
            else require(lists.size == listPage.totalCount) { "GitHub 列表读取不完整；未更改任何分类" }
        } while (cursor != null)
        return RepositoryListMemberships(requireNotNull(repositoryId), starred, lists, memberships)
    }

    /** Replacement API: re-read complete memberships, then merge just the user's picker delta. */
    suspend fun setRepositoryLists(account: StarListAccount, owner: String, name: String, selected: Set<String>, original: Set<String>) = mutationLock.withLock {
        val added = selected - original
        val removed = original - selected
        if (added.isEmpty() && removed.isEmpty()) return@withLock
        val latest = memberships(account, owner, name)
        require(added.isEmpty() || latest.starred) { "请先 Star 此仓库，再加入 GitHub 列表" }
        val ids = replacementMemberships(latest.lists.map { it.id }.toSet(), latest.memberships, added, removed)
        val input = buildJsonObject { put("itemId", latest.repositoryId); put("listIds", JsonArray(ids.sorted().map(::JsonPrimitive))) }
        val data = call(account, "mutation MobileGHSetListMemberships(\$input:UpdateUserListsForItemInput!) { updateUserListsForItem(input:\$input) { item { ... on Repository { id } } lists { id } user { login } } }", buildJsonObject { put("input", input) })
        val payload = data.requiredObject("updateUserListsForItem")
        require(payload.requiredObject("user").requiredString("login").equals(account.login, true) && payload.requiredObject("item").requiredString("id") == latest.repositoryId) { "GitHub 未确认分类更新，请刷新后查看" }
        val confirmed = payload.requiredArray("lists").map { (it as? JsonObject)?.requiredString("id") ?: error("GitHub 分类结果不完整") }.toSet()
        require(confirmed == ids) { "GitHub 分类结果与选择不一致，请刷新后查看" }
    }

    internal fun replacementMemberships(available: Set<String>, current: Set<String>, added: Set<String>, removed: Set<String>): Set<String> {
        require(added.all { it in available } && current.all { it in available }) { "列表已被删除或读取不完整，请重新选择" }
        return (current + added) - removed
    }

    private fun metadata(name: String, description: String, isPrivate: Boolean): JsonObject {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= MAX_NAME && clean.none(Char::isISOControl)) { "列表名称需为 1–$MAX_NAME 个字符" }
        require(description.trim().length <= MAX_DESCRIPTION) { "列表描述最多 $MAX_DESCRIPTION 个字符" }
        return buildJsonObject { put("name", clean); put("description", description.trim()); put("isPrivate", isPrivate) }
    }

    private fun parseList(node: JsonObject, owner: String) = GitHubStarList(
        node.requiredString("id"), node.requiredString("name"), node.stringOrEmpty("description"), node.requiredBoolean("isPrivate"),
        node.requiredString("slug"), node.requiredObject("user").requiredString("login"), node.requiredObject("items").requiredInt("totalCount"),
    ).also { require(it.owner.equals(owner, true)) { "GitHub 返回了其他账号的列表" } }

    private fun listPage(connection: JsonObject, owner: String) = page(connection) { parseList(it, owner) }
    private fun <T> page(connection: JsonObject, parse: (JsonObject) -> T): UserListPage<T> {
        val info = connection.requiredObject("pageInfo")
        val hasNext = info.requiredBoolean("hasNextPage")
        val cursor = info.stringOrEmpty("endCursor").takeIf { it.isNotBlank() }
        require(!hasNext || cursor != null) { "GitHub 分页结果不完整，请刷新" }
        val nodes = connection.requiredArray("nodes")
        require(nodes.size <= PAGE_SIZE) { "GitHub 分页超过读取上限" }
        return UserListPage(nodes.map { parse(it as? JsonObject ?: error("GitHub 列表节点不可读取；分类未更改")) }, connection.requiredInt("totalCount"), cursor.takeIf { hasNext })
    }
    private fun incompleteMessage() = "分类读取超过上限（$MAX_LISTS 个列表、$MAX_SCAN_ITEMS 条记录、$MAX_SCAN_PAGES 页）；为保留其他分类，未更改任何列表。请在 GitHub 网页中管理。"
}

/** Strict GraphQL transport rejects partial errors and pins the account token for each request. */
object UserLists {
    val changes = MutableStateFlow(0L)
    private val json = Json { ignoreUnknownKeys = true }
    private val http by lazy { Api.http.newBuilder().retryOnConnectionFailure(false).build() }
    private val client = UserListsClient({ currentAccount() }, ::graphql)
    private fun currentAccount() = Session.login?.takeIf { Session.token != null }?.let { StarListAccount(it, Session.generation) }
    private suspend fun graphql(account: StarListAccount, query: String, vars: JsonObject): JsonObject {
        check(currentAccount()?.let { it.generation == account.generation && it.login.equals(account.login, true) } == true) { "账号已切换或退出" }
        val token = Session.token ?: error("请先登录 GitHub")
        val body = buildJsonObject { put("query", query); put("variables", vars) }
        val request = Request.Builder().url(Api.url("/graphql"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .cacheControl(CacheControl.Builder().noCache().build())
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        currentCoroutineContext().ensureActive()
        check(Session.token == token && currentAccount()?.let { it.generation == account.generation && it.login.equals(account.login, true) } == true) { "账号已切换或退出" }
        val raw = withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            check(Session.token == token && currentAccount()?.let { it.generation == account.generation && it.login.equals(account.login, true) } == true) { "账号已切换或退出" }
            http.newCall(request).await().use { response ->
                val bytes = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(bytes.size() + n <= 4 * 1024 * 1024) { "GitHub 列表响应超过读取上限" }
                        bytes.write(buffer, 0, n)
                    }
                }
                val text = bytes.toByteArray().toString(Charsets.UTF_8)
                Api.ensureOk(Resp(response.code, text, response.headers)).body
            }
        }
        check(currentAccount()?.let { it.generation == account.generation && it.login.equals(account.login, true) } == true) { "账号已切换或退出" }
        return strictGraphqlData(json.parseToJsonElement(raw) as? JsonObject ?: error("GitHub 列表响应无法解析"))
    }
    internal fun strictGraphqlData(root: JsonObject): JsonObject {
        val errors = root["errors"] as? JsonArray
        if (!errors.isNullOrEmpty()) {
            val message = (errors.firstOrNull() as? JsonObject)?.stringOrEmpty("message")?.take(240)
            throw ApiException(400, message?.takeIf { it.isNotBlank() } ?: "GitHub 列表请求未完成，请刷新后确认状态")
        }
        return root.requiredObject("data")
    }
    suspend fun lists(account: StarListAccount, login: String) = client.allLists(account, login)
    suspend fun items(account: StarListAccount, list: GitHubStarList, cursor: String? = null) = client.items(account, list, cursor)
    suspend fun memberships(account: StarListAccount, owner: String, name: String) = client.memberships(account, owner, name)
    suspend fun create(account: StarListAccount, name: String, description: String, isPrivate: Boolean) = client.create(account, name, description, isPrivate).also { changes.update { it + 1 } }
    suspend fun update(account: StarListAccount, list: GitHubStarList, name: String, description: String, isPrivate: Boolean) = client.update(account, list, name, description, isPrivate).also { changes.update { it + 1 } }
    suspend fun delete(account: StarListAccount, list: GitHubStarList) { client.delete(account, list); changes.update { it + 1 } }
    suspend fun setRepositoryLists(account: StarListAccount, owner: String, name: String, selected: Set<String>, original: Set<String>) { client.setRepositoryLists(account, owner, name, selected, original); changes.update { it + 1 } }
    suspend fun addRepositories(account: StarListAccount, listId: String, repos: List<Repo>) {
        require(repos.size <= UserListsClient.MAX_BATCH_ADD) { "一次最多添加 ${UserListsClient.MAX_BATCH_ADD} 个仓库" }
        var completed = 0
        try {
            for (repo in repos.distinctBy { it.id }) {
                setRepositoryLists(account, repo.owner.login, repo.name, setOf(listId), emptySet())
                completed++
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = failure.message ?: "请求失败"
            throw IllegalStateException("已完成 $completed 个仓库；$reason。请刷新后查看，重试会保留已有分类。", failure)
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putCursor(cursor: String?) { put("after", cursor?.let(::JsonPrimitive) ?: JsonNull) }
private fun JsonObject.requiredObject(name: String) = this[name] as? JsonObject ?: error("GitHub 列表字段 $name 不完整，请刷新")
private fun JsonObject.requiredArray(name: String) = this[name] as? JsonArray ?: error("GitHub 列表字段 $name 不完整，请刷新")
private fun JsonObject.requiredString(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("GitHub 列表字段 $name 不完整，请刷新")
private fun JsonObject.stringOrEmpty(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.requiredBoolean(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull ?: error("GitHub 列表字段 $name 不完整，请刷新")
private fun JsonObject.requiredInt(name: String) = (this[name] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: error("GitHub 列表字段 $name 不完整，请刷新")
