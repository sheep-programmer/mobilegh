package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentReviewsTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun parseMultipleEnvironmentsAndUserTeamReviewers() = runBlocking {
        val reviews = DeploymentReviews.pendingDeploymentRequests("WR_node") { query, variables ->
            assertTrue(query.contains("node(id: \$id)"))
            assertTrue(query.contains("pendingDeploymentRequests"))
            assertEquals("WR_node", variables["id"])
            json("""
                {"node":{"pendingDeploymentRequests":{"nodes":[
                  {"environment":{"id":"E_production","name":"production"},"currentUserCanApprove":true,
                   "reviewers":{"nodes":[{"__typename":"User","login":"mona"},{"__typename":"Team","name":"Release admins","slug":"release-admins"}]}},
                  {"environment":{"id":"E_staging","name":"staging"},"currentUserCanApprove":false,"reviewers":{"nodes":[]}}
                ]}}}
            """)
        }
        assertEquals(listOf("E_production", "E_staging"), reviews.map { it.environmentId })
        assertEquals("production", reviews[0].environmentName)
        assertTrue(reviews[0].canApprove)
        assertEquals(listOf("mona", "Release admins"), reviews[0].reviewers)
        assertFalse(reviews[1].canApprove)
    }

    @Test fun successfulEmptyRunHasNoPendingReviews() = runBlocking {
        val empty = DeploymentReviews.pendingDeploymentRequests("WR_node") { _, _ ->
            json("""{"node":{"pendingDeploymentRequests":{"nodes":[]}}}""")
        }
        assertTrue(empty.isEmpty())
    }

    @Test fun missingOrNullWorkflowRunIsNotAnEmptyReviewResult() = runBlocking {
        for (response in listOf("""{"node":null}""", "{}")) {
            val failure = runCatching {
                DeploymentReviews.pendingDeploymentRequests("WR_missing") { _, _ -> json(response) }
            }.exceptionOrNull()
            assertTrue("partial query data must fail: $response", failure is ApiException)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankNodeIdNeverFallsBackToNumericRunId() = runBlocking {
        DeploymentReviews.pendingDeploymentRequests("") { _, _ -> error("must not call GitHub with a numeric ID fallback") }
        Unit
    }

    @Test fun restRunIdIsNotUsedAsGraphqlNodeId() {
        val run = Api.json.decodeFromString(Run.serializer(), """{"id":42,"node_id":null}""")
        assertEquals(42, run.id)
        assertNull(run.nodeId)
    }

    @Test fun approvalUsesWorkflowRunNodeIdAndEnvironmentIds() = runBlocking {
        var query = ""
        var variables = emptyMap<String, Any?>()
        DeploymentReviews.reviewDeployments(
            DeploymentReviewInput("WR_node", listOf("E_one", "E_two"), "Ready to ship"),
            approve = true,
        ) { q, v -> query = q; variables = v; json("""{"approveDeployments":{}}""") }
        assertTrue(query.contains("approveDeployments"))
        assertFalse(query.contains("rejectDeployments"))
        val input = variables["input"] as JsonObject
        assertEquals("WR_node", input["workflowRunId"]?.jsonPrimitive?.content)
        assertEquals(listOf("E_one", "E_two"), (input["environmentIds"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("Ready to ship", input["comment"]?.jsonPrimitive?.content)
    }

    @Test fun rejectionUsesRejectMutationAndBlankCommentBecomesNull() = runBlocking {
        var query = ""
        var variables = emptyMap<String, Any?>()
        DeploymentReviews.reviewDeployments(
            DeploymentReviewInput("WR_node", listOf("E_one"), "  \n "),
            approve = false,
        ) { q, v -> query = q; variables = v; json("""{"rejectDeployments":{}}""") }
        assertTrue(query.contains("rejectDeployments"))
        assertFalse(query.contains("approveDeployments"))
        val input = variables["input"] as JsonObject
        assertEquals("WR_node", input["workflowRunId"]?.jsonPrimitive?.content)
        assertTrue(input.containsKey("comment"))
        assertEquals("null", input["comment"].toString())
    }

    @Test fun reviewRejectsMissingOrNullMutationPayload() = runBlocking {
        for (approve in listOf(true, false)) {
            val payload = if (approve) "approveDeployments" else "rejectDeployments"
            for (response in listOf("{}", "{\"$payload\":null}")) {
                val failure = runCatching {
                    DeploymentReviews.reviewDeployments(
                        DeploymentReviewInput("WR_node", listOf("E_one"), null),
                        approve,
                    ) { _, _ -> json(response) }
                }.exceptionOrNull()
                assertTrue("partial mutation data must not report success: $response", failure is ApiException)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun reviewRejectsBlankWorkflowRunNodeId() = runBlocking {
        DeploymentReviews.reviewDeployments(DeploymentReviewInput(" \n ", listOf("E_one"), null), true) { _, _ ->
            error("must not call GitHub without a WorkflowRun Node ID")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun reviewRequiresAtLeastOneEnvironment() = runBlocking {
        DeploymentReviews.reviewDeployments(DeploymentReviewInput("WR_node", emptyList(), null), true) { _, _ ->
            error("must not call GitHub without an environment")
        }
    }
}
