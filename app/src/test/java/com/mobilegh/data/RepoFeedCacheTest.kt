package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepoFeedCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun persistsRepositoryRowsAndPagingState() {
        val cache = RepoFeedCache(folder.root)
        val rows = listOf(Repo(id = 7, fullName = "octo/app", name = "app"))

        cache.write("repos:all", rows, pages = 2, ended = false, nowMs = 1_000L)
        val restored = cache.read("repos:all", nowMs = 2_000L)

        assertNotNull(restored)
        assertEquals(rows, restored!!.items)
        assertEquals(2, restored.pages)
        assertFalse(restored.ended)
    }

    @Test fun expiredRowsAreStillReadableButMarkedStale() {
        val cache = RepoFeedCache(folder.root)
        cache.write("repos:all", listOf(Repo(id = 9)), pages = 1, ended = true, nowMs = 1_000L)

        val restored = cache.read("repos:all", nowMs = 1_000L + RepoFeedCache.MAX_AGE_MS + 1)

        assertTrue(restored!!.stale)
    }

    @Test fun rowsOlderThanTheOldFourTimesCutoffAreStillShownAsStale() {
        val cache = RepoFeedCache(folder.root)
        cache.write("repos:all", listOf(Repo(id = 9)), pages = 1, ended = true, nowMs = 1_000L)

        val restored = cache.read("repos:all", nowMs = 1_000L + RepoFeedCache.MAX_AGE_MS * 4 + 1)

        assertNotNull(restored)
        assertTrue(restored!!.stale)
    }

    @Test fun restoresEveryRowAndTheMatchingPageCountBeyondTwoHundred() {
        // 旧实现截断到 200 条但保留真实页数，恢复后会从错误页继续加载而重复条目。
        val cache = RepoFeedCache(folder.root)
        val rows = (1..250).map { Repo(id = it.toLong(), fullName = "octo/r$it", name = "r$it") }

        cache.write("repos:all", rows, pages = 9, ended = false, nowMs = 1_000L)
        val restored = cache.read("repos:all", nowMs = 2_000L)

        assertNotNull(restored)
        assertEquals(rows, restored!!.items)
        assertEquals(9, restored.pages)
    }

    @Test fun savedTimeInTheFutureIsRejected() {
        val cache = RepoFeedCache(folder.root)
        cache.write("repos:all", listOf(Repo(id = 9)), pages = 1, ended = true, nowMs = 5_000L)

        assertNull(cache.read("repos:all", nowMs = 1_000L))
    }
}
