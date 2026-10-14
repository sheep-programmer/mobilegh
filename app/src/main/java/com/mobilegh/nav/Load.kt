package com.mobilegh.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobilegh.data.ApiException
import com.mobilegh.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Throwable.friendly(): String = when (this) {
    is ApiException -> message
    is kotlinx.serialization.SerializationException -> "数据解析失败：${message?.take(160)}"
    else -> message ?: javaClass.simpleName
}

/** 单个异步数据 */
class Loader<T>(private val scope: CoroutineScope, private val block: suspend (force: Boolean) -> T) {
    var data by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    private var job: Job? = null

    fun load(force: Boolean = false) {
        job?.cancel()
        loading = true
        job = scope.launch {
            try {
                // 网络 + JSON 解析 + 高亮等全部在后台线程执行，避免卡主线程
                data = withContext(Dispatchers.Default) { block(force) }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.friendly()
            } finally {
                loading = false
                refreshing = false
            }
        }
    }

    fun refresh() {
        refreshing = true
        load(true)
    }
}

/** 分页列表 */
class Pager<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int = 30,
    private val fetch: suspend (page: Int, force: Boolean) -> List<T>,
) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var end by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loadedOnce by mutableStateOf(false)
    private var page = 0
    private var job: Job? = null

    fun loadMore() {
        if (loading || end) return
        loading = true
        val next = page + 1
        job = scope.launch {
            try {
                val list = withContext(Dispatchers.Default) { fetch(next, false) }
                items.addAll(list)
                page = next
                if (list.size < pageSize) end = true
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.friendly()
            } finally {
                loading = false
                loadedOnce = true
            }
        }
    }

    fun refresh() {
        job?.cancel()
        refreshing = true
        loading = true
        job = scope.launch {
            try {
                val list = withContext(Dispatchers.Default) { fetch(1, true) }
                items.clear()
                items.addAll(list)
                page = 1
                end = list.size < pageSize
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.friendly()
            } finally {
                loading = false
                refreshing = false
                loadedOnce = true
            }
        }
    }

    /** 加载全部剩余页（用于本地搜索过滤） */
    fun loadAll() {
        if (loading || end) return
        loading = true
        job = scope.launch {
            try {
                while (!end) {
                    val list = withContext(Dispatchers.Default) { fetch(page + 1, false) }
                    items.addAll(list)
                    page++
                    if (list.size < pageSize) end = true
                }
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.friendly()
            } finally {
                loading = false
                loadedOnce = true
            }
        }
    }

    fun update(transform: (MutableList<T>) -> Unit) = transform(items)
}

@Composable
fun <T> rememberLoader(key: String, block: suspend (force: Boolean) -> T): Loader<T> =
    retain(key) { e -> Loader(e.scope, block).also { it.load() } }

@Composable
fun <T> rememberPager(key: String, pageSize: Int = 30, fetch: suspend (page: Int, force: Boolean) -> List<T>): Pager<T> =
    retain(key) { e -> Pager(e.scope, pageSize, fetch).also { it.loadMore() } }

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
    check(sameAccount()) { "账号已切换，请重新打开页面" }
    val result = block()
    check(sameAccount()) { "账号已切换，旧账号的操作结果不再更新当前页面" }
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
