package com.mobilegh.data

import com.mobilegh.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

private typealias HierarchyGraphQL = suspend (String, Map<String, Any?>, Boolean) -> JsonObject

/** 只保留跳转与状态展示需要的 Issue 身份，不把 REST 数字 ID 当作 GraphQL Node ID。 */
data class IssueRef(
    val nodeId: String,
    val number: Int,
    val title: String,
    val state: String,
    val owner: String,
    val name: String,
)

data class SubIssuesSummary(val total: Int, val completed: Int, val percentCompleted: Int)

data class IssueHierarchy(
    val issueType: IssueType? = null,
    val parent: IssueRef? = null,
    val subIssues: List<IssueRef> = emptyList(),
    val summary: SubIssuesSummary? = null,
) {
    companion object {
        /** 参数为 GraphQL issue 节点；层级是增强信息，字段缺失不能使正文解析失败。 */
        fun parse(issue: JsonObject): IssueHierarchy = IssueHierarchy(
            issueType = IssueTypes.parseIssueType(issue.obj("issueType")),
            parent = parseRef(issue.obj("parent")),
            subIssues = issue.obj("subIssues").arr("nodes").orEmpty().mapNotNull { parseRef(it as? JsonObject) },
            summary = issue.obj("subIssuesSummary")?.let {
                SubIssuesSummary(
                    it.int("total") ?: 0,
                    it.int("completed") ?: 0,
                    it.str("percentCompleted")?.toDoubleOrNull()?.toInt() ?: 0,
                )
            },
        )

        private fun parseRef(value: JsonObject?): IssueRef? {
            val id = value.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val number = value.int("number") ?: return null
            val repository = value.obj("repository") ?: return null
            return IssueRef(
                nodeId = id,
                number = number,
                title = value.str("title").orEmpty(),
                state = value.str("state")?.lowercase().orEmpty(),
                owner = repository.obj("owner").str("login").orEmpty(),
                name = repository.str("name").orEmpty(),
            )
        }

        suspend fun load(
            owner: String,
            name: String,
            number: Int,
            force: Boolean = false,
            graphql: HierarchyGraphQL = { q, v, f -> Api.graphql(q, v, f) },
        ): IssueHierarchy {
            val children = ArrayList<IssueRef>()
            var cursor: String? = null
            var first: IssueHierarchy? = null
            var pages = 0
            while (pages++ < 20) {
                val issue = graphql(
                    QUERY,
                    mapOf("owner" to owner, "name" to name, "number" to number, "after" to cursor),
                    force,
                ).obj("repository").obj("issue") ?: return first ?: IssueHierarchy()
                val page = parse(issue)
                if (first == null) first = page
                children += page.subIssues
                val pageInfo = issue.obj("subIssues").obj("pageInfo")
                if (pageInfo.str("hasNextPage") != "true") break
                val next = pageInfo.str("endCursor") ?: break
                if (next == cursor) break
                cursor = next
            }
            return (first ?: IssueHierarchy()).copy(subIssues = children)
        }

        suspend fun closingIssues(
            owner: String,
            name: String,
            number: Int,
            force: Boolean = false,
            graphql: HierarchyGraphQL = { q, v, f -> Api.graphql(q, v, f) },
        ): List<IssueRef> {
            val result = ArrayList<IssueRef>()
            var cursor: String? = null
            var pages = 0
            while (pages++ < 20) {
                val connection = graphql(
                    CLOSING_QUERY,
                    mapOf("owner" to owner, "name" to name, "number" to number, "after" to cursor),
                    force,
                ).obj("repository").obj("pullRequest").obj("closingIssuesReferences") ?: break
                result += connection.arr("nodes").orEmpty().mapNotNull { parseRef(it as? JsonObject) }
                val pageInfo = connection.obj("pageInfo")
                if (pageInfo.str("hasNextPage") != "true") break
                val next = pageInfo.str("endCursor") ?: break
                if (next == cursor) break
                cursor = next
            }
            return result
        }

        suspend fun addSubIssue(
            issueNodeId: String,
            subIssueNodeId: String,
            replaceParent: Boolean = false,
            graphql: HierarchyGraphQL = { q, v, f -> Api.graphql(q, v, f) },
        ) {
            require(issueNodeId != subIssueNodeId) { "Issue 不能添加为自身的子 Issue" }
            mutate(ADD_MUTATION, "addSubIssue", issueNodeId, subIssueNodeId, replaceParent, graphql)
        }

        suspend fun removeSubIssue(
            issueNodeId: String,
            subIssueNodeId: String,
            graphql: HierarchyGraphQL = { q, v, f -> Api.graphql(q, v, f) },
        ) = mutate(REMOVE_MUTATION, "removeSubIssue", issueNodeId, subIssueNodeId, null, graphql)

        /** removeSubIssue 的 issueId 是父节点；subIssueId 是要从该父节点移除的当前 Issue。 */
        suspend fun removeParent(
            parentNodeId: String,
            currentNodeId: String,
            graphql: HierarchyGraphQL = { q, v, f -> Api.graphql(q, v, f) },
        ) = removeSubIssue(parentNodeId, currentNodeId, graphql)

        /** 新建成功后的附加操作返回失败结果；调用方绝不能因此重新创建 Issue。 */
        suspend fun addAfterCreation(
            parentNodeId: String?,
            childNodeId: String,
            adder: suspend (String, String, Boolean) -> Unit = { parent, child, replace -> addSubIssue(parent, child, replace) },
        ): Result<Unit> {
            if (parentNodeId == null) return Result.success(Unit)
            return try {
                Result.success(adder(parentNodeId, childNodeId, false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

        private suspend fun mutate(
            query: String,
            payloadName: String,
            issueId: String,
            subIssueId: String,
            replaceParent: Boolean?,
            graphql: HierarchyGraphQL,
        ) {
            val payload = graphql(
                query,
                mapOf("issueId" to issueId, "subIssueId" to subIssueId, "replaceParent" to replaceParent),
                false,
            ).obj(payloadName) ?: throw ApiException(403, tr("操作失败（可能没有权限）"))
            payload.obj("issue") ?: throw ApiException(403, tr("操作失败（可能没有权限）"))
        }
    }
}

private const val REF_FIELDS = """
    id number title state
    repository { name owner { login } }
"""

private const val QUERY = """
    query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
      repository(owner: ${'$'}owner, name: ${'$'}name) {
        issue(number: ${'$'}number) {
          issueType { id name description color isEnabled }
          parent { $REF_FIELDS }
          subIssues(first: 100, after: ${'$'}after) {
            nodes { $REF_FIELDS }
            pageInfo { hasNextPage endCursor }
          }
          subIssuesSummary { total completed percentCompleted }
        }
      }
    }
"""

private const val CLOSING_QUERY = """
    query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
      repository(owner: ${'$'}owner, name: ${'$'}name) {
        pullRequest(number: ${'$'}number) {
          closingIssuesReferences(first: 100, after: ${'$'}after) {
            nodes { $REF_FIELDS }
            pageInfo { hasNextPage endCursor }
          }
        }
      }
    }
"""

private const val ADD_MUTATION = """
    mutation(${'$'}issueId: ID!, ${'$'}subIssueId: ID!, ${'$'}replaceParent: Boolean) {
      addSubIssue(input: { issueId: ${'$'}issueId, subIssueId: ${'$'}subIssueId, replaceParent: ${'$'}replaceParent }) {
        issue { id }
      }
    }
"""

private const val REMOVE_MUTATION = """
    mutation(${'$'}issueId: ID!, ${'$'}subIssueId: ID!) {
      removeSubIssue(input: { issueId: ${'$'}issueId, subIssueId: ${'$'}subIssueId }) {
        issue { id }
      }
    }
"""
