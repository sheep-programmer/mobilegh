package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UserListsTest {
    private val alice = StarListAccount("Alice", 1)
    private var active: StarListAccount? = alice
    private fun connection(nodes: List<JsonObject>, total: Int = nodes.size, next: String? = null) = buildJsonObject {
        put("nodes", JsonArray(nodes)); put("totalCount", total)
        put("pageInfo", buildJsonObject { put("hasNextPage", next != null); put("endCursor", next?.let(::JsonPrimitive) ?: JsonNull) })
    }
    private fun ids(values: List<String>) = values.map { buildJsonObject { put("id", it) } }
    /** 列表目录里的一个列表节点（`MobileGHLists` / `Repository.lists` 的形状）。 */
    private fun listNode(id: String, count: Int = 0, owner: String = "Alice") = buildJsonObject {
        put("id", id); put("name", id); put("slug", id); put("description", ""); put("isPrivate", true)
        put("user", buildJsonObject { put("login", owner) })
        put("items", buildJsonObject { put("totalCount", count) })
    }
    private fun catalog(nodes: List<JsonObject>, total: Int = nodes.size, next: String? = null) = buildJsonObject {
        put("viewer", buildJsonObject { put("login", "Alice") })
        put("user", buildJsonObject { put("login", "Alice"); put("lists", connection(nodes, total, next)) })
    }
    /** `Repository.lists` 的回答：官方客户端读归属就是这么问的。 */
    private fun repositoryLists(lists: List<JsonObject>, starred: Boolean = true, id: String = "R_target") = buildJsonObject {
        put("viewer", buildJsonObject { put("login", "Alice") })
        put("repository", buildJsonObject {
            put("id", id); put("viewerHasStarred", starred)
            put("lists", buildJsonObject { put("nodes", JsonArray(lists)) })
        })
    }
    /** 兜底扫描用的探针。 */
    private fun probe(starred: Boolean = true) = buildJsonObject {
        put("viewer", buildJsonObject { put("login", "Alice") })
        put("repository", buildJsonObject { put("id", "R_target"); put("viewerHasStarred", starred) })
    }
    /** 兜底扫描的批量条目查询。 */
    private fun batchPage(vars: JsonObject, page: (String) -> Triple<List<String>, Int, String?>): JsonObject = buildJsonObject {
        put("viewer", buildJsonObject { put("login", "Alice") })
        var index = 0
        while (true) {
            val id = (vars["id$index"] as? JsonPrimitive)?.contentOrNull ?: break
            val (values, total, next) = page(id)
            put("l$index", buildJsonObject {
                put("id", id); put("user", buildJsonObject { put("login", "Alice") })
                put("items", connection(ids(values), total, next))
            })
            index++
        }
    }
    private fun confirmed(vars: JsonObject) = buildJsonObject {
        val input = vars["input"]!!.jsonObject
        put("updateUserListsForItem", buildJsonObject {
            put("user", buildJsonObject { put("login", "Alice") })
            put("item", buildJsonObject { put("id", "R_target") })
            put("lists", JsonArray((input["listIds"] as JsonArray).map { buildJsonObject { put("id", it) } }))
        })
    }
    private fun client(handler: suspend (String, JsonObject) -> JsonObject) = UserListsClient({ active }) { _, query, vars -> handler(query, vars) }
    private fun varsAfter(vars: JsonObject) = (vars["after"] as? JsonPrimitive)?.contentOrNull
    private fun fieldUnavailable() = ApiException(400, "Field 'lists' doesn't exist on type 'Repository'")

    @Test fun repositoryListsMoveToTheFrontWhileEveryListKeepsItsRelativeOrder() {
        val lists = listOf("L_a", "L_b", "L_c", "L_d").map { GitHubStarList(it, it, "", false, it, "Alice", 1) }
        assertEquals(listOf("L_b", "L_d", "L_a", "L_c"), starListsForRepository(lists, setOf("L_d", "L_b")).map { it.id })
        assertEquals(listOf("L_a", "L_b", "L_c", "L_d"), starListsForRepository(lists, emptySet()).map { it.id })
        assertEquals(listOf("L_a", "L_b", "L_c", "L_d"), starListsForRepository(lists, lists.map { it.id }.toSet()).map { it.id })
    }

    @Test fun nativeCreateUpdateDeleteUsePublicMutationInputsAndValidatePayloadOwners() {
        runBlocking {
            val inputs = ArrayList<JsonObject>()
            val api = client { query, vars ->
                assertTrue(query.startsWith("mutation "))
                assertFalse(query.contains("{ viewer")) // viewer belongs to Query, not Mutation.
                inputs += vars["input"]!!.jsonObject
                when {
                    query.contains("MobileGHCreateList") -> buildJsonObject { put("createUserList", buildJsonObject { put("list", listNode("L_1")) }) }
                    query.contains("MobileGHUpdateList") -> buildJsonObject { put("updateUserList", buildJsonObject { put("list", listNode("L_1")) }) }
                    else -> buildJsonObject { put("deleteUserList", buildJsonObject { put("user", buildJsonObject { put("login", "Alice") }) }) }
                }
            }
            val created = api.create(alice, "  Reading  ", "  Later  ", true)
            assertEquals("Reading", inputs.first()["name"]!!.jsonPrimitive.content)
            assertEquals("Later", inputs.first()["description"]!!.jsonPrimitive.content)
            assertEquals("true", inputs.first()["isPrivate"]!!.jsonPrimitive.content)
            api.update(alice, created, "New", "Description", false)
            assertEquals("L_1", inputs[1]["listId"]!!.jsonPrimitive.content)
            assertEquals("false", inputs[1]["isPrivate"]!!.jsonPrimitive.content)
            api.delete(alice, created)
            assertEquals(setOf("listId"), inputs.last().keys)
        }
    }

    /** 归属只用 `Repository.lists` 一个请求就读完，和官方客户端读法一致。 */
    @Test fun repositoryListsReadsMembershipsInOneRequest() {
        runBlocking {
            var queries = 0
            val api = client { query, _ ->
                queries++
                assertTrue(query.contains("lists(first:100, onlyOwnedByViewer: true)"))
                repositoryLists(listOf(listNode("L_a", 3), listNode("L_b", 0)))
            }
            val snapshot = api.memberships(alice, "mona", "repo")
            assertEquals(1, queries)
            assertEquals(setOf("L_a", "L_b"), snapshot.memberships)
            assertEquals(listOf("L_a", "L_b"), snapshot.lists.map { it.id })
            assertEquals(3, snapshot.lists.first().count)
            assertEquals("R_target", snapshot.repositoryId)
            assertTrue(snapshot.starred)
        }
    }

    @Test fun unstarredFlagComesBackFromTheSameRequest() {
        runBlocking {
            val api = client { _, _ -> repositoryLists(listOf(listNode("L_a")), starred = false) }
            assertFalse(api.memberships(alice, "mona", "repo").starred)
        }
    }

    /** 这个字段没写进公开文档：万一不可用，退回逐个列表读条目，结果一样。 */
    @Test fun membershipReadFallsBackToListScanWhenTheFieldIsUnavailable() {
        runBlocking {
            var batches = 0
            val api = client { query, vars ->
                when {
                    query.contains("MobileGHRepositoryLists") -> throw fieldUnavailable()
                    query.contains("MobileGHLists") -> catalog(listOf(listNode("L_old", 1), listNode("L_new")))
                    query.contains("MobileGHRepositoryId") -> probe()
                    else -> {
                        batches++
                        batchPage(vars) { id -> if (id == "L_old") Triple(listOf("R_target"), 1, null) else Triple(emptyList(), 0, null) }
                    }
                }
            }
            val snapshot = api.memberships(alice, "mona", "repo")
            assertEquals(setOf("L_old"), snapshot.memberships)
            assertEquals(listOf("L_old", "L_new"), snapshot.lists.map { it.id })
            assertEquals(1, batches)
        }
    }

    @Test fun otherReadFailuresAreNotTreatedAsAMissingField() {
        val api = client { _, _ -> throw IllegalStateException("账号已切换或退出") }
        assertThrows(IllegalStateException::class.java) { runBlocking { api.memberships(alice, "mona", "repo") } }
        val permission = client { _, _ -> throw ApiException(400, "Resource not accessible by integration") }
        assertThrows(ApiException::class.java) { runBlocking { permission.memberships(alice, "mona", "repo") } }
    }

    @Test fun duplicateOrOverLimitRepositoryListsAreRejected() {
        repeat(2) {
            val api = client { _, _ -> repositoryLists(listOf(listNode("L_a"), listNode("L_a"))) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.memberships(alice, "mona", "repo") } }
        }
        // 归属读取按翻页上限（PAGE_SIZE）卡，不按新建上限（MAX_LISTS）卡
        val tooMany = (1..UserListsClient.PAGE_SIZE + 1).map { listNode("L_$it") }
        val api = client { _, _ -> repositoryLists(tooMany) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.memberships(alice, "mona", "repo") } }
    }

    /** 新建上限是 32 个列表，但读取不能被这个数字卡住：账号里真有超额列表也要能读出来。 */
    @Test fun accountsWithMoreListsThanTheCreateLimitAreStillReadable() {
        runBlocking {
            val many = (1..UserListsClient.MAX_LISTS + 1).map { listNode("L_$it") }
            val api = client { _, _ -> repositoryLists(many) }
            assertEquals(UserListsClient.MAX_LISTS + 1, api.memberships(alice, "mona", "repo").lists.size)
        }
    }

    @Test fun nativeRepositoryCardsReceiveCountsLanguageTimeAndOrganizationType() {
        runBlocking {
            val api = client { query, _ ->
                assertTrue(query.contains("stargazerCount forkCount primaryLanguage { name } pushedAt updatedAt"))
                Json.parseToJsonElement("""{
                  "viewer":{"login":"Alice"},"node":{"id":"L_1","user":{"login":"Alice"},"items":{
                    "totalCount":1,"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[{
                      "id":"R_target","databaseId":2147483648,"name":"repo","nameWithOwner":"org/repo","isPrivate":false,
                      "description":"Tools","stargazerCount":42,"forkCount":7,"primaryLanguage":{"name":"Kotlin"},
                      "pushedAt":"2026-10-06T00:00:00Z","updatedAt":"2026-10-06T01:00:00Z",
                      "owner":{"__typename":"Organization","login":"org","avatarUrl":"https://github.com/avatar.png"}
                    }]}}} """).jsonObject
            }
            val repo = api.items(alice, GitHubStarList("L_1", "List", "", true, "list", "Alice", 1)).items.single().repo
            assertEquals(2147483648L, repo.id)
            assertEquals(42, repo.stargazersCount)
            assertEquals(7, repo.forksCount)
            assertEquals("Kotlin", repo.language)
            assertEquals("Organization", repo.owner.type)
            assertEquals("2026-10-06T00:00:00Z", repo.pushedAt)
            assertEquals("2026-10-06T01:00:00Z", repo.updatedAt)
        }
    }

    @Test fun staleSelectionsPreserveNewMembershipsAndOnlyRemoveTheRequestedList() {
        runBlocking {
            var sent: Set<String> = emptySet()
            val api = client { query, vars ->
                when {
                    query.contains("MobileGHRepositoryLists") ->
                        repositoryLists(listOf(listNode("L_old", 1), listNode("L_concurrent", 1)))
                    query.contains("MobileGHLists") -> catalog(listOf(listNode("L_old", 1), listNode("L_concurrent", 1), listNode("L_new")))
                    else -> {
                        sent = (vars["input"]!!.jsonObject["listIds"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
                        confirmed(vars)
                    }
                }
            }
            api.setRepositoryLists(alice, "mona", "repo", setOf("L_new"), setOf("L_old"))
            assertEquals(setOf("L_concurrent", "L_new"), sent)
        }
    }

    @Test fun addingAListThatIsNotInTheCatalogIsRejected() {
        var mutations = 0
        val api = client { query, vars ->
            when {
                query.contains("MobileGHRepositoryLists") -> repositoryLists(emptyList())
                query.contains("MobileGHLists") -> catalog(listOf(listNode("L_known")))
                else -> { mutations++; confirmed(vars) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_deleted"), emptySet()) }
        }
        assertEquals(0, mutations)
    }

    @Test fun unstarredRepositoriesCannotBeAddedButCanBeRemoved() {
        var mutated = false
        val api = client { query, vars ->
            when {
                query.contains("MobileGHRepositoryLists") -> repositoryLists(listOf(listNode("L_1", 1)), starred = false)
                query.contains("MobileGHLists") -> catalog(listOf(listNode("L_1", 1)))
                else -> { mutated = true; confirmed(vars) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
        assertFalse(mutated)
        runBlocking { api.setRepositoryLists(alice, "mona", "repo", emptySet(), setOf("L_1")) }
        assertTrue(mutated)
    }

    @Test fun incompleteListCatalogBlocksReplacementWrites() {
        var mutations = 0
        fun expectFailure(handler: suspend (String, JsonObject) -> JsonObject) {
            val api = client { query, vars ->
                if (query.startsWith("mutation ")) { mutations++; confirmed(vars) } else handler(query, vars)
            }
            assertThrows(Exception::class.java) {
                runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) }
            }
        }
        expectFailure { query, _ -> if (query.contains("MobileGHRepositoryLists")) repositoryLists(emptyList()) else catalog(listOf(listNode("L_1")), total = 2) }
        expectFailure { query, _ ->
            if (query.contains("MobileGHRepositoryLists")) repositoryLists(emptyList())
            else catalog(listOf(listNode("L_1")), total = UserListsClient.PAGE_SIZE + 1)
        }
        assertEquals(0, mutations)
    }

    @Test fun fallbackScanBudgetStillBlocksTheNextWrite() {
        var requests = 0
        var mutations = 0
        var pages = 0
        val api = client { query, vars ->
            requests++
            when {
                query.contains("MobileGHRepositoryLists") -> throw fieldUnavailable()
                query.startsWith("mutation ") -> { mutations++; confirmed(vars) }
                query.contains("MobileGHLists") -> catalog(listOf(listNode("L_a", 50_000)))
                query.contains("MobileGHRepositoryId") -> probe()
                query.contains("MobileGHBatchMemberships") -> {
                    pages++
                    // 永远给下一页：只有页数预算能拦住这个扫描
                    batchPage(vars) { id -> Triple(listOf("R_${id}_$pages"), 50_000, "${id}_$pages") }
                }
                else -> {
                    pages++
                    buildJsonObject {
                        put("viewer", buildJsonObject { put("login", "Alice") })
                        put("node", buildJsonObject {
                            put("id", "L_a"); put("user", buildJsonObject { put("login", "Alice") })
                            put("items", connection(ids(listOf("R_page_$pages")), 50_000, "page-$pages"))
                        })
                    }
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_a"), emptySet()) } }
        assertEquals(UserListsClient.MAX_SCAN_PAGES, pages)
        assertEquals(0, mutations)
        assertTrue(requests > pages)
    }

    @Test fun accountSwitchDuringReadsAndCancelledReadsDoNotMutate() {
        var mutated = false
        val switched = client { query, _ ->
            if (query.startsWith("mutation ")) mutated = true
            active = StarListAccount("Bob", 2)
            repositoryLists(listOf(listNode("L_1")))
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { switched.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
        assertFalse(mutated)
        active = alice
        val cancelled = client { _, _ -> throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
        active = null
        assertThrows(IllegalStateException::class.java) { runBlocking { cancelled.create(alice, "New", "", true) } }
    }

    @Test fun partialGraphqlErrorsCannotBeTreatedAsASuccessfulSnapshotOrWrite() {
        val response = Json.parseToJsonElement("""{"data":{"viewer":{"login":"Alice"}},"errors":[{"message":"Permission denied"}]}""").jsonObject
        assertEquals("Permission denied", assertThrows(ApiException::class.java) { UserLists.strictGraphqlData(response) }.message)
        assertThrows(IllegalStateException::class.java) { UserLists.strictGraphqlData(buildJsonObject { put("data", JsonNull) }) }
    }

    @Test fun missingPayloadAndChangedOwnersAreRejectedAndInvalidInputNeverCallsNetwork() {
        var calls = 0
        val api = client { _, _ -> calls++; buildJsonObject {} }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.create(alice, " ", "", true) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.create(alice, "name", "x".repeat(UserListsClient.MAX_DESCRIPTION + 1), true) } }
        assertEquals(0, calls)
        assertThrows(IllegalStateException::class.java) { runBlocking { api.create(alice, "name", "", true) } }
        val other = GitHubStarList("L_other", "Other", "", true, "other", "Bob", 0)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.delete(alice, other) } }
    }

    @Test fun savingWritesTheSelectionDirectlyWithoutReadingMemberships() {
        runBlocking {
            val queries = ArrayList<String>()
            var sent: JsonObject? = null
            val api = client { query, vars ->
                queries += query
                if (query.startsWith("query ")) probe()
                else {
                    sent = vars["input"]!!.jsonObject
                    confirmed(vars)
                }
            }
            api.saveRepositoryLists(alice, "mona", "repo", setOf("L_b", "L_a"))
            // 取仓库节点 id + 写入，两个请求；不再为了归属读一遍列表
            assertEquals(2, queries.size)
            assertTrue(queries.none { it.contains("MobileGHRepositoryLists") || it.contains("MobileGHLists") })
            assertEquals("R_target", sent!!["itemId"]!!.jsonPrimitive.content)
            assertEquals(listOf("L_a", "L_b"), (sent!!["listIds"] as JsonArray).map { it.jsonPrimitive.content })
        }
    }

    @Test fun savingRefusesToAddOnAnUnstarredRepositoryButStillAllowsClearing() {
        var mutations = 0
        val api = client { query, vars ->
            if (query.startsWith("query ")) probe(starred = false)
            else { mutations++; confirmed(vars) }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.saveRepositoryLists(alice, "mona", "repo", setOf("L_1")) } }
        assertEquals(0, mutations)
        // 全部取消 = 只移除，未 Star 也允许
        runBlocking { api.saveRepositoryLists(alice, "mona", "repo", emptySet()) }
        assertEquals(1, mutations)
    }

    @Test fun savingUsesThePayloadShapeFromTheSchema() {
        runBlocking {
            var mutation = ""
            val api = client { query, vars ->
                if (query.startsWith("query ")) probe()
                else {
                    mutation = query
                    confirmed(vars)
                }
            }
            api.saveRepositoryLists(alice, "mona", "repo", setOf("L_a"))
            // updateUserListsForItem 的 lists 是 [UserList!] 普通数组，不是连接：带 first/last/after 会被判未知参数
            assertTrue(mutation.contains("lists { id }"))
            assertFalse(mutation.contains("lists("))
            assertTrue(mutation.contains("item { ... on Repository { id } }"))
            assertTrue(mutation.contains("user { login }"))
        }
    }

    /** 弹窗里勾一下就写一次：仓库节点 id 已经有了，所以只有一次写入请求。 */
    @Test fun membershipWriteIsOneRequestAndSendsTheWholeSelection() {
        runBlocking {
            val queries = ArrayList<String>()
            var sent: JsonObject? = null
            val api = client { query, vars ->
                queries += query
                sent = vars["input"]!!.jsonObject
                confirmed(vars)
            }
            api.setRepositoryMembership(alice, "R_target", setOf("L_b", "L_a"))
            assertEquals(1, queries.size)
            assertTrue(queries.single().startsWith("mutation "))
            assertTrue(queries.single().contains("MobileGHSetListMemberships"))
            assertEquals("R_target", sent!!["itemId"]!!.jsonPrimitive.content)
            assertEquals(listOf("L_a", "L_b"), (sent!!["listIds"] as JsonArray).map { it.jsonPrimitive.content })
        }
    }

    @Test fun membershipWriteAcceptsClearingEveryList() {
        runBlocking {
            var sent: JsonObject? = null
            val api = client { _, vars -> sent = vars["input"]!!.jsonObject; confirmed(vars) }
            api.setRepositoryMembership(alice, "R_target", emptySet())
            assertEquals(0, (sent!!["listIds"] as JsonArray).size)
        }
    }

    @Test fun membershipWriteRejectsBlankRepositoryIdsAndOverlongSelectionsWithoutCallingNetwork() {
        var calls = 0
        val api = client { _, vars -> calls++; confirmed(vars) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.setRepositoryMembership(alice, "  ", setOf("L_1")) } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.setRepositoryMembership(alice, "R_target", (1..UserListsClient.MAX_LISTS + 1).map { "L_$it" }.toSet()) }
        }
        assertEquals(0, calls)
    }

    @Test fun membershipWriteRejectsAConfirmationThatDisagreesWithTheSelection() {
        var calls = 0
        // 返回的 lists 少一个：写入结果与选择不一致，必须报错而不是静默成功
        val api = client { _, _ ->
            calls++
            buildJsonObject {
                put("updateUserListsForItem", buildJsonObject {
                    put("user", buildJsonObject { put("login", "Alice") })
                    put("item", buildJsonObject { put("id", "R_target") })
                    put("lists", JsonArray(ids(listOf("L_a"))))
                })
            }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.setRepositoryMembership(alice, "R_target", setOf("L_a", "L_b")) } }
        assertEquals(1, calls)
    }
}
