package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueHierarchyTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun parseKeepsParentChildrenProgressAndRepositoryCoordinates() {
        val value = IssueHierarchy.parse(json("""
            {"issueType":{"id":"IT_epic","name":"Epic","isEnabled":true},
             "parent":{"id":"I_parent","number":8,"title":"Parent","state":"CLOSED",
               "repository":{"name":"parent-repo","owner":{"login":"other"}}},
             "subIssues":{"nodes":[
               {"id":"I_child","number":11,"title":"Child","state":"OPEN",
                "repository":{"name":"child-repo","owner":{"login":"mona"}}}
             ]},
             "subIssuesSummary":{"total":3,"completed":1,"percentCompleted":33.33}}
        """))
        assertEquals("Epic", value.issueType?.name)
        assertEquals("other", value.parent?.owner)
        assertEquals("parent-repo", value.parent?.name)
        assertEquals("closed", value.parent?.state)
        assertEquals("I_child", value.subIssues.single().nodeId)
        assertEquals("mona", value.subIssues.single().owner)
        assertEquals("child-repo", value.subIssues.single().name)
        assertEquals(SubIssuesSummary(3, 1, 33), value.summary)
    }

    @Test fun parseAcceptsNoParentEmptyChildrenAndNullSummary() {
        val value = IssueHierarchy.parse(json("""{"issueType":null,"parent":null,"subIssues":{"nodes":[]},"subIssuesSummary":null}"""))
        assertNull(value.issueType)
        assertNull(value.parent)
        assertTrue(value.subIssues.isEmpty())
        assertNull(value.summary)
        assertTrue(IssueHierarchy.parse(json("{}")).subIssues.isEmpty())
    }

    @Test fun loadFollowsChildPagesAndPassesRepositoryCoordinates() = runBlocking {
        var calls = 0
        val value = IssueHierarchy.load("mona", "repo", 42, force = true) { query, variables, force ->
            calls++
            assertTrue(query.contains("subIssues("))
            assertTrue(force)
            assertFalse("force is an HTTP option, not a GraphQL variable", variables.containsKey("force"))
            assertEquals(if (calls == 1) null else "cursor-1", variables["after"])
            if (calls == 1) json("""{"repository":{"issue":{"parent":null,"subIssues":{"nodes":[{"id":"I_1","number":1,"title":"one","state":"OPEN","repository":{"name":"repo","owner":{"login":"mona"}}}],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-1"}},"subIssuesSummary":null}}}""")
            else json("""{"repository":{"issue":{"parent":null,"subIssues":{"nodes":[{"id":"I_2","number":2,"title":"two","state":"CLOSED","repository":{"name":"repo","owner":{"login":"mona"}}}],"pageInfo":{"hasNextPage":false,"endCursor":null}},"subIssuesSummary":null}}}""")
        }
        assertEquals(listOf(1, 2), value.subIssues.map { it.number })
        assertEquals(2, calls)
    }

    @Test fun addAndRemoveUseCurrentAndChildIdsInTheRightDirection() = runBlocking {
        val calls = mutableListOf<Map<String, Any?>>()
        IssueHierarchy.addSubIssue("I_parent", "I_child", replaceParent = true) { _, variables, _ -> calls += variables; json("""{"addSubIssue":{"issue":{"id":"I_parent"}}}""") }
        IssueHierarchy.removeSubIssue("I_parent", "I_child") { _, variables, _ -> calls += variables; json("""{"removeSubIssue":{"issue":{"id":"I_parent"}}}""") }
        assertEquals("I_parent", calls[0]["issueId"])
        assertEquals("I_child", calls[0]["subIssueId"])
        assertEquals(true, calls[0]["replaceParent"])
        assertEquals("I_parent", calls[1]["issueId"])
        assertEquals("I_child", calls[1]["subIssueId"])
    }

    @Test fun removingParentExchangesParentAndCurrentIds() = runBlocking {
        var variables = emptyMap<String, Any?>()
        IssueHierarchy.removeParent("I_parent", "I_current") { _, v, _ -> variables = v; json("""{"removeSubIssue":{"issue":{"id":"I_parent"}}}""") }
        assertEquals("I_parent", variables["issueId"])
        assertEquals("I_current", variables["subIssueId"])
    }

    @Test fun hierarchyMutationsRejectMissingPayloadOrIssue() = runBlocking {
        val responses = listOf("{}", """{"addSubIssue":null}""", """{"addSubIssue":{"issue":null}}""")
        for (response in responses) {
            val failure = runCatching {
                IssueHierarchy.addSubIssue("I_parent", "I_child") { _, _, _ -> json(response) }
            }.exceptionOrNull()
            assertTrue("partial mutation data must not report success: $response", failure is ApiException)
        }
        for (response in listOf("{}", """{"removeSubIssue":null}""", """{"removeSubIssue":{"issue":null}}""")) {
            val failure = runCatching {
                IssueHierarchy.removeSubIssue("I_parent", "I_child") { _, _, _ -> json(response) }
            }.exceptionOrNull()
            assertTrue("partial mutation data must not report success: $response", failure is ApiException)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun currentIssueCannotBecomeItsOwnChild() = runBlocking {
        IssueHierarchy.addSubIssue("I_same", "I_same") { _, _, _ -> error("must not call GitHub") }
    }

    @Test fun closingIssuesFollowPagesAndKeepCrossRepositoryTargets() = runBlocking {
        var calls = 0
        val result = IssueHierarchy.closingIssues("mona", "repo", 9) { query, variables, force ->
            calls++
            assertTrue(query.contains("pullRequest"))
            assertTrue(query.contains("closingIssuesReferences"))
            assertFalse(force)
            assertFalse(variables.containsKey("force"))
            assertEquals(if (calls == 1) null else "next", variables["after"])
            if (calls == 1) json("""{"repository":{"pullRequest":{"closingIssuesReferences":{"nodes":[{"id":"I_1","number":1,"title":"one","state":"OPEN","repository":{"name":"repo","owner":{"login":"mona"}}},{"id":"I_2","number":2,"title":"two","state":"CLOSED","repository":{"name":"other","owner":{"login":"hubot"}}}],"pageInfo":{"hasNextPage":true,"endCursor":"next"}}}}}""")
            else json("""{"repository":{"pullRequest":{"closingIssuesReferences":{"nodes":[{"id":"I_3","number":3,"title":"three","state":"OPEN","repository":{"name":"repo","owner":{"login":"mona"}}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}""")
        }
        assertEquals(listOf(1, 2, 3), result.map { it.number })
        assertEquals("hubot", result[1].owner)
        assertEquals("other", result[1].name)
        assertEquals("closed", result[1].state)
        assertEquals(2, calls)
    }

    @Test fun closingIssuesAcceptEmptyConnectionAndNullPageInfo() = runBlocking {
        val result = IssueHierarchy.closingIssues("mona", "repo", 9) { _, _, _ ->
            json("""{"repository":{"pullRequest":{"closingIssuesReferences":{"nodes":[],"pageInfo":null}}}}""")
        }
        assertTrue(result.isEmpty())
    }

    @Test fun linkingAfterCreationReturnsFailureWithoutRecreatingIssue() = runBlocking {
        val failure = IssueHierarchy.addAfterCreation("I_parent", "I_created") { _, _, _ -> error("no triage permission") }
        assertEquals("no triage permission", failure.exceptionOrNull()?.message)
        val skipped = IssueHierarchy.addAfterCreation(null, "I_created") { _, _, _ -> error("must not link") }
        assertTrue(skipped.isSuccess)
    }
}
