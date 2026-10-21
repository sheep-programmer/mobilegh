package com.mobilegh.data

import java.time.LocalDate

/** 搜索类型，顺序与界面 Chips 一致 */
object SearchType {
    const val REPO = 0
    const val USER = 1
    const val ISSUE = 2
    const val PR = 3
    const val CODE = 4
    val labels = listOf("仓库", "用户", "Issue", "PR", "代码")
}

/** 常见语言（筛选菜单用；仍可直接在关键词里写 language:xxx） */
val SEARCH_LANGUAGES = listOf(
    "Kotlin", "Java", "JavaScript", "TypeScript", "Python", "Go", "Rust", "C", "C++", "C#",
    "Swift", "Objective-C", "Ruby", "PHP", "HTML", "CSS", "SCSS", "Vue", "Svelte", "Shell",
    "Dart", "Lua", "Scala", "Haskell", "Elixir", "Clojure", "R", "Perl", "Jupyter Notebook",
    "Dockerfile", "Makefile", "Zig", "Nix", "Markdown", "TeX", "Solidity",
)

private fun searchName(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9_./-]"), "")
private fun searchValue(value: String): String = value.trim().replace("\\", "").replace("\"", "").trim()

/**
 * 结构化搜索条件。各类型只使用自己相关的字段，切换类型时互不干扰。
 * 构造出的查询串由 [SearchQuery] 负责，便于单元测试。
 */
