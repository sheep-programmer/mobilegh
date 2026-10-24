package com.mobilegh.data

import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StarredReposTest {
    private val alice = StarListAccount("Alice", 1)
    private var active: StarListAccount? = alice
    private fun client(handler: suspend (String, JsonObject) -> JsonObject) = StarredReposClient({ active }) { _, query, vars -> handler(query, vars) }

    private fun repoNode(
        login: String = "Alice",
        name: String = "repo",
        id: Long = 42,
        next: String? = null,
        truncated: Boolean = false,
    ) = buildJsonObject {
        put("__typename", "User")
        put("starredRepositories", buildJsonObject {
            put("isOverLimit", truncated)
            put("pageInfo", buildJsonObject {
                put("hasNextPage", next != null)
                put("endCursor", next?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("nodes", JsonArray(listOf(buildJsonObject {
                put("id", "R_$name")
                put("databaseId", id)
                put("name", name)
                put("nameWithOwner", "$login/$name")
                put("isPrivate", false)
                put("description", "about $name")
                put("stargazerCount", 7)
                put("forkCount", 2)
                put("isFork", true)
                put("isArchived", false)
                put("primaryLanguage", buildJsonObject { put("name", "Kotlin") })
                put("pushedAt", "2026-01-02T03:04:05Z")
                put("updatedAt", "2026-02-03T04:05:06Z")
                put("owner", buildJsonObject {
                    put("__typename", "User")
                    put("login", login)
                    put("avatarUrl", "https://avatars.example/$login")
                })
            })))
        })
    }

    private fun response(owner: JsonObject = repoNode(), viewer: String = "Alice") = buildJsonObject {
        put("viewer", buildJsonObject { put("login", viewer) })
        put("repositoryOwner", owner)
    }

    @Test fun officialQueryUsesServerSearchAndStarredOrder() {
        runBlocking {
            var query = ""
            var vars = JsonObject(emptyMap())
            val api = client { q, v -> query = q; vars = v; response() }
            val page = api.page(alice, "Alice", "  language:kotlin  ")
            assertTrue(query.startsWith("query MobileGHStarredRepositories"))
            assertTrue(query.contains("starredRepositories(first:\$first, after:\$after, query:\$query, orderBy:\$order)"))
            assertTrue(query.contains("... on User"))
            assertEquals("Alice", vars["login"]!!.jsonPrimitive.content)
            assertEquals("language:kotlin", vars["query"]!!.jsonPrimitive.content)
            assertEquals("100", vars["first"]!!.jsonPrimitive.content)
            val order = vars["order"]!!.jsonObject
            assertEquals("STARRED_AT", order["field"]!!.jsonPrimitive.content)
            assertEquals("DESC", order["direction"]!!.jsonPrimitive.content)
            assertEquals(1, page.repos.size)
        }
    }

    @Test fun sortDirectionIsPassedToTheServer() {
        runBlocking {
            var vars = JsonObject(emptyMap())
            val api = client { _, v -> vars = v; response() }
            api.page(alice, "Alice", "kotlin", null, "ASC")
            assertEquals("ASC", vars["order"]!!.jsonObject["direction"]!!.jsonPrimitive.content)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { api.page(alice, "Alice", "kotlin", null, "SIDEWAYS") }
            }
        }
    }

    @Test fun browsingWithoutKeywordSendsNullQueryAndMapsRepository() {
        runBlocking {
            var vars = JsonObject(emptyMap())
            val api = client { _, v -> vars = v; response() }
            val page = api.page(alice, "Alice")
            assertEquals(JsonNull, vars["query"])
            val repo = page.repos.single()
            assertEquals(42L, repo.id)
            assertEquals("Alice/repo", repo.fullName)
            assertEquals("repo", repo.name)
            assertEquals("Alice", repo.owner.login)
            assertEquals(7, repo.stargazersCount)
            assertEquals(2, repo.forksCount)
            assertEquals("Kotlin", repo.language)
            assertEquals("about repo", repo.description)
            assertTrue(repo.fork)
            assertFalse(repo.archived)
            assertEquals("2026-01-02T03:04:05Z", repo.pushedAt)
            assertEquals("2026-02-03T04:05:06Z", repo.updatedAt)
            assertNull(page.nextCursor)
            assertFalse(page.truncated)
        }
    }

    @Test fun nextPageReusesEndCursorAndReportsTruncation() {
        runBlocking {
            val cursors = ArrayList<String?>()
            val api = client { _, vars ->
                cursors += (vars["after"] as? JsonPrimitive)?.contentOrNull
                response(repoNode(next = "cursor-2", truncated = true))
            }
            val first = api.page(alice, "Alice", "starred:>2020-01-01")
            assertEquals("cursor-2", first.nextCursor)
            assertTrue(first.truncated)
            val second = api.page(alice, "Alice", "starred:>2020-01-01", first.nextCursor)
            assertEquals(listOf(null, "cursor-2"), cursors)
            assertEquals("cursor-2", second.nextCursor)
        }
    }

    @Test fun repositoriesOwnedByOtherAccountsAreRejected() {
        val api = client { _, _ -> response(viewer = "Mallory") }
        val failure = assertThrows(IllegalArgumentException::class.java) { runBlocking { api.page(alice, "Alice") } }
        assertTrue(failure.message!!.contains("其他账号"))
    }

    @Test fun organizationOwnerHasNoStarsRatherThanAnError() {
        runBlocking {
            val api = client { _, _ -> response(buildJsonObject { put("__typename", "Organization") }) }
            val page = api.page(alice, "some-org")
            assertTrue(page.repos.isEmpty())
            assertNull(page.nextCursor)
        }
    }

    @Test fun unsupportedQueryArgumentFallsBackToLocalSearch() {
        val api = client { _, _ ->
            throw ApiException(400, "Field 'starredRepositories' doesn't accept argument 'query'")
        }
        assertThrows(StarSearchUnavailable::class.java) { runBlocking { api.page(alice, "Alice", "kotlin") } }
    }

    @Test fun otherGraphqlFailuresAreNotMistakenForMissingQuerySupport() {
        val api = client { _, _ -> throw ApiException(502, "服务暂时不可用") }
        val failure = assertThrows(ApiException::class.java) { runBlocking { api.page(alice, "Alice", "kotlin") } }
        assertEquals(502, failure.code)
    }

    @Test fun rejectsInvalidLoginsAndLongKeywords() {
        val api = client { _, _ -> response() }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { api.page(alice, "../etc") } }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { api.page(alice, "Alice", "k".repeat(StarredReposClient.MAX_QUERY + 1)) }
        }
    }

    @Test fun inactiveAccountStopsTheRequestBeforeItLeaves() {
        active = StarListAccount("Bob", 2)
        var called = false
        val api = client { _, _ -> called = true; response() }
        assertThrows(IllegalStateException::class.java) { runBlocking { api.page(alice, "Alice") } }
        assertFalse(called)
    }

    @Test fun localFilterMatchesNameCoordinatesDescriptionAndOwner() {
        val repo = Repo(
            name = "mobilegh", fullName = "Alice/mobilegh", owner = User(login = "Alice"),
            description = "GitHub 客户端",
        )
        assertTrue(starMatches(repo, ""))
        assertTrue(starMatches(repo, "mobile"))
        assertTrue(starMatches(repo, "alice/"))
        assertTrue(starMatches(repo, "客户端"))
        assertTrue(starMatches(repo, "ALICE"))
        assertFalse(starMatches(repo, "nothing-here"))
    }
}
