package com.mobilegh.service

import com.mobilegh.data.BackgroundNotificationConfig
import com.mobilegh.data.Notification
import com.mobilegh.data.NotificationMuteRules
import com.mobilegh.data.NotificationPollDecision
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 一次后台轮询；请求结束后重新读取设置与会话，并在每次系统发布前响应取消。 */
internal object NotificationPollRunner {
    suspend fun run(
        config: () -> BackgroundNotificationConfig,
        login: () -> String?,
        generation: () -> Int,
        foreground: () -> Boolean,
        fetch: suspend () -> List<Notification>,
        notified: (String) -> Map<String, String>,
        deliver: (Notification) -> Boolean,
        record: (List<Notification>) -> Unit,
        now: () -> Instant = { Instant.now() },
        zone: () -> ZoneId = { ZoneId.systemDefault() },
    ) {
        val initialConfig = config()
        val initialLogin = login()
        val initialGeneration = generation()
        if (!initialConfig.enabled || initialLogin == null || foreground() ||
            !initialConfig.workingHours.allows(now(), zone())
        ) return

        val threads = fetch().filter { NotificationMuteRules.shouldRemind(it) }
        currentCoroutineContext().ensureActive()
        val fresh = NotificationPollDecision.choose(
            config(), initialLogin, login(), initialGeneration, generation(),
            foreground(), now(), zone(), threads, notified(initialLogin),
        )
        val sent = fresh.filter { thread ->
            currentCoroutineContext().ensureActive()
            // 长通知列表发布期间也允许开关、工作时间和账号状态立即生效。
            NotificationPollDecision.choose(
                config(), initialLogin, login(), initialGeneration, generation(),
                foreground(), now(), zone(), listOf(thread), emptyMap(),
            ).isNotEmpty() && deliver(thread)
        }
        currentCoroutineContext().ensureActive()
        if (sent.isNotEmpty() && initialLogin.equals(login(), ignoreCase = true) && initialGeneration == generation()) {
            record(sent)
        }
    }
}
