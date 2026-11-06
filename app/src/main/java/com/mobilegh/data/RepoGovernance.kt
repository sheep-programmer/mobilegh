package com.mobilegh.data

import com.mobilegh.i18n.tr
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream

/*
 * 仓库治理：安全告警（Dependabot / Code scanning / Secret scanning）、分支保护与规则集的只读查看，
 * 以及 PR 的自动合并开关。接口都是 GitHub REST / GraphQL 的公开接口，权限不足时由 Api 给出中文错误。
 */

/** 每类告警最多拉取的条数；更多的在网页上查看 */
internal const val GOVERNANCE_PAGE = 50

/** Release 单个附件的上限（GitHub 限制为 2 GiB） */
internal const val RELEASE_ASSET_MAX = 2L * 1024 * 1024 * 1024

@Serializable
data class AutoMergeInfo(val mergeMethod: String = "")

@Serializable
data class AdvisoryInfo(val summary: String? = null, val severity: String? = null, val cveId: String? = null)

@Serializable
data class AlertPackage(val ecosystem: String = "", val name: String = "")

@Serializable
data class AlertDependency(@SerialName("package") val pkg: AlertPackage? = null, val manifestPath: String? = null)

@Serializable
data class DependabotAlert(
    val number: Int = 0,
    val state: String = "",
    val htmlUrl: String = "",
    val createdAt: String? = null,
    val dependency: AlertDependency? = null,
    val securityAdvisory: AdvisoryInfo? = null,
    val securityVulnerability: AdvisoryInfo? = null,
)

@Serializable
data class ScanRule(val description: String? = null, val severity: String? = null, val securitySeverityLevel: String? = null)

@Serializable
data class ScanTool(val name: String = "")

@Serializable
data class ScanLocation(val path: String = "", val startLine: Int? = null)

@Serializable
data class ScanInstance(val location: ScanLocation? = null)

@Serializable
data class CodeScanningAlert(
    val number: Int = 0,
    val state: String = "",
    val htmlUrl: String = "",
    val createdAt: String? = null,
    val rule: ScanRule? = null,
    val tool: ScanTool? = null,
    val mostRecentInstance: ScanInstance? = null,
)

@Serializable
data class SecretAlert(
    val number: Int = 0,
    val state: String = "",
    val secretTypeDisplayName: String? = null,
    val htmlUrl: String = "",
    val createdAt: String? = null,
    val resolution: String? = null,
)

@Serializable
data class RuleToggle(val enabled: Boolean = false)

@Serializable
data class StatusCheckRule(val strict: Boolean = false, val contexts: List<String> = emptyList())

@Serializable
data class ReviewRule(
    val requiredApprovingReviewCount: Int = 0,
    val dismissStaleReviews: Boolean = false,
    val requireCodeOwnerReviews: Boolean = false,
)

/** 经典分支保护（GET /branches/{branch}/protection）；只保留界面用到的字段 */
@Serializable
data class BranchProtection(
    val requiredStatusChecks: StatusCheckRule? = null,
    val requiredPullRequestReviews: ReviewRule? = null,
    val enforceAdmins: RuleToggle? = null,
    val requiredLinearHistory: RuleToggle? = null,
    val allowForcePushes: RuleToggle? = null,
    val allowDeletions: RuleToggle? = null,
    val requiredConversationResolution: RuleToggle? = null,
)

@Serializable
data class Ruleset(
    val id: Long = 0,
    val name: String = "",
    val target: String? = null,
    val enforcement: String = "",
    val sourceType: String? = null,
)

/** 告警严重程度，统一了 Dependabot、Code scanning 的不同写法 */
enum class AlertLevel { Critical, High, Medium, Low, Unknown }

fun alertLevel(raw: String?): AlertLevel = when (raw?.trim()?.lowercase()) {
    "critical" -> AlertLevel.Critical
    "high" -> AlertLevel.High
    "medium", "moderate" -> AlertLevel.Medium
    "low" -> AlertLevel.Low
    else -> AlertLevel.Unknown
}

fun dependabotLevel(a: DependabotAlert): AlertLevel =
    alertLevel(a.securityVulnerability?.severity ?: a.securityAdvisory?.severity)

