package com.mobilegh.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 通知线程的写接口：线程订阅（忽略 / 恢复）与仓库级「标为已读」。
 * 与 GitHub.kt 中的同类接口分开维护，便于单独测试请求路径和请求体。
 */
object ThreadApi {
    /** 忽略线程：即使之后被 @ 也不再收到该线程的通知 */
    suspend fun ignoreThread(id: String) =
        Api.exec("PUT", threadSubscriptionPath(id), subscriptionBody(ignored = true))

    /**
     * 恢复订阅：把线程的忽略标记改回未忽略。
     * GitHub 的通知列表不返回订阅状态，调用方只能对本机记录为忽略的线程使用。
     */
    suspend fun restoreThread(id: String) =
        Api.exec("PUT", threadSubscriptionPath(id), subscriptionBody(ignored = false))

    /**
     * 仓库级「标为已读」：[lastReadAt] 之前更新的该仓库通知全部标为已读，包括未加载的条目。
     * [lastReadAt] 为 ISO-8601 时间，通常取当前时间。
     */
    suspend fun markRepoRead(owner: String, repo: String, lastReadAt: String) =
        Api.exec("PUT", repoNotificationsPath(owner, repo), repoReadBody(lastReadAt))
}

internal fun threadSubscriptionPath(id: String): String = "/notifications/threads/$id/subscription"

internal fun subscriptionBody(ignored: Boolean): JsonObject = buildJsonObject { put("ignored", ignored) }

internal fun repoNotificationsPath(owner: String, repo: String): String = "/repos/$owner/$repo/notifications"

internal fun repoReadBody(lastReadAt: String): JsonObject = buildJsonObject { put("last_read_at", lastReadAt) }
