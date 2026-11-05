package com.mobilegh.nav

/**
 * 剪贴板里的 GitHub 链接提示。
 * - 只认完整链接、无协议地址、owner/repo 或克隆地址，普通文字不提示；
 * - 同一条内容在进程内最多提示一次：系统会在应用获得焦点时反复暴露剪贴板，
 *   若只按内容变化提示，几乎每次回到前台都会弹出；
 * - 应用内自己复制（「复制链接」等）时当下不提示，但退到后台再回来时允许提示一次，
 *   和从其他应用复制后回到应用里的表现一致；
 * - 不同内容在应用回到前台后可以重新提示（例如用户在别的应用复制了新链接）。
 */
class ClipboardLinkTracker {
    data class Update(val link: Links.RepositoryLink?)

    private var lastTimestamp: Long? = null
    private var lastHash = 0
    /** 已经提示过的内容：同一条链接只提示一次，不因切换前后台反复弹出 */
    private var offeredHash: Int? = null
    /** 应用内复制、当下不提示的内容；退到后台后解除 */
    private var notedHash: Int? = null
    /** 本次前台会话是否已经提示过 */
    private var suggested = false

    fun inspect(timestamp: Long, texts: List<String>): Update? {
        val hash = texts.hashCode()
        if (timestamp == lastTimestamp && hash == lastHash) return null
        record(timestamp, hash)
        if (suggested || hash == offeredHash || hash == notedHash) return Update(null)
        val link = texts.firstNotNullOfOrNull(Links::clipboardLink) ?: return Update(null)
        suggested = true
        offeredHash = hash
        return Update(link)
    }

    /** 应用内自己复制的内容（“复制链接”等）：只记下内容，不给出跳转提示。 */
    fun note(timestamp: Long, texts: List<String>) {
        val hash = texts.hashCode()
        record(timestamp, hash)
        notedHash = hash
    }

    /**
     * 应用退到后台。应用内复制的内容不再处于「刚刚复制」的状态，下次回到前台可以提示一次；
     * 同时开始新的前台会话，允许提示在后台复制的新链接。
     */
    fun onBackground() {
        lastTimestamp = null
        lastHash = 0
        notedHash = null
        suggested = false
    }

    private fun record(timestamp: Long, hash: Int) {
        lastTimestamp = timestamp
        lastHash = hash
    }
}