fun codeScanLevel(a: CodeScanningAlert): AlertLevel =
    alertLevel(a.rule?.securitySeverityLevel ?: a.rule?.severity)

enum class RulesetEnforcement { Active, Evaluate, Disabled, Unknown }

fun rulesetEnforcement(raw: String): RulesetEnforcement = when (raw.lowercase()) {
    "active" -> RulesetEnforcement.Active
    "evaluate" -> RulesetEnforcement.Evaluate
    "disabled" -> RulesetEnforcement.Disabled
    else -> RulesetEnforcement.Unknown
}

/** 自动合并的合并方式（GraphQL 枚举值） */
val AUTO_MERGE_METHODS = listOf("MERGE", "SQUASH", "REBASE")

internal const val ENABLE_AUTO_MERGE =
    "mutation(\$id: ID!, \$method: PullRequestMergeMethod!) { enablePullRequestAutoMerge(input: { pullRequestId: \$id, mergeMethod: \$method }) { clientMutationId } }"

internal const val DISABLE_AUTO_MERGE =
    "mutation(\$id: ID!) { disablePullRequestAutoMerge(input: { pullRequestId: \$id }) { clientMutationId } }"

/** Release 附件的上传地址：资产上传走 uploads.github.com，文件名需要编码 */
fun releaseAssetUploadUrl(owner: String, name: String, releaseId: Long, fileName: String): String =
    "https://uploads.github.com/repos/$owner/$name/releases/$releaseId/assets?name=${Api.q(fileName)}"

object RepoGovernance {
    private fun r(o: String, n: String) = "/repos/$o/$n"

    suspend fun dependabotAlerts(o: String, n: String, force: Boolean) =
        Api.get<List<DependabotAlert>>("${r(o, n)}/dependabot/alerts?state=open&per_page=$GOVERNANCE_PAGE", force)

    suspend fun codeScanningAlerts(o: String, n: String, force: Boolean) =
        Api.get<List<CodeScanningAlert>>("${r(o, n)}/code-scanning/alerts?state=open&per_page=$GOVERNANCE_PAGE", force)

    suspend fun secretScanningAlerts(o: String, n: String, force: Boolean) =
        Api.get<List<SecretAlert>>("${r(o, n)}/secret-scanning/alerts?state=open&per_page=$GOVERNANCE_PAGE", force)

    /** 分支没有经典保护规则时 GitHub 返回 404，这里返回 null；账号无权查看也会是 404，界面会一并说明 */
    suspend fun branchProtection(o: String, n: String, branch: String, force: Boolean): BranchProtection? = try {
        Api.get<BranchProtection>("${r(o, n)}/branches/${Api.encodePath(branch)}/protection", force)
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    suspend fun rulesets(o: String, n: String, force: Boolean) =
        Api.get<List<Ruleset>>("${r(o, n)}/rulesets?per_page=100", force)

    /** 开启自动合并（[method] 为 [AUTO_MERGE_METHODS] 之一），或取消（[method] 为 null） */
    suspend fun setAutoMerge(pullNodeId: String, method: String?) {
        if (method == null) {
            Api.graphql(DISABLE_AUTO_MERGE, mapOf("id" to pullNodeId))
        } else {
            require(method in AUTO_MERGE_METHODS) { tr("不支持的合并方式") }
            Api.graphql(ENABLE_AUTO_MERGE, mapOf("id" to pullNodeId, "method" to method))
        }
    }

    /** 上传一个 Release 附件，内容从 [open] 流式读取。返回 GitHub 创建好的附件信息。 */
    suspend fun uploadReleaseAsset(
        o: String,
        n: String,
        releaseId: Long,
        fileName: String,
        mediaType: String,
        size: Long,
        open: () -> InputStream,
    ): Asset {
        require(size <= RELEASE_ASSET_MAX) { tr("单个附件不能超过 2 GB") }
        val resp = Api.uploadRaw(releaseAssetUploadUrl(o, n, releaseId, fileName), mediaType, size, open)
        Api.ensureOk(resp)
        return Api.json.decodeFromString(resp.body)
    }
}
