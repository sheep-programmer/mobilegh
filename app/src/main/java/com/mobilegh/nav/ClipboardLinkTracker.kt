package com.mobilegh.nav

/**
 * 剪贴板里的 GitHub 仓库链接提示。
 * - 只认完整链接、无协议地址、owner/repo 或克隆地址，普通文字不提示；
 * - 每次启动最多提示一次：系统会在应用获得焦点时反复暴露剪贴板，
 *   若按内容变化提示，几乎每次打开都会弹出。
 */
class ClipboardLinkTracker {
    data class Update(val link: Links.RepositoryLink?)

    private var lastTimestamp: Long? = null
    private var lastHash = 0
    private var suggested = false

    fun inspect(timestamp: Long, texts: List<String>): Update? {
        val hash = texts.hashCode()
        if (timestamp == lastTimestamp && hash == lastHash) return null
        record(timestamp, hash)
        if (suggested) return Update(null)
        val link = texts.firstNotNullOfOrNull(Links::clipboardLink)
        if (link != null) suggested = true
        return Update(link)
    }

    /** 应用内自己复制的内容（“复制链接”等）：只记下内容，不给出跳转提示。 */
    fun note(timestamp: Long, texts: List<String>) = record(timestamp, texts.hashCode())

    private fun record(timestamp: Long, hash: Int) {
        lastTimestamp = timestamp
        lastHash = hash
    }
}
