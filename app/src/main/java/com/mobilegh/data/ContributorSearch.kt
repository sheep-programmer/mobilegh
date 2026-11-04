package com.mobilegh.data

import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Page size used for GET /repos/{o}/{r}/contributors. */
internal const val CONTRIBUTOR_PAGE_SIZE = 100

/** Pause between background search-scan pages, so the scan never hammers the API. */
internal const val CONTRIBUTOR_SCAN_PAUSE_MILLIS = 300L

/** Trimmed, lowercase query. An empty result means the user is not searching. */
fun normalizeContributorQuery(query: String): String = query.trim().lowercase(Locale.ROOT)

/**
 * Case-insensitive substring match on login or display name. [needle] must already be normalized.
 * An empty needle matches everyone.
 */
fun contributorMatches(author: User?, needle: String): Boolean {
    if (needle.isEmpty()) return true
    return author?.login?.lowercase(Locale.ROOT)?.contains(needle) == true ||
        author?.name?.lowercase(Locale.ROOT)?.contains(needle) == true
}

/**
 * Whether another /contributors page exists. A Link header is authoritative when present;
 * without one, a full page is taken to mean there may be more. An empty page never has a next page.
 */
fun hasNextContributorPage(linkHeader: String?, pageRecordCount: Int, pageSize: Int = CONTRIBUTOR_PAGE_SIZE): Boolean {
    if (pageRecordCount == 0) return false
    return if (linkHeader != null) linkHeader.contains("rel=\"next\"") else pageRecordCount >= pageSize
}

enum class ContributorScanPhase { Off, Scanning, Stopped, Failed, Complete }

/**
 * The progressive contributor list and the background search scan over the same pages.
 * All transitions are pure; [ContributorPager] only sequences the network calls.
 */
data class ContributorListState(
    val records: List<ContributionRecord> = emptyList(),
    /** Next page to request; null once GitHub reports no further page. */
    val nextPage: Int? = null,
    /** A page request is in flight. */
    val busy: Boolean = false,
    /** The last page request failed. [nextPage] still points at that page, so retry resumes there. */
    val error: String? = null,
    /** Normalized search query; empty when not searching. */
    val query: String = "",
    /** The user pressed 停止检索 for the current query. */
    val stopped: Boolean = false,
    /**
     * 每页只是一段最近提交里的作者次数（超大仓库），同一个人会出现在多页：追加新页时按登录名合并并累加次数，
     * 而不是当作新的一行。普通的 /contributors 分页每人只出现一次，保持追加。
     */
    val mergeByLogin: Boolean = false,
) {
    val scanPhase: ContributorScanPhase
        get() = when {
            query.isEmpty() -> ContributorScanPhase.Off
            error != null -> ContributorScanPhase.Failed
            nextPage == null -> ContributorScanPhase.Complete
            stopped -> ContributorScanPhase.Stopped
            else -> ContributorScanPhase.Scanning
        }
}

/** Marks a page request as started, or returns null when none should start. */
fun ContributorListState.beginPage(): ContributorListState? =
    if (busy || error != null || nextPage == null) null else copy(busy = true)

/** Appends a loaded page and advances the cursor only when GitHub reports another page. */
fun ContributorListState.finishPage(page: ContributorPage): ContributorListState = copy(
    records = if (mergeByLogin) mergeAuthorCounts(records, page.records) else records + page.records,
    nextPage = nextPage?.takeIf { page.hasNext }?.plus(page.pagesRead),
    busy = false,
    error = null,
)

/**
 * 把新一页的作者次数并入已有列表：登录名不区分大小写，同一人的次数相加，保留首次出现时的资料；
 * 保持首次出现的先后顺序，不按次数重排（滚动加载时已显示的行不能换位）。没有登录名的记录无法合并，原样追加。
 */
fun mergeAuthorCounts(existing: List<ContributionRecord>, incoming: List<ContributionRecord>): List<ContributionRecord> {
    val merged = LinkedHashMap<String, ContributionRecord>()
    val unlinked = ArrayList<ContributionRecord>()
    for (record in existing + incoming) {
        val login = record.author?.login?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
        if (login == null) { unlinked += record; continue }
        val previous = merged[login]
        merged[login] = if (previous == null) record
            else previous.copy(total = (previous.total ?: 0L) + (record.total ?: 0L))
    }
    return merged.values + unlinked
}

