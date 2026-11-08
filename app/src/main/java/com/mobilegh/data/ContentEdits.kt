package com.mobilegh.data

import android.webkit.CookieManager
import com.mobilegh.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 正文 / 评论的编辑记录（网页上 "Edited N times" 里的历史版本）。
 * 通过 GraphQL 的 userContentEdits 读取；每条记录的 diff 是那次编辑之后的完整正文。
 * 历史版本删除走 GitHub 网页端私有接口（需基于 CookieManager 的 Web Session）。
 */
object ContentEdits {
    data class Revision(
        val id: String,
        val createdAt: String?,
        val editor: String?,
        val body: String?,
        val deletedAt: String? = null,
        val deletedBy: String? = null,
    ) {
        val isDeleted: Boolean get() = deletedAt != null
    }

    private const val QUERY = """
        query(${'$'}id: ID!) {
          node(id: ${'$'}id) {
            ... on IssueComment {
              userContentEdits(first: 50) {
                nodes {
                  id
                  createdAt
                  deletedAt
                  deletedBy { login }
                  editor { login }
                  diff
                }
              }
            }
            ... on Issue {
              userContentEdits(first: 50) {
                nodes {
                  id
                  createdAt
                  deletedAt
                  deletedBy { login }
                  editor { login }
                  diff
                }
              }
            }
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
                id = o.str("id").orEmpty(),
                createdAt = o.str("createdAt"),
                deletedAt = o.str("deletedAt"),
                deletedBy = (o?.get("deletedBy") as? JsonObject).str("login"),
                editor = (o?.get("editor") as? JsonObject).str("login"),
                body = o.str("diff"),
            )
        }.sortedByDescending { it.createdAt.orEmpty() }
    }

    /**
     * 删除单条编辑历史记录。
     * GitHub 公开 GraphQL/REST API 未开放删除 Mutation，此处走网页端私有端点（依赖 Web Session Cookie）。
     */
    suspend fun delete(
        revisionId: String,
        cookieHeader: () -> String? = { runCatching { CookieManager.getInstance().getCookie("https://github.com") }.getOrNull() },
        client: OkHttpClient = Api.http,
    ) = withContext(Dispatchers.IO) {
        val cookie = cookieHeader()
        if (!WebAttachmentUploader.hasSession(cookie)) {
            throw WebSessionMissing()
        }
        val req = Request.Builder()
            .url("https://github.com/user_content_edits/$revisionId.json")
            .delete()
            .header("Cookie", cookie.orEmpty())
            .header("Accept", "application/json")
            .header("GitHub-Verified-Fetch", "true")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", "https://github.com")
            .header("Referer", "https://github.com/")
            .build()
        client.routed().newCall(req).await().use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                throw WebSessionMissing()
            }
            if (!resp.isSuccessful) {
                throw IOException(trf("删除失败（HTTP {0}）", resp.code))
            }
        }
    }
}
