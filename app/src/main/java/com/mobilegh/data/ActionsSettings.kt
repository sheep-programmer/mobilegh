package com.mobilegh.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class ActionRequest(val method: String, val path: String, val body: JsonObject? = null)

/** Repository-relative Actions REST contracts. GitHub adds the /repos/{owner}/{repo} prefix. */
object ActionsRequests {
    fun permissions(enabled: Boolean, allowedActions: String, shaPinningRequired: Boolean) = ActionRequest("PUT", "/actions/permissions", buildJsonObject {
        put("enabled", enabled)
        put("allowed_actions", allowedActions)
        put("sha_pinning_required", shaPinningRequired)
    })
    fun access(level: String) = ActionRequest("PUT", "/actions/permissions/access", buildJsonObject { put("access_level", level) })
    fun artifactRetention(days: Int) = ActionRequest("PUT", "/actions/permissions/artifact-and-log-retention", buildJsonObject { put("days", days) })
    fun forkApproval(policy: String) = ActionRequest("PUT", "/actions/permissions/fork-pr-contributor-approval", buildJsonObject { put("approval_policy", policy) })
    fun privateForks(run: Boolean, writeTokens: Boolean, secrets: Boolean, approval: Boolean) = ActionRequest("PUT", "/actions/permissions/fork-pr-workflows-private-repos", buildJsonObject {
        put("run_workflows_from_fork_pull_requests", run)
        put("send_write_tokens_to_workflows", writeTokens)
        put("send_secrets_and_variables", secrets)
        put("require_approval_for_fork_pr_workflows", approval)
    })
    fun selected(githubOwned: Boolean, verified: Boolean, patterns: List<String>) = ActionRequest("PUT", "/actions/permissions/selected-actions", buildJsonObject {
        put("github_owned_allowed", githubOwned)
        put("verified_allowed", verified)
        putJsonArray("patterns_allowed") { patterns.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
    })
    fun workflow(default: String, canApprove: Boolean) = ActionRequest("PUT", "/actions/permissions/workflow", buildJsonObject {
        put("default_workflow_permissions", default)
        put("can_approve_pull_request_reviews", canApprove)
    })

    fun secrets(page: Int) = ActionRequest("GET", "/actions/secrets?per_page=100&page=$page")
    fun organizationSecrets(page: Int) = ActionRequest("GET", "/actions/organization-secrets?per_page=100&page=$page")
    fun publicKey() = ActionRequest("GET", "/actions/secrets/public-key")
    fun secret(name: String) = ActionRequest("DELETE", "/actions/secrets/${Api.q(name).replace("+", "%20")}")
    fun putSecret(name: String, encrypted: String, keyId: String) = ActionRequest("PUT", "/actions/secrets/${Api.q(name).replace("+", "%20")}", buildJsonObject {
        put("encrypted_value", encrypted)
        put("key_id", keyId)
    })

    fun variables(page: Int) = ActionRequest("GET", "/actions/variables?per_page=30&page=$page")
    fun createVariable(name: String, value: String) = ActionRequest("POST", "/actions/variables", buildJsonObject {
        put("name", name)
        put("value", value)
    })
    fun variable(name: String) = ActionRequest("DELETE", "/actions/variables/${Api.q(name).replace("+", "%20")}")
    fun patchVariable(originalName: String, name: String?, value: String?) = ActionRequest("PATCH", "/actions/variables/${Api.q(originalName).replace("+", "%20")}", buildJsonObject {
        name?.let { put("name", it) }
        value?.let { put("value", it) }
    })

    fun runners(page: Int, name: String? = null) = ActionRequest("GET", "/actions/runners?per_page=100&page=$page" + name?.takeIf { it.isNotBlank() }?.let { "&name=${Api.q(it)}" }.orEmpty())
    fun deleteRunner(id: Long) = ActionRequest("DELETE", "/actions/runners/$id")
    fun runnerRegistrationToken() = ActionRequest("POST", "/actions/runners/registration-token")
    fun runnerDownloads() = ActionRequest("GET", "/actions/runners/downloads")

    fun cacheUsage() = ActionRequest("GET", "/actions/cache/usage")
    fun caches(page: Int, key: String? = null, ref: String? = null, sort: String? = null, direction: String? = null) = ActionRequest("GET", buildString {
        append("/actions/caches?per_page=100&page=").append(page)
        key?.takeIf { it.isNotBlank() }?.let { append("&key=").append(Api.q(it)) }
        ref?.takeIf { it.isNotBlank() }?.let { append("&ref=").append(Api.q(it)) }
        sort?.takeIf { it.isNotBlank() }?.let { append("&sort=").append(Api.q(it)) }
        direction?.takeIf { it.isNotBlank() }?.let { append("&direction=").append(Api.q(it)) }
    })
    fun deleteCache(id: Long) = ActionRequest("DELETE", "/actions/caches/$id")
    fun deleteCaches(key: String, ref: String? = null) = ActionRequest("DELETE", "/actions/caches?key=${Api.q(key)}" + ref?.takeIf { it.isNotBlank() }?.let { "&ref=${Api.q(it)}" }.orEmpty())
    fun cacheRetention(days: Int) = ActionRequest("PUT", "/actions/cache/retention-limit", buildJsonObject { put("max_cache_retention_days", days) })
    fun cacheStorage(gb: Int) = ActionRequest("PUT", "/actions/cache/storage-limit", buildJsonObject { put("max_cache_size_gb", gb) })
}

@Serializable data class ActionPermissions(val enabled: Boolean = false, @SerialName("allowed_actions") val allowedActions: String = "all", @SerialName("sha_pinning_required") val shaPinningRequired: Boolean = false)
@Serializable data class ActionAccess(@SerialName("access_level") val accessLevel: String = "none")
@Serializable data class ActionArtifactRetention(val days: Int = 90, @SerialName("maximum_allowed_days") val maximumAllowedDays: Int = 400)
@Serializable data class ActionForkApproval(@SerialName("approval_policy") val approvalPolicy: String = "first_time_contributors")
@Serializable data class ActionPrivateForks(
    @SerialName("run_workflows_from_fork_pull_requests") val run: Boolean = false,
    @SerialName("send_write_tokens_to_workflows") val writeTokens: Boolean = false,
    @SerialName("send_secrets_and_variables") val secrets: Boolean = false,
    @SerialName("require_approval_for_fork_pr_workflows") val approval: Boolean = true,
)
@Serializable data class ActionSelectedActions(
    @SerialName("github_owned_allowed") val githubOwnedAllowed: Boolean = true,
    @SerialName("verified_allowed") val verifiedAllowed: Boolean = true,
    @SerialName("patterns_allowed") val patternsAllowed: List<String> = emptyList(),
)
@Serializable data class ActionWorkflowPermissions(
    @SerialName("default_workflow_permissions") val defaultWorkflowPermissions: String = "read",
    @SerialName("can_approve_pull_request_reviews") val canApprovePullRequestReviews: Boolean = false,
)

@Serializable data class ActionSecret(val name: String = "", @SerialName("created_at") val createdAt: String? = null, @SerialName("updated_at") val updatedAt: String? = null)
@Serializable data class ActionSecretList(@SerialName("total_count") val totalCount: Int = 0, val secrets: List<ActionSecret> = emptyList())
@Serializable data class ActionPublicKey(@SerialName("key_id") val keyId: String = "", val key: String = "")
@Serializable data class ActionVariable(val name: String = "", val value: String = "", @SerialName("created_at") val createdAt: String? = null, @SerialName("updated_at") val updatedAt: String? = null)
@Serializable data class ActionVariableList(@SerialName("total_count") val totalCount: Int = 0, val variables: List<ActionVariable> = emptyList())

@Serializable data class ActionRunnerLabel(val id: Long = 0, val name: String = "", val type: String = "")
@Serializable data class ActionRunner(
    val id: Long = 0,
    @SerialName("runner_group_id") val runnerGroupId: Long = 0,
    val name: String = "",
    val os: String = "",
    val status: String = "",
    val busy: Boolean = false,
    val labels: List<ActionRunnerLabel> = emptyList(),
    val ephemeral: Boolean = false,
    val version: String = "",
)
@Serializable data class ActionRunnerList(@SerialName("total_count") val totalCount: Int = 0, val runners: List<ActionRunner> = emptyList())
@Serializable data class ActionRunnerToken(val token: String = "", @SerialName("expires_at") val expiresAt: String = "")
@Serializable data class ActionRunnerDownload(
    val os: String = "",
    val architecture: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
    val filename: String = "",
    @SerialName("temp_download_token") val tempDownloadToken: String? = null,
    @SerialName("sha256_checksum") val sha256Checksum: String? = null,
)

@Serializable data class ActionCacheUsage(
    @SerialName("full_name") val fullName: String = "",
    @SerialName("active_caches_size_in_bytes") val activeCachesSizeInBytes: Long = 0,
    @SerialName("active_caches_count") val activeCachesCount: Int = 0,
)
@Serializable data class ActionCache(
    val id: Long = 0,
    val ref: String? = null,
    val key: String = "",
    val version: String = "",
    @SerialName("last_accessed_at") val lastAccessedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("size_in_bytes") val sizeInBytes: Long = 0,
)
@Serializable data class ActionCacheList(@SerialName("total_count") val totalCount: Int = 0, @SerialName("actions_caches") val caches: List<ActionCache> = emptyList())
@Serializable data class ActionCacheRetentionLimit(@SerialName("max_cache_retention_days") val maxCacheRetentionDays: Int = 0)
@Serializable data class ActionCacheStorageLimit(@SerialName("max_cache_size_gb") val maxCacheSizeGb: Int = 0)
