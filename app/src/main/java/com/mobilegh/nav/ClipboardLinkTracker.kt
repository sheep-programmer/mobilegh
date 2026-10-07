package com.mobilegh.nav

/** 只记住剪贴板版本和摘要：恢复焦点/重建页面不会重复提示，重新复制则可以再次提示。 */
class ClipboardLinkTracker {
    data class Update(val link: Links.RepositoryLink?)

    private var lastTimestamp: Long? = null
    private var lastHash = 0

    fun inspect(timestamp: Long, texts: List<String>): Update? {
        val hash = texts.hashCode()
        if (timestamp == lastTimestamp && hash == lastHash) return null
        lastTimestamp = timestamp
        lastHash = hash
        return Update(texts.firstNotNullOfOrNull(Links::repositoryInText))
    }
}
