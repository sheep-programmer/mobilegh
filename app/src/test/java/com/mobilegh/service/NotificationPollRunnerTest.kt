package com.mobilegh.service

import com.mobilegh.data.BackgroundNotificationConfig
import com.mobilegh.data.Notification
import com.mobilegh.data.Repo
import com.mobilegh.data.Subject
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPollRunnerTest {
    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val zone = ZoneId.of("UTC")
    private val thread = Notification(
        id = "42", unread = true, updatedAt = "2026-11-16T11:00:00Z",
        subject = Subject("Fix the bug", "https://api.github.com/repos/octo/hello/issues/42"),
        repository = Repo(fullName = "octo/hello"),
    )

    private suspend fun runPoll(
        config: () -> BackgroundNotificationConfig = { BackgroundNotificationConfig(enabled = true) },
        fetch: suspend () -> List<Notification> = { listOf(thread) },
        delivered: MutableList<String> = mutableListOf(),
    ) = NotificationPollRunner.run(
        config = config,
        login = { "octocat" },
        generation = { 3 },
        foreground = { false },
        fetch = fetch,
        notified = { emptyMap() },
        deliver = { delivered += it.id; true },
        record = {},
        now = { now },
        zone = { zone },
    )

    @Test fun disablingNotificationsWhileFetchIsInFlightSuppressesDelivery() {
        var enabled = true
        val delivered = mutableListOf<String>()
        runBlocking {
            runPoll(
                config = { BackgroundNotificationConfig(enabled = enabled) },
                fetch = { enabled = false; listOf(thread) },
                delivered = delivered,
            )
        }
        assertTrue(delivered.isEmpty())
    }

    @Test fun cancellationWhileFetchIsInFlightSuppressesDelivery() {
        val delivered = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runPoll(
                    fetch = {
                        currentCoroutineContext().cancel()
                        listOf(thread)
                    },
                    delivered = delivered,
                )
            }
        }
        assertTrue(delivered.isEmpty())
    }

    @Test fun enabledPollDeliversAndReturnsFreshThreads() {
        val delivered = mutableListOf<String>()
        runBlocking { runPoll(delivered = delivered) }
        assertEquals(listOf("42"), delivered)
    }
}
