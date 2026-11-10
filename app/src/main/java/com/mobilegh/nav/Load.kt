package com.mobilegh.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobilegh.data.ApiException
import com.mobilegh.data.AppLog
import com.mobilegh.data.Session
import com.mobilegh.data.networkHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

fun Throwable.friendly(): String = when (this) {
    is ApiException -> message
    // 解析失败通常是代理或接口返回了非预期内容：只给用户一句中文提示，原始错误留给日志
    is kotlinx.serialization.SerializationException -> tr("数据格式异常，请稍后重试")
    else -> networkHint() ?: message ?: javaClass.simpleName
}

/** 单个异步数据 */
class Loader<T>(private val scope: CoroutineScope, private val block: suspend (force: Boolean) -> T) {
    private val generation = Session.generation
    var data by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    private var job: Job? = null
    private var operation = 0

    fun load(force: Boolean = false) {
        if (Session.generation != generation) return
        val id = ++operation
        job?.cancel()
        loading = true
        refreshing = force
        error = null
        // Enter try/finally even when the owner cancels before the first dispatch.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.Default) {
                    if (Session.generation != generation) throw CancellationException("Account changed")
                    block(force)
                }
                if (id == operation && Session.generation == generation) { data = result; error = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (id == operation && Session.generation == generation) error = e.friendly()
            } finally {
                if (id == operation) { loading = false; refreshing = false }
            }
        }
    }

    fun accept(value: T) {
        operation++
        job?.cancel()
        data = value
        error = null
        loading = false
        refreshing = false
    }

    fun refresh() = load(true)

    /**
     * 静默刷新：强制取最新数据，但不切换 loading / refreshing，界面没有加载态（按钮不变灰、下拉圈不出现）。
     * 正在加载时跳过，避免和进行中的请求互相覆盖；失败时保留现有数据，不弹错误。
     */
    fun refreshSilently() {
        if (loading || Session.generation != generation) return
        val id = ++operation
        job?.cancel()
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.Default) {
                    if (Session.generation != generation) throw CancellationException("Account changed")
                    block(true)
                }
                if (id == operation && Session.generation == generation) data = result
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // 静默：保留已有数据
            }
        }
    }
}

/**
 * 一页结果及其元数据。[totalPages] 为数据源已知的总页数（如搜索结果的 total_count 换算），未知时为 null。
 * [onCommit] 只会在请求仍是最新时、于 Pager 所在主线程执行。
 */
data class PagerPage<T>(
    val items: List<T>,
    val hasMore: Boolean? = null,
    val totalPages: Int? = null,
    val onCommit: () -> Unit = {},
)

/** [Pager.loadAll] 默认的条目上限：不限制 */
const val PAGER_UNLIMITED = Int.MAX_VALUE

/** 分块加载每一步最多读取的页数 */
const val CHUNK_PAGES_PER_STEP = 3

/** 分块加载两步之间的停顿（毫秒），给列表留出渲染与滚动的时间 */
const val CHUNK_PAUSE_MS = 300L

/** GitHub 搜索 API 最多返回的结果条数（超过的页直接返回 422） */
const val GITHUB_SEARCH_RESULT_CAP = 1000

/**
 * 计算分块加载的一步要读取的页码区间（页码从 1 开始，闭区间）。
 * [nextPage] 为下一个未读页，[pagesPerStep] 为每步上限，[totalPages] 已知时不会越过最后一页。
 * 返回空区间表示没有可读的页。
 */
fun planChunk(nextPage: Int, pagesPerStep: Int, totalPages: Int?): IntRange {
    require(nextPage >= 1 && pagesPerStep >= 1) { "nextPage and pagesPerStep must be positive" }
    val last = (nextPage.toLong() + pagesPerStep - 1).coerceAtMost(Int.MAX_VALUE.toLong())
    val bound = totalPages?.let { minOf(it.toLong(), last) } ?: last
    return nextPage..bound.toInt()
}

/**
 * 分块加载是否应因条目上限而停止：已加载条目数达到上限，且服务端仍有下一页。
 * 没有下一页时即使超过上限也不算截断（列表已经读完）。
 */
fun chunkLimitReached(loaded: Int, maxItems: Int, hasNext: Boolean): Boolean = hasNext && loaded >= maxItems

