package com.mobilegh.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// 说明：REST 使用 SnakeCase 命名策略，属性名写 camelCase 即可；
// 必须用 @SerialName 的地方，值本身保持 snake_case。

@Serializable
data class User(
    val login: String = "",
    val id: Long = 0,
    val avatarUrl: String = "",
    val htmlUrl: String = "",
    val type: String = "User",
    val name: String? = null,
    val company: String? = null,
    val blog: String? = null,
    val location: String? = null,
    val email: String? = null,
    val bio: String? = null,
    val twitterUsername: String? = null,
    val publicRepos: Int = 0,
    val publicGists: Int = 0,
    val followers: Int = 0,
    val following: Int = 0,
    val createdAt: String? = null,
    val totalPrivateRepos: Int? = null,
    val ownedPrivateRepos: Int? = null,
    val privateGists: Int? = null,
    val diskUsage: Long? = null,
    val twoFactorAuthentication: Boolean? = null,
    val plan: Plan? = null,
    val siteAdmin: Boolean = false,
    val hireable: Boolean? = null,
    // 仅协作者接口
    val roleName: String? = null,
    val contributions: Int? = null,
    val description: String? = null,
)

@Serializable
data class Plan(val name: String = "", val privateRepos: Long = 0)

@Serializable
data class License(val key: String = "", val name: String = "", val spdxId: String? = null)

@Serializable
data class Permissions(
    val admin: Boolean = false,
    val maintain: Boolean = false,
    val push: Boolean = false,
    val triage: Boolean = false,
    val pull: Boolean = false,
)

@Serializable
data class Repo(
    val id: Long = 0,
    val name: String = "",
    val fullName: String = "",
    val owner: User = User(),
    @SerialName("private") val isPrivate: Boolean = false,
    val htmlUrl: String = "",
    val description: String? = null,
    val fork: Boolean = false,
    val language: String? = null,
    val stargazersCount: Int = 0,
    val watchersCount: Int = 0,
    val forksCount: Int = 0,
    val openIssuesCount: Int = 0,
    val subscribersCount: Int? = null,
    val defaultBranch: String = "main",
    val topics: List<String> = emptyList(),
    val archived: Boolean = false,
    val disabled: Boolean = false,
    val visibility: String? = null,
    val pushedAt: String? = null,
    val updatedAt: String? = null,
    val createdAt: String? = null,
    val homepage: String? = null,
    val size: Long = 0,
    val license: License? = null,
    val permissions: Permissions? = null,
    val parent: Repo? = null,
    val hasIssues: Boolean = true,
    val hasWiki: Boolean = false,
    val hasProjects: Boolean = false,
    val hasDiscussions: Boolean = false,
    val isTemplate: Boolean = false,
    val allowForking: Boolean = true,
    val cloneUrl: String? = null,
    val sshUrl: String? = null,
)

@Serializable
data class Label(val id: Long = 0, val name: String = "", val color: String = "888888", val description: String? = null)

@Serializable
data class Milestone(val number: Int = 0, val title: String = "", val state: String = "open")

@Serializable
data class PrRef(val url: String? = null, val htmlUrl: String? = null, val mergedAt: String? = null)

@Serializable
data class Reactions(
    val totalCount: Int = 0,
    @SerialName("+1") val plusOne: Int = 0,
    @SerialName("-1") val minusOne: Int = 0,
    val laugh: Int = 0,
    val hooray: Int = 0,
    val confused: Int = 0,
    val heart: Int = 0,
    val rocket: Int = 0,
    val eyes: Int = 0,
) {
    fun count(content: String): Int = when (content) {
        "+1" -> plusOne; "-1" -> minusOne; "laugh" -> laugh; "hooray" -> hooray
        "confused" -> confused; "heart" -> heart; "rocket" -> rocket; "eyes" -> eyes
        else -> 0
    }
}

@Serializable
data class ReactionResp(val id: Long = 0, val content: String = "")

@Serializable
data class Assignable(val login: String = "", val avatarUrl: String = "")

@Serializable
data class Issue(
    val id: Long = 0,
    val number: Int = 0,
    val title: String = "",
    val user: User? = null,
    val state: String = "open",
    val stateReason: String? = null,
    val labels: List<Label> = emptyList(),
    val assignees: List<User> = emptyList(),
    val milestone: Milestone? = null,
    val comments: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val closedAt: String? = null,
    val body: String? = null,
    val bodyHtml: String? = null,
    val pullRequest: PrRef? = null,
    val htmlUrl: String = "",
    val locked: Boolean = false,
    val draft: Boolean = false,
    val repositoryUrl: String? = null,
    val authorAssociation: String? = null,
    val reactions: Reactions? = null,
)

@Serializable
data class Comment(
    val id: Long = 0,
    val user: User? = null,
    val body: String? = null,
    val bodyHtml: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val authorAssociation: String? = null,
    val htmlUrl: String = "",
    val reactions: Reactions? = null,
)

