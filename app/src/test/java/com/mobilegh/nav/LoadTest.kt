package com.mobilegh.nav

import com.mobilegh.data.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadTest {
    @Test fun cancellingBeforeDispatchClearsLoaderSpinner() = runBlocking {
        val owner = Job()
        val loader = Loader(CoroutineScope(coroutineContext + owner)) { "unused" }
        loader.load(true)
        owner.cancelAndJoin()
        assertFalse(loader.loading)
        assertFalse(loader.refreshing)
        assertNull(loader.error)
    }

    @Test fun cancellingBeforeDispatchClearsPagerSpinner() = runBlocking {
        val owner = Job()
        val pager = Pager(CoroutineScope(coroutineContext + owner)) { _, _ -> listOf("unused") }
        pager.refresh()
        owner.cancelAndJoin()
        assertFalse(pager.loading)
        assertFalse(pager.refreshing)
        assertNull(pager.error)
    }

    @Test fun serverHasMoreOverridesShortDecodedPages() = runBlocking {
        val pager = Pager(this, pageSize = 100, hasMore = { page -> page == 1 }) { page, _ ->
            if (page == 1) listOf("only decoded repository") else emptyList()
        }
        pager.loadMore()
        withTimeout(5000) { while (pager.loading) yield() }
        assertFalse("GraphQL null nodes must not mark a short page as the end", pager.end)
        pager.loadMore()
        withTimeout(5000) { while (pager.loading) yield() }
        assertTrue(pager.end)
        assertEquals(listOf("only decoded repository"), pager.items)
    }

    @Test fun loadAllContinuesThroughEmptyPagesWhenTheServerHasMore() = runBlocking {
        val pages = mutableListOf<Int>()
        val pager = Pager(this, pageSize = 2, hasMore = { it == 1 }) { page, _ ->
            pages += page
            if (page == 1) emptyList() else listOf("a", "b")
        }
        pager.loadAll()
        withTimeout(5000) { while (pager.loading) yield() }
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf("a", "b"), pager.items)
        assertTrue(pager.end)
    }

    @Test fun localUpdateInvalidatesAnInFlightRefresh() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pager = Pager(this, pageSize = 1) { _, _ ->
            started.complete(Unit)
            release.await()
            listOf("stale response")
        }
        pager.refresh()
        started.await()
        pager.update { items -> items.clear(); items += "confirmed local state" }
        release.complete(Unit)
        withTimeout(5000) { while (pager.loading) yield() }
        assertEquals(listOf("confirmed local state"), pager.items)
        assertFalse(pager.loading)
        assertFalse(pager.refreshing)
    }

    @Test fun stalePageDoesNotCommitCursorMetadata() = runBlocking {
        val calls = AtomicInteger()
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        val cursors = mutableMapOf<Int, String>()
        val pager = Pager(this, pageSize = 1, fetchPage = { _, _ ->
            if (calls.incrementAndGet() == 1) {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { releaseOld.await() }
                oldReturned.complete(Unit)
                PagerPage(listOf("old"), hasMore = true) { cursors[2] = "old" }
            } else {
                PagerPage(listOf("new"), hasMore = true) { cursors[2] = "new" }
            }
        })
        try {
            pager.refresh()
            oldStarted.await()
            pager.refresh()
            withTimeout(5000) { while (pager.items.toList() != listOf("new")) yield() }
            releaseOld.complete(Unit)
            oldReturned.await()
            yield()
            assertEquals("new", cursors[2])
            assertEquals(listOf("new"), pager.items.toList())
        } finally {
            releaseOld.complete(Unit)
        }
    }

    @Test fun accountSwitchDiscardsLoaderResult() = runBlocking {
        val generation = Session.generation
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val loader = Loader(this) { started.complete(Unit); release.await(); "old account" }
            loader.load()
            started.await()
            Session.generation++
            release.complete(Unit)
            withTimeout(5000) { while (loader.loading) yield() }
            assertNull(loader.data)
            assertNull(loader.error)
        } finally {
            Session.generation = generation
        }
    }

    @Test fun accountSwitchStopsLoadAllBeforeFetchingAnotherPage() = runBlocking {
        val generation = Session.generation
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pages = mutableListOf<Int>()
            val pager = Pager(this, pageSize = 1) { page, _ ->
                pages += page
                if (page == 1) {
                    started.complete(Unit)
                    release.await()
                    listOf("old account")
                } else emptyList()
            }
            pager.loadAll()
            started.await()
            Session.generation++
            release.complete(Unit)
            withTimeout(5000) { while (pager.loading) yield() }
            assertEquals(listOf(1), pages)
            assertEquals(emptyList<String>(), pager.items)
            pager.loadMore()
            yield()
            assertFalse(pager.loading)
            assertEquals(listOf(1), pages)
        } finally {
            Session.generation = generation
        }
    }
}
