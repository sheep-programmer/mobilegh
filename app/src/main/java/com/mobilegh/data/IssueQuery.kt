package com.mobilegh.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Issue / PR 列表的状态筛选 */
@Serializable
enum class IssueStateFilter(val label: String, val api: String) { Open("开放", "open"), Closed("已关闭", "closed"), All("全部", "all") }

/** 排序：search 与 list 两种接口的参数各不相同 */
@Serializable
enum class IssueSort(val label: String, val field: String, val order: String) {
    Newest("最新", "created", "desc"),
    Oldest("最早", "created", "asc"),
    Comments("最多评论", "comments", "desc"),
    Updated("最近更新", "updated", "desc"),
}

/** PR 的评审状态 */
@Serializable
enum class ReviewFilter(val label: String, val qualifier: String) {
    Required("需要评审", "review:required"),
    Approved("已批准", "review:approved"),
    ChangesRequested("请求修改", "review:changes_requested"),
}

/** 「我的 Issues / PR」中与我相关的方式 */
@Serializable
enum class Involvement(val label: String, val qualifier: String, val pullsOnly: Boolean = false) {
    Created("创建的", "author:@me"),
    Assigned("指派给我", "assignee:@me"),
    Mentioned("提及我", "mentions:@me"),
    ReviewRequested("评审请求", "review-requested:@me", pullsOnly = true),
}

enum class FilterKind(val label: String) { Author("作者"), Assignee("指派人"), Label("标签"), Milestone("里程碑"), Review("评审"), Draft("草稿"), Text("搜索") }

/** 一个可单独移除的已生效筛选条件 */
data class ActiveFilter(val kind: FilterKind, val value: String, val text: String)

/**
 * Issue / PR 列表的完整查询条件。repo 为空表示跨仓库（需配合 [involves]）。
 * 只有状态与排序时可以走仓库的列表接口；其余条件都需要 Search API。
 */
@Serializable
data class IssueQuery(
    val pulls: Boolean = false,
    val repo: String? = null,
    val state: IssueStateFilter = IssueStateFilter.Open,
    val sort: IssueSort = IssueSort.Newest,
    val author: String? = null,
    val assignee: String? = null,
    val labels: List<String> = emptyList(),
    val milestone: String? = null,
    val review: ReviewFilter? = null,
    val draft: Boolean? = null,
    val text: String = "",
    val involves: Involvement? = null,
) {
    /** 除状态、排序与「与我相关」外，是否还有其他筛选 */
    val hasFilters: Boolean get() = activeFilters().isNotEmpty()

    /** 是否必须使用 Search API（列表接口无法表达的条件） */
    val needsSearch: Boolean
        get() = hasFilters || involves != null || repo == null || (pulls && sort == IssueSort.Comments)

    /** 生成 /search/issues 的 q 参数 */
    fun searchQuery(): String = buildList {
        repo?.let { add("repo:$it") }
        add(if (pulls) "is:pr" else "is:issue")
        if (state != IssueStateFilter.All) add("state:${state.api}")
        involves?.takeIf { pulls || !it.pullsOnly }?.let { add(it.qualifier) }
        author?.let { add("author:${login(it)}") }
        assignee?.let { add("assignee:${login(it)}") }
        labels.forEach { add("label:${quoted(it)}") }
        milestone?.let { add("milestone:${quoted(it)}") }
        if (pulls) {
            review?.let { add(it.qualifier) }
            draft?.let { add("draft:$it") }
        }
        if (repo == null) add("archived:false")
        text.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" ")

    /** 已生效的筛选条件（用于可移除的标签） */
    fun activeFilters(): List<ActiveFilter> = buildList {
        author?.let { add(ActiveFilter(FilterKind.Author, it, "作者：$it")) }
        assignee?.let { add(ActiveFilter(FilterKind.Assignee, it, "指派人：$it")) }
        labels.forEach { add(ActiveFilter(FilterKind.Label, it, "标签：$it")) }
        milestone?.let { add(ActiveFilter(FilterKind.Milestone, it, "里程碑：$it")) }
        if (pulls) {
            review?.let { add(ActiveFilter(FilterKind.Review, it.name, "评审：${it.label}")) }
            draft?.let { add(ActiveFilter(FilterKind.Draft, it.toString(), if (it) "仅草稿" else "排除草稿")) }
        }
        text.trim().takeIf { it.isNotEmpty() }?.let { add(ActiveFilter(FilterKind.Text, it, "“$it”")) }
    }

    fun without(f: ActiveFilter): IssueQuery = when (f.kind) {
        FilterKind.Author -> copy(author = null)
        FilterKind.Assignee -> copy(assignee = null)
        FilterKind.Label -> copy(labels = labels - f.value)
        FilterKind.Milestone -> copy(milestone = null)
        FilterKind.Review -> copy(review = null)
        FilterKind.Draft -> copy(draft = null)
        FilterKind.Text -> copy(text = "")
    }

    /** 清除筛选：保留类型、仓库、状态、排序与「与我相关」 */
    fun cleared(): IssueQuery = IssueQuery(pulls, repo, state, sort, involves = involves)

    /** 规范化：去掉空白值，避免产生空的限定词 */
    fun normalized(): IssueQuery = copy(
        repo = repo?.trim()?.takeIf { REPO.matches(it) },
        author = author?.let(::login)?.takeIf { it.isNotEmpty() },
        assignee = assignee?.let(::login)?.takeIf { it.isNotEmpty() },
        labels = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_LABELS),
        milestone = milestone?.trim()?.takeIf { it.isNotEmpty() },
        review = if (pulls) review else null,
        draft = if (pulls) draft else null,
        text = text.trim().take(MAX_TEXT),
        involves = involves?.takeIf { pulls || !it.pullsOnly },
    )

    /** 用于缓存 key：不同条件对应不同的分页器 */
    fun key(): String = json.encodeToString(this)

    /** 简短描述，用于保存的搜索 */
    fun summary(): String = buildList {
        add(repo ?: involves?.label ?: "全部")
        add(state.label)
        addAll(activeFilters().map { it.text })
        if (sort != IssueSort.Newest) add(sort.label)
    }.joinToString(" · ")

    companion object {
        const val MAX_LABELS = 10
        const val MAX_TEXT = 256
        private val REPO = Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")
        private val json = Json { encodeDefaults = false }

        /** 登录名只保留合法字符，防止注入其他限定词 */
        fun login(s: String): String = s.trim().let { t -> if (t == "@me") t else t.removePrefix("@").filter { it.isLetterOrDigit() || it == '-' || it == '[' || it == ']' || it == '/' } }

        /** 标签、里程碑一律加引号；搜索语法不支持转义，直接去掉值里的引号 */
        fun quoted(s: String): String = "\"" + s.replace("\"", "").trim() + "\""
    }
}
