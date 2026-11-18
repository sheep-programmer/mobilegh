package com.mobilegh.data

import com.mobilegh.i18n.tr
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
        graphql: SavedReplyGraphQL = { query, variables -> Api.graphqlRoot(query, variables) },
    ): SavedReplyPage {
        val response = graphql(QUERY, mapOf("after" to after))
        response.arr("errors")?.firstOrNull()?.let { error ->
            val message = error.str("message")
            throw ApiException(400, DeviceAuth.insufficientScopes(message) ?: message ?: tr("GraphQL 错误"))
        }
        val connection = response.obj("data").obj("viewer").obj("savedReplies")
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
