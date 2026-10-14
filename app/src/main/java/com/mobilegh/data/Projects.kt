package com.mobilegh.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// ======================= 模型 =======================

/** 项目列表的归属：用户、组织或仓库（仓库列出的是关联到该仓库的项目） */
enum class ProjectOwnerKind { User, Org, Repo }

/** GitHub ProjectV2SingleSelectFieldOptionColor；界面再映射到 Gh.c 令牌 */
enum class ProjectColor {
    Gray, Blue, Green, Yellow, Orange, Red, Pink, Purple;

    companion object {
        fun of(name: String?): ProjectColor = when (name?.trim()?.uppercase()) {
            "BLUE" -> Blue
            "GREEN" -> Green
            "YELLOW" -> Yellow
            "ORANGE" -> Orange
            "RED" -> Red
            "PINK" -> Pink
            "PURPLE" -> Purple
            else -> Gray
        }
    }
}

data class ProjectSummary(
    val id: String,
    val number: Int,
    val title: String,
    val shortDescription: String?,
    val closed: Boolean,
    val public: Boolean,
    val updatedAt: String?,
    val url: String,
    val itemCount: Int?,
)

data class ProjectOption(val id: String, val name: String, val color: ProjectColor)

data class ProjectIteration(val id: String, val title: String, val startDate: String?)

data class ProjectField(
    val id: String,
    val name: String,
    val dataType: String,
    val options: List<ProjectOption> = emptyList(),
    val iterations: List<ProjectIteration> = emptyList(),
) {
    val isSingleSelect: Boolean get() = dataType == "SINGLE_SELECT"
}

enum class ProjectLayout {
    Board, Table, Roadmap;

    companion object {
        fun of(name: String?): ProjectLayout = when (name) {
            "BOARD_LAYOUT" -> Board
            "ROADMAP_LAYOUT" -> Roadmap
            else -> Table
        }
    }
}

data class ProjectView(
    val id: String,
    val number: Int,
    val name: String,
    val layout: ProjectLayout,
    val groupByFieldIds: List<String> = emptyList(),
    val verticalGroupByFieldIds: List<String> = emptyList(),
    val filter: String? = null,
)

enum class ProjectItemType {
    Issue, PullRequest, DraftIssue, Redacted;

    companion object {
        fun of(type: String?, typename: String?): ProjectItemType = when (type ?: typename) {
            "ISSUE", "Issue" -> Issue
            "PULL_REQUEST", "PullRequest" -> PullRequest
            "DRAFT_ISSUE", "DraftIssue" -> DraftIssue
            else -> Redacted
        }
    }
}

enum class ProjectValueKind { SingleSelect, Text, Number, Date, Iteration, Labels, Users, Milestone }

data class ProjectFieldValue(
    val fieldId: String,
    val fieldName: String,
    val fieldType: String?,
    val kind: ProjectValueKind,
    val text: String,
    val optionId: String? = null,
)

data class ProjectItem(
    val id: String,
    val type: ProjectItemType,
    val archived: Boolean,
    val title: String,
    val number: Int? = null,
    val url: String? = null,
    val repository: String? = null,
    /** OPEN / CLOSED / MERGED（仅议题与拉取请求） */
    val state: String? = null,
    val stateReason: String? = null,
    val isDraft: Boolean = false,
    val merged: Boolean = false,
    val body: String? = null,
    val values: List<ProjectFieldValue> = emptyList(),
) {
    fun value(fieldId: String): ProjectFieldValue? = values.firstOrNull { it.fieldId == fieldId }

    /** "owner/repo#12"；草稿与无权访问的条目为 null */
    val reference: String?
        get() = if (repository != null && number != null) "$repository#$number" else null

    /** 仓库的 owner、name，用于打开议题/拉取请求详情 */
    val repoParts: Pair<String, String>?
        get() = repository?.split('/', limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] }
}

