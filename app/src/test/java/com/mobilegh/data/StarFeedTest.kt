package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StarFeedTest {
    private val alice = StarListAccount("Alice", 1)
    private var active: StarListAccount? = alice

    private fun client(handler: suspend (JsonObject) -> JsonObject) =
        StarFeedClient({ active }) { _, _, vars -> handler(vars) }

    private fun response(
        viewer: String = "Alice",
        owner: String = "Alice",
        type: String = "User",
        entries: List<Pair<Long, String>> = emptyList(),
        next: String? = null,
        overLimit: Boolean = false,
    ) = buildJsonObject {
        put("viewer", buildJsonObject { put("login", viewer) })
        put("repositoryOwner", buildJsonObject {
            put("__typename", type)
            put("login", owner)
            if (type == "User") put("starredRepositories", buildJsonObject {
                put("isOverLimit", overLimit)
                put("pageInfo", buildJsonObject {
                    put("hasNextPage", next != null)
                    put("endCursor", next?.let(::JsonPrimitive) ?: JsonNull)
                })
                put("edges", JsonArray(entries.map { (id, at) ->
                    buildJsonObject {
                        put("starredAt", at)
                        put("node", buildJsonObject {
                            put("databaseId", id)
                            put("name", "repo$id")
                            put("nameWithOwner", "a/repo$id")
                            put("isPrivate", false)
                            put("owner", buildJsonObject { put("login", "a"); put("avatarUrl", ""); put("__typename", "User") })
                        })
                    }
                }))
            })
        })
    }

    @Test fun descendingKeepsServerOrderAndReadsStarredAt() {
        runBlocking {
            var seen: JsonObject? = null
            val page = client { vars -> seen = vars; response(entries = listOf(3L to "2026-01-03T00:00:00Z", 2L to "2026-01-02T00:00:00Z")) }
                .page(alice, "Alice", direction = "DESC")
            assertEquals(listOf(3L, 2L), page.items.map { it.repo.id })
            assertEquals("2026-01-03T00:00:00Z", page.items.first().starredAt)
            assertNull(page.nextCursor)
            // 排序完全交给服务端：请求必须带上 STARRED_AT + DESC
            val order = seen!!["order"] as JsonObject
            assertEquals("STARRED_AT", (order["field"] as JsonPrimitive).content)
            assertEquals("DESC", (order["direction"] as JsonPrimitive).content)
        }
    }

    @Test fun ascendingIsSentToTheServerInsteadOfReversingLocally() {
        runBlocking {
            var seen: JsonObject? = null
            val page = client { vars -> seen = vars; response(entries = listOf(1L to "2023-07-20T00:00:00Z")) }
                .page(alice, "Alice", direction = "ASC")
            assertEquals(listOf(1L), page.items.map { it.repo.id })
            val order = seen!!["order"] as JsonObject
            assertEquals("ASC", (order["direction"] as JsonPrimitive).content)
        }
    }

    @Test fun cursorIsForwardedForLazyPaging() {
        runBlocking {
            val cursors = mutableListOf<JsonPrimitive?>()
            val page = client { vars ->
                cursors += vars["after"] as JsonPrimitive?
                response(entries = listOf(9L to "2026-05-05T00:00:00Z"), next = "cursor-2")
            }.page(alice, "Alice", cursor = "cursor-1")
            assertEquals("cursor-1", cursors.single()!!.content)
            assertEquals("cursor-2", page.nextCursor)
        }
    }

    @Test fun invalidDirectionIsRejectedBeforeAnyRequest() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client { error("must not be called") }.page(alice, "Alice", direction = "SIDEWAYS") }
        }
        assertTrue(failure.message!!.contains("排序方向"))
    }

    @Test fun organisationReturnsAnEmptyPageInsteadOfFailing() {
        runBlocking {
            val page = client { response(owner = "acme", type = "Organization") }.page(alice, "acme")
            assertTrue(page.items.isEmpty())
            assertNull(page.nextCursor)
        }
    }

    @Test fun anotherAccountsFeedIsRejected() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client { response(viewer = "Mallory") }.page(alice, "Alice") }
        }
        assertTrue(failure.message!!.contains("其他账号"))
    }

    @Test fun accountSwitchStopsTheRequest() {
        active = StarListAccount("Bob", 2)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { client { response() }.page(alice, "Alice") }
        }
    }

    @Test fun queryAsksForStarredAtOrderAndStarredAtField() {
        assertTrue(StarFeedClient.query().contains("orderBy:\$order"))
        assertTrue(StarFeedClient.query().contains("edges { starredAt node {"))
        assertTrue(StarFeedClient.query().contains("repositoryOwner(login:\$login)"))
    }
}
