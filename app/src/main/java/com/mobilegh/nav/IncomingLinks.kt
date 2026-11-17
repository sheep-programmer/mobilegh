package com.mobilegh.nav

import android.content.Intent

object IncomingLinks {
    fun fromIntent(intent: Intent?, activeApiHost: String = "api.github.com"): Links.InputLink? = runCatching {
        when (intent?.action) {
            Intent.ACTION_VIEW -> view(intent.dataString, activeApiHost)
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(Links::sharedLink) else null
            else -> null
        }
    }.getOrNull()

    /** 独立接收 URL 字符串，避免 JVM 测试中 Android Intent stub 丢掉 dataString。 */
    fun view(data: String?, activeApiHost: String = "api.github.com"): Links.InputLink? =
        data?.let { Links.inputLink(it, activeApiHost) }

    /** 企业链接即使当前账号不匹配也保留原文，由 NavVm 守卫并提示，不在这里静默丢弃。 */
    fun enterpriseUrl(action: String?, data: String?): String? =
        data?.takeIf { action == Intent.ACTION_VIEW && Links.enterpriseHost(it) != null }
}
