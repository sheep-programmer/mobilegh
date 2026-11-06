package com.mobilegh.data

/**
 * 仓库 Wiki 页面。Wiki 的每个页面是 git 仓库里的一个 Markdown 文件，
 * 可以直接从 raw.githubusercontent.com/wiki 取到原文，再用 /markdown 渲染，
 * 不需要退到浏览器。
 */
object Wiki {
    /** URL 里的页面名：空地址就是首页 Home；去掉偶尔带上的 .md */
    fun pageName(page: String?): String =
        page?.trim()?.takeIf { it.isNotEmpty() }?.removeSuffix(".md") ?: "Home"

    /** 页面在应用内显示时的标题（把连字符还原成空格） */
    fun title(page: String?): String = pageName(page).replace('-', ' ')

    fun rawUrl(owner: String, name: String, page: String? = null): String =
        "https://raw.githubusercontent.com/wiki/$owner/$name/${Api.encodePath(pageName(page))}.md"

    fun webUrl(owner: String, name: String, page: String? = null): String =
        "https://github.com/$owner/$name/wiki/${Api.encodePath(pageName(page))}"

    /** 渲染后的 HTML 里相对链接（图片、其他 Wiki 页面）的基准地址 */
    fun baseUrl(owner: String, name: String): String = "https://github.com/$owner/$name/wiki/"

    /** 当前页面的标题/正文用：把 [[页面]]、[[页面|显示文字]] 展开成正常的 Markdown 链接 */
    fun resolveLinks(markdown: String, owner: String, name: String): String {
        if (!markdown.contains("[[")) return markdown
        return WIKI_LINK.replace(markdown) { m ->
            val target = m.groupValues[1].trim()
            val label = m.groupValues[2].trim().ifEmpty { target }
            if (target.isEmpty()) return@replace m.value
            "[$label](https://github.com/$owner/$name/wiki/${Api.encodePath(target.replace(' ', '-'))})"
        }
    }

    suspend fun page(owner: String, name: String, page: String?, force: Boolean = false): String =
        Api.text(rawUrl(owner, name, page), accept = "text/plain", force = force)

    private val WIKI_LINK = Regex("""\[\[([^\[\]|]+)(?:\|([^\[\]]+))?]]""")
}
