package com.mobilegh.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPollingTest {
    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val zone = ZoneId.of("UTC")
    private val config = BackgroundNotificationConfig(enabled = true)
    private val thread = Notification(
        id = "42", unread = true, updatedAt = "2026-11-16T11:00:00Z",
        subject = Subject("Fix the bug", "https://api.github.com/repos/octo/hello/issues/42"),
        repository = Repo(fullName = "octo/hello"),
    )

    private fun choose(
        settings: BackgroundNotificationConfig = config,
        login: String? = "octocat",
        currentLogin: String? = login,
        generation: Int = 3,
        currentGeneration: Int = generation,
        foreground: Boolean = false,
        threads: List<Notification> = listOf(thread),
        notified: Map<String, String> = emptyMap(),
    ) = NotificationPollDecision.choose(
        settings, login, currentLogin, generation, currentGeneration,
        foreground, now, zone, threads, notified,
    )

    @Test fun disabledForegroundAndOutsideWorkingHoursDoNotNotify() {
        assertTrue(choose(settings = config.copy(enabled = false)).isEmpty())
        assertTrue(choose(foreground = true).isEmpty())
        val hours = WorkingHours(true, 13 * 60, 14 * 60)
        assertTrue(choose(settings = config.copy(workingHours = hours)).isEmpty())
    }

    @Test fun accountOrGenerationSwitchDropsTheStaleResult() {
        assertTrue(choose(currentLogin = "another").isEmpty())
        assertTrue(choose(currentGeneration = 4).isEmpty())
        assertTrue(choose(login = null, currentLogin = null).isEmpty())
    }

    @Test fun unchangedThreadIsNotifiedOnlyOnce() {
        assertEquals(listOf("42"), choose().map { it.id })
        val sent = mapOf("42" to "2026-11-16T11:00:00Z")
        assertTrue(choose(notified = sent).isEmpty())
    }

    @Test fun changedUpdateTimeNotifiesTheSameThreadAgain() {
        val sent = mapOf("42" to "2026-11-16T10:00:00Z")
        assertEquals(listOf("42"), choose(notified = sent).map { it.id })
    }

    @Test fun readThreadsAreNotTurnedIntoSystemNotifications() {
        assertTrue(choose(threads = listOf(thread.copy(unread = false))).isEmpty())
    }
}