data class ProjectDetail(
    val id: String,
    val number: Int,
    val title: String,
    val shortDescription: String?,
    val closed: Boolean,
    val url: String,
    val viewerCanUpdate: Boolean,
    val fields: List<ProjectField>,
    val views: List<ProjectView>,
    val items: DiscussionPage<ProjectItem>,
)

data class ProjectColumn(val option: ProjectOption?, val items: List<ProjectItem>) {
    val key: String get() = option?.id ?: NO_VALUE_KEY

    companion object { const val NO_VALUE_KEY = "__none__" }
}

// ======================= 纯逻辑 =======================

/**
 * 看板的分列字段：优先使用视图的 verticalGroupByFields（看板的列），其次 groupByFields，
 * 再退回名为 Status 的单选字段，最后取第一个单选字段。只有单选字段可以分列。
 */
fun boardGroupField(fields: List<ProjectField>, view: ProjectView?): ProjectField? {
    val single = fields.filter { it.isSingleSelect }
    val fromView = view?.let { v -> (v.verticalGroupByFieldIds + v.groupByFieldIds).firstNotNullOfOrNull { id -> single.firstOrNull { it.id == id } } }
    return fromView
        ?: single.firstOrNull { it.name.equals("Status", ignoreCase = true) }
        ?: single.firstOrNull { it.name == "状态" }
        ?: single.firstOrNull()
}

/**
 * 按单选字段分列：列顺序与字段选项一致；没有取值（或取值对应的选项已被删除）的条目放入首列"无 字段名"。
 * 无值列只在有条目时出现；没有分列字段时返回单列。归档条目不显示。
 */
fun groupProjectItems(items: List<ProjectItem>, field: ProjectField?): List<ProjectColumn> {
    val visible = items.filterNot { it.archived }
    if (field == null) return listOf(ProjectColumn(null, visible))
    val ids = field.options.map { it.id }.toSet()
    val byOption = LinkedHashMap<String, MutableList<ProjectItem>>()
    val none = ArrayList<ProjectItem>()
    visible.forEach { item ->
        val option = item.value(field.id)?.optionId
        if (option != null && option in ids) byOption.getOrPut(option) { ArrayList() }.add(item) else none.add(item)
    }
    val columns = field.options.map { ProjectColumn(it, byOption[it.id].orEmpty()) }
    return if (none.isEmpty()) columns else listOf(ProjectColumn(null, none)) + columns
}

/** 卡片上展示的字段值：跳过标题与已作为分列的字段、空值，最多 [limit] 个 */
fun cardFieldValues(item: ProjectItem, exclude: Set<String> = emptySet(), limit: Int = 3): List<ProjectFieldValue> =
    item.values.asSequence()
        .filter { it.fieldType != "TITLE" && it.fieldId !in exclude && it.text.isNotBlank() }
        .take(limit)
        .toList()

/** 本地更新单选字段的值；option 为 null 表示清空 */
fun ProjectItem.withSingleSelect(field: ProjectField, option: ProjectOption?): ProjectItem {
    val rest = values.filterNot { it.fieldId == field.id }
    if (option == null) return copy(values = rest)
    val value = ProjectFieldValue(field.id, field.name, field.dataType, ProjectValueKind.SingleSelect, option.name, option.id)
    return copy(values = listOf(value) + rest)
}

/**
 * 将本地编辑叠加到已加载的条目上：edits 中 null 表示已移除；added 是本地新建、尚未出现在加载结果中的条目，排在最前。
 * 归档条目不显示。
 */
fun applyProjectEdits(loaded: List<ProjectItem>, edits: Map<String, ProjectItem?>, added: List<ProjectItem>): List<ProjectItem> {
    val loadedIds = loaded.map { it.id }.toSet()
    val fresh = added.filter { it.id !in loadedIds }.map { if (edits.containsKey(it.id)) edits[it.id] else it }
    val merged = loaded.map { if (edits.containsKey(it.id)) edits[it.id] else it }
    return (fresh + merged).filterNotNull().filterNot { it.archived }.distinctBy { it.id }
}

enum class ProjectErrorKind { Scope, Forbidden, NotFound, Other }