/**
 * 失败后续读的页码：最后一次成功提交的页之后的那一页。失败的那一页没有提交，所以正好是它本身。
 */
fun resumePage(lastCommittedPage: Int): Int = lastCommittedPage.coerceAtLeast(0) + 1

/** GitHub 搜索在第 [page] 页之后是否还有可读的结果（上限取 [cap] 与 [totalCount] 的较小值） */
fun searchHasNextPage(page: Int, perPage: Int, totalCount: Int, cap: Int = GITHUB_SEARCH_RESULT_CAP): Boolean =
    page.toLong() * perPage < minOf(totalCount, cap)

/** GitHub 搜索可读的总页数（超过上限的部分不计入） */
fun searchTotalPages(perPage: Int, totalCount: Int, cap: Int = GITHUB_SEARCH_RESULT_CAP): Int {
    val readable = minOf(totalCount, cap).coerceAtLeast(0)
    return (readable + perPage - 1) / perPage
}

/** 分页列表 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int = 30,
    /** 服务端游标分页在过滤 null 节点后可能不足 pageSize，此时由数据源明确报告是否还有下一页。 */
    private val hasMore: ((page: Int) -> Boolean)? = null,
    private val fetchPage: (suspend (page: Int, force: Boolean) -> PagerPage<T>)? = null,
    private val fetch: suspend (page: Int, force: Boolean) -> List<T> = { _, _ -> error(tr("Pager 缺少数据源")) },
) {
    private val generation = Session.generation
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var end by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedOnce by mutableStateOf(false)
    private var page = 0
    private var job: Job? = null
    private var operation = 0
    private enum class Request { More, Refresh, All, Chunked }
    private var failed: Request? = null
    /** 最近一次请求的类型，供 [cancelLoadAll] 判断当前是否在全量加载 */
    private var active: Request? = null
    /** 全量加载的已加载条目上限；[retry] 沿用同一上限 */
    private var allLimit = PAGER_UNLIMITED
    /** 分块加载的每步页数与步间停顿；[retry] 沿用同一设置 */
    private var chunkPages = CHUNK_PAGES_PER_STEP
    private var chunkPauseMs = CHUNK_PAUSE_MS
    /** 最近一次提交的页所报告的总页数；未知时为 null */
    private var knownTotalPages: Int? = null

    /** 全量加载因达到条目上限而停止、后面仍有未读页时为 true；新请求开始时复位 */
    var truncated by mutableStateOf(false)
        private set

    /** 正在进行的是全量或分块加载（而非普通翻页与刷新），界面据此决定是否显示“停止”按钮 */
    val fullScanning: Boolean get() = loading && isFullScan(active)

    fun loadMore() {
        if (!loading && !end) request(Request.More)
    }

    fun refresh() = request(Request.Refresh)

    /**
     * 加载全部剩余页（用于本地搜索过滤）。[maxItems] 为已加载条目的上限：达到后停止翻页，
     * 并把 [truncated] 置为 true；已加载的条目与游标保留，之后仍可 [loadMore] 继续。默认不限制。
     */
    fun loadAll(maxItems: Int = PAGER_UNLIMITED) {
        if (!loading && !end) {
            allLimit = maxItems
            request(Request.All)
        }
    }

    /**
     * 分块加载剩余页（用于本地搜索过滤）：每步最多读取 [pagesPerStep] 页，步与步之间停顿 [pauseMs] 毫秒。
     * 每一页取回后立即加入列表，已加载的条目不会因后续某一块失败而丢弃；失败后 [error] 非空，
     * [retry] 从失败的那一页继续。[maxItems]、停止与 [truncated] 的语义与 [loadAll] 相同。
     */
    fun loadChunked(
        maxItems: Int = PAGER_UNLIMITED,
        pagesPerStep: Int = CHUNK_PAGES_PER_STEP,
        pauseMs: Long = CHUNK_PAUSE_MS,
    ) {
        if (!loading && !end) {
            allLimit = maxItems
            chunkPages = pagesPerStep
            chunkPauseMs = pauseMs
            request(Request.Chunked)
        }
    }

    /**
     * 停止正在进行的全量加载（搜索词变化、清空或用户点击停止时调用）。只取消 [loadAll] 与 [loadChunked]，
     * 不影响普通翻页与刷新；已加载的条目和页码保持不变，之后可再次加载从断点继续。
     */
    fun cancelLoadAll() {
        truncated = false
        if (!loading || !isFullScan(active)) return
        operation++
        job?.cancel()
        job = null
        loading = false
        refreshing = false
    }

    fun retry() {
        if (loading) return
        when (failed) {
            Request.Refresh -> refresh()
            Request.All -> loadAll(allLimit)
            Request.Chunked -> loadChunked(allLimit, chunkPages, chunkPauseMs)
            else -> loadMore()
        }
    }

    private fun isFullScan(kind: Request?) = kind == Request.All || kind == Request.Chunked

    private fun request(kind: Request) {
        if (Session.generation != generation) return
        val id = ++operation
        job?.cancel()
        loading = true
        refreshing = kind == Request.Refresh
        error = null
        failed = null
        truncated = false
        active = kind
        val limit = if (isFullScan(kind)) allLimit else PAGER_UNLIMITED
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (kind == Request.Chunked) {
                    scanChunks(id, limit)
                } else {
                    do {
                        // 全量加载达到上限且仍有下一页：停止翻页。不丢弃已加载条目，也不把 end 置位，之后仍可 loadMore。
                        if (items.size >= limit && !end) {
                            if (id == operation) truncated = true
                            break
                        }
                        // 刷新：旧条目保持可见，直到新的第一页取回并提交后才替换（失败则保留旧条目）
                        val next = if (kind == Request.Refresh) 1 else page + 1
                        if (!commitPage(id, next, kind)) return@launch
                    } while (kind == Request.All && !end)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (id == operation && Session.generation == generation) { error = e.friendly(); failed = kind }
            } finally {
                if (id == operation) {
                    loading = false
                    refreshing = false
                    loadedOnce = true
                }
            }
        }
    }

    /**
     * 分块加载：每步按 [planChunk] 依次读取最多 [chunkPages] 页，每页取回后立即提交，步与步之间停顿。
     * 任一页失败会抛出到 [request] 统一处理，此前提交的页保留，[resumePage] 决定从哪一页续读。
     */
    private suspend fun scanChunks(id: Int, limit: Int) {
        val pagesPerStep = chunkPages
        val pauseMs = chunkPauseMs
        var stepsDone = 0
        while (!end) {
            // 达到条目上限且仍有下一页：停止，已加载的条目与游标保留
            if (chunkLimitReached(items.size, limit, hasNext = true)) {
                if (id == operation) truncated = true
                return
            }
            val step = planChunk(resumePage(page), pagesPerStep, knownTotalPages)
            if (step.isEmpty()) return
            // 第一步立即开始（重试时也是），之后每步前停顿，让已提交的结果先渲染；取消会在 delay 处生效
            if (stepsDone++ > 0) delay(pauseMs)
            for (next in step) {
                if (!commitPage(id, next, Request.Chunked)) return
                if (end) return
                if (chunkLimitReached(items.size, limit, hasNext = true)) {
                    if (id == operation) truncated = true
                    return
                }
            }
        }
    }

    /**
     * 读取第 [next] 页并提交。请求已过期（被取消、切换账号或被本地写操作覆盖）时返回 false，且不改动任何状态。
     */
    private suspend fun commitPage(id: Int, next: Int, kind: Request): Boolean {
        val result = withContext(Dispatchers.Default) {
            if (Session.generation != generation) throw CancellationException("Account changed")
            fetchPage?.invoke(next, kind == Request.Refresh)
                ?: PagerPage(fetch(next, kind == Request.Refresh))
        }
        if (id != operation || Session.generation != generation) return false
        // 刷新：旧条目保持可见，直到新的第一页取回并提交后才替换（失败则保留旧条目）
        if (kind == Request.Refresh) items.clear()
        result.onCommit()
        items.addAll(result.items)
        page = next
        knownTotalPages = result.totalPages
        end = result.hasMore?.not() ?: hasMore?.invoke(next)?.not() ?: (result.items.size < pageSize)
        return true
    }

    /** 已确认的本地写操作会使旧响应失效，避免进行中的刷新把已删除条目加回来。 */
    fun update(transform: (MutableList<T>) -> Unit) {
        operation++
        job?.cancel()
        loading = false
        refreshing = false
        transform(items)
    }
}

