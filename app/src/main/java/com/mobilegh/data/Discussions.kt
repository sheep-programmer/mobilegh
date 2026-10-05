package com.mobilegh.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DiscussionAuthor(val login: String, val avatarUrl: String?)

data class DiscussionCategory(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String?,
    val isAnswerable: Boolean,
)

data class DiscussionRepository(val id: String, val enabled: Boolean, val archived: Boolean) {
    fun canWrite(allowed: Boolean) = allowed && enabled && !archived
}

data class Discussion(
    val id: String,
    val number: Int,
    val title: String,
    val url: String,
    val author: DiscussionAuthor?,
    val category: DiscussionCategory,
    val createdAt: String?,
    val updatedAt: String?,
    val commentCount: Int,
    val upvoteCount: Int,
    val answered: Boolean,
    val locked: Boolean,
    val closed: Boolean,
    // Only detail queries request HTML. List pages retain metadata, not entire bodies.
    val bodyHtml: String? = null,
) {
    fun canReply(repository: DiscussionRepository, allowed: Boolean) =
        repository.canWrite(allowed) && !locked && !closed

    fun matches(query: String): Boolean {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val text = "$title ${author?.login.orEmpty()} ${category.name} #$number"
        return terms.all { text.contains(it, ignoreCase = true) }
    }
}

data class DiscussionComment(
    val id: String,
    val url: String,
    val author: DiscussionAuthor?,
    val createdAt: String?,
    val bodyHtml: String,
    val isAnswer: Boolean,
    val isMinimized: Boolean,
    val replyCount: Int,
)

data class DiscussionPage<T>(
    val items: List<T>,
    val endCursor: String?,
    val hasNextPage: Boolean,
    val totalCount: Int,
)

data class DiscussionThread(
    val repository: DiscussionRepository,
    val discussion: Discussion,
    val comments: DiscussionPage<DiscussionComment>,
    val answer: DiscussionComment?,
)

/** A fixed-size window; reaching the limit requires refresh, never an unbounded load-all. */
internal class DiscussionWindow<T>(private val maxItems: Int, private val id: (T) -> String) {
    init { require(maxItems > 0) }

    var items: List<T> = emptyList()
        private set
    var endCursor: String? = null
        private set
    var hasNextPage = true
        private set
    var totalCount = 0
        private set
    var limited = false
        private set

    fun accept(page: DiscussionPage<T>, replace: Boolean = false) {
        val merged = LinkedHashMap<String, T>()
        if (!replace) items.forEach { merged[id(it)] = it }
        page.items.forEach { merged[id(it)] = it }
        items = merged.values.take(maxItems)
        endCursor = page.endCursor
        totalCount = page.totalCount
        limited = merged.size > maxItems || (items.size >= maxItems && page.hasNextPage)
        hasNextPage = page.hasNextPage && !limited
    }
}

/**
 * Public schema only, verified against:
 * https://docs.github.com/en/graphql/guides/using-the-graphql-api-for-discussions
 * https://docs.github.com/en/graphql/reference/discussions
 * https://docs.github.com/en/graphql/reference/repos
 *
 * There is no public viewerCanComment/viewerCanCreateDiscussion field. canWrite comes
 * from the caller; GitHub still enforces token, category and interaction restrictions.
 * The injected transport allows offline tests without credentials or real mutations.
 */
