package com.mobilegh.data

import com.mobilegh.nav.Pager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StarListCacheTest {
    private val max = StarListCache.MAX_AGE_MS

    @Test fun neverLoadedSnapshotIsStale() {
        assertTrue(StarListCache.isStale(loadedAtMs = 0, loadedRevision = 0, revision = 0, nowMs = 1_000))
    }

    @Test fun freshSnapshotWithSameRevisionIsNotStale() {
        assertFalse(StarListCache.isStale(loadedAtMs = 1_000, loadedRevision = 3, revision = 3, nowMs = 1_000 + max / 2))
    }

    @Test fun exactlyMaxAgeIsStillFresh() {
        assertFalse(StarListCache.isStale(loadedAtMs = 1_000, loadedRevision = 3, revision = 3, nowMs = 1_000 + max))
    }

    @Test fun olderThanMaxAgeIsStale() {
        assertTrue(StarListCache.isStale(loadedAtMs = 1_000, loadedRevision = 3, revision = 3, nowMs = 1_000 + max + 1))
    }

    @Test fun changedRevisionIsStaleEvenWhenRecent() {
        assertTrue(StarListCache.isStale(loadedAtMs = 1_000, loadedRevision = 3, revision = 4, nowMs = 1_001))
    }

    @Test fun snapshotIsSharedForSameKeyAndAccount() {
        val key = "rl-starred:test-shared:0:true::true"
        val first = StarListCache.snapshot<String>(key, generation = 7)
        assertSame(first, StarListCache.snapshot<String>(key, generation = 7))
    }

    @Test fun snapshotIsReplacedForAnotherAccount() {
        val key = "rl-starred:test-account:0:true::true"
        val old = StarListCache.snapshot<String>(key, generation = 1)
        old.items = listOf("private")
        val fresh = StarListCache.snapshot<String>(key, generation = 2)
        assertNotSame(old, fresh)
        assertTrue(fresh.items.isEmpty())
    }

    @Test fun starWriteBumpsRevisionSoSnapshotsGoStale() {
        val before = StarListCache.starRevision
        StarListCache.onStarChanged()
        assertEquals(before + 1, StarListCache.starRevision)
    }

    @Test fun restoreFromPutsCachedStateBackWithoutLoading() {
        val pager = Pager<Int>(CoroutineScope(Dispatchers.Unconfined))
        assertTrue(pager.restoreFrom(listOf(1, 2, 3), pages = 2, ended = false))
        assertEquals(listOf(1, 2, 3), pager.items.toList())
        assertEquals(2, pager.committedPages)
        assertFalse(pager.end)
        assertTrue(pager.loadedOnce)
        assertFalse(pager.loading)
    }

    @Test fun restoreFromIsRejectedOnceLoaded() {
        val pager = Pager<Int>(CoroutineScope(Dispatchers.Unconfined))
        assertTrue(pager.restoreFrom(listOf(1), pages = 1, ended = true))
        assertFalse(pager.restoreFrom(listOf(9), pages = 3, ended = false))
        assertEquals(listOf(1), pager.items.toList())
    }

    @Test fun restoreFromIgnoresEmptySnapshot() {
        val pager = Pager<Int>(CoroutineScope(Dispatchers.Unconfined))
        assertFalse(pager.restoreFrom(emptyList(), pages = 0, ended = false))
        assertFalse(pager.loadedOnce)
    }
}