object ProjectErrors {
    const val READ_SCOPE_MESSAGE = "当前授权缺少 read:project 权限，无法读取 GitHub Projects。" +
        "请重新授权登录；如使用 Token 登录，请为 Token 勾选 project（或 read:project）权限。"
    const val WRITE_SCOPE_MESSAGE = "当前授权缺少 project 写入权限，无法修改项目。" +
        "请重新授权登录；如使用 Token 登录，请为 Token 勾选 project 权限。"
    const val FORBIDDEN_PREFIX = "无权访问此项目："
    const val NOT_FOUND_MESSAGE = "未找到项目，或当前账号没有访问权限"

    /** 原始 GraphQL 错误：type 优先，message 兜底 */
    fun classify(type: String?, message: String?): ProjectErrorKind {
        val msg = message.orEmpty()
        return when {
            type == "INSUFFICIENT_SCOPES" -> ProjectErrorKind.Scope
            msg.contains("read:project") || msg.contains("required scopes", true) || msg.contains("INSUFFICIENT_SCOPES") -> ProjectErrorKind.Scope
            type == "FORBIDDEN" -> ProjectErrorKind.Forbidden
            type == "NOT_FOUND" -> ProjectErrorKind.NotFound
            else -> ProjectErrorKind.Other
        }
    }

    /** 已转换为界面文案的错误（Loader / Pager 只保留字符串） */
    fun ofMessage(message: String?): ProjectErrorKind {
        val msg = message ?: return ProjectErrorKind.Other
        return when {
            msg == READ_SCOPE_MESSAGE || msg == WRITE_SCOPE_MESSAGE -> ProjectErrorKind.Scope
            msg.startsWith(FORBIDDEN_PREFIX) -> ProjectErrorKind.Forbidden
            msg == NOT_FOUND_MESSAGE -> ProjectErrorKind.NotFound
            else -> classify(null, msg)
        }
    }
}

/**
 * 把 GraphQL 游标分页适配为 [com.mobilegh.nav.Pager] 的页码分页：第 n 页使用第 n-1 页返回的 endCursor。
 * 第 1 页（含刷新）清空记录；已到末页后继续请求返回空列表，Pager 随即结束。
 */
class GraphQlCursors {
    private val cursors = HashMap<Int, String>()
    private val ended = HashSet<Int>()

    suspend fun <T> page(page: Int, fetch: suspend (after: String?) -> DiscussionPage<T>): List<T> {
        val after: String? = if (page <= 1) {
            synchronized(this) { cursors.clear(); ended.clear() }
            null
        } else {
            synchronized(this) { if (page - 1 in ended) null else cursors[page - 1] } ?: return emptyList()
        }
        val result = fetch(after)
        synchronized(this) {
            val next = result.endCursor
            if (result.hasNextPage && !next.isNullOrBlank() && next != after) cursors[page] = next else ended.add(page)
        }
        return result.items
    }
}

// ======================= GraphQL =======================

/**
 * Projects (v2) GraphQL：读取需要 read:project，写入需要 project scope。
 * transport 返回完整响应（含 data 与 errors），以便识别 INSUFFICIENT_SCOPES 等错误类型；可注入以便离线测试。
 */
