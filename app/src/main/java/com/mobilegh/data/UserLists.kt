package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

/** Public GitHub GraphQL UserList API, not GitHub's private web endpoints. */
data class GitHubStarList(
    val id: String,
    val name: String,
    val description: String,
    val isPrivate: Boolean,
    val slug: String,
    val owner: String,
    val count: Int,
    /** 列表创建时间（ISO 8601） */
    val createdAt: String = "",
    /** 列表最后更新时间 */
    val updatedAt: String = "",
    /** 最后一次往列表里添加仓库的时间 */
    val lastAddedAt: String = "",
)

/**
 * Picker order: lists that already contain the repository come first, then the remaining
 * lists in GitHub's order. [memberships] must be the snapshot taken when the picker opened,
 * so rows do not jump while the user is changing the selection. The next time the picker
 * opens it re-reads memberships and the newly added lists move to the front.
 */
internal fun starListsForRepository(lists: List<GitHubStarList>, memberships: Set<String>): List<GitHubStarList> {
    val (joined, rest) = lists.partition { it.id in memberships }
    return joined + rest
}

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
        /** GitHub 现在的上限是一个账号 32 个列表（新建第 33 个会被拒），满额后界面不再给新建入口。 */
        const val MAX_LISTS = 32
        const val MAX_SCAN_ITEMS = 40_000
        const val MAX_SCAN_PAGES = 200
        /** 归属判断一次请求里并排读几个列表的第一页（别名），以及同时发几个请求。 */
        private const val MEMBERSHIP_BATCH = 6
        private const val MEMBERSHIP_PARALLEL = 4
        /** 列表详情的每页条数：与官方 FetchList 的 `first: 30` 保持一致 */
        const val DETAIL_PAGE_SIZE = 30
        const val MAX_LIST_ITEMS = 50_000
        const val MAX_DETAIL_ITEMS = 5000
        const val MAX_BATCH_ADD = 10
        const val MAX_NAME = 80
        const val MAX_DESCRIPTION = 280
        internal const val LIST_FIELDS = "id name description isPrivate slug createdAt updatedAt lastAddedAt user { login } items { totalCount }"
        /** 取仓库节点 id、Star 状态和它所属的列表：官方客户端的读法，一个请求。 */
        private const val REPOSITORY_LISTS = "query MobileGHRepositoryLists(\$owner:String!,\$name:String!) { viewer { login } repository(owner:\$owner,name:\$name) { id viewerHasStarred lists(first:$PAGE_SIZE, onlyOwnedByViewer: true) { nodes { $LIST_FIELDS } } } }"
        /** 取仓库节点 id 与 Star 状态：兜底扫描用。 */
        private const val REPOSITORY_PROBE = "query MobileGHRepositoryId(\$owner:String!,\$name:String!) { viewer { login } repository(owner:\$owner,name:\$name) { id viewerHasStarred } }"
        /**
         * 写入归属的 mutation。
         *
         * 结果里的 `lists` 是 `[UserList!]`（普通数组，不是连接），不能带 `first`／`after` 之类的分页参数，
         * 带上会被 GitHub 判成未知参数、整个 mutation 直接失败。
         */
        private const val UPDATE_LISTS = "mutation MobileGHSetListMemberships(\$input:UpdateUserListsForItemInput!) { updateUserListsForItem(input:\$input) { item { ... on Repository { id } } lists { id } user { login } } }"
        private const val PAGE_FIELDS = "totalCount pageInfo { hasNextPage endCursor hasPreviousPage startCursor }"
        /**
         * 读一个列表的条目 id，用来判断仓库是否在这个列表里。
         *
         * 这是应用里一直在用的形状（列表详情页同样走 `node(id:)` + `items`），
         * 不再把上百个列表塞进一个嵌套请求里。
         */
        private const val LIST_CONTAINS_REPOSITORY = "query MobileGHListContainsRepository(\$id:ID!,\$after:String) { viewer { login } node(id:\$id) { ... on UserList { id user { login } items(first:$PAGE_SIZE,after:\$after) { $PAGE_FIELDS nodes { ... on Repository { id } } } } } }"
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
            // 读的时候按翻页上限卡，不按新建上限卡：账号里真有几个超额的列表也要能显示出来
            require(page.totalCount <= PAGE_SIZE) { "GitHub 列表超过 $PAGE_SIZE 个读取上限，请在网页中查看" }
            result += page.items
            require(result.size <= PAGE_SIZE && result.map { it.id }.distinct().size == result.size) { "GitHub 列表分页重复或超过上限，请刷新" }
            cursor = page.nextCursor
            if (cursor != null) require(seen.add(cursor)) { "GitHub 列表分页游标重复，请刷新" }
            else require(result.size == page.totalCount) { "GitHub 列表读取不完整，请刷新" }
        } while (cursor != null)
        return result
    }

    /**
     * 列表里的仓库。两个方向各自向服务端发不同的请求：
     * - 正序 [descending] = false：`items(first:, after:)`，从列表头部往后翻；
     * - 倒序 [descending] = true：`items(last:, before:)`，从列表尾部往回翻，
     *   并把这页结果倒过来放，界面上就是「倒着看同一份列表」。
     *
     * 倒序绝不是复用正序的请求再把结果反转：那样只能看到已经加载过的那部分，
     * 列表尾部的内容永远不会被请求。
     */
    suspend fun items(
        account: StarListAccount,
        list: GitHubStarList,
        cursor: String? = null,
        descending: Boolean = false,
    ): UserListPage<UserListRepository> {
        val args = if (descending) "last:$DETAIL_PAGE_SIZE,before:\$cursor" else "first:$DETAIL_PAGE_SIZE,after:\$cursor"
        val query = "query MobileGHListItems(\$id:ID!,\$cursor:String) { viewer { login } node(id:\$id) { ... on UserList { id user { login } items($args) { $PAGE_FIELDS nodes { ... on Repository { $REPO_FIELDS } } } } } }"
        val data = call(account, query, buildJsonObject { put("id", list.id); put("cursor", cursor?.let(::JsonPrimitive) ?: JsonNull) })
        val node = data.requiredObject("node")
        require(node.requiredString("id") == list.id && node.requiredObject("user").requiredString("login").equals(list.owner, true)) { "GitHub 列表已变化，请刷新" }
        val result = page(node.requiredObject("items"), fromEnd = descending) { value ->
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
        // 从尾部取回来的这一页仍是「旧 → 新」，倒着放才是用户要的倒序
        return if (descending) result.copy(items = result.items.reversed()) else result
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

    /**
     * 仓库属于哪些列表。
     *
     * GitHub 的 `Repository.lists` 直接给出答案（官方 Android 客户端就是这么读的）：一个请求
     * 同时拿到仓库节点 id、Star 状态和所属列表的完整信息。这个字段没写进公开文档，万一不可用，
     * 退回 [scanMemberships] 逐个列表读条目——慢一些，结果一样。
     */
    suspend fun memberships(account: StarListAccount, owner: String, name: String): RepositoryListMemberships {
        require(LOGIN.matches(owner) && NAME.matches(name) && name !in setOf(".", "..")) { "无效的仓库信息" }
        return try {
            repositoryLists(account, owner, name)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            if (!fieldUnavailable(failure)) throw failure
            scanMemberships(account, owner, name)
        }
    }

    /** 失败只是因为「这个字段不给你用」时返回 true，其它错误照常往上抛。 */
    private fun fieldUnavailable(failure: Exception): Boolean {
        if (failure !is ApiException) return false
        val message = failure.message.orEmpty()
        return message.contains("lists") &&
            (message.contains("doesn't exist") || message.contains("undefined") || message.contains("not exist") || message.contains("Unknown"))
    }

    /** 一个请求读出仓库的归属：`Repository.lists`，与官方客户端一致。 */
    private suspend fun repositoryLists(account: StarListAccount, owner: String, name: String): RepositoryListMemberships {
        val data = call(account, REPOSITORY_LISTS, buildJsonObject { put("owner", owner); put("name", name) })
        val repository = data.requiredObject("repository")
        val lists = repository.requiredObject("lists").requiredArray("nodes")
            .mapNotNull { it as? JsonObject }.map { parseList(it, account.login) }
        require(lists.map { it.id }.distinct().size == lists.size) { "GitHub 列表重复，请刷新" }
        require(lists.size <= PAGE_SIZE) { "GitHub 列表读取超过 $PAGE_SIZE 个上限，请刷新" }
        return RepositoryListMemberships(
            repository.requiredString("id"),
            repository.requiredBoolean("viewerHasStarred"),
            lists,
            lists.map { it.id }.toSet(),
        )
    }

    /**
     * 兜底：没有 `Repository.lists` 时只能逐个列表读条目。为了少跑来回，一次请求用别名并排读
     * [MEMBERSHIP_BATCH] 个列表的第一页，[MEMBERSHIP_PARALLEL] 个请求并发；超过一页的大列表
     * 再单独往后翻，找到目标仓库就停。
     */
    private suspend fun scanMemberships(account: StarListAccount, owner: String, name: String): RepositoryListMemberships {
        val probe = call(account, REPOSITORY_PROBE, buildJsonObject { put("owner", owner); put("name", name) })
        val repository = probe.requiredObject("repository")
        val repositoryId = repository.requiredString("id")
        val starred = repository.requiredBoolean("viewerHasStarred")
        val lists = allLists(account, account.login)
        val pages = AtomicInteger()
        val records = AtomicInteger()
        val memberships = LinkedHashSet<String>()
        // 第一页放不下的大列表，等第一页都读完再单独往后翻
        val pending = LinkedHashMap<String, Pagination>()
        val waves = lists.chunked(MEMBERSHIP_BATCH).chunked(MEMBERSHIP_PARALLEL)
        for (wave in waves) {
            val scans = coroutineScope {
                wave.map { batch -> async { scanBatch(account, batch, repositoryId, pages, records) } }.awaitAll()
            }
            scans.forEach { scan ->
                scan.memberships.forEach { memberships += it }
                pending.putAll(scan.pending)
            }
        }
        // 大列表：接着往后翻，找到就停；读不完就报错
        for ((listId, start) in pending) {
            if (listId in memberships) continue
            val list = lists.first { it.id == listId }
            if (containsRepository(account, list, repositoryId, start, pages, records)) memberships += listId
        }
        return RepositoryListMemberships(repositoryId, starred, lists, memberships)
    }

    private class Pagination(val total: Int, val cursor: String, val read: Int)
    private class BatchScan(val memberships: List<String>, val pending: Map<String, Pagination>)

    /** 一次请求读完几个列表的第一页：别名并排，省掉每个列表一趟来回。 */
    private suspend fun scanBatch(
        account: StarListAccount,
        batch: List<GitHubStarList>,
        repositoryId: String,
        pages: AtomicInteger,
        records: AtomicInteger,
    ): BatchScan {
        require(pages.incrementAndGet() <= MAX_SCAN_PAGES) { incompleteMessage() }
        val fields = batch.indices.joinToString(" ") { index ->
            "l$index:node(id:\$id$index) { ... on UserList { id user { login } items(first:$PAGE_SIZE) { $PAGE_FIELDS nodes { ... on Repository { id } } } } }"
        }
        val declared = batch.indices.joinToString(", ") { "\$id$it:ID!" }
        val vars = buildJsonObject { batch.forEachIndexed { index, list -> put("id$index", list.id) } }
        val data = call(account, "query MobileGHBatchMemberships($declared) { viewer { login } $fields }", vars)
        val memberships = ArrayList<String>()
        val pending = LinkedHashMap<String, Pagination>()
        batch.forEachIndexed { index, list ->
            val node = data.requiredObject("l$index")
            require(node.requiredString("id") == list.id) { "GitHub 列表已变化；未更改任何分类" }
            require(node.requiredObject("user").requiredString("login").equals(account.login, true)) { "GitHub 列表已变化；未更改任何分类" }
            val itemPage = page(node.requiredObject("items")) { it.requiredString("id") }
            require(itemPage.items.toSet().size == itemPage.items.size) { "GitHub 仓库分页重复；未更改任何分类" }
            require(itemPage.totalCount <= MAX_LIST_ITEMS && records.addAndGet(itemPage.items.size) <= MAX_SCAN_ITEMS) { incompleteMessage() }
            val next = itemPage.nextCursor
            when {
                repositoryId in itemPage.items -> memberships += list.id
                next != null -> pending[list.id] = Pagination(itemPage.totalCount, next, itemPage.items.size)
                else -> require(itemPage.items.size == itemPage.totalCount) { "GitHub 列表仓库读取不完整；未更改任何分类" }
            }
        }
        return BatchScan(memberships, pending)
    }

    /** 大列表：从第二页往后翻，读到目标仓库就停；超出总预算就报错。 */
    private suspend fun containsRepository(
        account: StarListAccount,
        list: GitHubStarList,
        repositoryId: String,
        start: Pagination,
        pages: AtomicInteger,
        records: AtomicInteger,
    ): Boolean {
        var after = start.cursor
        var read = 0
        val seen = HashSet<String>()
        val cursors = HashSet<String>()
        while (true) {
            require(pages.incrementAndGet() <= MAX_SCAN_PAGES) { incompleteMessage() }
            require(cursors.add(after)) { "GitHub 仓库分页重复；未更改任何分类" }
            val data = call(account, LIST_CONTAINS_REPOSITORY, buildJsonObject { put("id", list.id); putCursor(after) })
            val node = data.requiredObject("node")
            require(node.requiredString("id") == list.id) { "GitHub 列表已变化；未更改任何分类" }
            require(node.requiredObject("user").requiredString("login").equals(account.login, true)) { "GitHub 列表已变化；未更改任何分类" }
            val itemPage = page(node.requiredObject("items")) { it.requiredString("id") }
            require(itemPage.totalCount == start.total) { "GitHub 列表已变化；未更改任何分类，请刷新" }
            require(itemPage.totalCount <= MAX_LIST_ITEMS && records.addAndGet(itemPage.items.size) <= MAX_SCAN_ITEMS) { incompleteMessage() }
            if (repositoryId in itemPage.items) return true
            itemPage.items.forEach { itemId -> require(seen.add(itemId)) { "GitHub 仓库分页重复；未更改任何分类" } }
            read += itemPage.items.size
            after = itemPage.nextCursor ?: break
        }
        require(start.read + read == start.total) { "GitHub 列表仓库读取不完整；未更改任何分类" }
        return false
    }

    /**
     * Replacement API: re-read complete memberships, then merge just the user's picker delta.
     *
     * GitHub 的列表不要求仓库在 Star 里，只是网页入口在 Stars 页；[allowUnstarred] 用于
     * 明确知道自己拥有的仓库（UI 已核实所有权限），此时不加 Star 也能加入列表。
     */
    suspend fun setRepositoryLists(
        account: StarListAccount, owner: String, name: String, selected: Set<String>, original: Set<String>,
        allowUnstarred: Boolean = false,
    ): Set<String> = mutationLock.withLock {
        val added = selected - original
        val removed = original - selected
        if (added.isEmpty() && removed.isEmpty()) return@withLock selected
        val latest = memberships(account, owner, name)
        require(added.isEmpty() || latest.starred || allowUnstarred) { "请先 Star 此仓库，再加入 GitHub 列表" }
        // 要新增的列表必须在自己的列表目录里；只做移除时用当前归属就够了
        val available = if (added.isEmpty()) latest.lists.map { it.id }.toSet() else allLists(account, account.login).map { it.id }.toSet()
        val ids = replacementMemberships(available, latest.memberships, added, removed)
        val input = buildJsonObject { put("itemId", latest.repositoryId); put("listIds", JsonArray(ids.sorted().map(::JsonPrimitive))) }
        val data = call(account, UPDATE_LISTS, buildJsonObject { put("input", input) })
        confirmLists(data.requiredObject("updateUserListsForItem"), latest.repositoryId, account, ids)
        ids
    }

    /**
     * 直接写入最终归属：勾了哪几个列表就写哪几个。
     *
     * 弹窗里已经读过一遍归属时不需要再扫所有列表——[updateUserListsForItem] 本来就是整组替换，
     * 传进去的就是结果。这样保存只有两个请求（取仓库节点 id + 写入），不会因为扫描慢或者
     * 扫描失败而保存不了。
     */
    suspend fun saveRepositoryLists(
        account: StarListAccount, owner: String, name: String, desired: Set<String>, allowUnstarred: Boolean = false,
    ) = mutationLock.withLock {
        require(LOGIN.matches(owner) && NAME.matches(name) && name !in setOf(".", "..")) { "无效的仓库信息" }
        require(desired.size <= MAX_LISTS) { "GitHub 列表超过 $MAX_LISTS 个，请在网页中管理" }
        val probe = call(account, REPOSITORY_PROBE, buildJsonObject { put("owner", owner); put("name", name) })
        val node = probe.requiredObject("repository")
        val repositoryId = node.requiredString("id")
        require(desired.isEmpty() || node.requiredBoolean("viewerHasStarred") || allowUnstarred) { "请先 Star 此仓库，再加入 GitHub 列表" }
        val input = buildJsonObject { put("itemId", repositoryId); put("listIds", JsonArray(desired.sorted().map(::JsonPrimitive))) }
        val data = call(account, UPDATE_LISTS, buildJsonObject { put("input", input) })
        confirmLists(data.requiredObject("updateUserListsForItem"), repositoryId, account, desired)
    }

    /**
     * 直接把仓库的归属写成 [listIds]：勾一下写一次，与官方客户端的选择面板一致。
     *
     * 仓库节点 id 已由 `Repository.lists` 给出，所以这里只有一次写入请求，不再读任何列表；
     * `updateUserListsForItem` 本来就是整组替换，传进去的就是最终结果。
     */
    suspend fun setRepositoryMembership(account: StarListAccount, repositoryId: String, listIds: Set<String>) = mutationLock.withLock {
        require(repositoryId.isNotBlank()) { "仓库信息未读取完整，请稍后再试" }
        val ids = listIds.filter { it.isNotBlank() }.toSet()
        require(ids.size <= MAX_LISTS) { "GitHub 列表超过 $MAX_LISTS 个，请在网页中管理" }
        val input = buildJsonObject { put("itemId", repositoryId); put("listIds", JsonArray(ids.sorted().map(::JsonPrimitive))) }
        val data = call(account, UPDATE_LISTS, buildJsonObject { put("input", input) })
        confirmLists(data.requiredObject("updateUserListsForItem"), repositoryId, account, ids)
    }

    /** 核对写入结果。GitHub 对 `lists` 是普通数组（不是连接），没给就是没返回，不能当成失败。 */
    private fun confirmLists(payload: JsonObject, repositoryId: String, account: StarListAccount, expected: Set<String>) {
        val owner = (payload["user"] as? JsonObject)?.stringOrEmpty("login").orEmpty()
        require(owner.isEmpty() || owner.equals(account.login, true)) { "GitHub 返回了其他账号的列表" }
        val item = (payload["item"] as? JsonObject)?.stringOrEmpty("id").orEmpty()
        require(item.isEmpty() || item == repositoryId) { "GitHub 未确认分类更新，请刷新后查看" }
        val returned = (payload["lists"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.stringOrEmpty("id")?.takeIf { id -> id.isNotBlank() } }
            ?.toSet() ?: return
        require(returned == expected) { "GitHub 分类结果与选择不一致，请刷新后查看" }
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
        createdAt = node.stringOrEmpty("createdAt"),
        updatedAt = node.stringOrEmpty("updatedAt"),
        lastAddedAt = node.stringOrEmpty("lastAddedAt"),
    ).also { require(it.owner.equals(owner, true)) { "GitHub 返回了其他账号的列表" } }

    private fun listPage(connection: JsonObject, owner: String) = page(connection) { parseList(it, owner) }

    /**
     * 解析一页连接。[fromEnd] 表示这一页是用 `last:`/`before:` 从尾部读的：
     * 那么「还有更多」看 [hasPreviousPage]，下一页游标取 [startCursor]。
     */
    private fun <T> page(connection: JsonObject, fromEnd: Boolean = false, parse: (JsonObject) -> T): UserListPage<T> {
        val info = connection.requiredObject("pageInfo")
        val more = info.requiredBoolean(if (fromEnd) "hasPreviousPage" else "hasNextPage")
        val raw = info.stringOrEmpty(if (fromEnd) "startCursor" else "endCursor").takeIf { it.isNotBlank() }
        require(!more || raw != null) { "GitHub 分页结果不完整，请刷新" }
        val nodes = connection.requiredArray("nodes")
        require(nodes.size <= PAGE_SIZE) { "GitHub 分页超过读取上限" }
        return UserListPage(nodes.map { parse(it as? JsonObject ?: error("GitHub 列表节点不可读取；分类未更改")) }, connection.requiredInt("totalCount"), raw.takeIf { more })
    }
    private fun incompleteMessage() = "分类读取超过上限（$PAGE_SIZE 个列表、$MAX_SCAN_ITEMS 条记录、$MAX_SCAN_PAGES 页）；为保留其他分类，未更改任何列表。请在 GitHub 网页中管理。"
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
            // Only queries may be retried: a failed mutation response can follow a committed write.
            var attempt = 0
            var body: String? = null
            while (body == null) {
                currentCoroutineContext().ensureActive()
                check(Session.token == token && currentAccount()?.let { it.generation == account.generation && it.login.equals(account.login, true) } == true) { "账号已切换或退出" }
                http.routed().newCall(request).await().use { response ->
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
                    if (query.startsWith("query ") && response.code in 500..599 && attempt < 1) {
                        attempt++
                        AppLog.warn("lists", "GitHub 列表请求返回 ${response.code}，正在重试")
                        delay(800)
                    } else {
                        body = Api.ensureOk(Resp(response.code, text, response.headers)).body
                    }
                }
            }
            body
        }
        check(currentAccount()?.let { it.generation == account.generation && it.login.equals(account.login, true) } == true) { "账号已切换或退出" }
        return strictGraphqlData(json.parseToJsonElement(raw) as? JsonObject ?: error("GitHub 列表响应无法解析"))
    }
    internal fun strictGraphqlData(root: JsonObject): JsonObject {
        val errors = root["errors"] as? JsonArray
        if (!errors.isNullOrEmpty()) {
            val message = (errors.firstOrNull() as? JsonObject)?.stringOrEmpty("message")?.take(240)
            throw ApiException(400, DeviceAuth.insufficientScopes(message) ?: message?.takeIf { it.isNotBlank() } ?: "GitHub 列表请求未完成，请刷新后确认状态")
        }
        return root.requiredObject("data")
    }
    suspend fun lists(account: StarListAccount, login: String) = client.allLists(account, login)
    suspend fun items(account: StarListAccount, list: GitHubStarList, cursor: String? = null, descending: Boolean = false) =
        client.items(account, list, cursor, descending)
    suspend fun memberships(account: StarListAccount, owner: String, name: String) = client.memberships(account, owner, name)
    suspend fun create(account: StarListAccount, name: String, description: String, isPrivate: Boolean) = client.create(account, name, description, isPrivate).also { changes.update { it + 1 } }
    suspend fun update(account: StarListAccount, list: GitHubStarList, name: String, description: String, isPrivate: Boolean) = client.update(account, list, name, description, isPrivate).also { changes.update { it + 1 } }
    suspend fun delete(account: StarListAccount, list: GitHubStarList) { client.delete(account, list); changes.update { it + 1 } }
    suspend fun setRepositoryLists(
        account: StarListAccount, owner: String, name: String, selected: Set<String>, original: Set<String>,
        allowUnstarred: Boolean = false,
    ) {
        client.setRepositoryLists(account, owner, name, selected, original, allowUnstarred)
        changes.update { it + 1 }
    }
    suspend fun saveRepositoryLists(
        account: StarListAccount, owner: String, name: String, desired: Set<String>, allowUnstarred: Boolean = false,
    ) {
        client.saveRepositoryLists(account, owner, name, desired, allowUnstarred)
        changes.update { it + 1 }
    }
    suspend fun setRepositoryMembership(account: StarListAccount, repositoryId: String, listIds: Set<String>) {
        client.setRepositoryMembership(account, repositoryId, listIds)
        changes.update { it + 1 }
    }

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
            val reason = failure.networkHint() ?: failure.message ?: "请求失败"
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
