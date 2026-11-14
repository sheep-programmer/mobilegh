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
    var value: T? = null
    var loadedAtMs = 0L
    /** 加载时的版本号（Star 写入或列表变化的计数）；之后版本变了，快照即过期 */
    var loadedRevision = -1L
}

/** 星标快照的进程内存放处。账号切换后快照会被丢弃，最多保留 [MAX_ENTRIES] 份。 */
object StarListCache {
    /** 快照超过这个时间才在重新进入页面时刷新；下拉刷新不受此限制 */
    const val MAX_AGE_MS = 5 * 60_000L
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

    /**
     * 快照是否过期：从未加载、超过 [MAX_AGE_MS]（恰好等于时不算过期），或版本已经变化。
     */
    fun isStale(loadedAtMs: Long, loadedRevision: Long, revision: Long, nowMs: Long): Boolean =
        loadedAtMs == 0L || loadedRevision != revision || nowMs - loadedAtMs > MAX_AGE_MS
}
