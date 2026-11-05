package com.mobilegh.data

/**
 * 相关议题可能来自其他仓库（网页版 Relationships 也支持跨仓库），来源仓库由 API 返回的
 * repository_url 决定，解析失败时回退到当前仓库。
 */
fun relatedIssueRepo(issue: Issue, currentOwner: String, currentName: String): Pair<String, String> {
    val parts = issue.repositoryUrl?.substringAfter("/repos/", "")?.trim('/')?.split('/')
    return if (parts != null && parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
        parts[0] to parts[1]
    else currentOwner to currentName
}

/** 同一仓库内显示 #123，跨仓库显示 owner/name#123（与 GitHub 网页一致） */
fun relatedIssueLabel(issue: Issue, currentOwner: String, currentName: String): String {
    val (o, n) = relatedIssueRepo(issue, currentOwner, currentName)
    return if (o.equals(currentOwner, true) && n.equals(currentName, true)) "#${issue.number}"
    else "$o/$n#${issue.number}"
}
