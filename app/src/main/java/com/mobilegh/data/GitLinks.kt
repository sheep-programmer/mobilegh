package com.mobilegh.data

import com.mobilegh.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 链接里的网页地址与 GitHub 数据之间的对应：分支 / 路径分界、评论地址等，链接本身无法单独确定的部分靠这里去问 GitHub。 */
object GitLinks {
    /** 解析出的结果：[ref] 是分支、标签或提交，[path] 是仓库内路径（根目录为空），[isDir] 表示目录 */
    data class Resolved(val ref: String, val path: String, val isDir: Boolean)

    @Serializable
    private data class CommentWebLink(val htmlUrl: String = "")

    /**
     * blob / tree 链接的「分支 + 路径」候选，按 ref 由短到长排列。
     * 分支名可能含 /（如 feature/x），所以每个分界都是一个候选；去掉 refs/heads/、refs/tags/ 前缀。
     */
    internal fun candidates(rest: String): List<Pair<String, String>> {
        var parts = rest.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 2 && parts[0] == "refs" && (parts[1] == "heads" || parts[1] == "tags")) parts = parts.drop(2)
        return (1..parts.size).map { k -> parts.take(k).joinToString("/") to parts.drop(k).joinToString("/") }
    }

    /**
     * 依次向 GitHub 询问每个候选：contents 接口对不存在的 ref 或路径返回 404，存在则返回对象或目录。
     * 第一个存在的候选即为结果；全部不存在返回 null。
     */
    suspend fun resolve(owner: String, name: String, rest: String, force: Boolean = false): Resolved? {
        for ((ref, path) in candidates(rest)) {
            val resp = Api.call(
                "GET",
                "/repos/$owner/$name/contents/${Api.encodePath(path)}?ref=${Api.q(ref)}",
                accept = "application/vnd.github.object",
                force = force,
            )
            when (resp.code) {
                200 -> return Resolved(ref, path, isDirectory(resp.body))
                404 -> continue
                else -> Api.ensureOk(resp)
            }
        }
        return null
    }

    /** object 形式的响应：目录为 {"type":"dir"}，文件为 {"type":"file",...}；目录列表则是数组。 */
    internal fun isDirectory(body: String): Boolean = when (val el = Api.plain.parseToJsonElement(body)) {
        is JsonArray -> true
        is JsonObject -> el["type"]?.jsonPrimitive?.content == "dir"
        else -> false
    }

    /**
     * 通知的「最新评论」只给 API 地址（…/issues/comments/N）。读出它在网页上的地址，
     * 其中带着 #issuecomment-N 之类的锚点，交给路由即可定位到那条评论。
     */
    suspend fun commentWebUrl(apiUrl: String): String {
        val path = apiUrl.substringAfter("api.github.com", missingDelimiterValue = "")
        require(path.startsWith("/repos/")) { tr("无法识别的评论地址") }
        return Api.get<CommentWebLink>(path).htmlUrl
    }
}