@Composable
fun <T> rememberLoader(key: String, block: suspend (force: Boolean) -> T): Loader<T> =
    retain(key) { e -> Loader(e.scope, block).also { it.load() } }

@Composable
fun <T> rememberPager(
    key: String,
    pageSize: Int = 30,
    hasMore: ((page: Int) -> Boolean)? = null,
    fetchPage: (suspend (page: Int, force: Boolean) -> PagerPage<T>)? = null,
    fetch: suspend (page: Int, force: Boolean) -> List<T> = { _, _ -> error(tr("Pager 缺少数据源")) },
): Pager<T> = retain(key) { e -> Pager(e.scope, pageSize, hasMore, fetchPage, fetch).also { it.loadMore() } }

/**
 * 在页面作用域内执行一次性操作（如 star、合并），并返回错误提示。
 * 失败会写入诊断日志：HTTP 错误记一行摘要；网络错误记摘要；解析异常和其它未知异常记录堆栈。
 * [label] 是操作名（如「创建 Issue」），写进日志便于定位是哪一步出的问题。
 */
fun Entry.act(onError: (String) -> Unit = {}, onDone: () -> Unit = {}, label: String? = null, block: suspend () -> Unit) {
    scope.launch {
        try {
            block()
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logActionFailure(label, e)
            onError(e.friendly())
        }
    }
}

