package com.mobilegh.data

import com.mobilegh.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

private typealias IssueTypeGraphQL = suspend (String, Map<String, Any?>) -> JsonObject

/** GitHub Issue Type 是 GraphQL 数据，不能与 REST 的 label 混用。 */
data class IssueType(
    val id: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val enabled: Boolean = true,
)

object IssueTypes {
    fun parseIssueType(value: JsonObject?): IssueType? {
        val id = value.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val name = value.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return IssueType(id, name, value.str("description"), value.str("color"), value.str("isEnabled") != "false")
    }

    /** 参数为 repository 节点；缺失或 null 的 issueTypes 连接按空列表处理。 */
    fun parseOptions(repository: JsonObject): List<IssueType> =
        repository.obj("issueTypes").arr("nodes").orEmpty().mapNotNull { parseIssueType(it as? JsonObject) }

    suspend fun options(owner: String, name: String, graphql: IssueTypeGraphQL = { q, v -> Api.graphql(q, v) }): List<IssueType> {
        val result = ArrayList<IssueType>()
        var cursor: String? = null
        repeat(20) {
            val repository = graphql(OPTIONS_QUERY, mapOf("owner" to owner, "name" to name, "after" to cursor))
                .obj("repository") ?: return result
            result += parseOptions(repository)
            val page = repository.obj("issueTypes").obj("pageInfo")
            if (page.str("hasNextPage") != "true") return result
            val next = page.str("endCursor") ?: return result
            if (next == cursor) return result
            cursor = next
        }
        return result
    }

    suspend fun current(owner: String, name: String, number: Int, graphql: IssueTypeGraphQL = { q, v -> Api.graphql(q, v) }): IssueType? {
        val repository = graphql(CURRENT_QUERY, mapOf("owner" to owner, "name" to name, "number" to number)).obj("repository")
        return parseIssueType(repository.obj("issue").obj("issueType"))
    }

    /** 创建已经成功后才调用；类型失败作为结果返回，调用方绝不能因此重新创建 Issue。 */
    suspend fun setAfterCreation(
        issueNodeId: String,
        issueTypeId: String?,
        setter: suspend (String, String?) -> IssueType? = { id, type -> set(id, type) },
    ): Result<IssueType?> {
        if (issueTypeId == null) return Result.success(null)
        return try {
            Result.success(setter(issueNodeId, issueTypeId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    suspend fun set(issueNodeId: String, issueTypeId: String?, graphql: IssueTypeGraphQL = { q, v -> Api.graphql(q, v) }): IssueType? {
        val mutation = graphql(SET_MUTATION, mapOf("issueId" to issueNodeId, "issueTypeId" to issueTypeId))
            .obj("updateIssueIssueType") ?: throw ApiException(403, tr("操作失败（可能没有权限）"))
        val issue = mutation.obj("issue") ?: throw ApiException(403, tr("操作失败（可能没有权限）"))
        val type = parseIssueType(issue.obj("issueType"))
        if (issueTypeId != null && type?.id != issueTypeId) {
            throw ApiException(403, tr("操作失败（可能没有权限）"))
        }
        return type
    }

    private const val OPTIONS_QUERY = """
        query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            issueTypes(first: 100, after: ${'$'}after) {
              nodes { id name description color isEnabled }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
    """

    private const val CURRENT_QUERY = """
        query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            issue(number: ${'$'}number) { issueType { id name description color isEnabled } }
          }
        }
    """

    private const val SET_MUTATION = """
        mutation(${'$'}issueId: ID!, ${'$'}issueTypeId: ID) {
          updateIssueIssueType(input: { issueId: ${'$'}issueId, issueTypeId: ${'$'}issueTypeId }) {
            issue { issueType { id name description color isEnabled } }
          }
        }
    """
}
