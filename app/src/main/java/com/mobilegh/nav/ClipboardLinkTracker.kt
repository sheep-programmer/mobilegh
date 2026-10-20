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
        lastTimestamp = timestamp
        lastHash = hash
        if (suggested) return Update(null)
        val link = texts.firstNotNullOfOrNull(Links::clipboardLink)
        if (link != null) suggested = true
        return Update(link)
    }
}
