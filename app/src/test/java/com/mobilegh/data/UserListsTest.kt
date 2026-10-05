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
    private fun list(id: String, members: List<String> = emptyList(), total: Int = members.size, next: String? = null, owner: String = "Alice") = buildJsonObject {
        put("id", id); put("name", id); put("slug", id); put("description", ""); put("isPrivate", true)
        put("user", buildJsonObject { put("login", owner) })
        put("items", buildJsonObject { put("totalCount", total) })
        put("scanned", connection(members.map { buildJsonObject { put("id", it) } }, total, next))
    }
    private fun snapshot(lists: List<JsonObject>, total: Int = lists.size, next: String? = null, starred: Boolean = true) = buildJsonObject {
        put("viewer", buildJsonObject { put("login", "Alice"); put("lists", connection(lists, total, next)) })
        put("repository", buildJsonObject { put("id", "R_target"); put("viewerHasStarred", starred) })
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

    @Test fun nativeCreateUpdateDeleteUsePublicMutationInputsAndValidatePayloadOwners() {
        runBlocking {
            val inputs = ArrayList<JsonObject>()
            val api = client { query, vars ->
                assertTrue(query.startsWith("mutation "))
                assertFalse(query.contains("{ viewer")) // viewer belongs to Query, not Mutation.
                inputs += vars["input"]!!.jsonObject
                when {
                    query.contains("MobileGHCreateList") -> buildJsonObject { put("createUserList", buildJsonObject { put("list", list("L_1")) }) }
                    query.contains("MobileGHUpdateList") -> buildJsonObject { put("updateUserList", buildJsonObject { put("list", list("L_1")) }) }
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

    @Test fun allViewerListPagesAndAllItemPagesAreReadBeforeReplacement() {
        runBlocking {
            var mutations = 0
            var sent: Set<String> = emptySet()
            val api = client { query, vars ->
                when {
                    query.contains("MobileGHListMemberships") -> {
                        val after = (vars["after"] as? JsonPrimitive)?.contentOrNull
                        if (after == null) snapshot(listOf(list("L_old", listOf("R_other"), 2, "items-2")), 2, "lists-2")
                        else snapshot(listOf(list("L_new")), 2)
                    }
                    query.contains("MobileGHMembershipItems") -> buildJsonObject {
                        put("viewer", buildJsonObject { put("login", "Alice") })
                        put("node", buildJsonObject {
                            put("id", "L_old"); put("user", buildJsonObject { put("login", "Alice") })
                            put("items", connection(listOf(buildJsonObject { put("id", "R_target") }), 2))
                        })
                    }
                    else -> { mutations++; sent = (vars["input"]!!.jsonObject["listIds"] as JsonArray).map { it.jsonPrimitive.content }.toSet(); confirmed(vars) }
                }
            }
            api.setRepositoryLists(alice, "mona", "repo", setOf("L_new"), emptySet())
            assertEquals(setOf("L_old", "L_new"), sent)
            assertEquals(1, mutations)
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

    @Test fun scanRequestBudgetIsSharedAcrossAllListsAndBlocksTheNextWrite() {
        var requests = 0
        var mutations = 0
        val api = client { query, vars ->
            requests++
            if (query.startsWith("mutation ")) { mutations++; return@client confirmed(vars) }
            if (query.contains("MobileGHListMemberships")) snapshot(listOf(list("L_a", listOf("R_a0"), 61, "a-1"), list("L_b", listOf("R_b0"), 61, "b-1")))
            else {
                val id = vars["id"]!!.jsonPrimitive.content
                val offset = vars["after"]!!.jsonPrimitive.content.substringAfter('-').toInt()
                buildJsonObject {
                    put("viewer", buildJsonObject { put("login", "Alice") })
                    put("node", buildJsonObject {
                        put("id", id); put("user", buildJsonObject { put("login", "Alice") })
                        put("items", connection(listOf(buildJsonObject { put("id", "R_${id}_$offset") }), 61, if (offset < 60) "${if (id == "L_a") "a" else "b"}-${offset + 1}" else null))
                    })
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_a"), emptySet()) } }
        assertEquals(UserListsClient.MAX_SCAN_PAGES, requests)
        assertEquals(0, mutations)
    }

    @Test fun staleSelectionsPreserveNewMembershipsAndOnlyRemoveTheRequestedList() {
        runBlocking {
            var sent: Set<String> = emptySet()
            val api = client { query, vars ->
                if (query.startsWith("query ")) snapshot(listOf(list("L_old", listOf("R_target")), list("L_concurrent", listOf("R_target")), list("L_new")))
                else { sent = (vars["input"]!!.jsonObject["listIds"] as JsonArray).map { it.jsonPrimitive.content }.toSet(); confirmed(vars) }
            }
            api.setRepositoryLists(alice, "mona", "repo", setOf("L_new"), setOf("L_old"))
            assertEquals(setOf("L_concurrent", "L_new"), sent)
        }
    }

    @Test fun incompleteAndOverLimitSnapshotsNeverIssueAMutation() {
        listOf(
            snapshot(listOf(list("L_1")), total = 2),
            snapshot(listOf(list("L_1")), total = UserListsClient.MAX_LISTS + 1),
            snapshot(listOf(list("L_1", total = 1))),
            snapshot(listOf(list("L_1", total = UserListsClient.MAX_SCAN_ITEMS + 1))),
        ).forEach { invalid ->
            var mutations = 0
            val api = client { query, _ -> if (query.startsWith("mutation ")) mutations++; invalid }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
            assertEquals(0, mutations)
        }
    }

    @Test fun absentCursorNullNodesAndRepeatedCursorsBlockMembershipWrites() {
        val noCursor = snapshot(listOf(list("L_1")), 2).let { root ->
            val viewer = root["viewer"]!!.jsonObject
            val lists = viewer["lists"]!!.jsonObject
            JsonObject(root + ("viewer" to JsonObject(viewer + ("lists" to JsonObject(lists + ("pageInfo" to buildJsonObject { put("hasNextPage", true); put("endCursor", JsonNull) }))))))
        }
        val nullNode = snapshot(emptyList(), 1).let { root ->
            val viewer = root["viewer"]!!.jsonObject
            val lists = viewer["lists"]!!.jsonObject
            JsonObject(root + ("viewer" to JsonObject(viewer + ("lists" to JsonObject(lists + ("nodes" to JsonArray(listOf(JsonNull))))))))
        }
        listOf(noCursor, nullNode, snapshot(listOf(list("L_1")), 2, "repeated")).forEach { invalid ->
            var mutated = false
            val api = client { query, _ -> if (query.startsWith("mutation ")) mutated = true; invalid }
            assertThrows(Exception::class.java) { runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
            assertFalse(mutated)
        }
    }

    @Test fun accountSwitchDuringReadsAndCancelledReadsDoNotMutate() {
        var mutated = false
        val switched = client { query, _ ->
            if (query.startsWith("mutation ")) mutated = true
            active = StarListAccount("Bob", 2)
            snapshot(listOf(list("L_1")))
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { switched.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
        assertFalse(mutated)
        active = alice
        val cancelled = client { _, _ -> throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { runBlocking { cancelled.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
        active = null
        assertThrows(IllegalStateException::class.java) { runBlocking { cancelled.create(alice, "New", "", true) } }
    }

    @Test fun unstarredRepositoriesCannotBeAddedButCanBeRemoved() {
        var mutated = false
        val api = client { query, vars ->
            if (query.startsWith("query ")) snapshot(listOf(list("L_1", listOf("R_target"))), starred = false)
            else { mutated = true; confirmed(vars) }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.setRepositoryLists(alice, "mona", "repo", setOf("L_1"), emptySet()) } }
        assertFalse(mutated)
        runBlocking { api.setRepositoryLists(alice, "mona", "repo", emptySet(), setOf("L_1")) }
        assertTrue(mutated)
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
}