@Serializable
data class PrBranch(val label: String = "", val ref: String = "", val sha: String = "", val repo: Repo? = null)

@Serializable
data class Pull(
    val id: Long = 0,
    val number: Int = 0,
    val title: String = "",
    val user: User? = null,
    val state: String = "open",
    val body: String? = null,
    val bodyHtml: String? = null,
    val draft: Boolean = false,
    val merged: Boolean = false,
    val mergeable: Boolean? = null,
    val mergeableState: String? = null,
    val mergedAt: String? = null,
    val closedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val head: PrBranch = PrBranch(),
    val base: PrBranch = PrBranch(),
    val additions: Int = 0,
    val deletions: Int = 0,
    val changedFiles: Int = 0,
    val commits: Int = 0,
    val comments: Int = 0,
    val reviewComments: Int = 0,
    val labels: List<Label> = emptyList(),
    val htmlUrl: String = "",
    val mergedBy: User? = null,
    val requestedReviewers: List<User> = emptyList(),
    val authorAssociation: String? = null,
)

@Serializable
data class Review(
    val id: Long = 0,
    val user: User? = null,
    val body: String? = null,
    val bodyHtml: String? = null,
    val state: String = "",
    val submittedAt: String? = null,
)

@Serializable
data class CommitFile(
    val filename: String = "",
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null,
    val previousFilename: String? = null,
    val sha: String? = null,
)

@Serializable
data class GitActor(val name: String? = null, val email: String? = null, val date: String? = null)

@Serializable
data class CommitInfo(val message: String = "", val author: GitActor? = null, val committer: GitActor? = null, val commentCount: Int = 0)

@Serializable
data class CommitStats(val total: Int = 0, val additions: Int = 0, val deletions: Int = 0)

@Serializable
data class ShaRef(val sha: String = "", val url: String? = null)

@Serializable
data class Commit(
    val sha: String = "",
    val commit: CommitInfo = CommitInfo(),
    val author: User? = null,
    val committer: User? = null,
    val htmlUrl: String = "",
    val stats: CommitStats? = null,
    val files: List<CommitFile>? = null,
    val parents: List<ShaRef> = emptyList(),
)

@Serializable
data class Branch(val name: String = "", val protected: Boolean = false, val commit: ShaRef = ShaRef())

@Serializable
data class Tag(val name: String = "", val commit: ShaRef = ShaRef())

@Serializable
data class Content(
    val name: String = "",
    val path: String = "",
    val sha: String = "",
    val size: Long = 0,
    val type: String = "file",
    val downloadUrl: String? = null,
    val htmlUrl: String? = null,
    val content: String? = null,
    val encoding: String? = null,
    val submoduleGitUrl: String? = null,
)

@Serializable
data class Asset(
    val id: Long = 0,
    val name: String = "",
    val size: Long = 0,
    val downloadCount: Int = 0,
    val browserDownloadUrl: String = "",
    val contentType: String? = null,
)

@Serializable
data class Release(
    val id: Long = 0,
    val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val bodyHtml: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val createdAt: String? = null,
    val publishedAt: String? = null,
    val author: User? = null,
    val assets: List<Asset> = emptyList(),
    val htmlUrl: String = "",
)

@Serializable
data class Subject(val title: String = "", val url: String? = null, val latestCommentUrl: String? = null, val type: String = "")

@Serializable
data class Notification(
    val id: String = "",
    val unread: Boolean = false,
    val reason: String = "",
    val updatedAt: String? = null,
    val subject: Subject = Subject(),
    val repository: Repo = Repo(),
)

@Serializable
data class Run(
    val id: Long = 0,
    val name: String? = null,
    val displayTitle: String? = null,
    val headBranch: String? = null,
    val headSha: String = "",
    val event: String = "",
    val status: String? = null,
    val conclusion: String? = null,
    val runNumber: Int = 0,
    val runAttempt: Int = 1,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val runStartedAt: String? = null,
    val actor: User? = null,
    val htmlUrl: String = "",
    val workflowId: Long = 0,
    val path: String? = null,
)

@Serializable
data class Runs(val totalCount: Int = 0, val workflowRuns: List<Run> = emptyList())

@Serializable
data class Step(val name: String = "", val status: String = "", val conclusion: String? = null, val number: Int = 0, val startedAt: String? = null, val completedAt: String? = null)

@Serializable
data class Job(
    val id: Long = 0,
    val name: String = "",
    val status: String = "",
    val conclusion: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val steps: List<Step> = emptyList(),
    val htmlUrl: String? = null,
)

@Serializable
data class Jobs(val totalCount: Int = 0, val jobs: List<Job> = emptyList())

@Serializable
data class Workflow(val id: Long = 0, val name: String = "", val path: String = "", val state: String = "", val htmlUrl: String = "")

