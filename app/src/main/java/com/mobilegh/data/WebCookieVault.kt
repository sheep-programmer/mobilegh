package com.mobilegh.data

import java.net.URLDecoder

/**
 * GitHub 网页会话 Cookie 解析与多账号隔离校验。
 *
 * Android WebView 全局共享同一个 CookieManager；切换账号或添加新账号时，需要把
 * `https://github.com` 下的会话 Cookie 按账号加密快照保存并在切换时还原，避免跨账号串用。
 */
object WebCookieVault {
    private val HTTP_ONLY_NAMES = setOf("user_session", "__Host-user_session_same_site", "_gh_sess")

    fun parseCookiePairs(cookieHeader: String?): List<Pair<String, String>> {
        if (cookieHeader.isNullOrBlank()) return emptyList()
        return cookieHeader.split(';')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isEmpty()) null else name to value
            }
    }

    fun extractOwner(cookieHeader: String?): String? {
        val raw = parseCookiePairs(cookieHeader)
            .firstOrNull { it.first == "dotcom_user" }
            ?.second
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim().takeIf { it.isNotEmpty() }
    }

    fun hasValidUserSession(cookieHeader: String?): Boolean =
        parseCookiePairs(cookieHeader).any { it.first == "user_session" && it.second.isNotBlank() }

    /**
     * 判断 [cookieHeader] 是否包含有效的 `user_session`，并在同时存在 `dotcom_user` 且给出
     * [expectedLogin] 时确认二者属于同一账号，防止多账号切换瞬间或未登录态串用他人会话。
     */
    fun belongsTo(cookieHeader: String?, expectedLogin: String?): Boolean {
        if (!hasValidUserSession(cookieHeader)) return false
        if (expectedLogin.isNullOrBlank()) return true
        val owner = extractOwner(cookieHeader) ?: return true
        return owner.equals(expectedLogin.trim(), ignoreCase = true)
    }

    /** 将 `Cookie` 请求头拆成适用于 `CookieManager.setCookie("https://github.com", ...)` 的 Set-Cookie 字符串列表。 */
    fun toSetCookieDirectives(cookieHeader: String): List<String> =
        parseCookiePairs(cookieHeader).map { (name, value) ->
            buildString {
                append(name).append('=').append(value).append("; Path=/; Secure")
                if (name in HTTP_ONLY_NAMES) append("; HttpOnly")
            }
        }
}
