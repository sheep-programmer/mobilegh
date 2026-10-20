package com.mobilegh.ui.screens

import com.mobilegh.data.UserListPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserListDetailLoaderTest {
    private suspend fun awaitIdle(loader: UserListDetailLoader<String>) {
        withTimeout(5000) { while (loader.loading) yield() }
    }

    @Test fun switchingListsRejectsTheOldResponseAndLoadingState() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<Unit>()
        val loader = UserListDetailLoader<String>(this, 100) { it }
        loader.open("old") {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            UserListPage(listOf("old"), 1, null)
        }
        started.await()
        loader.open("new") { fresh.await(); UserListPage(listOf("new"), 1, null) }
        release.complete(Unit)
        yield()
        assertTrue(loader.loading)
        assertTrue(loader.items.isEmpty())
        fresh.complete(Unit)
        awaitIdle(loader)
        assertEquals(listOf("new"), loader.items)
        assertNull(loader.error)
    }

    @Test fun refreshingAtDisplayLimitWorksAndFailedRefreshRetriesFirstPage() = runBlocking {
        val cursors = mutableListOf<String?>()
        var calls = 0
        val loader = UserListDetailLoader<String>(this, 2) { it }
        loader.open("list") { cursor ->
            cursors += cursor
            when (++calls) {
                1 -> UserListPage(listOf("a", "b"), 3, "next")
                2 -> error("refresh failed")
                else -> UserListPage(listOf("fresh"), 1, null)
            }
        }
        awaitIdle(loader)
        loader.loadMore()
        assertEquals(1, calls)
        loader.refresh()
        awaitIdle(loader)
        assertEquals(listOf("a", "b"), loader.items)
        assertEquals("refresh failed", loader.error)
        loader.retry()
        awaitIdle(loader)
        assertEquals(listOf<String?>(null, null, null), cursors)
        assertEquals(listOf("fresh"), loader.items)
        assertFalse(loader.limited)
    }

    @Test fun cancelledScopeRejectsAResponseEvenWhenTheSourceSwallowsCancellation() = runBlocking {
        val parent = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val loader = UserListDetailLoader<String>(scope, 100) { it }
        loader.open("list") {
            if (++calls == 1) UserListPage(listOf("saved"), 1, null)
            else {
                started.complete(Unit)
                try { release.await() } catch (_: CancellationException) { /* Simulate a source that ignores cancellation. */ }
                UserListPage(listOf("stale"), 1, null)
            }
        }
        awaitIdle(loader)
        loader.refresh()
        started.await()
        parent.cancelAndJoin()
        assertEquals(listOf("saved"), loader.items)
        assertFalse(loader.loading)
        assertNull(loader.error)
    }

    @Test fun closingAListClearsItsRowsAndInvalidatesItsRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = UserListDetailLoader<String>(this, 100) { it }
        loader.open("list") {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            UserListPage(listOf("stale"), 1, null)
        }
        started.await()
        loader.open(null)
        release.complete(Unit)
        yield()
        assertTrue(loader.items.isEmpty())
        assertFalse(loader.loading)
        assertFalse(loader.hasMore)
        assertFalse(loader.loaded)
        assertNull(loader.error)
    }

    @Test fun failedAppendRetainsItemsAndRetriesTheSameCursor() = runBlocking {
        val cursors = mutableListOf<String?>()
        var calls = 0
        val loader = UserListDetailLoader<String>(this, 100) { it }
        loader.open("list") { cursor ->
            cursors += cursor
            when (++calls) {
                1 -> UserListPage(listOf("a"), 2, "next")
                2 -> error("append failed")
                else -> UserListPage(listOf("b"), 2, null)
            }
        }
        awaitIdle(loader)
        loader.loadMore()
        awaitIdle(loader)
        assertEquals(listOf("a"), loader.items)
        loader.retry()
        awaitIdle(loader)
        assertEquals(listOf<String?>(null, "next", "next"), cursors)
        assertEquals(listOf("a", "b"), loader.items)
        assertFalse(loader.hasMore)
    }
}
