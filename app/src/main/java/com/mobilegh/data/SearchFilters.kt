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
        SearchType.REPO -> listOf(repoOwner, repoLanguage, topic).count { it.isNotBlank() } +
            listOf(minStars, minForks, archived).count { it > 0 }
        SearchType.USER -> listOf(location, userLanguage).count { it.isNotBlank() } +
            listOf(userType, minFollowers).count { it > 0 }
        SearchType.ISSUE, SearchType.PR -> listOf(author, labels, issueRepo, issueOwner, issueLanguage).count { it.isNotBlank() } +
            listOf(state, minComments, updatedDays).count { it > 0 }
        else -> listOf(codeRepo, codeOwner, codeLanguage, path, filename).count { it.isNotBlank() }
    }

    fun hasActive(type: Int): Boolean = activeCount(type) > 0

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
    /** 去掉会破坏查询语法的引号/反斜杠，再整体加引号 */
    private fun quoted(value: String): String =
        "\"" + value.trim().replace("\\", "").replace("\"", "") + "\""

    /** 保留 owner/name 中的斜杠；空格、引号、冒号等会拼出额外限定符，必须去掉 */
    private fun name(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9_./-]"), "")

    private fun since(days: Int): String = LocalDate.now().minusDays(days.toLong()).toString()

    fun build(type: Int, text: String, f: SearchFilters): String {
        val parts = mutableListOf(text.trim())
        when (type) {
            SearchType.REPO -> {
                if (f.repoOwner.isNotBlank()) name(f.repoOwner).takeIf { it.isNotEmpty() }?.let { parts += "user:$it" }
                if (f.repoLanguage.isNotBlank()) parts += "language:${quoted(f.repoLanguage)}"
                if (f.minStars > 0) parts += "stars:>=${f.minStars}"
                if (f.minForks > 0) parts += "forks:>=${f.minForks}"
                name(f.topic).takeIf { it.isNotEmpty() }?.let { parts += "topic:$it" }
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
                if (f.location.isNotBlank()) parts += "location:${quoted(f.location)}"
                if (f.userLanguage.isNotBlank()) parts += "language:${quoted(f.userLanguage)}"
                if (f.minFollowers > 0) parts += "followers:>=${f.minFollowers}"
            }
            SearchType.ISSUE, SearchType.PR -> {
                parts += if (type == SearchType.PR) "is:pr" else "is:issue"
                when (f.state) {
                    1 -> parts += "is:open"
                    2 -> parts += "is:closed"
                }
                name(f.issueRepo).takeIf { it.isNotEmpty() }?.let { parts += "repo:$it" }
                name(f.author).takeIf { it.isNotEmpty() }?.let { parts += "author:$it" }
                f.labels.split(',').map(String::trim).filter(String::isNotEmpty).forEach { parts += "label:${quoted(it)}" }
                if (f.minComments > 0) parts += "comments:>=${f.minComments}"
                if (f.updatedDays > 0) parts += "updated:>${since(f.updatedDays)}"
                if (f.issueLanguage.isNotBlank()) parts += "language:${quoted(f.issueLanguage)}"
                // 跨用户的 Issue 搜索可用 user:；给了具体仓库时以 repo: 为准
                if (f.issueRepo.isBlank() && f.issueOwner.isNotBlank()) name(f.issueOwner).takeIf { it.isNotEmpty() }?.let { parts += "user:$it" }
            }
            SearchType.CODE -> {
                val repo = name(f.codeRepo).takeIf { it.isNotEmpty() }
                repo?.let { parts += "repo:$it" }
                // 指定了具体仓库时 user: 多余且可能互相收窄
                if (repo == null && f.codeOwner.isNotBlank()) name(f.codeOwner).takeIf { it.isNotEmpty() }?.let { parts += "user:$it" }
                if (f.codeLanguage.isNotBlank()) parts += "language:${quoted(f.codeLanguage)}"
                if (f.path.isNotBlank()) parts += "path:${quoted(f.path)}"
                if (f.filename.isNotBlank()) parts += "filename:${quoted(f.filename)}"
            }
        }
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    /** 关键词之外没有任何条件时不必发请求（Issue/PR 天然带 is: 限定，但有主体词才算有效） */
    fun hasCriteria(type: Int, text: String, f: SearchFilters): Boolean =
        text.isNotBlank() || f.hasActive(type)
}
