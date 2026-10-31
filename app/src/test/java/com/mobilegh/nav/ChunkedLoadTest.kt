package com.mobilegh.nav

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedLoadTest {
    // ---- planChunk ----

    @Test fun firstStepReadsUpToPagesPerStep() {
        assertEquals(listOf(1, 2, 3), planChunk(1, 3, null).toList())
        assertEquals(listOf(4, 5, 6), planChunk(4, 3, null).toList())
    }

    @Test fun stepIsBoundedByKnownTotalPages() {
        assertEquals(listOf(1, 2), planChunk(1, 3, 2).toList())
        assertEquals(listOf(3), planChunk(3, 3, 3).toList())
    }

    @Test fun stepIsEmptyWhenEverythingIsAlreadyRead() {
        assertTrue(planChunk(4, 3, 3).isEmpty())
        assertTrue(planChunk(2, 3, 1).isEmpty())
    }

    @Test fun singlePageStepsWork() {
        assertEquals(listOf(7), planChunk(7, 1, null).toList())
    }

    @Test fun stepDoesNotOverflowNearIntMax() {
        val step = planChunk(Int.MAX_VALUE, 3, null)
        assertEquals(Int.MAX_VALUE, step.first)
        assertEquals(Int.MAX_VALUE, step.last)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stepRejectsNonPositivePageNumbers() {
        planChunk(0, 3, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stepRejectsNonPositiveStepSize() {
        planChunk(1, 0, null)
    }

    // ---- limit reached ----

    @Test fun limitIsReachedOnlyWhenItemsMeetItAndMoreRemains() {
        assertTrue(chunkLimitReached(loaded = 5, maxItems = 5, hasNext = true))
        assertTrue(chunkLimitReached(loaded = 1020, maxItems = 1000, hasNext = true))
        assertFalse(chunkLimitReached(loaded = 4, maxItems = 5, hasNext = true))
    }

    @Test fun reachingTheLimitAtTheEndIsNotTruncation() {
        assertFalse(chunkLimitReached(loaded = 1000, maxItems = 1000, hasNext = false))
    }

    @Test fun unlimitedNeverReachesTheLimit() {
        assertFalse(chunkLimitReached(loaded = Int.MAX_VALUE - 1, maxItems = PAGER_UNLIMITED, hasNext = true))
    }

    // ---- error resume ----

    @Test fun resumeStartsAtThePageAfterTheLastCommittedOne() {
        assertEquals(1, resumePage(0))
        assertEquals(2, resumePage(1))
        assertEquals(4, resumePage(3))
    }

    @Test fun resumeNeverGoesBelowPageOne() {
        assertEquals(1, resumePage(-1))
    }

    // ---- search cap ----

    @Test fun searchHasNextPageRespectsTheResultCap() {
        assertTrue(searchHasNextPage(page = 1, perPage = 30, totalCount = 45))
        assertFalse(searchHasNextPage(page = 2, perPage = 30, totalCount = 45))
        assertFalse(searchHasNextPage(page = 2, perPage = 30, totalCount = 60))
        // 总数超过 1000 时，第 34 页起已超出上限
        assertTrue(searchHasNextPage(page = 33, perPage = 30, totalCount = 5000))
        assertFalse(searchHasNextPage(page = 34, perPage = 30, totalCount = 5000))
        assertFalse(searchHasNextPage(page = 1, perPage = 30, totalCount = 0))
    }

    @Test fun searchTotalPagesIgnoresResultsBeyondTheCap() {
        assertEquals(2, searchTotalPages(perPage = 30, totalCount = 45))
        assertEquals(2, searchTotalPages(perPage = 30, totalCount = 60))
        assertEquals(34, searchTotalPages(perPage = 30, totalCount = 5000))
        assertEquals(0, searchTotalPages(perPage = 30, totalCount = 0))
    }

    // ---- Pager integration (pure fetch fakes, no delay) ----

    private suspend fun awaitIdle(pager: Pager<*>) {
        withTimeout(5000) { while (pager.loading) yield() }
    }

    @Test fun chunkedLoadPublishesEachPageAndStopsAtTheLimit() = runBlocking {
        val pages = CopyOnWriteArrayList<Int>()
        val sizesSeen = CopyOnWriteArrayList<Int>()
        lateinit var pager: Pager<String>
        pager = Pager(this, pageSize = 2) { page, _ ->
            pages += page
            sizesSeen += pager.items.size
            listOf("p$page-a", "p$page-b")
        }
        pager.loadChunked(maxItems = 5, pagesPerStep = 3, pauseMs = 0)
        awaitIdle(pager)
        assertEquals(listOf(1, 2, 3), pages.toList())
        // 每页取回时前面的页已经在列表里（逐页提交，而不是等整段结束）
        assertEquals(listOf(0, 2, 4), sizesSeen.toList())
        assertEquals(6, pager.items.size)
        assertTrue(pager.truncated)
        assertFalse(pager.end)
        assertFalse(pager.fullScanning)
    }

    @Test fun failedChunkKeepsLoadedItemsAndRetryResumesFromTheFailedPage() = runBlocking {
        val pages = CopyOnWriteArrayList<Int>()
        val failures = AtomicInteger()
        val pager = Pager(this, pageSize = 2) { page, _ ->
            pages += page
            if (page == 2 && failures.incrementAndGet() == 1) error("offline")
            if (page >= 4) emptyList() else listOf("p$page-a", "p$page-b")
        }
        pager.loadChunked(pagesPerStep = 3, pauseMs = 0)
        awaitIdle(pager)
        // 第 1 页已经显示，第 2 页失败后不丢弃
        assertEquals(listOf("p1-a", "p1-b"), pager.items.toList())
        assertNotNull(pager.error)
        assertFalse(pager.end)
        assertEquals(listOf(1, 2), pages.toList())

        pager.retry()
        awaitIdle(pager)
        assertEquals(listOf(1, 2, 2, 3, 4), pages.toList())
        assertEquals(listOf("p1-a", "p1-b", "p2-a", "p2-b", "p3-a", "p3-b"), pager.items.toList())
        assertTrue(pager.end)
        assertEquals(null, pager.error)
    }

    @Test fun stoppingAChunkedLoadKeepsItemsAndResumesFromTheSamePage() = runBlocking {
        val pages = CopyOnWriteArrayList<Int>()
        val started = CompletableDeferred<Unit>()
        val stuck = CompletableDeferred<Unit>()
        val hung = AtomicBoolean(false)
        val pager = Pager(this, pageSize = 2) { page, _ ->
            pages += page
            if (page == 2 && hung.compareAndSet(false, true)) {
                started.complete(Unit)
                stuck.await()
            }
            if (page >= 3) emptyList() else listOf("p$page-a", "p$page-b")
        }
        pager.loadChunked(pagesPerStep = 3, pauseMs = 0)
        started.await()
        assertTrue(pager.fullScanning)
        pager.cancelLoadAll()
        assertFalse(pager.loading)
        assertFalse(pager.fullScanning)
        assertEquals(listOf("p1-a", "p1-b"), pager.items.toList())
        pager.loadChunked(pagesPerStep = 3, pauseMs = 0)
        awaitIdle(pager)
        assertEquals(listOf(1, 2, 2, 3), pages.toList())
        assertTrue(pager.end)
        assertEquals(4, pager.items.size)
    }

    @Test fun chunkedLoadWithoutLimitReadsUntilTheEnd() = runBlocking {
        val pager = Pager(this, pageSize = 2) { page, _ -> if (page < 5) listOf("a$page", "b$page") else emptyList() }
        pager.loadChunked(pauseMs = 0)
        awaitIdle(pager)
        assertTrue(pager.end)
        assertFalse(pager.truncated)
        assertEquals(8, pager.items.size)
    }

    @Test fun chunkedLoadDoesNotStartWhileAPlainPageIsLoading() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pager = Pager(this, pageSize = 2) { _, _ ->
            started.complete(Unit)
            release.await()
            listOf("x", "y")
        }
        pager.loadMore()
        started.await()
        pager.loadChunked(pauseMs = 0)
        // 普通翻页仍在进行，分块加载不应在其上另起一次请求
        assertTrue(pager.loading)
        assertFalse(pager.fullScanning)
        release.complete(Unit)
        awaitIdle(pager)
        assertEquals(listOf("x", "y"), pager.items.toList())
    }
}
