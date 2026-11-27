package com.mobilegh.data

/**
 * 进程内的「每次启动只自动刷新一次」标记。
 *
 * 页面每次进入都会重新组合，但缓存内容应当直接显示、不必每次都请求网络。
 * 同一个 key 在一次启动里第一次进入时返回 true（允许静默刷新一次），之后都返回 false；
 * 重新启动应用后标记清空，下一次进入会再刷新一次。
 */
object ProcessRefreshClaims {
    private val claimed = HashSet<String>()

    @Synchronized
    fun claim(key: String): Boolean = claimed.add(key)

    @Synchronized
    internal fun clearForTest() = claimed.clear()
}
