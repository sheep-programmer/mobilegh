package com.mobilegh.data

import com.mobilegh.i18n.trf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 审查结论（reviewDecision）。NONE 表示仓库没有要求审查，列表不显示 */
enum class PullReviewState { APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED, NONE }

/** 最后一次提交的检查汇总（statusCheckRollup.state）。NONE 表示没有任何检查 */
enum class PullCiState { SUCCESS, FAILURE, PENDING, NONE }

data class PullStatus(val review: PullReviewState, val ci: PullCiState)

/** GraphQL 查询的构造与解析，纯函数，无网络依赖 */
object PullStatusQuery {
    const val NAME = "MobileGHPullStatuses"

    fun reviewStateOf(decision: String?): PullReviewState = when (decision) {
        "APPROVED" -> PullReviewState.APPROVED
        "CHANGES_REQUESTED" -> PullReviewState.CHANGES_REQUESTED
        "REVIEW_REQUIRED" -> PullReviewState.REVIEW_REQUIRED
        else -> PullReviewState.NONE
    }

    /** ERROR 与 FAILURE 同归为失败；EXPECTED 表示等待中的检查，归为进行中 */
    fun ciStateOf(rollupState: String?): PullCiState = when (rollupState) {
        "SUCCESS" -> PullCiState.SUCCESS
        "FAILURE", "ERROR" -> PullCiState.FAILURE
        "PENDING", "EXPECTED" -> PullCiState.PENDING
        else -> PullCiState.NONE
    }

    /**
     * 一页 PR 只发一个查询：每个 PR 用别名 p<number> 取 id、reviewDecision 与最后一次提交的检查状态。
     * 编号是整数，直接拼入查询；仓库的 owner / name 走变量。
     */
    fun build(numbers: List<Int>): String {
        val fields = numbers.joinToString(" ") { n ->
            "p$n: pullRequest(number: $n) { id reviewDecision commits(last: 1) { nodes { commit { statusCheckRollup { state } } } } }"
        }
        return "query $NAME(\$owner: String!, \$name: String!) { repository(owner: \$owner, name: \$name) { $fields } }"
    }

    /** 返回 PR 节点 id → 状态；缺少 id 或别名不存在的 PR 直接跳过 */
    fun parse(data: JsonObject, numbers: List<Int>): Map<String, PullStatus> {
        val repo = data.obj("repository") ?: return emptyMap()
        val out = LinkedHashMap<String, PullStatus>()
        for (n in numbers) {
            val pr = repo.obj("p$n") ?: continue
            val id = pr.str("id")?.takeIf { it.isNotBlank() } ?: continue
            val rollup = pr.obj("commits")?.arr("nodes")?.objects()?.firstOrNull()
                ?.obj("commit")?.obj("statusCheckRollup")
            out[id] = PullStatus(reviewStateOf(pr.str("reviewDecision")), ciStateOf(rollup.str("state")))
        }
        return out
    }
}

/**
 * PR 列表上的审查与 CI 状态。每翻一页只发一个 GraphQL 请求，在后台完成，不阻塞列表。
 * 任何失败（包括响应里带 errors）都静默忽略，该页行上就不显示状态。
 * 键为 "账号代次:PR 节点 id"，切换账号后旧数据自然不再命中。
 */
object PullRequestStatuses {
    private const val MAX_ENTRIES = 1000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = MutableStateFlow<Map<String, PullStatus>>(emptyMap())
    val flow: StateFlow<Map<String, PullStatus>> = store.asStateFlow()

    fun key(generation: Int, nodeId: String) = "$generation:$nodeId"

    /** 读取一页 PR 的状态并写入 [flow]；当前账号没有 token 或页面为空时不发请求 */
    fun loadPage(owner: String, name: String, pulls: List<Pull>) {
        val numbers = pulls.map { it.number }.filter { it > 0 }.distinct()
        if (numbers.isEmpty() || Session.token == null) return
        val account = Session.generation
        scope.launch {
            try {
                if (Session.generation != account) return@launch
                val root = Api.graphqlRoot(PullStatusQuery.build(numbers), mapOf("owner" to owner, "name" to name))
                // 严格模式：只要有 errors 就整页不显示，与列表读取的其他 GraphQL 调用保持一致
                if ((root["errors"] as? JsonArray)?.isNotEmpty() == true) return@launch
                if (Session.generation != account) return@launch
                val parsed = PullStatusQuery.parse(root["data"] as? JsonObject ?: return@launch, numbers)
                val keyed = parsed.mapKeys { key(account, it.key) }
                store.update { old ->
                    val merged = old + keyed
                    if (merged.size > MAX_ENTRIES) merged.entries.toList().takeLast(MAX_ENTRIES).associate { it.key to it.value }
                    else merged
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.warn("network", trf("PR 状态读取失败：{0}", e.message))
            }
        }
    }
}
