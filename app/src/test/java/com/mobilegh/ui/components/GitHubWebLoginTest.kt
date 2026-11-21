package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubWebLoginTest {
    @Test
    fun userCloseNotifiesDismissSoAnUnfinishedAuthorizationIsCancelled() {
        var dismissed = 0
        GitHubWebLogin.request("授权", "https://github.com/login/oauth/authorize", onDismiss = { dismissed++ })
        GitHubWebLogin.close()
        assertEquals(1, dismissed)
        assertNull(GitHubWebLogin.url)
    }

    @Test
    fun finishingAfterTheCallbackDoesNotCancelTheAuthorizationItJustStarted() {
        var dismissed = 0
        GitHubWebLogin.request("授权", "https://github.com/login/oauth/authorize", onDismiss = { dismissed++ })
        GitHubWebLogin.finish()
        assertEquals("finish must not fire onDismiss (it would cancel the token exchange)", 0, dismissed)
        assertNull(GitHubWebLogin.url)
        assertNull(GitHubWebLogin.title)
        // 之后再 close 也不应补发已丢弃的回调
        GitHubWebLogin.close()
        assertEquals(0, dismissed)
    }
}
