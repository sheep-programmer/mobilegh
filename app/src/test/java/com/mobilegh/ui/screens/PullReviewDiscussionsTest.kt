package com.mobilegh.ui.screens

import com.mobilegh.data.ReviewComment
import com.mobilegh.data.ThreadAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PullReviewDiscussionsTest {
    private val author = ThreadAccess(write = false, triage = false, author = true)

    @Test fun countsConfirmedUnresolvedThreadsByPathEvenWhenTheirCodeIsOutdated() {
        val comments = listOf(
            ReviewComment(id = 1, path = "src/A.kt", line = 10, threadId = "t1"),
            ReviewComment(id = 2, path = "src/A.kt", line = 10, inReplyToId = 1, threadId = "t1"),
            ReviewComment(id = 3, path = "src/A.kt", subjectType = "file", threadId = "t3"),
            ReviewComment(id = 4, path = "src/A.kt", line = 12, threadId = "t4", resolved = true),
            ReviewComment(id = 5, path = "src/Removed.kt", originalLine = 15, threadId = "t5"),
            ReviewComment(id = 6, path = "src/A.kt", line = 13),
            ReviewComment(id = 7, path = "src/B.kt", line = 13, threadId = ""),
        )
        val discussions = PullReviewDiscussions(groupReviewThreads(comments))

        assertEquals(3, discussions.unresolvedCount)
        assertEquals(2, discussions.unknownCount)
        assertEquals(mapOf("src/A.kt" to listOf(1L, 3L), "src/Removed.kt" to listOf(5L)),
            discussions.unresolvedByPath.mapValues { (_, threads) -> threads.map { it.id } })
        assertEquals(1, discussions.fileIndex("src/A.kt", listOf("src/Other.kt", "src/A.kt")))
        assertEquals(2, discussions.fileIndex("src/Removed.kt", listOf("src/A.kt", "src/B.kt", "src/Removed.kt")))
        assertNull(discussions.fileIndex("src/B.kt", listOf("src/A.kt", "src/Removed.kt")))
    }

    @Test fun batchTargetsReuseSingleThreadPermissionsAndExcludeIneligibleThreads() {
        val open = thread(1)
        val sameThread = thread(2, nodeId = "t1")
        val resolved = thread(3, resolved = true)
        val unknown = thread(4, nodeId = " ")
        val threads = listOf(open, sameThread, resolved, unknown)
        val triage = ThreadAccess(write = false, triage = true, author = false)
        val writer = ThreadAccess(write = true, triage = true, author = false)

        assertEquals("t1", open.resolutionId(author))
        assertEquals("t3", resolved.resolutionId(writer)) // individual action can reopen
        assertNull(unknown.resolutionId(author))
        assertNull(open.resolutionId(triage))
        assertTrue(pullReviewResolveTargets(threads, triage).isEmpty())
        assertEquals(listOf("t1"), pullReviewResolveTargets(threads, writer).map { it.nodeId })
    }

    @Test fun batchTargetsCapTheWorkShownInAConfirmation() {
        val targets = pullReviewResolveTargets((1L..100L).map { thread(it) }, author)

        assertEquals((1L..20L).toList(), targets.map { it.thread.id })
    }

    @Test fun batchReportsFailureAndContinuesWithTheNextThread() = runTest {
        val targets = pullReviewResolveTargets((1L..3L).map { thread(it) }, author)
        val failure = IllegalStateException("permission denied")
        val results = mutableListOf<Pair<String, PullReviewResolveOutcome>>()

        runPullReviewResolveBatch(targets, shouldStop = { false }, resolve = { id ->
            if (id == "t2") throw failure
        }, report = { target, result -> results += target.nodeId to result })

        assertEquals(listOf(
            "t1" to PullReviewResolveOutcome.Resolved,
            "t2" to PullReviewResolveOutcome.Failed(failure),
            "t3" to PullReviewResolveOutcome.Resolved,
        ), results)
    }

    @Test fun stopKeepsTheInFlightOutcomeAndSkipsRemainingThreads() = runTest {
        val targets = pullReviewResolveTargets((1L..3L).map { thread(it) }, author)
        val calls = mutableListOf<String>()
        val results = mutableListOf<PullReviewResolveOutcome>()
        var stopped = false

        runPullReviewResolveBatch(targets, shouldStop = { stopped }, resolve = { id ->
            calls += id
            stopped = true
        }, report = { _, result -> results += result })

        assertEquals(listOf("t1"), calls)
        assertEquals(listOf(PullReviewResolveOutcome.Resolved, PullReviewResolveOutcome.Skipped,
            PullReviewResolveOutcome.Skipped), results)
    }

    @Test fun coroutineCancellationDoesNotBecomeAThreadFailureOrContinueWriting() = runTest {
        val targets = pullReviewResolveTargets((1L..2L).map { thread(it) }, author)
        val failure = CancellationException("screen removed")
        var reported = false
        val result = runCatching {
            runPullReviewResolveBatch(targets, shouldStop = { false }, resolve = { throw failure },
                report = { _, _ -> reported = true })
        }

        assertEquals(failure, result.exceptionOrNull())
        assertFalse(reported)
    }

    private fun thread(id: Long, nodeId: String = "t$id", resolved: Boolean = false) =
        ReviewThread(ReviewComment(id = id, path = "file", threadId = nodeId, resolved = resolved), emptyList())
}
