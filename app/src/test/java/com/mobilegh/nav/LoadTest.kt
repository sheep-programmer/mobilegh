package com.mobilegh.nav

import com.mobilegh.data.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
