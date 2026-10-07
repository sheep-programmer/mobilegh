package com.mobilegh.data

import com.mobilegh.nav.Links
import com.mobilegh.nav.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

@Serializable
data class CodeMatch(val fragment: String = "")
@Serializable
data class CodeSearchHit(val name: String = "", val path: String = "", val htmlUrl: String = "", val repository: Repo = Repo(), val textMatches: List<CodeMatch> = emptyList())
@Serializable
data class CodeSearchPage(val totalCount: Int = 0, val incompleteResults: Boolean = false, val items: List<CodeSearchHit> = emptyList())
@Serializable
data class RepositoryTreeEntry(val path: String, val type: String, val sha: String = "", val size: Long = 0)
@Serializable
data class RepositoryTree(val tree: List<RepositoryTreeEntry> = emptyList(), val truncated: Boolean = false)
data class RepositoryFileIndex(val commit: String, val entries: List<RepositoryTreeEntry>, val truncated: Boolean)
data class FileMatches(val entries: List<RepositoryTreeEntry>, val count: Int)
data class BlameRange(val start: Int, val end: Int, val commit: String, val message: String, val author: String, val avatar: String?, val date: String)
data class BlameSnapshot(val commit: String, val ranges: List<BlameRange>, val lines: List<String> = emptyList(), val sourceNote: String? = null) {
    fun rangeFor(line: Int): BlameRange? = ranges.firstOrNull { line in it.start..it.end }
}

object CodeReading {
    private val oid = Regex("[0-9a-fA-F]{40,64}")
    fun repoPath(owner: String, name: String): String {
        require(Links.fromInput("$owner/$name") is Screen.Repo) { "无效的仓库地址" }
        return "/repos/$owner/$name"
    }
    fun searchQuery(query: String, owner: String? = null, name: String? = null): String {
        require(query.trim().isNotEmpty()) { "请输入要搜索的代码" }
        return if (owner != null && name != null) {
            repoPath(owner, name)
            "${query.trim()} repo:$owner/$name"
        } else query.trim()
    }
    suspend fun search(query: String, page: Int, force: Boolean): CodeSearchPage {
        if (page > 34) return CodeSearchPage()
        val result = Api.get<CodeSearchPage>("/search/code?q=${Api.q(query)}&per_page=30&page=$page", force, "application/vnd.github.text-match+json")
        result.items.filter { it.repository.isPrivate }.forEach { Net.markPrivate(it.repository.fullName.ifBlank { "${it.repository.owner.login}/${it.repository.name}" }) }
        return result.copy(items = result.items.take((1000 - (page - 1) * 30).coerceAtMost(30)))
    }
    suspend fun files(owner: String, name: String, ref: String?, force: Boolean): RepositoryFileIndex {
        val root = repoPath(owner, name)
        val repo = GitHub.repo(owner, name, force)
        val commit = Api.get<JsonObject>("$root/commits/${Api.q(ref ?: repo.defaultBranch)}", force)
        val sha = (commit["sha"] as? JsonPrimitive)?.contentOrNull ?: throw ApiException(400, "提交信息不完整")
        val tree = ((commit["commit"] as? JsonObject)?.get("tree") as? JsonObject)?.get("sha")?.jsonPrimitive?.contentOrNull
        require(oid.matches(sha) && tree != null && oid.matches(tree)) { "提交未返回有效的文件树" }
        val result = Api.get<RepositoryTree>("$root/git/trees/$tree?recursive=1", force)
        return RepositoryFileIndex(sha, result.tree.filter { validPath(it.path) && it.type in setOf("blob", "tree") }.take(100_000), result.truncated || result.tree.size > 100_000)
    }
    fun validPath(path: String): Boolean = path.length in 1..4096 && path.none(Char::isISOControl) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    fun filterFiles(entries: List<RepositoryTreeEntry>, query: String, limit: Int = 200): FileMatches {
        val q = query.trim().lowercase()
        val tokens = q.split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = entries.filter { e -> tokens.all { e.path.contains(it, true) } }
        fun rank(e: RepositoryTreeEntry): Int {
            val name = e.path.substringAfterLast('/').lowercase()
            return when { name == q -> 0; name.startsWith(q) -> 1; name.contains(q) -> 2; else -> 3 }
        }
        return FileMatches(matches.sortedWith(compareBy<RepositoryTreeEntry> { rank(it) }.thenBy { it.path.lowercase() }).take(limit), matches.size)
    }
    internal const val BLAME_QUERY = "query MobileGHBlame(\$owner:String!,\$name:String!,\$ref:String!,\$path:String!){repository(owner:\$owner,name:\$name){object(expression:\$ref){... on Commit{oid blame(path:\$path){ranges{startingLine endingLine commit{oid messageHeadline committedDate author{name user{login avatarUrl}}}}}}}}}"
    fun parseBlame(data: JsonObject): BlameSnapshot {
        val commit = ((data["repository"] as? JsonObject)?.get("object") as? JsonObject) ?: throw ApiException(404, "无法读取该文件的提交归因，请检查路径、分支和仓库权限")
        val sha = (commit["oid"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(oid.matches(sha)) { "归因结果没有有效的提交版本" }
        val raw = ((commit["blame"] as? JsonObject)?.get("ranges") as? JsonArray) ?: throw ApiException(400, "该文件没有可用的逐行归因")
        val ranges = raw.map { element ->
            val r = element.jsonObject
            val start = r["startingLine"]?.jsonPrimitive?.intOrNull ?: 0
            val end = r["endingLine"]?.jsonPrimitive?.intOrNull ?: 0
            val c = r["commit"]?.jsonObject ?: throw ApiException(400, "归因提交缺失")
            val author = c["author"] as? JsonObject
            val user = author?.get("user") as? JsonObject
            val id = c["oid"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(start >= 1 && end >= start && oid.matches(id)) { "归因行号或提交无效" }
            BlameRange(start, end, id, c["messageHeadline"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                user?.get("login")?.jsonPrimitive?.contentOrNull ?: (author?.get("name") as? JsonPrimitive)?.contentOrNull ?: "未知作者",
                (user?.get("avatarUrl") as? JsonPrimitive)?.contentOrNull, c["committedDate"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }.sortedBy { it.start }
        require(ranges.zipWithNext().all { (a, b) -> a.end < b.start }) { "归因行号发生重叠" }
        return BlameSnapshot(sha, ranges)
    }
    suspend fun blame(owner: String, name: String, path: String, ref: String?): BlameSnapshot {
        require(validPath(path)) { "无效的文件路径" }
        GitHub.repo(owner, name)
        val data = Api.graphql(BLAME_QUERY, mapOf("owner" to owner, "name" to name, "ref" to (ref ?: "HEAD"), "path" to path))
        val result = parseBlame(data)
        return try {
            val content = Api.get<Content>("${repoPath(owner, name)}/contents/${Api.encodePath(path)}?ref=${Api.q(result.commit)}")
            if (content.size > 1_000_000 || content.encoding != "base64" || content.content == null) result.copy(sourceNote = "该文件无法预览完整源码，仍可查看每段的作者和提交")
            else {
                val bytes = Base64.getMimeDecoder().decode(content.content)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                if ('\u0000' in text) result.copy(sourceNote = "二进制文件不展示源码")
                else {
                    val lines = text.lineSequence().take(5001).toList()
                    result.copy(lines = lines.take(5000), sourceNote = if (lines.size > 5000) "源码预览显示前 5,000 行，可打开文件查看完整内容" else null)
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { result.copy(sourceNote = "源码预览暂不可用，提交归因已加载：${e.message?.take(100).orEmpty()}") }
    }
}