data class SearchFilters(
    // 仓库（各类型各自的语言字段，切换类型时互不影响）
    val repoOwner: String = "",
    val repoLanguage: String = "",
    val minStars: Int = 0,
    val minForks: Int = 0,
    val topic: String = "",
    /** 0 不限 1 仅归档 2 排除归档 */
    val archived: Int = 0,
    // 用户
    /** 0 不限 1 用户 2 组织 */
    val userType: Int = 0,
    val location: String = "",
    val minFollowers: Int = 0,
    val userLanguage: String = "",
    // Issue / PR
    /** 0 不限 1 开放 2 已关闭 */
    val state: Int = 0,
    val author: String = "",
    val labels: String = "",
    val minComments: Int = 0,
    val issueRepo: String = "",
    val issueOwner: String = "",
    val issueLanguage: String = "",
    val updatedDays: Int = 0,
    // 代码
    val codeRepo: String = "",
    val codeOwner: String = "",
    val codeLanguage: String = "",
    val path: String = "",
    val filename: String = "",
) {
    /** 当前类型下实际生效的筛选数量（用于按钮角标） */
    fun activeCount(type: Int): Int = when (type) {
        SearchType.REPO -> listOf(
            searchName(repoOwner).isNotEmpty(), searchValue(repoLanguage).isNotEmpty(), searchName(topic).isNotEmpty(),
            minStars > 0, minForks > 0, archived in 1..2,
        ).count { it }
        SearchType.USER -> listOf(
            searchValue(location).isNotEmpty(), searchValue(userLanguage).isNotEmpty(), userType in 1..2, minFollowers > 0,
        ).count { it }
        SearchType.ISSUE, SearchType.PR -> listOf(
            searchName(author).isNotEmpty(), labels.split(',').any { searchValue(it).isNotEmpty() },
            searchName(issueRepo).isNotEmpty() || searchName(issueOwner).isNotEmpty(),
            searchValue(issueLanguage).isNotEmpty(), state in 1..2, minComments > 0, updatedDays > 0,
        ).count { it }
        else -> listOf(
            searchName(codeRepo).isNotEmpty() || searchName(codeOwner).isNotEmpty(),
            searchValue(codeLanguage).isNotEmpty(), searchValue(path).isNotEmpty(), searchValue(filename).isNotEmpty(),
        ).count { it }
    }

    fun hasActive(type: Int): Boolean = activeCount(type) > 0

    /**
     * 当前类型已生效的条件，按界面顺序返回 (key, 可读描述)。
     * key 与 [remove] 对应，供搜索页以可单独撤销的标签展示。
     */
    fun activeChips(type: Int): List<Pair<String, String>> {
        fun name(v: String) = searchName(v).takeIf { it.isNotEmpty() }
        fun value(v: String) = searchValue(v).takeIf { it.isNotEmpty() }
        return when (type) {
            SearchType.REPO -> buildList {
                name(repoOwner)?.let { add("owner" to it) }
                value(repoLanguage)?.let { add("language" to it) }
                if (minStars > 0) add("stars" to "Star ≥ $minStars")
                if (minForks > 0) add("forks" to "Fork ≥ $minForks")
                name(topic)?.let { add("topic" to "topic:$it") }
                if (archived == 1) add("archived" to "仅归档")
                if (archived == 2) add("archived" to "排除归档")
            }
            SearchType.USER -> buildList {
                if (userType == 1) add("userType" to "用户")
                if (userType == 2) add("userType" to "组织")
                value(location)?.let { add("location" to it) }
                value(userLanguage)?.let { add("userLanguage" to it) }
                if (minFollowers > 0) add("followers" to "关注者 ≥ $minFollowers")
            }
            SearchType.ISSUE, SearchType.PR -> buildList {
                if (state == 1) add("state" to "开放")
                if (state == 2) add("state" to "已关闭")
                name(issueRepo)?.let { add("repo" to it) }
                name(issueOwner)?.let { add("issueOwner" to it) }
                name(author)?.let { add("author" to "作者 $it") }
                labels.split(',').mapNotNull { value(it) }.forEach { add("label:$it" to "标签 $it") }
                value(issueLanguage)?.let { add("issueLanguage" to it) }
                if (minComments > 0) add("comments" to "评论 ≥ $minComments")
                if (updatedDays > 0) add("updated" to "近 $updatedDays 天更新")
            }
            else -> buildList {
                name(codeRepo)?.let { add("repo" to it) }
                name(codeOwner)?.let { add("codeOwner" to it) }
                value(codeLanguage)?.let { add("codeLanguage" to it) }
                value(path)?.let { add("path" to "path:$it") }
                value(filename)?.let { add("filename" to "filename:$it") }
            }
        }
    }

    /** 移除单个条件；标签里的多个值（如多个 label）只移除被点的那一个 */
    fun remove(type: Int, key: String): SearchFilters = when {
        key.startsWith("label:") -> {
            val target = key.removePrefix("label:")
            copy(labels = labels.split(',').filterNot { searchValue(it) == target }.joinToString(","))
        }
        type == SearchType.REPO -> when (key) {
            "owner" -> copy(repoOwner = "")
            "language" -> copy(repoLanguage = "")
            "stars" -> copy(minStars = 0)
            "forks" -> copy(minForks = 0)
            "topic" -> copy(topic = "")
            "archived" -> copy(archived = 0)
            else -> this
        }
        type == SearchType.USER -> when (key) {
            "userType" -> copy(userType = 0)
            "location" -> copy(location = "")
            "userLanguage" -> copy(userLanguage = "")
            "followers" -> copy(minFollowers = 0)
            else -> this
        }
        type == SearchType.ISSUE || type == SearchType.PR -> when (key) {
            "state" -> copy(state = 0)
            "repo" -> copy(issueRepo = "")
            "issueOwner" -> copy(issueOwner = "")
            "author" -> copy(author = "")
            "issueLanguage" -> copy(issueLanguage = "")
            "comments" -> copy(minComments = 0)
            "updated" -> copy(updatedDays = 0)
            else -> this
        }
        else -> when (key) {
            "repo" -> copy(codeRepo = "")
            "codeOwner" -> copy(codeOwner = "")
            "codeLanguage" -> copy(codeLanguage = "")
            "path" -> copy(path = "")
            "filename" -> copy(filename = "")
            else -> this
        }
    }

    /** 只重置当前类型的筛选，避免影响其他类型已设置的条件 */
    fun cleared(type: Int): SearchFilters = when (type) {
        SearchType.REPO -> copy(repoOwner = "", repoLanguage = "", topic = "", minStars = 0, minForks = 0, archived = 0)
        SearchType.USER -> copy(location = "", userLanguage = "", userType = 0, minFollowers = 0)
        SearchType.ISSUE, SearchType.PR -> copy(author = "", labels = "", issueRepo = "", issueOwner = "",
            issueLanguage = "", state = 0, minComments = 0, updatedDays = 0)
        else -> copy(codeRepo = "", codeOwner = "", codeLanguage = "", path = "", filename = "")
    }
}

