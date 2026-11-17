package com.mobilegh.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsSettingsTest {
    @Test fun generalPermissionsUseDocumentedFields() {
        val request = ActionsRequests.permissions(enabled = true, allowedActions = "selected", shaPinningRequired = true)
        assertEquals("PUT", request.method)
        assertEquals("/actions/permissions", request.path)
        assertTrue(request.body!!["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("selected", request.body["allowed_actions"]!!.jsonPrimitive.content)
        assertTrue(request.body["sha_pinning_required"]!!.jsonPrimitive.boolean)
    }

    @Test fun privateRepositorySettingsHaveSeparateEndpoints() {
        assertEquals("/actions/permissions/access", ActionsRequests.access("organization").path)
        assertEquals("organization", ActionsRequests.access("organization").body!!["access_level"]!!.jsonPrimitive.content)
        val retention = ActionsRequests.artifactRetention(45)
        assertEquals("/actions/permissions/artifact-and-log-retention", retention.path)
        assertEquals(45, retention.body!!["days"]!!.jsonPrimitive.int)
        val approval = ActionsRequests.forkApproval("all_external_contributors")
        assertEquals("/actions/permissions/fork-pr-contributor-approval", approval.path)
        assertEquals("all_external_contributors", approval.body!!["approval_policy"]!!.jsonPrimitive.content)
    }

    @Test fun privateForkAndWorkflowBodiesMatchApi() {
        val fork = ActionsRequests.privateForks(run = true, writeTokens = false, secrets = true, approval = false)
        assertEquals("/actions/permissions/fork-pr-workflows-private-repos", fork.path)
        assertTrue(fork.body!!["run_workflows_from_fork_pull_requests"]!!.jsonPrimitive.boolean)
        assertFalse(fork.body["send_write_tokens_to_workflows"]!!.jsonPrimitive.boolean)
        assertTrue(fork.body["send_secrets_and_variables"]!!.jsonPrimitive.boolean)
        assertFalse(fork.body["require_approval_for_fork_pr_workflows"]!!.jsonPrimitive.boolean)
        val workflow = ActionsRequests.workflow("write", canApprove = true)
        assertEquals("/actions/permissions/workflow", workflow.path)
        assertEquals("write", workflow.body!!["default_workflow_permissions"]!!.jsonPrimitive.content)
        assertTrue(workflow.body["can_approve_pull_request_reviews"]!!.jsonPrimitive.boolean)
    }

    @Test fun selectedActionsIncludeAllPatterns() {
        val request = ActionsRequests.selected(githubOwned = true, verified = false, patterns = listOf("octo/*", "actions/checkout@*"))
        assertEquals("/actions/permissions/selected-actions", request.path)
        assertTrue(request.body!!["github_owned_allowed"]!!.jsonPrimitive.boolean)
        assertFalse(request.body["verified_allowed"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("octo/*", "actions/checkout@*"), request.body["patterns_allowed"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun secretAndVariableNamesArePathEncoded() {
        assertEquals("/actions/secrets/A%20B%2FC", ActionsRequests.secret("A B/C").path)
        assertEquals("DELETE", ActionsRequests.secret("A B/C").method)
        assertEquals("/actions/variables/A%20B%2FC", ActionsRequests.variable("A B/C").path)
        assertEquals("DELETE", ActionsRequests.variable("A B/C").method)
        assertEquals("/actions/variables/A%20B%2FC", ActionsRequests.patchVariable("A B/C", name = null, value = "").path)
    }

    @Test fun secretPutContainsOnlyEncryptedValueAndKeyId() {
        val request = ActionsRequests.putSecret("PROD", encrypted = "cipher", keyId = "key-1")
        assertEquals("PUT", request.method)
        assertEquals(setOf("encrypted_value", "key_id"), request.body!!.keys)
        assertEquals("cipher", request.body["encrypted_value"]!!.jsonPrimitive.content)
        assertEquals("key-1", request.body["key_id"]!!.jsonPrimitive.content)
    }

    @Test fun variableCreateAndDeltaPatchAreDistinct() {
        val create = ActionsRequests.createVariable("EMPTY_OK", "")
        assertEquals("POST", create.method)
        assertEquals("/actions/variables", create.path)
        assertEquals("", create.body!!["value"]!!.jsonPrimitive.content)
        val rename = ActionsRequests.patchVariable("OLD", name = "NEW", value = null)
        assertEquals("PATCH", rename.method)
        assertEquals("/actions/variables/OLD", rename.path)
        assertEquals(setOf("name"), rename.body!!.keys)
        val clear = ActionsRequests.patchVariable("NEW", name = null, value = "")
        assertEquals(setOf("value"), clear.body!!.keys)
        assertEquals("", clear.body["value"]!!.jsonPrimitive.content)
    }

    @Test fun listQueriesUseTheirDifferentPageLimitsAndFilters() {
        assertEquals("/actions/secrets?per_page=100&page=2", ActionsRequests.secrets(2).path)
        assertEquals("/actions/organization-secrets?per_page=100&page=1", ActionsRequests.organizationSecrets(1).path)
        assertEquals("/actions/variables?per_page=30&page=3", ActionsRequests.variables(3).path)
        assertEquals("/actions/runners?per_page=100&page=1&name=linux+x64", ActionsRequests.runners(1, "linux x64").path)
        val caches = ActionsRequests.caches(2, key = "npm cache", ref = "refs/heads/main")
        assertTrue(caches.path.startsWith("/actions/caches?per_page=100&page=2&key="))
        assertTrue(caches.path.endsWith("&ref=refs%2Fheads%2Fmain"))
    }

    @Test fun cacheDeletesAndLimitsAreNotInterchangeable() {
        val byKey = ActionsRequests.deleteCaches("npm cache", "refs/heads/main")
        assertEquals("DELETE", byKey.method)
        assertTrue(byKey.path.startsWith("/actions/caches?key="))
        assertTrue(byKey.path.endsWith("&ref=refs%2Fheads%2Fmain"))
        assertEquals("/actions/caches/42", ActionsRequests.deleteCache(42).path)
        val retention = ActionsRequests.cacheRetention(80)
        assertEquals("/actions/cache/retention-limit", retention.path)
        assertEquals(80, retention.body!!["max_cache_retention_days"]!!.jsonPrimitive.int)
        val storage = ActionsRequests.cacheStorage(150)
        assertEquals("/actions/cache/storage-limit", storage.path)
        assertEquals(150, storage.body!!["max_cache_size_gb"]!!.jsonPrimitive.int)
    }

    @Test fun runnerTokensAndDownloadsUseDocumentedRoutes() {
        assertEquals("POST", ActionsRequests.runnerRegistrationToken().method)
        assertEquals("/actions/runners/registration-token", ActionsRequests.runnerRegistrationToken().path)
        assertEquals("/actions/runners/downloads", ActionsRequests.runnerDownloads().path)
        assertEquals("DELETE", ActionsRequests.deleteRunner(9).method)
        assertEquals("/actions/runners/9", ActionsRequests.deleteRunner(9).path)
    }

    @Test fun dtoDecodingHandlesSnakeCaseAndNullableCacheFields() {
        val secret = Api.plain.decodeFromString(ActionSecret.serializer(), """{"name":"TOKEN","created_at":"2026-01-01","updated_at":null}""")
        assertEquals("2026-01-01", secret.createdAt)
        assertNull(secret.updatedAt)
        val runner = Api.plain.decodeFromString(ActionRunner.serializer(), """{"id":1,"name":"r","os":"linux","status":"online","busy":false,"labels":[{"id":2,"name":"self-hosted","type":"read-only"}],"ephemeral":true,"version":"2.1"}""")
        assertTrue(runner.ephemeral)
        assertEquals("read-only", runner.labels.single().type)
        val cache = Api.plain.decodeFromString(ActionCache.serializer(), """{"id":3,"ref":null,"key":"k","version":"v","last_accessed_at":null,"created_at":"c","size_in_bytes":12}""")
        assertNull(cache.ref)
        assertEquals(12, cache.sizeInBytes)
        val variables = Api.plain.decodeFromString(ActionVariableList.serializer(), """{"total_count":1,"variables":[{"name":"A","value":"","created_at":"c","updated_at":"u"}]}""")
        assertEquals("", variables.variables.single().value)
    }
}
