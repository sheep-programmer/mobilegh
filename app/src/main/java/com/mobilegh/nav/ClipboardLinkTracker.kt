package com.mobilegh.nav

/**
 * 剪贴板里的 GitHub 链接提示。
 * - 只认完整链接、无协议地址、owner/repo 或克隆地址，普通文字不提示；
 * - 每次从其他应用切回本应用都会提示一次，不管是不是同一条链接；
 * - 同一次前台会话内（比如弹窗拿走焦点又回来）不重复提示；
 * - 应用内自己复制（「复制链接」等）时当下不提示，但退到后台再回来时照常提示。
 */
class ClipboardLinkTracker {
    data class Update(val link: Links.RepositoryLink?)

    /** 本次前台会话是否已经处理过：提示过，或是应用内复制 */
    private var handled = false

    fun inspect(texts: List<String>): Update? {
        if (handled) return null
        val link = texts.firstNotNullOfOrNull(Links::clipboardLink) ?: return null
        handled = true
        return Update(link)
    }

    /** 应用内自己复制的内容：本次前台会话不提示，下次回到前台再提示。 */
    fun note(texts: List<String>) {
        handled = true
    }

    /** 应用退到后台：新的前台会话开始，下次回来可以再提示。 */
    fun onBackground() {
        handled = false
    }
}