/** 把关键词与结构化筛选拼成 GitHub 搜索语法 */
object SearchQuery {
    /** 去掉会破坏查询语法的引号/反斜杠，忽略清理后为空的条件 */
    private fun MutableList<String>.addValue(qualifier: String, value: String) {
        searchValue(value).takeIf { it.isNotEmpty() }?.let { add("$qualifier:\"$it\"") }
    }

    private fun since(days: Int): String = LocalDate.now().minusDays(days.toLong()).toString()

    /** 历史已另存搜索类型，不重复保存隐式 is:issue / is:pr，切换类型时仍能复用关键词。 */
    fun build(type: Int, text: String, f: SearchFilters, includeType: Boolean = true): String {
        val parts = mutableListOf(text.trim())
        when (type) {
            SearchType.REPO -> {
                if (f.repoOwner.isNotBlank()) searchName(f.repoOwner).takeIf { it.isNotEmpty() }?.let { parts += "user:$it" }
                parts.addValue("language", f.repoLanguage)
                if (f.minStars > 0) parts += "stars:>=${f.minStars}"
                if (f.minForks > 0) parts += "forks:>=${f.minForks}"
                searchName(f.topic).takeIf { it.isNotEmpty() }?.let { parts += "topic:$it" }
                when (f.archived) {
                    1 -> parts += "archived:true"
                    2 -> parts += "archived:false"
                }
            }
            SearchType.USER -> {
                when (f.userType) {
                    1 -> parts += "type:user"
                    2 -> parts += "type:org"
                }
                parts.addValue("location", f.location)
                parts.addValue("language", f.userLanguage)
                if (f.minFollowers > 0) parts += "followers:>=${f.minFollowers}"
            }
            SearchType.ISSUE, SearchType.PR -> {
                if (includeType) parts += if (type == SearchType.PR) "is:pr" else "is:issue"
                when (f.state) {
                    1 -> parts += "is:open"
                    2 -> parts += "is:closed"
                }
                val repo = searchName(f.issueRepo).takeIf { it.isNotEmpty() }
                repo?.let { parts += "repo:$it" }
                searchName(f.author).takeIf { it.isNotEmpty() }?.let { parts += "author:$it" }
                f.labels.split(',').forEach { parts.addValue("label", it) }
                if (f.minComments > 0) parts += "comments:>=${f.minComments}"
                if (f.updatedDays > 0) parts += "updated:>${since(f.updatedDays)}"
                parts.addValue("language", f.issueLanguage)
                // 跨用户的 Issue 搜索可用 user:；给了具体仓库时以 repo: 为准
                if (repo == null) searchName(f.issueOwner).takeIf { it.isNotEmpty() }?.let { parts += "user:$it" }
            }
            SearchType.CODE -> {
                val repo = searchName(f.codeRepo).takeIf { it.isNotEmpty() }
                repo?.let { parts += "repo:$it" }
                // 指定了具体仓库时 user: 多余且可能互相收窄
                if (repo == null && f.codeOwner.isNotBlank()) searchName(f.codeOwner).takeIf { it.isNotEmpty() }?.let { parts += "user:$it" }
                parts.addValue("language", f.codeLanguage)
                parts.addValue("path", f.path)
                parts.addValue("filename", f.filename)
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * 关键词之外没有任何条件时不必发请求（Issue/PR 天然带 is: 限定，但有主体词才算有效）。
     * 代码搜索例外：GitHub 的 /search/code 只认关键词，只有 repo:/language: 这类限定符会返回 422，
     * 所以必须填关键词才能发起搜索。
     */
    fun hasCriteria(type: Int, text: String, f: SearchFilters): Boolean = when (type) {
        SearchType.CODE -> text.isNotBlank()
        else -> text.isNotBlank() || f.hasActive(type)
    }
}
