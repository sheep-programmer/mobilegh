package com.mobilegh.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 正文 / 评论的编辑记录（网页上 "Edited N times" 里的历史版本）。
 * 通过 GraphQL 的 userContentEdits 读取；每条记录的 diff 是那次编辑之后的完整正文（已在 BiliSail 的 issue 上验证）。
 */
object ContentEdits {
    data class Revision(val createdAt: String?, val editor: String?, val body: String?)

    private const val QUERY = """
        query(${'$'}id: ID!) {
          node(id: ${'$'}id) {
            ... on IssueComment { userContentEdits(first: 50) { nodes { createdAt editor { login } diff } } }
            ... on Issue { userContentEdits(first: 50) { nodes { createdAt editor { login } diff } } }
          }
        }
    """

    /** 最新的在前，与网页版一致 */
    suspend fun load(nodeId: String): List<Revision> {
        val node = Api.graphql(QUERY, mapOf("id" to nodeId))["node"] as? JsonObject ?: return emptyList()
        val nodes = (node["userContentEdits"] as? JsonObject)?.get("nodes") as? JsonArray ?: return emptyList()
        return nodes.map { el ->
            val o = el as? JsonObject
            Revision(
                createdAt = o.str("createdAt"),
                editor = (o?.get("editor") as? JsonObject).str("login"),
                body = o.str("diff"),
            )
        }.sortedByDescending { it.createdAt.orEmpty() }
    }
}