/** Keeps everything loaded so far and remembers the failure; the cursor is unchanged. */
fun ContributorListState.failPage(message: String): ContributorListState = copy(busy = false, error = message)

/** A request was cancelled before it produced a result; nothing changes except the busy flag. */
fun ContributorListState.abandonPage(): ContributorListState = copy(busy = false)

/** A new query starts a fresh scan, which is not stopped. The same normalized query changes nothing. */
fun ContributorListState.withQuery(query: String): ContributorListState {
    val normalized = normalizeContributorQuery(query)
    return if (normalized == this.query) this else copy(query = normalized, stopped = false)
}

/** 停止检索: only meaningful while a scan is actually running. */
fun ContributorListState.stopScan(): ContributorListState =
    if (scanPhase == ContributorScanPhase.Scanning) copy(stopped = true) else this

/** 重试 or 继续检索: clears the failure and any stop, so the next request can start. */
fun ContributorListState.resume(): ContributorListState = copy(error = null, stopped = false)

/**
 * Owns the progressive contributor list for one screen. Scroll-driven [loadNext] and the background
 * search scan share [gate], so at most one /contributors request is ever in flight.
 */
class ContributorPager(
    private val scope: CoroutineScope,
    initial: ContributorListState,
    private val fetchPage: suspend (Int) -> ContributorPage,
    private val pauseMillis: Long = CONTRIBUTOR_SCAN_PAUSE_MILLIS,
) {
    private val gate = Mutex()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<ContributorListState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var pageJob: Job? = null

    /** Called whenever the search text changes. A changed query cancels the scan and restarts it over the cached pages. */
    fun setQuery(query: String) {
        val before = _state.value
        if (normalizeContributorQuery(query) == before.query) return
        scanJob?.cancel()
        scanJob = null
        _state.value = before.withQuery(query)
        if (_state.value.scanPhase == ContributorScanPhase.Scanning) launchScan()
    }

    /** 停止检索 pressed. Any request already running is cancelled; the loaded pages are kept. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.stopScan()
    }

    /** 重试 after a failed page, or 继续检索 after stopping. Resumes from the page that failed. */
    fun resume() {
        _state.value = _state.value.resume()
        if (_state.value.scanPhase == ContributorScanPhase.Scanning) launchScan() else loadNext()
    }

    /** Scroll near the end, or the 加载更多 footer. Does nothing if a page is already loading or nothing is left. */
    fun loadNext() {
        if (_state.value.beginPage() == null) return
        if (!gate.tryLock()) return
        // UNDISPATCHED guarantees the body runs, so the gate is always released in finally.
        pageJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                fetchLocked()
            } finally {
                gate.unlock()
            }
        }
    }

    /** Releases any running request or scan. Called when the screen leaves or the list is replaced. */
    fun close() {
        scanJob?.cancel()
        pageJob?.cancel()
        scanJob = null
        pageJob = null
    }

    private fun launchScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            while (_state.value.scanPhase == ContributorScanPhase.Scanning) {
                if (!gate.withLock { fetchLocked() }) break
                if (_state.value.scanPhase != ContributorScanPhase.Scanning) break
                delay(pauseMillis)
            }
        }
    }

    /** Must be called while holding [gate]. Returns true when a page was appended. */
    private suspend fun fetchLocked(): Boolean {
        val begun = _state.value.beginPage() ?: return false
        val page = begun.nextPage ?: return false
        _state.value = begun
        return try {
            val result = fetchPage(page)
            _state.value = _state.value.finishPage(result)
            true
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.abandonPage()
            throw cancelled
        } catch (error: Exception) {
            _state.value = _state.value.failPage(
                trf("第 {0} 页加载失败：{1}", page, error.message?.take(180) ?: tr("请求失败")),
            )
            false
        }
    }
}