open class ProjectsClient(
    private val graphql: suspend (String, Map<String, Any?>) -> JsonObject = { query, variables -> Api.graphqlRoot(query, variables) },
) {
    suspend fun list(kind: ProjectOwnerKind, login: String, name: String = "", after: String? = null): DiscussionPage<ProjectSummary> {
        val (query, vars, ownerKey) = when (kind) {
            ProjectOwnerKind.User -> Triple(USER_PROJECTS, mapOf("login" to login), "user")
            ProjectOwnerKind.Org -> Triple(ORG_PROJECTS, mapOf("login" to login), "organization")
            ProjectOwnerKind.Repo -> Triple(REPO_PROJECTS, mapOf("owner" to login, "name" to name), "repository")
        }
        val r = read(query, vars + ("after" to after))
        val owner = r.data.obj(ownerKey) ?: throw r.failure(if (kind == ProjectOwnerKind.Repo) "未找到仓库，或当前账号没有访问权限" else "未找到该账号，或当前账号没有访问权限")
        val conn = owner.obj("projectsV2") ?: throw r.failure(ProjectErrors.NOT_FOUND_MESSAGE)
        return connection(conn, after, ::parseSummary)
    }

    suspend fun detail(projectId: String): ProjectDetail {
        val r = read(DETAIL_QUERY, mapOf("id" to projectId))
        val node = r.data.obj("node")?.takeIf { it.str("id") != null } ?: throw r.failure(ProjectErrors.NOT_FOUND_MESSAGE)
        return ProjectDetail(
            id = node.need("id"), number = node.int("number") ?: 0, title = node.need("title"),
            shortDescription = node.str("shortDescription"), closed = node.bool("closed") ?: false,
            url = node.str("url").orEmpty(), viewerCanUpdate = node.bool("viewerCanUpdate") ?: false,
            fields = node.obj("fields")?.arr("nodes")?.objects().orEmpty().mapNotNull(::parseField),
            views = node.obj("views")?.arr("nodes")?.objects().orEmpty().mapNotNull(::parseView),
            items = connection(node.obj("items") ?: malformed("items"), null, ::parseItem),
        )
    }

    suspend fun items(projectId: String, after: String?): DiscussionPage<ProjectItem> {
        val r = read(ITEMS_QUERY, mapOf("id" to projectId, "after" to after))
        val node = r.data.obj("node") ?: throw r.failure(ProjectErrors.NOT_FOUND_MESSAGE)
        return connection(node.obj("items") ?: malformed("items"), after, ::parseItem)
    }

    /** 设置单选字段；optionId 为 null 时清空该字段。仅由用户的显式操作调用，不自动重试。 */
    suspend fun setSingleSelect(projectId: String, itemId: String, fieldId: String, optionId: String?) {
        val input = buildJsonObject {
            put("projectId", projectId)
            put("itemId", itemId)
            put("fieldId", fieldId)
            if (optionId != null) put("value", buildJsonObject { put("singleSelectOptionId", optionId) })
        }
        val r = write(if (optionId == null) CLEAR_MUTATION else UPDATE_MUTATION, mapOf("input" to input))
        val key = if (optionId == null) "clearProjectV2ItemFieldValue" else "updateProjectV2ItemFieldValue"
        r.data.obj(key)?.obj("projectV2Item")?.str("id") ?: throw r.failure("GitHub 未确认字段已修改；请刷新检查")
    }

    suspend fun delete(projectId: String, itemId: String) {
        val input = buildJsonObject { put("projectId", projectId); put("itemId", itemId) }
        val r = write(DELETE_MUTATION, mapOf("input" to input))
        r.data.obj("deleteProjectV2Item")?.str("deletedItemId") ?: throw r.failure("GitHub 未确认条目已移除；请刷新检查")
    }

    suspend fun addDraft(projectId: String, title: String, body: String): ProjectItem {
        require(title.isNotBlank()) { "请填写标题" }
        val input = buildJsonObject {
            put("projectId", projectId)
            put("title", title.trim())
            if (body.isNotBlank()) put("body", body)
        }
        val r = write(ADD_DRAFT_MUTATION, mapOf("input" to input))
        val node = r.data.obj("addProjectV2DraftIssue")?.obj("projectItem") ?: throw r.failure("GitHub 未确认草稿已添加；请刷新检查")
        return parseItem(node)
    }

    // ---------------- 响应处理 ----------------

    internal class Result(val data: JsonObject?, private val errors: List<JsonObject>) {
        fun failure(fallback: String): ApiException {
            val e = errors.firstOrNull() ?: return ApiException(404, fallback)
            val msg = e.str("message")
            return when (ProjectErrors.classify(e.str("type"), msg)) {
                ProjectErrorKind.Forbidden -> ApiException(403, ProjectErrors.FORBIDDEN_PREFIX + (msg ?: "权限不足"))
                ProjectErrorKind.NotFound -> ApiException(404, fallback)
                else -> ApiException(400, msg ?: fallback)
            }
        }
    }

    private suspend fun read(query: String, vars: Map<String, Any?>) = unwrap(graphql(query, vars), write = false)
    private suspend fun write(query: String, vars: Map<String, Any?>) = unwrap(graphql(query, vars), write = true)

    internal fun unwrap(root: JsonObject, write: Boolean): Result {
        val errors = (root["errors"] as? JsonArray)?.objects().orEmpty()
        // 缺少 scope 时 GitHub 可能仍返回部分 data（字段为 null），必须优先识别
        if (errors.any { ProjectErrors.classify(it.str("type"), it.str("message")) == ProjectErrorKind.Scope }) {
            throw ApiException(403, if (write) ProjectErrors.WRITE_SCOPE_MESSAGE else ProjectErrors.READ_SCOPE_MESSAGE)
        }
        val data = root["data"] as? JsonObject
        val result = Result(data, errors)
        if (data == null) throw result.failure("GraphQL 错误")
        return result
    }

    // ---------------- 解析 ----------------

    private fun parseSummary(n: JsonObject) = ProjectSummary(
        id = n.need("id"), number = n.int("number") ?: 0, title = n.need("title"),
        shortDescription = n.str("shortDescription")?.takeIf { it.isNotBlank() }, closed = n.bool("closed") ?: false,
        public = n.bool("public") ?: false, updatedAt = n.str("updatedAt"), url = n.str("url").orEmpty(),
        itemCount = n.obj("items")?.int("totalCount"),
    )

    private fun parseField(n: JsonObject): ProjectField? {
        val id = n.str("id") ?: return null
        val config = n.obj("configuration")
        val iterations = (config?.arr("iterations")?.objects().orEmpty() + config?.arr("completedIterations")?.objects().orEmpty())
            .mapNotNull { i -> i.str("id")?.let { ProjectIteration(it, i.str("title").orEmpty(), i.str("startDate")) } }
        return ProjectField(
            id, n.str("name").orEmpty(), n.str("dataType").orEmpty(),
            n.arr("options")?.objects().orEmpty().mapNotNull { o ->
                o.str("id")?.let { ProjectOption(it, o.str("name").orEmpty(), ProjectColor.of(o.str("color"))) }
            },
            iterations,
        )
    }

    private fun parseView(n: JsonObject): ProjectView? {
        val id = n.str("id") ?: return null
        fun ids(key: String) = n.obj(key)?.arr("nodes")?.objects().orEmpty().mapNotNull { it.str("id") }
        return ProjectView(
            id, n.int("number") ?: 0, n.str("name").orEmpty(), ProjectLayout.of(n.str("layout")),
            ids("groupByFields"), ids("verticalGroupByFields"), n.str("filter")?.takeIf(String::isNotBlank),
        )
    }

    internal fun parseItem(n: JsonObject): ProjectItem {
        val content = n.obj("content")
        val type = ProjectItemType.of(n.str("type"), content?.str("__typename"))
        val pr = type == ProjectItemType.PullRequest
        return ProjectItem(
            id = n.need("id"), type = if (content == null) ProjectItemType.Redacted else type,
            archived = n.bool("isArchived") ?: false,
            title = content?.str("title") ?: "无权访问的条目",
            number = content?.int("number"), url = content?.str("url"),
            repository = content?.obj("repository")?.str("nameWithOwner"),
            state = content?.str(if (pr) "prState" else "issueState"), stateReason = content?.str("stateReason"),
            isDraft = content?.bool("isDraft") ?: false, merged = content?.bool("merged") ?: false,
            body = content?.str("body"),
            values = n.obj("fieldValues")?.arr("nodes")?.objects().orEmpty().mapNotNull(::parseValue),
        )
    }

    private fun parseValue(n: JsonObject): ProjectFieldValue? {
        val field = n.obj("field") ?: return null
        val fieldId = field.str("id") ?: return null
        fun v(kind: ProjectValueKind, text: String?, option: String? = null) =
            text?.let { ProjectFieldValue(fieldId, field.str("name").orEmpty(), field.str("dataType"), kind, it, option) }
        return when (n.str("__typename")) {
            "ProjectV2ItemFieldSingleSelectValue" -> v(ProjectValueKind.SingleSelect, n.str("name"), n.str("optionId"))
            "ProjectV2ItemFieldTextValue" -> v(ProjectValueKind.Text, n.str("text"))
            "ProjectV2ItemFieldNumberValue" -> v(ProjectValueKind.Number, fmtNumber((n["number"] as? JsonPrimitive)?.doubleOrNull))
            "ProjectV2ItemFieldDateValue" -> v(ProjectValueKind.Date, n.str("date"))
            "ProjectV2ItemFieldIterationValue" -> v(ProjectValueKind.Iteration, n.str("title"), n.str("iterationId"))
            "ProjectV2ItemFieldLabelValue" -> v(ProjectValueKind.Labels, names(n.obj("labels"), "name"))
            "ProjectV2ItemFieldUserValue" -> v(ProjectValueKind.Users, names(n.obj("users"), "login"))
            "ProjectV2ItemFieldMilestoneValue" -> v(ProjectValueKind.Milestone, n.obj("milestone")?.str("title"))
            else -> null
        }
    }

    private fun names(conn: JsonObject?, key: String) =
        conn?.arr("nodes")?.objects().orEmpty().mapNotNull { it.str(key) }.takeIf { it.isNotEmpty() }?.joinToString(", ")

    private fun <T> connection(node: JsonObject, after: String?, parse: (JsonObject) -> T): DiscussionPage<T> {
        val nodes = node["nodes"] as? JsonArray ?: malformed("nodes")
        val info = node.obj("pageInfo") ?: malformed("pageInfo")
        val next = info.str("endCursor")
        val hasNext = info.bool("hasNextPage") ?: false
        if (hasNext && (next.isNullOrBlank() || next == after)) throw ApiException(502, "GitHub 返回的分页游标无效，请刷新重试")
        val items = nodes.mapNotNull { if (it is JsonObject) parse(it) else null }
        return DiscussionPage(items, next, hasNext, node.int("totalCount") ?: items.size)
    }

    private fun JsonElement.bool(key: String) = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
    private fun JsonObject.need(key: String) = str(key) ?: malformed(key)
    private fun malformed(key: String): Nothing = throw ApiException(502, "GitHub 项目数据不完整：$key")

    companion object {
        const val LIST_PAGE = 20
        const val ITEMS_PAGE = 50
        const val MAX_ITEMS = 500

        internal fun fmtNumber(d: Double?): String? = d?.let { if (it == Math.floor(it) && !it.isInfinite() && kotlin.math.abs(it) < 1e15) it.toLong().toString() else it.toString() }

        private const val PAGE_INFO = "totalCount pageInfo { endCursor hasNextPage }"
        private const val SUMMARY_FIELDS = "id number title shortDescription closed public updatedAt url items { totalCount }"
        private const val PROJECTS_CONNECTION =
            "projectsV2(first: $LIST_PAGE, after: \$after, orderBy: {field: UPDATED_AT, direction: DESC}) { $PAGE_INFO nodes { $SUMMARY_FIELDS } }"
        private const val USER_PROJECTS = """
            query UserProjects(${'$'}login: String!, ${'$'}after: String) { user(login: ${'$'}login) { $PROJECTS_CONNECTION } }
        """
        private const val ORG_PROJECTS = """
            query OrgProjects(${'$'}login: String!, ${'$'}after: String) { organization(login: ${'$'}login) { $PROJECTS_CONNECTION } }
        """
        private const val REPO_PROJECTS = """
            query RepoProjects(${'$'}owner: String!, ${'$'}name: String!, ${'$'}after: String) {
                repository(owner: ${'$'}owner, name: ${'$'}name) { $PROJECTS_CONNECTION }
            }
        """
        private const val FIELD_FIELDS = """
            ... on ProjectV2Field { id name dataType }
            ... on ProjectV2SingleSelectField { id name dataType options { id name color } }
            ... on ProjectV2IterationField { id name dataType configuration {
                iterations { id title startDate } completedIterations { id title startDate } } }
        """
        private const val VALUE_FIELD = "field { ... on ProjectV2FieldCommon { id name dataType } }"
        // Issue.state 与 PullRequest.state 类型不同，必须用别名区分，否则 GraphQL 校验失败
        private const val ITEM_FIELDS = """
            id type isArchived
            content {
                __typename
                ... on Issue { number title url issueState: state stateReason repository { nameWithOwner } }
                ... on PullRequest { number title url prState: state isDraft merged repository { nameWithOwner } }
                ... on DraftIssue { title body }
            }
            fieldValues(first: 20) { nodes {
                __typename
                ... on ProjectV2ItemFieldSingleSelectValue { name optionId $VALUE_FIELD }
                ... on ProjectV2ItemFieldTextValue { text $VALUE_FIELD }
                ... on ProjectV2ItemFieldNumberValue { number $VALUE_FIELD }
                ... on ProjectV2ItemFieldDateValue { date $VALUE_FIELD }
                ... on ProjectV2ItemFieldIterationValue { title iterationId $VALUE_FIELD }
                ... on ProjectV2ItemFieldLabelValue { labels(first: 5) { nodes { name } } $VALUE_FIELD }
                ... on ProjectV2ItemFieldUserValue { users(first: 3) { nodes { login } } $VALUE_FIELD }
                ... on ProjectV2ItemFieldMilestoneValue { milestone { title } $VALUE_FIELD }
            } }
        """
        private const val DETAIL_QUERY = """
            query ProjectDetail(${'$'}id: ID!) { node(id: ${'$'}id) { ... on ProjectV2 {
                id number title shortDescription closed url viewerCanUpdate
                fields(first: 50) { nodes { $FIELD_FIELDS } }
                views(first: 20) { nodes { id number name layout filter
                    groupByFields(first: 5) { nodes { ... on ProjectV2FieldCommon { id } } }
                    verticalGroupByFields(first: 5) { nodes { ... on ProjectV2FieldCommon { id } } }
                } }
                items(first: $ITEMS_PAGE) { $PAGE_INFO nodes { $ITEM_FIELDS } }
            } } }
        """
        private const val ITEMS_QUERY = """
            query ProjectItems(${'$'}id: ID!, ${'$'}after: String) { node(id: ${'$'}id) { ... on ProjectV2 {
                items(first: $ITEMS_PAGE, after: ${'$'}after) { $PAGE_INFO nodes { $ITEM_FIELDS } }
            } } }
        """
        private const val UPDATE_MUTATION = """
            mutation UpdateProjectItemField(${'$'}input: UpdateProjectV2ItemFieldValueInput!) {
                updateProjectV2ItemFieldValue(input: ${'$'}input) { projectV2Item { id } }
            }
        """
        private const val CLEAR_MUTATION = """
            mutation ClearProjectItemField(${'$'}input: ClearProjectV2ItemFieldValueInput!) {
                clearProjectV2ItemFieldValue(input: ${'$'}input) { projectV2Item { id } }
            }
        """
        private const val DELETE_MUTATION = """
            mutation DeleteProjectItem(${'$'}input: DeleteProjectV2ItemInput!) {
                deleteProjectV2Item(input: ${'$'}input) { deletedItemId }
            }
        """
        private const val ADD_DRAFT_MUTATION = """
            mutation AddProjectDraft(${'$'}input: AddProjectV2DraftIssueInput!) {
                addProjectV2DraftIssue(input: ${'$'}input) { projectItem { $ITEM_FIELDS } }
            }
        """
    }
}

object Projects : ProjectsClient()
