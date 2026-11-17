package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueTypesTest {
    @Test fun parsesEnabledAndDisabledRepositoryIssueTypes() {
        val repository = buildJsonObject {
            putJsonObject("issueTypes") {
                putJsonObject("pageInfo") { put("hasNextPage", false) }
                putJsonArray("nodes") {
                    add(buildJsonObject {
                        put("id", "IT_bug")
                        put("name", "Bug")
                        put("description", "Something is not working")
                        put("color", "RED")
                        put("isEnabled", true)
                    })
                    add(buildJsonObject {
                        put("id", "IT_internal")
                        put("name", "Internal")
                        put("isEnabled", false)
                        put("futureColor", "PURPLE")
                    })
                }
            }
        }

        val types = IssueTypes.parseOptions(repository)

        assertEquals(listOf("IT_bug", "IT_internal"), types.map { it.id })
        assertEquals("Bug", types[0].name)
        assertEquals("Something is not working", types[0].description)
        assertEquals("RED", types[0].color)
        assertTrue(types[0].enabled)
        assertFalse(types[1].enabled)
        assertNull(types[1].description)
    }

    @Test fun missingOrNullConnectionsAndIssueTypesAreSafe() {
        assertEquals(emptyList<IssueType>(), IssueTypes.parseOptions(buildJsonObject { }))
        assertNull(IssueTypes.parseIssueType(null))
        assertNull(IssueTypes.parseIssueType(buildJsonObject { }))
    }

    @Test fun optionsFollowCursorsWithoutEmbeddingRepositoryNames() = runBlocking {
        val cursors = mutableListOf<Any?>()
        val types = IssueTypes.options("mona", "repo") { query, variables ->
            cursors += variables["after"]
            assertEquals("mona", variables["owner"])
            assertEquals("repo", variables["name"])
            assertTrue(query.contains("issueTypes"))
            assertFalse(query.contains("mona"))
            val after = variables["after"]
            Json.parseToJsonElement(
                if (after == null)
                    """{"repository":{"issueTypes":{"nodes":[{"id":"IT_bug","name":"Bug","isEnabled":true}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}"""
                else
                    """{"repository":{"issueTypes":{"nodes":[null,{"id":"IT_task","name":"Task","isEnabled":true}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}"""
            ) as JsonObject
        }
        assertEquals(listOf(null, "next"), cursors)
        assertEquals(listOf("Bug", "Task"), types.map { it.name })
    }

    @Test fun currentTypeUsesIssueNumberAndAcceptsMissingType() = runBlocking {
        var variables = emptyMap<String, Any?>()
        val current = IssueTypes.current("mona", "repo", 42) { query, v ->
            variables = v
            assertTrue(query.contains("issue(number:"))
            assertTrue(query.contains("issueType"))
            Json.parseToJsonElement("""{"repository":{"issue":{"issueType":{"id":"IT_bug","name":"Bug","isEnabled":true}}}}""") as JsonObject
        }
        assertEquals("Bug", current?.name)
        assertEquals(42, variables["number"])

        val absent = IssueTypes.current("mona", "repo", 43) { _, _ ->
            Json.parseToJsonElement("""{"repository":{"issue":{"issueType":null}}}""") as JsonObject
        }
        assertNull(absent)
    }

    @Test fun createTypeFailureIsReturnedWithoutCancellingTheCreatedIssue() = runBlocking {
        val failure = IssueTypes.setAfterCreation("I_created", "IT_bug") { _, _ -> error("no triage permission") }
        assertEquals("no triage permission", failure.exceptionOrNull()?.message)
        val cleared = IssueTypes.setAfterCreation("I_created", null) { _, _ -> error("must not mutate") }
        assertTrue(cleared.isSuccess)
        assertNull(cleared.getOrNull())
    }

    @Test fun mutationUsesNodeIdsAndCanClearOrReturnLatestType() = runBlocking {
        val calls = mutableListOf<Map<String, Any?>>()
        var query = ""
        val updated = IssueTypes.set("I_issue", "IT_bug") { q, variables ->
            query = q
            calls += variables
            Json.parseToJsonElement("""{"updateIssueIssueType":{"issue":{"issueType":{"id":"IT_bug","name":"Bug","isEnabled":true}}}}""") as JsonObject
        }
        assertEquals("Bug", updated?.name)
        assertEquals("I_issue", calls.single()["issueId"])
        assertEquals("IT_bug", calls.single()["issueTypeId"])
        assertTrue(query.contains("updateIssueIssueType"))

        val cleared = IssueTypes.set("I_issue", null) { _, variables ->
            calls += variables
            Json.parseToJsonElement("""{"updateIssueIssueType":{"issue":{"issueType":null}}}""") as JsonObject
        }
        assertNull(cleared)
        assertNull(calls.last()["issueTypeId"])
    }

    @Test fun mutationRejectsMissingIssueNullOrMismatchedType() = runBlocking {
        val responses = listOf(
            """{"updateIssueIssueType":{"issue":null}}""",
            """{"updateIssueIssueType":{"issue":{"issueType":null}}}""",
            """{"updateIssueIssueType":{"issue":{"issueType":{"id":"IT_other","name":"Other","isEnabled":true}}}}""",
        )
        for (response in responses) {
            val failure = runCatching {
                IssueTypes.set("I_issue", "IT_bug") { _, _ -> Json.parseToJsonElement(response) as JsonObject }
            }.exceptionOrNull()
            assertTrue("partial or mismatched mutation data must fail: $response", failure is ApiException)
        }
    }
}
