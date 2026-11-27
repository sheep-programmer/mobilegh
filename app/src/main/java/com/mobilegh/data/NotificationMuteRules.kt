package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class NotificationMuteConfig(
    val muteBots: Boolean = false,
    val mutedRepos: Set<String> = emptySet(),
    val mutedKeywords: List<String> = emptyList(),
    val notifyIssues: Boolean = false,
    val notifyPrs: Boolean = false,
) {
    val isActive: Boolean get() = muteBots || mutedRepos.isNotEmpty() || mutedKeywords.isNotEmpty() || notifyIssues || notifyPrs
}

/**
 * Persistent notification noise-reduction rules:
 * - Auto-mute bot/automation notifications (Dependabot, Renovate, GitHub Actions, etc.)
 * - Mute specific repositories or title keywords
 */
object NotificationMuteRules {
    private const val PREFS = "mobilegh_notify_mute"
    private const val KEY_MUTE_BOTS = "mute_bots"
    private const val KEY_MUTED_REPOS = "muted_repos"
    private const val KEY_MUTED_KEYWORDS = "muted_keywords"
    private const val KEY_NOTIFY_ISSUES = "notify_issues"
    private const val KEY_NOTIFY_PRS = "notify_prs"

    private val botMarkers = listOf(
        "[bot]", "dependabot", "renovate", "github-actions", "codecov",
        "sonarcloud", "snyk-bot", "pre-commit-ci", "mergify", "allcontributors",
    )

    private var prefs: SharedPreferences? = null
    var config by mutableStateOf(NotificationMuteConfig())
        private set

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        config = NotificationMuteConfig(
            muteBots = p.getBoolean(KEY_MUTE_BOTS, false),
            mutedRepos = p.getStringSet(KEY_MUTED_REPOS, emptySet()).orEmpty().map { it.lowercase() }.toSet(),
            mutedKeywords = p.getString(KEY_MUTED_KEYWORDS, "")
                .orEmpty()
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            notifyIssues = p.getBoolean(KEY_NOTIFY_ISSUES, false),
            notifyPrs = p.getBoolean(KEY_NOTIFY_PRS, false),
        )
    }

    fun update(next: NotificationMuteConfig) {
        val normalized = NotificationMuteConfig(
            muteBots = next.muteBots,
            mutedRepos = next.mutedRepos.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
            mutedKeywords = next.mutedKeywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            notifyIssues = next.notifyIssues,
            notifyPrs = next.notifyPrs,
        )
        config = normalized
        prefs?.edit()
            ?.putBoolean(KEY_MUTE_BOTS, normalized.muteBots)
            ?.putStringSet(KEY_MUTED_REPOS, normalized.mutedRepos)
            ?.putString(KEY_MUTED_KEYWORDS, normalized.mutedKeywords.joinToString("\n"))
            ?.putBoolean(KEY_NOTIFY_ISSUES, normalized.notifyIssues)
            ?.putBoolean(KEY_NOTIFY_PRS, normalized.notifyPrs)
            ?.apply()
    }

    fun isMuted(n: Notification, rules: NotificationMuteConfig = config): Boolean {
        if (!rules.isActive) return false
        val repo = n.repository.fullName.ifBlank {
            listOf(n.repository.owner.login, n.repository.name).filter { it.isNotBlank() }.joinToString("/")
        }.lowercase()
        if (repo.isNotEmpty() && repo in rules.mutedRepos) return true
        val title = n.subject.title
        if (rules.muteBots) {
            val lowerTitle = title.lowercase()
            val ownerLower = n.repository.owner.login.lowercase()
            if (botMarkers.any { it in lowerTitle || it in ownerLower }) return true
        }
        if (rules.mutedKeywords.any { kw -> title.contains(kw, ignoreCase = true) || repo.contains(kw, ignoreCase = true) }) {
            return true
        }
        return false
    }

    /** 系统后台轮询提醒判断：先过滤静音规则，再按 Issue / PullRequest 提醒开关决定（默认关闭） */
    fun shouldRemind(n: Notification, rules: NotificationMuteConfig = config): Boolean {
        if (isMuted(n, rules)) return false
        return when (n.subject.type) {
            "Issue" -> rules.notifyIssues
            "PullRequest" -> rules.notifyPrs
            else -> true
        }
    }
}
