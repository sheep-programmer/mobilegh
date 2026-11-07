package com.mobilegh.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

/** 合并队列中的一条：对应网页 /owner/repo/queue 上的一行 */
@Serializable
data class MergeQueueEntry(
    val position: Int = 0,
    /** GraphQL 枚举：QUEUED / AWAITING_CHECKS / MERGEABLE / UNMERGEABLE / LOCKED */
    val state: String = "",
    val enqueuedAt: String? = null,
    val pullRequest: MergeQueuePull? = null,
)

@Serializable
data class MergeQueuePull(val number: Int = 0, val title: String = "", val url: String? = null)

/** 仓库的合并队列：[enabled] 为 false 表示默认分支没有启用合并队列 */
data class MergeQueueInfo(val enabled: Boolean, val entries: List<MergeQueueEntry>)

object MergeQueues {
    private const val QUERY = """
        query(${'$'}owner: String!, ${'$'}name: String!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            mergeQueue {
              entries(first: 100) {
                nodes { position state enqueuedAt pullRequest { number title url } }
              }
            }
          }
        }
    """

    /** 不传分支时 GraphQL 默认使用仓库的默认分支，与网页 /queue 的默认行为一致 */
    suspend fun load(owner: String, name: String): MergeQueueInfo {
        val repo = Api.graphql(QUERY, mapOf("owner" to owner, "name" to name))["repository"] as? JsonObject
        val queue = repo?.get("mergeQueue") as? JsonObject ?: return MergeQueueInfo(false, emptyList())
        val nodes = (queue["entries"] as? JsonObject)?.get("nodes") ?: return MergeQueueInfo(true, emptyList())
        val entries = Api.plain.decodeFromJsonElement(ListSerializer(MergeQueueEntry.serializer()), nodes)
        return MergeQueueInfo(true, entries)
    }
}