@Serializable
data class Workflows(val totalCount: Int = 0, val workflows: List<Workflow> = emptyList())

@Serializable
data class Artifact(val id: Long = 0, val name: String = "", val sizeInBytes: Long = 0, val expired: Boolean = false, val createdAt: String? = null)

@Serializable
data class Artifacts(val totalCount: Int = 0, val artifacts: List<Artifact> = emptyList())

@Serializable
data class SearchResult<T>(val totalCount: Int = 0, val incompleteResults: Boolean = false, val items: List<T> = emptyList())

@Serializable
data class Org(
    val login: String = "",
    val id: Long = 0,
    val avatarUrl: String = "",
    val description: String? = null,
    val name: String? = null,
    val blog: String? = null,
    val location: String? = null,
    val email: String? = null,
    val publicRepos: Int = 0,
    val followers: Int = 0,
    val htmlUrl: String? = null,
    val totalPrivateRepos: Int? = null,
    val isVerified: Boolean = false,
)

@Serializable
data class Invitation(
    val id: Long = 0,
    val repository: Repo = Repo(),
    val inviter: User? = null,
    val permissions: String = "",
    val createdAt: String? = null,
    val expired: Boolean = false,
)

@Serializable
data class OrgMembership(val state: String = "", val role: String = "", val organization: Org = Org())

@Serializable
data class EventActor(val login: String = "", val displayLogin: String? = null, val avatarUrl: String = "")

@Serializable
data class EventRepo(val name: String = "")

@Serializable
data class Event(
    val id: String = "",
    val type: String? = null,
    val actor: EventActor = EventActor(),
    val repo: EventRepo = EventRepo(),
    val payload: JsonObject = JsonObject(emptyMap()),
    val public: Boolean = true,
    val createdAt: String? = null,
)

@Serializable
data class TrafficPoint(val timestamp: String = "", val count: Int = 0, val uniques: Int = 0)

@Serializable
data class Traffic(val count: Int = 0, val uniques: Int = 0, val views: List<TrafficPoint>? = null, val clones: List<TrafficPoint>? = null)

@Serializable
data class Referrer(val referrer: String = "", val count: Int = 0, val uniques: Int = 0)

@Serializable
data class PopularPath(val path: String = "", val title: String = "", val count: Int = 0, val uniques: Int = 0)

@Serializable
data class WeekActivity(val days: List<Int> = emptyList(), val total: Int = 0, val week: Long = 0)

@Serializable
data class GistFile(val filename: String = "", val type: String? = null, val language: String? = null, val rawUrl: String = "", val size: Long = 0, val content: String? = null, val truncated: Boolean = false)

@Serializable
data class Gist(
    val id: String = "",
    val description: String? = null,
    val htmlUrl: String = "",
    val files: Map<String, GistFile> = emptyMap(),
    val public: Boolean = true,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val owner: User? = null,
    val comments: Int = 0,
)

@Serializable
data class RateItem(val limit: Int = 0, val remaining: Int = 0, val reset: Long = 0, val used: Int = 0)

@Serializable
data class RateLimit(val resources: Map<String, RateItem> = emptyMap())

// ---------- GraphQL（camelCase，用 Api.plain 解析） ----------

@Serializable
data class ContribDay(val date: String = "", val contributionCount: Int = 0, val contributionLevel: String = "NONE", val weekday: Int = 0)

@Serializable
data class ContribWeek(val contributionDays: List<ContribDay> = emptyList())

@Serializable
data class ContribCalendar(val totalContributions: Int = 0, val weeks: List<ContribWeek> = emptyList())

@Serializable
data class GqlRepoRef(val nameWithOwner: String = "", val isPrivate: Boolean = false)

@Serializable
data class GqlCount(val totalCount: Int = 0)

@Serializable
data class RepoContrib(val repository: GqlRepoRef = GqlRepoRef(), val contributions: GqlCount = GqlCount())

@Serializable
data class Contributions(
    val contributionYears: List<Int> = emptyList(),
    val totalCommitContributions: Int = 0,
    val totalIssueContributions: Int = 0,
    val totalPullRequestContributions: Int = 0,
    val totalPullRequestReviewContributions: Int = 0,
    val totalRepositoryContributions: Int = 0,
    val restrictedContributionsCount: Int = 0,
    val contributionCalendar: ContribCalendar = ContribCalendar(),
    val commitContributionsByRepository: List<RepoContrib> = emptyList(),
)

@Serializable
data class GqlLang(val name: String = "", val color: String? = null)

@Serializable
data class GqlOwner(val login: String = "")

@Serializable
data class PinnedRepo(
    val name: String = "",
    val owner: GqlOwner = GqlOwner(),
    val description: String? = null,
    val stargazerCount: Int = 0,
    val forkCount: Int = 0,
    val isPrivate: Boolean = false,
    val primaryLanguage: GqlLang? = null,
)