private fun logActionFailure(label: String?, e: Throwable) {
    val what = (label ?: tr("操作")) + " " + tr("失败")
    when (e) {
        is ApiException -> AppLog.warn("action", "$what：HTTP ${e.code} ${e.message}")
        is java.io.IOException -> AppLog.warn("action", "$what：${e.javaClass.simpleName}", e)
        // 其余都是意外情况（解析异常、空指针等），需要堆栈才能定位
        else -> AppLog.error("action", "$what：${e.javaClass.simpleName}", e)
    }
}

/**
 * 该仓库发生写入（提交文件、分支、Release、触发工作流）后刷新本页数据。
 * 记下页面创建时的版本，页面重新显示时版本变了才刷新，首次打开不会重复加载。
 */
@Composable
fun OnRepoChanged(owner: String, name: String, kind: String = com.mobilegh.data.RepoWrite.CODE, refresh: () -> Unit) {
    val v = com.mobilegh.data.RepoChanges.version(owner, name, kind)
    // 按调用位置区分：同一页面里多处监听同一种变化时，各自记录已处理的版本，不会一处刷新后另一处就跳过
    val site = androidx.compose.runtime.currentCompositeKeyHash
    val seen = retain("repo-change:$kind:$site") { intArrayOf(v) }
    androidx.compose.runtime.LaunchedEffect(v) {
        if (v != seen[0]) {
            seen[0] = v
            refresh()
        }
    }
}

/** 当前账号仍是打开此页面时的账号 */
fun Entry.sameAccount(): Boolean = Session.token != null && Session.generation == generation && Session.login == login

/**
 * 写操作（发评论、加星标、删除等）：发出前确认仍是打开页面时的账号；
 * 完成后再确认一次，账号已切换时不再把结果写回页面。
 */
fun <T> Entry.write(onError: (String) -> Unit = {}, onDone: (T) -> Unit = {}, block: suspend () -> T) {
    scope.launch {
        try {
            val result = guarded(block)
            onDone(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e.friendly())
        }
    }
}

/** 在调用方自己的协程里执行写操作，前后各确认一次账号未切换 */
suspend fun <T> Entry.guarded(block: suspend () -> T): T {
    check(sameAccount()) { tr("账号已切换，请重新打开页面") }
    val result = block()
    check(sameAccount()) { tr("账号已切换，旧账号的操作结果不再更新当前页面") }
    return result
}

/** 写入成功后刷新导航栈中其他页面保留的数据（如返回后的列表），只刷新符合条件的页面 */
fun Navigator.refreshRetained(match: (Screen) -> Boolean) {
    stack.forEach { e ->
        if (e != top && match(e.screen)) e.bag.values.forEach { v ->
            when (v) {
                is Pager<*> -> v.refresh()
                is Loader<*> -> v.load(true)
            }
        }
    }
}
