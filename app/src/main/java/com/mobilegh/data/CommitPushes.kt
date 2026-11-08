package com.mobilegh.data

/**
 * 从分支的提交历史里还原「推送」记录。
 *
 * GitHub 的仓库事件接口（/repos/{o}/{n}/events）对大仓库并不完整，最近推送经常显示不出来。
 * 提交历史接口 /repos/{o}/{n}/commits?sha=<分支> 一直可用，所以改为拉每个分支的提交，
 * 把同一作者、间隔不超过 [MERGE_WINDOW_SECONDS] 的连续提交当成一次推送，最新的在前。
 */
data class CommitPush(
    val ref: String,
    val isTag: Boolean,
    /** 这次推送包含的提交数 */
    val commitCount: Int,
    val headSha: String,
    val headMessage: String,
    /** 推送前的提交，对比页的基准；该分支首个提交时为 null */
    val baseSha: String?,
    val authorLogin: String?,
    val authorAvatar: String,
    val at: String,
)

/** 同一次推送里的提交间隔通常只有几秒到几分钟；超过这个间隔就算另一次推送 */
internal const val MERGE_WINDOW_SECONDS = 30 * 60L

/** 每个分支最多看这么多提交：只看最近的一段，不为了久远的历史翻很多页 */
internal const val MAX_COMMITS_PER_BRANCH = 100

/** 最多汇总多少个分支；按最近推送排序，活动少的分支排在后面 */
internal const val MAX_BRANCHES = 30

/** 一次最多返回多少条推送记录 */
internal const val PUSH_PAGE = 30

/**
 * 把某个分支上按时间倒序排列的提交（[commits] 第一项最新）切成推送记录。
 * 相邻提交属于同一作者且时间相差不超过 [windowSeconds] 时合并为同一次推送。
 * 提交缺少作者或时间时无法可靠分组，单独成一条。
 */
fun splitPushes(ref: String, commits: List<Commit>, windowSeconds: Long = MERGE_WINDOW_SECONDS): List<CommitPush> {
    if (commits.isEmpty()) return emptyList()
    val isTag = ref.startsWith("refs/tags/")
    val name = ref.removePrefix("refs/heads/").removePrefix("refs/tags/")
    val groups = mutableListOf<MutableList<Commit>>()
    for (c in commits) {
        val last = groups.lastOrNull()
        val prev = last?.lastOrNull()
        val sameAuthor = prev != null && authorKey(prev) != null && authorKey(prev) == authorKey(c)
        // 列表从新到旧：prev 比 c 新，所以取两者间隔的绝对值
        val close = prev != null && kotlin.math.abs(secondsBetween(c.time(), prev.time())) in 0..windowSeconds
        if (last != null && sameAuthor && close) last.add(c) else groups.add(mutableListOf(c))
    }
    return groups.map { group ->
        // 组内按时间倒序，第一项是这次推送的最新提交
        val head = group.first()
        val oldest = group.last()
        CommitPush(
            ref = name,
            isTag = isTag,
            commitCount = group.size,
            headSha = head.sha,
            headMessage = head.commit.message.lineSequence().firstOrNull().orEmpty(),
            baseSha = oldest.parents.firstOrNull()?.sha?.takeIf { it.isNotBlank() },
            authorLogin = head.author?.login,
            authorAvatar = head.author?.avatarUrl.orEmpty(),
            at = head.time().orEmpty(),
        )
    }
}

private fun authorKey(c: Commit): String? =
    c.author?.login?.takeIf { it.isNotBlank() }
        ?: c.commit.author?.name?.takeIf { it.isNotBlank() }
        ?: c.commit.author?.email?.takeIf { it.isNotBlank() }

private fun Commit.time(): String? = commit.author?.date ?: commit.committer?.date

private fun secondsBetween(later: String?, earlier: String?): Long {
    if (later == null || earlier == null) return Long.MAX_VALUE
    return runCatching {
        java.time.Duration.between(java.time.Instant.parse(earlier), java.time.Instant.parse(later)).seconds
    }.getOrDefault(Long.MAX_VALUE)
}
