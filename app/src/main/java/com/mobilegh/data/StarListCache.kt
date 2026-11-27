package com.mobilegh.data

/**
 * 一份星标列表的快照。页面每次进入都会新建 Entry，页面自身的状态随之丢失；
 * 快照放在进程内，离开再回来时可以直接显示上次的结果，不必重新请求。
 */
class StarSnapshot<T>(val generation: Int) {
    /** 游标分页的游标，按页码保存；与分页器共用，续读下一页时需要 */
    val cursors = HashMap<Int, String?>()
    /** 已加载的条目（星标仓库列表） */
    var items: List<T> = emptyList()
    /** 已提交的页数；0 表示从未成功加载过 */
    var pages = 0
    var ended = false
    /** 单个值（Star 列表目录） */
    @Volatile var value: T? = null
    var loadedAtMs = 0L
    /** 加载时的版本号（Star 写入或列表变化的计数）；之后版本变了，快照即过期 */
    @Volatile var loadedRevision = -1L
    var silentRetryAfterMs = 0L
}

/** 星标快照的进程内存放处。账号切换后快照会被丢弃，最多保留 [MAX_ENTRIES] 份。 */
object StarListCache {
    const val SILENT_RETRY_BACKOFF_MS = 30_000L
    private const val MAX_ENTRIES = 16

    /** Star / Unstar 成功后递增；与快照记录的版本不同就视为过期 */
    @Volatile
    var starRevision = 0L
        private set

    private val snapshots = object : LinkedHashMap<String, StarSnapshot<*>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StarSnapshot<*>>?) = size > MAX_ENTRIES
    }

    /** 取出同一个账号的快照；账号不同则换成新的，旧账号的数据不会泄露给新账号。 */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T> snapshot(key: String, generation: Int): StarSnapshot<T> {
        val found = snapshots[key]
        if (found != null && found.generation == generation) return found as StarSnapshot<T>
        return StarSnapshot<T>(generation).also { snapshots[key] = it }
    }

    /** 一次 Star 或 Unstar 写入成功：所有星标快照都需要重新读取 */
    @Synchronized
    fun onStarChanged() {
        starRevision++
    }

    @Synchronized
    internal fun clearForTest() {
        snapshots.clear()
    }

    /**
     * 快照是否过期：只看版本。加载后没有 Star / 列表变化，数据就仍然有效，不因为时间流逝而重新请求。
     * 从未加载的快照 [StarSnapshot.loadedRevision] 为 -1，必然视为过期。
     */
    fun isStale(loadedRevision: Long, revision: Long): Boolean = loadedRevision != revision

    fun canAttemptSilentRefresh(snapshot: StarSnapshot<*>, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs >= snapshot.silentRetryAfterMs

    /**
     * 进入页面时是否静默刷新。有缓存、且「本次启动第一次进入」（[claimed]）或版本已变，才刷新；
     * 其余情况直接显示缓存，不发请求。决定刷新时记下退避时间，失败也不会每次进入都重试。
     * 调用方必须先取 [ProcessRefreshClaims.claim]，保证每次启动的第一次进入都消耗掉这个标记。
     */
    fun takeEntryRefresh(
        snapshot: StarSnapshot<*>,
        hasData: Boolean,
        claimed: Boolean,
        revision: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!hasData) return false
        if (!claimed && !isStale(snapshot.loadedRevision, revision)) return false
        if (!canAttemptSilentRefresh(snapshot, nowMs)) return false
        snapshot.silentRetryAfterMs = nowMs + SILENT_RETRY_BACKOFF_MS
        return true
    }
}
