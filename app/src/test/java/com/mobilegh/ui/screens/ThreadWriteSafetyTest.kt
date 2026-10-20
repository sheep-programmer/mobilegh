package com.mobilegh.ui.screens

import com.mobilegh.data.LocalNotifications
import com.mobilegh.data.Notification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadWriteSafetyTest {
    @Test fun quotePreservesExistingDraftIncludingWhitespace() {
        val draft = "My unfinished reply  \n"
        assertEquals(
            draft + "\n\n@alice 写道：\n\n> first\n> second\n\n",
            appendQuotedReply(draft, "alice", "first\nsecond"),
        )
        assertEquals("> quoted\n\n", appendQuotedReply("", null, "quoted"))
        assertEquals("draft\n\n> quoted\n\n", appendQuotedReply("draft\n\n", null, "quoted"))
    }

    @Test fun reactionCountsFollowServerStateWithoutDroppingOtherUsers() {
        assertEquals(4, settledReactionCount(3, false, true))
        assertEquals(3, settledReactionCount(3, true, true))
        assertEquals(2, settledReactionCount(3, true, false))
        // A pre-existing reaction unknown to this screen is removed by the server.
        assertEquals(2, settledReactionCount(3, false, false))
        assertEquals(0, settledReactionCount(0, false, false))
    }

    @Test fun failedCompletionRestoresLocalUnreadOverride() {
        val notification = Notification(id = "n", unread = false)
        val state = NotifState(LocalNotifications())
        state.markUnread(notification.id)
        state.pending[notification.id] = NotifState.Pending(wasRead = false, wasUnread = true)
        state.complete(notification)
        state.markRead(notification.id)
        assertFalse(state.unread(notification))
        state.restore(notification.id)
        assertTrue(state.unread(notification))
        assertTrue(state.done.isEmpty())
        assertTrue(state.doneItems.isEmpty())
        assertTrue(state.pending.isEmpty())
        assertFalse(notification.id in state.read)
    }

    @Test fun repeatedCompletionDoesNotDuplicateLocalRecords() {
        val state = NotifState(LocalNotifications())
        val notification = Notification(id = "n", unread = true)
        repeat(2) { state.complete(notification) }
        assertEquals(listOf("n"), state.done.toList())
        assertEquals(listOf(notification), state.doneItems.toList())
    }

    @Test fun reviewerRetryKeepsConfirmedAdditionAndRetriesOnlyRemoval() = runBlocking {
        val current = mutableListOf("alice")
        val requests = mutableListOf<List<String>>()
        val removals = mutableListOf<List<String>>()
        val confirmed = mutableListOf<List<String>>()
        try {
            updateReviewers(current, listOf("bob"), { requests.add(it) }, { error("denied") }, {
                confirmed.add(current.toList())
            })
            throw AssertionError("Removal failure was swallowed")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("alice", "bob"), current)
        }
        updateReviewers(current, listOf("bob"), { requests.add(it) }, { removals.add(it) }, {
            confirmed.add(current.toList())
        })
        assertEquals(listOf(listOf("alice", "bob"), listOf("bob")), confirmed)
        assertEquals(listOf(listOf("bob")), requests)
        assertEquals(listOf(listOf("alice")), removals)
        assertEquals(listOf("bob"), current)
    }

    @Test fun reviewerCancellationKeepsConfirmedAddition() = runBlocking {
        val current = mutableListOf("alice")
        val started = CompletableDeferred<Unit>()
        val operation = async {
            updateReviewers(current, listOf("bob"), {}, {
                started.complete(Unit)
                awaitCancellation()
            })
        }
        withTimeout(5000) { started.await() }
        operation.cancel()
        try {
            operation.await()
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(listOf("alice", "bob"), current)
        }
    }

    @Test fun bulkReportsPartialFailureAndKeepsSuccessfulWrites() = runBlocking {
        val committed = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val result = runBulk(listOf("ok", "failed", "also-ok"), { progress.add(it) }) { id ->
            if (id == "failed") error("denied")
            committed.add(id)
        }
        assertEquals(2, result.ok)
        assertEquals(1, result.failed)
        assertEquals(listOf("failed"), result.failedIds)
        assertEquals(setOf("ok", "also-ok"), committed.toSet())
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test fun bulkCancellationPropagatesWithoutErasingCompletedWrites() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val state = NotifState(LocalNotifications())
        val operation = async {
            runBulk(listOf("ok", "waiting"), {}) { id ->
                if (id == "ok") state.complete(Notification(id = id))
                else {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        withTimeout(5000) { started.await() }
        operation.cancel()
        try {
            operation.await()
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(listOf("ok"), state.done.toList())
        }
    }
}
