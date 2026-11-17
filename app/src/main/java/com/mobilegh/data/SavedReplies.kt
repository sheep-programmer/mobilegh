package com.mobilegh.data

import kotlinx.serialization.json.JsonObject

internal typealias SavedReplyGraphQL = suspend (String, Map<String, Any?>) -> JsonObject

data class SavedReply(
    val id: String,
    val title: String,
    val body: String,
)

data class SavedReplyPage(
    val items: List<SavedReply>,
    val endCursor: String? = null,
    val hasNextPage: Boolean = false,
)

/** 当前账号在 GitHub 设置中维护的 Saved Replies；公开 GraphQL 只提供读取。 */
object SavedReplies {
    suspend fun savedReplies(
        after: String? = null,
        graphql: SavedReplyGraphQL = { q, v -> Api.graphql(q, v) },
    ): SavedReplyPage {
        val connection = graphql(QUERY, mapOf("after" to after)).obj("viewer").obj("savedReplies")
            ?: return SavedReplyPage(emptyList())
        val items = connection.arr("nodes").orEmpty().mapNotNull { node ->
            val reply = node as? JsonObject ?: return@mapNotNull null
            val id = reply.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SavedReply(id, reply.str("title").orEmpty(), reply.str("body").orEmpty())
        }
        val pageInfo = connection.obj("pageInfo")
        return SavedReplyPage(items, pageInfo.str("endCursor"), pageInfo.str("hasNextPage") == "true")
    }

    private const val QUERY = """
        query(${'$'}after: String) {
          viewer {
            savedReplies(first: 30, after: ${'$'}after) {
              nodes { id title body }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
    """
}