open class DiscussionsClient(
    private val graphql: suspend (String, Map<String, Any?>) -> JsonObject = { query, variables ->
        Api.graphql(query, variables)
    },
) {
    suspend fun repository(owner: String, name: String): DiscussionRepository =
        parseRepository(repositoryNode(graphql(REPOSITORY_QUERY, repoVariables(owner, name))))

    suspend fun categories(owner: String, name: String, after: String? = null): DiscussionPage<DiscussionCategory> {
        val repository = repositoryNode(graphql(CATEGORIES_QUERY, repoVariables(owner, name) + ("after" to after)))
        return connection(repository.requiredObject("discussionCategories"), after, ::parseCategory)
    }

    suspend fun list(
        owner: String,
        name: String,
        after: String? = null,
        categoryId: String? = null,
        answered: Boolean? = null,
    ): DiscussionPage<Discussion> {
        val variables = repoVariables(owner, name) + mapOf("after" to after, "categoryId" to categoryId, "answered" to answered)
        val repository = repositoryNode(graphql(LIST_QUERY, variables))
        return connection(repository.requiredObject("discussions"), after) { parseDiscussion(it) }
    }

    suspend fun detail(owner: String, name: String, number: Int): DiscussionThread {
        require(number > 0)
        val repository = repositoryNode(graphql(DETAIL_QUERY, repoVariables(owner, name) + ("number" to number)))
        val node = repository.obj("discussion") ?: throw ApiException(404, "未找到讨论，或当前账号没有访问权限")
        return DiscussionThread(
            parseRepository(repository), parseDiscussion(node, withBody = true),
            connection(node.requiredObject("comments"), null, ::parseComment), node.obj("answer")?.let(::parseComment),
        )
    }

    suspend fun comments(owner: String, name: String, number: Int, after: String?): DiscussionPage<DiscussionComment> {
        val repository = repositoryNode(graphql(COMMENTS_QUERY, repoVariables(owner, name) + mapOf("number" to number, "after" to after)))
        val node = repository.obj("discussion") ?: throw ApiException(404, "未找到讨论，或当前账号没有访问权限")
        return connection(node.requiredObject("comments"), after, ::parseComment)
    }

    suspend fun replies(commentId: String, after: String? = null): DiscussionPage<DiscussionComment> {
        val root = graphql(REPLIES_QUERY, mapOf("id" to commentId, "after" to after))
        val node = root.obj("node") ?: throw ApiException(404, "未找到评论，或当前账号没有访问权限")
        return connection(node.requiredObject("replies"), after, ::parseComment)
    }

    /** Call only from the user's explicit Publish action; never retry a mutation automatically. */
    suspend fun create(
        repository: DiscussionRepository,
        categoryId: String,
        title: String,
        body: String,
        canWrite: Boolean,
    ): Discussion {
        if (!repository.canWrite(canWrite)) throw ApiException(403, "当前无法发表讨论")
        require(categoryId.isNotBlank()) { "请选择分类" }
        require(title.isNotBlank() && body.isNotBlank()) { "请填写标题和正文" }
        val input = buildJsonObject {
            put("repositoryId", repository.id)
            put("categoryId", categoryId)
            put("title", title.trim())
            put("body", body)
        }
        val node = graphql(CREATE_MUTATION, mapOf("input" to input)).obj("createDiscussion")?.obj("discussion")
            ?: throw ApiException(400, "GitHub 未确认讨论发表成功；请刷新检查后再提交")
        return parseDiscussion(node)
    }

    /** replyToId must be the selected top-level comment in this discussion, or null. */
    suspend fun reply(
        repository: DiscussionRepository,
        discussion: Discussion,
        body: String,
        canWrite: Boolean,
        replyToId: String? = null,
    ): DiscussionComment {
        if (!discussion.canReply(repository, canWrite)) throw ApiException(403, "当前无法回复此讨论")
        require(body.isNotBlank()) { "请填写回复内容" }
        require(replyToId == null || replyToId.isNotBlank())
        val input = buildJsonObject {
            put("discussionId", discussion.id)
            put("body", body)
            if (replyToId != null) put("replyToId", replyToId)
        }
        val node = graphql(REPLY_MUTATION, mapOf("input" to input)).obj("addDiscussionComment")?.obj("comment")
            ?: throw ApiException(400, "GitHub 未确认回复发表成功；请刷新检查后再提交")
        return parseComment(node)
    }

    private fun repoVariables(owner: String, name: String): Map<String, Any?> = mapOf("owner" to owner, "name" to name)

    private fun repositoryNode(root: JsonObject) =
        root.obj("repository") ?: throw ApiException(404, "未找到仓库，或当前账号没有访问权限")

    private fun parseRepository(node: JsonObject) = DiscussionRepository(
        node.requiredString("id"), node.requiredBoolean("hasDiscussionsEnabled"), node.requiredBoolean("isArchived"),
    )

    private fun parseAuthor(node: JsonObject?) = node?.let {
        DiscussionAuthor(it.requiredString("login"), it.str("avatarUrl"))
    }

    private fun parseCategory(node: JsonObject) = DiscussionCategory(
        node.requiredString("id"), node.requiredString("name"), node.requiredString("emoji"),
        node.str("description"), node.requiredBoolean("isAnswerable"),
    )

    private fun parseDiscussion(node: JsonObject, withBody: Boolean = false) = Discussion(
        id = node.requiredString("id"), number = node.requiredInt("number"), title = node.requiredString("title"),
        url = node.requiredString("url"), author = parseAuthor(node.obj("author")),
        category = parseCategory(node.requiredObject("category")), createdAt = node.str("createdAt"),
        updatedAt = node.str("updatedAt"), commentCount = node.requiredObject("comments").requiredInt("totalCount"),
        upvoteCount = node.requiredInt("upvoteCount"), answered = node.bool("isAnswered") ?: false,
        locked = node.requiredBoolean("locked"), closed = node.requiredBoolean("closed"),
        bodyHtml = if (withBody) node.requiredString("bodyHTML") else null,
    )

    private fun parseComment(node: JsonObject) = DiscussionComment(
        node.requiredString("id"), node.requiredString("url"), parseAuthor(node.obj("author")),
        node.str("createdAt"), node.requiredString("bodyHTML"), node.requiredBoolean("isAnswer"),
        node.requiredBoolean("isMinimized"), node.requiredObject("replies").requiredInt("totalCount"),
    )

    private fun <T> connection(node: JsonObject, after: String?, parse: (JsonObject) -> T): DiscussionPage<T> {
        val nodes = node["nodes"] as? JsonArray ?: malformed("nodes")
        val info = node.requiredObject("pageInfo")
        val next = info.str("endCursor")
        val hasNext = info.requiredBoolean("hasNextPage")
        if (hasNext && (next.isNullOrBlank() || next == after)) throw ApiException(502, "GitHub 返回的分页游标无效，请刷新重试")
        val items = nodes.mapNotNull {
            when (it) {
                JsonNull -> null
                is JsonObject -> parse(it)
                else -> malformed("nodes")
            }
        }
        return DiscussionPage(items, next, hasNext, node.requiredInt("totalCount"))
    }

    private fun JsonElement.bool(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.requiredObject(key: String) = obj(key) ?: malformed(key)
    private fun JsonObject.requiredString(key: String) = str(key) ?: malformed(key)
    private fun JsonObject.requiredInt(key: String) = int(key) ?: malformed(key)
    private fun JsonObject.requiredBoolean(key: String) = bool(key) ?: malformed(key)
    private fun malformed(key: String): Nothing = throw ApiException(502, "GitHub 讨论数据不完整：$key")

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_DISCUSSIONS = 200
        const val MAX_COMMENTS = 100
        const val MAX_REPLIES = 60
        const val MAX_CATEGORIES = 100

        private const val REPO_FIELDS = "id hasDiscussionsEnabled isArchived"
        private const val CATEGORY_FIELDS = "id name emoji description isAnswerable"
        private const val AUTHOR_FIELDS = "author { login avatarUrl }"
        private const val DISCUSSION_FIELDS = """
            id number title url createdAt updatedAt upvoteCount isAnswered locked closed
            $AUTHOR_FIELDS category { $CATEGORY_FIELDS }
        """
        private const val COMMENT_FIELDS = """
            id url createdAt bodyHTML isAnswer isMinimized $AUTHOR_FIELDS replies { totalCount }
        """
        private const val PAGE_INFO = "pageInfo { endCursor hasNextPage } totalCount"
        private const val REPOSITORY_QUERY = """
            query RepositoryDiscussions(${'$'}owner: String!, ${'$'}name: String!) {
                repository(owner: ${'$'}owner, name: ${'$'}name) { $REPO_FIELDS }
            }
        """
        private const val CATEGORIES_QUERY = """
            query DiscussionCategories(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    discussionCategories(first: $PAGE_SIZE, after: ${'$'}after) { $PAGE_INFO nodes { $CATEGORY_FIELDS } }
                }
            }
        """
        private const val LIST_QUERY = """
            query RepositoryDiscussionList(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String, ${'$'}categoryId: ID, ${'$'}answered: Boolean) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    discussions(first: $PAGE_SIZE, after: ${'$'}after, categoryId: ${'$'}categoryId, answered: ${'$'}answered,
                        orderBy: {field: UPDATED_AT, direction: DESC}) {
                        $PAGE_INFO nodes { $DISCUSSION_FIELDS comments { totalCount } }
                    }
                }
            }
        """
        private const val DETAIL_QUERY = """
            query RepositoryDiscussionDetail(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    $REPO_FIELDS
                    discussion(number: ${'$'}number) {
                        $DISCUSSION_FIELDS bodyHTML answer { $COMMENT_FIELDS }
                        comments(first: $PAGE_SIZE) { $PAGE_INFO nodes { $COMMENT_FIELDS } }
                    }
                }
            }
        """
        private const val COMMENTS_QUERY = """
            query DiscussionComments(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    discussion(number: ${'$'}number) {
                        comments(first: $PAGE_SIZE, after: ${'$'}after) { $PAGE_INFO nodes { $COMMENT_FIELDS } }
                    }
                }
            }
        """
        private const val REPLIES_QUERY = """
            query DiscussionReplies(${'$'}id: ID!, ${'$'}after: String) {
                node(id: ${'$'}id) { ... on DiscussionComment {
                    replies(first: $PAGE_SIZE, after: ${'$'}after) { $PAGE_INFO nodes { $COMMENT_FIELDS } }
                } }
            }
        """
        private const val CREATE_MUTATION = """
            mutation CreateRepositoryDiscussion(${'$'}input: CreateDiscussionInput!) {
                createDiscussion(input: ${'$'}input) { discussion { $DISCUSSION_FIELDS comments { totalCount } } }
            }
        """
        private const val REPLY_MUTATION = """
            mutation ReplyToDiscussion(${'$'}input: AddDiscussionCommentInput!) {
                addDiscussionComment(input: ${'$'}input) { comment { $COMMENT_FIELDS } }
            }
        """
    }
}

object Discussions : DiscussionsClient()
