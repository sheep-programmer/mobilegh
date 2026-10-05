package com.mobilegh.ui.screens

import com.mobilegh.data.DiscussionPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscussionCursorPagerTest {
    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5000) { while (!condition()) delay(1) }
    }

    private fun page(vararg items: String, more: Boolean = false) =
        DiscussionPage(items.toList(), if (more) "next" else null, more, items.size)

    @Test fun cancelledOldRequestCannotOverwriteANewFilterOrItsLoadingState() = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val pager = DiscussionCursorPager<String>(this, 3) { it }
        pager.restart({
            oldStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                page("old")
            } finally {
                withContext(NonCancellable) { releaseOld.await() }
                oldFinished.complete(Unit)
            }
        })
        withTimeout(5000) { oldStarted.await() }
        pager.restart({
            newStarted.complete(Unit)
            releaseNew.await()
            page("new")
        })
        withTimeout(5000) { newStarted.await() }
        releaseOld.complete(Unit)
        withTimeout(5000) { oldFinished.await() }
        assertTrue(pager.loading)
        assertTrue(pager.items.isEmpty())
        assertNull(pager.error)
        releaseNew.complete(Unit)
        awaitCondition { !pager.loading }
        assertEquals(listOf("new"), pager.items)
    }

    @Test fun failedRefreshRetainsDataAndRetryStartsAtTheFirstCursor() = runBlocking {
        val cursors = mutableListOf<String?>()
        var requests = 0
        val pager = DiscussionCursorPager<String>(this, 3) { it }
        pager.restart({ cursor ->
            cursors += cursor
            requests++
            if (requests == 1) throw IllegalStateException("refresh failed")
            page("fresh")
        }, seed = page("old"))
        pager.refresh()
        awaitCondition { !pager.loading }
        assertEquals(listOf("old"), pager.items)
        assertEquals("refresh failed", pager.error)
        assertFalse(pager.hasMore)
        pager.retry()
        awaitCondition { !pager.loading }
        assertEquals(listOf<String?>(null, null), cursors)
        assertEquals(listOf("fresh"), pager.items)
        assertNull(pager.error)
    }

    @Test fun stoppingPreservesCancellationWithoutDisplayingAnError() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val pager = DiscussionCursorPager<String>(this, 3) { it }
        pager.restart({
            started.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
                page("unreachable")
            } catch (e: CancellationException) {
                cancelled.complete(Unit)
                throw e
            }
        })
        withTimeout(5000) { started.await() }
        pager.stop()
        withTimeout(5000) { cancelled.await() }
        assertFalse(pager.loading)
        assertNull(pager.error)
        assertTrue(pager.items.isEmpty())
    }

    @Test fun pageLimitStopsFurtherRequestsAndFilterRestartReleasesOldItems() = runBlocking {
        var calls = 0
        val pager = DiscussionCursorPager<String>(this, 2) { it }
        pager.restart({ calls++; page("1", "2", "3", more = true) })
        awaitCondition { !pager.loading }
        assertEquals(listOf("1", "2"), pager.items)
        assertTrue(pager.limited)
        assertFalse(pager.hasMore)
        pager.loadMore()
        assertEquals(1, calls)
        pager.restart({ page("new") })
        awaitCondition { !pager.loading }
        assertEquals(listOf("new"), pager.items)
        assertFalse(pager.limited)
    }
}
