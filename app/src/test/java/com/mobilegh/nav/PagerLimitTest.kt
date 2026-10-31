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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerLimitTest {
    private suspend fun awaitIdle(pager: Pager<*>) {
        withTimeout(5000) { while (pager.loading) yield() }
    }

    @Test fun loadAllStopsAtMaxItemsAndKeepsLoadedPages() = runBlocking {
        val pages = CopyOnWriteArrayList<Int>()
        val pager = Pager(this, pageSize = 2) { page, _ -> pages += page; listOf("p$page-a", "p$page-b") }
        pager.loadAll(maxItems = 5)
        awaitIdle(pager)
        // 每页 2 条：第 3 页读完后达到 6 条（>= 5），停止翻页
        assertEquals(listOf(1, 2, 3), pages.toList())
        assertEquals(6, pager.items.size)
        assertTrue(pager.truncated)
        assertFalse(pager.end)
        // 达到上限不等于列表结束：之后仍可以普通翻页继续
        pager.loadMore()
        awaitIdle(pager)
        assertEquals(listOf(1, 2, 3, 4), pages.toList())
        assertEquals(8, pager.items.size)
        assertFalse(pager.truncated)
    }

    @Test fun loadAllAtLimitDoesNotFetchAgain() = runBlocking {
        val pages = CopyOnWriteArrayList<Int>()
        val pager = Pager(this, pageSize = 2) { page, _ -> pages += page; listOf("a$page", "b$page") }
        pager.loadMore()
        awaitIdle(pager)
        pager.loadMore()
        awaitIdle(pager)
        pager.loadAll(maxItems = 3)
        awaitIdle(pager)
        assertEquals(listOf(1, 2), pages.toList())
        assertTrue(pager.truncated)
    }

    @Test fun loadAllWithoutLimitReadsUntilTheEnd() = runBlocking {
        val pager = Pager(this, pageSize = 2) { page, _ -> if (page < 4) listOf("a$page", "b$page") else emptyList() }
        pager.loadAll()
        awaitIdle(pager)
        assertTrue(pager.end)
        assertFalse(pager.truncated)
        assertEquals(6, pager.items.size)
    }

    @Test fun cancelLoadAllStopsTheRunningScanAndResumesFromTheSamePage() = runBlocking {
        val pages = CopyOnWriteArrayList<Int>()
        val started = CompletableDeferred<Unit>()
        val stuck = CompletableDeferred<Unit>() // 第一次取第 2 页时永远挂起，等待被取消
        val hung = AtomicBoolean(false)
        val pager = Pager(this, pageSize = 2) { page, _ ->
            pages += page
            if (page == 2 && hung.compareAndSet(false, true)) {
                started.complete(Unit)
                stuck.await()
            }
            if (page >= 3) emptyList() else listOf("p$page-a", "p$page-b")
        }
        pager.loadAll()
        started.await()
        pager.cancelLoadAll()
        assertFalse(pager.loading)
        assertEquals(listOf("p1-a", "p1-b"), pager.items.toList())
        // 被取消的第 2 页没有提交，再次全量加载从第 2 页继续
        pager.loadAll()
        awaitIdle(pager)
        assertEquals(listOf(1, 2, 2, 3), pages.toList())
        assertEquals(listOf("p1-a", "p1-b", "p2-a", "p2-b"), pager.items.toList())
        assertTrue(pager.end)
    }

    @Test fun cancelLoadAllDoesNotCancelAPlainPageLoad() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pager = Pager(this, pageSize = 2) { _, _ ->
            started.complete(Unit)
            release.await()
            listOf("x", "y")
        }
        pager.loadMore()
        started.await()
        pager.cancelLoadAll()
        assertTrue(pager.loading)
        release.complete(Unit)
        awaitIdle(pager)
        assertEquals(listOf("x", "y"), pager.items.toList())
    }

    @Test fun failedRefreshKeepsOldItemsAndDoesNotClearThemWhileInFlight() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pager = Pager(this, pageSize = 2) { _, _ ->
            if (calls.incrementAndGet() == 1) listOf("old-1", "old-2")
            else {
                started.complete(Unit)
                release.await()
                error("offline")
            }
        }
        pager.loadMore()
        awaitIdle(pager)
        pager.refresh()
        started.await()
        // 新的第一页还没到，旧条目必须仍然可见
        assertTrue(pager.refreshing)
        assertEquals(listOf("old-1", "old-2"), pager.items.toList())
        release.complete(Unit)
        awaitIdle(pager)
        assertEquals(listOf("old-1", "old-2"), pager.items.toList())
        assertNotNull(pager.error)
    }

    @Test fun successfulRefreshReplacesItemsAndRestartsPaging() = runBlocking {
        val calls = AtomicInteger()
        val pager = Pager(this, pageSize = 2) { page, _ ->
            when (calls.incrementAndGet()) {
                1 -> listOf("old-1", "old-2")
                2 -> listOf("old-3", "old-4")
                3 -> listOf("new-1", "new-2")
                else -> listOf("p$page-a", "p$page-b")
            }
        }
        pager.loadMore()
        awaitIdle(pager)
        pager.loadMore()
        awaitIdle(pager)
        pager.refresh()
        awaitIdle(pager)
        assertEquals(listOf("new-1", "new-2"), pager.items.toList())
        assertNull(pager.error)
        // 刷新后从第 1 页重新翻页
        pager.loadMore()
        awaitIdle(pager)
        assertEquals(listOf("new-1", "new-2", "p2-a", "p2-b"), pager.items.toList())
    }
}
