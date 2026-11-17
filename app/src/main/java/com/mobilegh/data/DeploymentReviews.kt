package com.mobilegh.data

import com.mobilegh.i18n.tr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal typealias DeploymentGraphQL = suspend (String, Map<String, Any?>) -> JsonObject

data class DeploymentReview(
    val environmentId: String,
    val environmentName: String,
    val canApprove: Boolean,
    val reviewers: List<String>,
)

data class DeploymentReviewInput(
    val workflowRunId: String,
    val environmentIds: List<String>,
    val comment: String?,
)

/** Actions 环境保护规则产生的待审批请求；mutation 只接受 WorkflowRun 的 Node ID。 */
object DeploymentReviews {
    suspend fun pendingDeploymentRequests(
        workflowRunNodeId: String,
        graphql: DeploymentGraphQL = { q, v -> Api.graphql(q, v) },
    ): List<DeploymentReview> {
        require(workflowRunNodeId.isNotBlank()) { "部署审批需要 WorkflowRun Node ID" }
        val response = graphql(QUERY, mapOf("id" to workflowRunNodeId))
        val nodes = response.obj("node")?.obj("pendingDeploymentRequests")?.arr("nodes")
            ?: throw ApiException(502, tr("部署审批数据不完整"))
        return nodes
            .mapNotNull { node ->
                val request = node as? JsonObject ?: return@mapNotNull null
                val environment = request.obj("environment") ?: return@mapNotNull null
                val id = environment.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DeploymentReview(
                    environmentId = id,
                    environmentName = environment.str("name").orEmpty(),
                    canApprove = request.str("currentUserCanApprove") == "true",
                    reviewers = request.obj("reviewers").arr("nodes").orEmpty().mapNotNull { reviewer ->
                        val value = reviewer as? JsonObject
                        value?.str("login") ?: value?.str("name") ?: value?.str("slug")
                    },
                )
            }
    }

    suspend fun reviewDeployments(
        input: DeploymentReviewInput,
        approve: Boolean,
        graphql: DeploymentGraphQL = { q, v -> Api.graphql(q, v) },
    ) {
        require(input.workflowRunId.isNotBlank()) { "部署审批需要 WorkflowRun Node ID" }
        require(input.environmentIds.isNotEmpty()) { "请至少选择一个环境" }
        val payload = buildJsonObject {
            put("workflowRunId", input.workflowRunId)
            put("environmentIds", JsonArray(input.environmentIds.map(::JsonPrimitive)))
            put("comment", input.comment?.trim()?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        }
        val mutation = if (approve) "approveDeployments" else "rejectDeployments"
        graphql(if (approve) APPROVE else REJECT, mapOf("input" to payload)).obj(mutation)
            ?: throw ApiException(403, tr("操作失败（可能没有权限）"))
    }

    private const val QUERY = """
        query(${'$'}id: ID!) {
          node(id: ${'$'}id) {
            ... on WorkflowRun {
              pendingDeploymentRequests(first: 100) {
                nodes {
                  environment { id name }
                  currentUserCanApprove
                  reviewers(first: 100) {
                    nodes {
                      ... on User { login }
                      ... on Team { name slug }
                    }
                  }
                }
              }
            }
          }
        }
    """

    private const val APPROVE = """
        mutation(${'$'}input: ApproveDeploymentsInput!) {
          approveDeployments(input: ${'$'}input) { clientMutationId }
        }
    """

    private const val REJECT = """
        mutation(${'$'}input: RejectDeploymentsInput!) {
          rejectDeployments(input: ${'$'}input) { clientMutationId }
        }
    """
}
