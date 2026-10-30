package com.mobilegh.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobilegh.data.ApiException
import com.mobilegh.data.Session
import com.mobilegh.data.networkHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

fun Throwable.friendly(): String = when (this) {
    is ApiException -> message
    is kotlinx.serialization.SerializationException -> trf("数据解析失败：{0}", message?.take(160))
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

/** 一页结果及其元数据。[onCommit] 只会在请求仍是最新时、于 Pager 所在主线程执行。 */
data class PagerPage<T>(
    val items: List<T>,
    val hasMore: Boolean? = null,
    val onCommit: () -> Unit = {},
)

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
    private enum class Request { More, Refresh, All }
    private var failed: Request? = null

    fun loadMore() {
        if (!loading && !end) request(Request.More)
    }

    fun refresh() = request(Request.Refresh)

    /** 加载全部剩余页（用于本地搜索过滤） */
    fun loadAll() {
        if (!loading && !end) request(Request.All)
    }

    fun retry() {
        if (loading) return
        when (failed) {
            Request.Refresh -> refresh()
            Request.All -> loadAll()
            else -> loadMore()
        }
    }

    private fun request(kind: Request) {
        if (Session.generation != generation) return
        val id = ++operation
        job?.cancel()
        loading = true
        refreshing = kind == Request.Refresh
        error = null
        failed = null
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                do {
                    val next = if (kind == Request.Refresh) 1 else page + 1
                    val result = withContext(Dispatchers.Default) {
                        if (Session.generation != generation) throw CancellationException("Account changed")
                        fetchPage?.invoke(next, kind == Request.Refresh)
                            ?: PagerPage(fetch(next, kind == Request.Refresh))
                    }
                    if (id != operation || Session.generation != generation) return@launch
                    if (kind == Request.Refresh) items.clear()
                    result.onCommit()
                    items.addAll(result.items)
                    page = next
                    end = result.hasMore?.not() ?: hasMore?.invoke(next)?.not() ?: (result.items.size < pageSize)
                } while (kind == Request.All && !end)
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

/** 在页面作用域内执行一次性操作（如 star、合并），并返回错误提示 */
fun Entry.act(onError: (String) -> Unit = {}, onDone: () -> Unit = {}, block: suspend () -> Unit) {
    scope.launch {
        try {
            block()
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError(e.friendly())
        }
    }
}

/**
 * 该仓库发生写入（提交文件、分支、Release、触发工作流）后刷新本页数据。
 * 记下页面创建时的版本，页面重新显示时版本变了才刷新，首次打开不会重复加载。
 */
@Composable
fun OnRepoChanged(owner: String, name: String, kind: String = com.mobilegh.data.RepoWrite.CODE, refresh: () -> Unit) {
    val v = com.mobilegh.data.RepoChanges.version(owner, name, kind)
    val seen = retain("repo-change:$kind") { intArrayOf(v) }
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
