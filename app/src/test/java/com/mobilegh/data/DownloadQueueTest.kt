package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载并发上限与排队提升（纯函数部分）。
 * 队列顺序约定：queue 按入队先后排列，最早的在前。
 */
class DownloadQueueTest {
    private fun entry(key: String, paused: Boolean = false) = QueueEntry(key, paused)

    @Test fun maximumConcurrentDownloadsIsThree() {
        assertEquals(3, MAX_CONCURRENT_DOWNLOADS)
    }

    @Test fun canStartNextOnlyBelowTheLimit() {
        assertTrue(canStartNext(0, 3))
        assertTrue(canStartNext(2, 3))
        assertFalse(canStartNext(3, 3))
        assertFalse(canStartNext(4, 3))
    }

    @Test fun defaultLimitIsTheConstant() {
        assertTrue(canStartNext(MAX_CONCURRENT_DOWNLOADS - 1))
        assertFalse(canStartNext(MAX_CONCURRENT_DOWNLOADS))
    }

    @Test fun promotesInFirstInFirstOutOrderUpToFreeSlots() {
        val queue = listOf(entry("a"), entry("b"), entry("c"), entry("d"))
        // 运行中 2 个，只剩 1 个名额：先进先出，只提交最早的 a
        assertEquals(listOf("a"), keysToPromote(queue, running = 2))
        // 一个都没运行：前三个按顺序提交，d 继续排队
        assertEquals(listOf("a", "b", "c"), keysToPromote(queue, running = 0))
    }

    @Test fun nothingPromotedWhenTheLimitIsReached() {
        assertTrue(keysToPromote(listOf(entry("a")), running = 3).isEmpty())
        assertTrue(keysToPromote(listOf(entry("a")), running = 5).isEmpty())
    }

    @Test fun emptyQueuePromotesNothing() {
        assertTrue(keysToPromote(emptyList(), running = 0).isEmpty())
    }

    @Test fun pausedEntriesAreSkippedAndDoNotBlockLaterOnes() {
        // a、c 暂停：不提交；b、d 按顺序提交，暂停的不挡路
        val queue = listOf(entry("a", paused = true), entry("b"), entry("c", paused = true), entry("d"))
        assertEquals(listOf("b", "d"), keysToPromote(queue, running = 1))
    }

    @Test fun pausedEntriesDoNotTakeASlot() {
        // a 暂停时不占名额：剩余的 1 个名额给 b，c 继续等
        val queue = listOf(entry("a", paused = true), entry("b"), entry("c"))
        assertEquals(listOf("b"), keysToPromote(queue, running = 2))
    }

    @Test fun resumedEntryKeepsItsPlaceInTheQueue() {
        // 继续后的 a 仍在最前面，先于之后入队的 b 提交
        assertEquals(listOf("a", "b"), keysToPromote(listOf(entry("a"), entry("b")), running = 1, limit = 3))
    }

    @Test fun customLimitIsRespected() {
        val queue = listOf(entry("a"), entry("b"), entry("c"))
        assertEquals(listOf("a", "b"), keysToPromote(queue, running = 0, limit = 2))
    }

    @Test fun queuedTaskWithoutManagerIdIsWaitingForASlot() {
        val queued = DownloadTask(key = "q", url = "https://example.com/q", filename = "q.apk", state = DownloadState.Queued)
        assertTrue(queued.waitingForSlot)
        assertTrue(queued.copy(paused = true).waitingForSlot)
    }

    @Test fun pausedTransferCanBeResumedButIsNotQueued() {
        // 传输中途暂停：保留部分文件，状态为已暂停，可以继续，但不占用排队名额
        val paused = DownloadTask(key = "p", url = "https://example.com/p", filename = "p.apk", state = DownloadState.Waiting, paused = true)
        assertTrue(paused.canTogglePause)
        assertFalse(paused.waitingForSlot)
    }

    @Test fun runningTransferCanBePaused() {
        val running = DownloadTask(key = "r", url = "https://example.com/r", filename = "r.apk", state = DownloadState.Running)
        assertTrue(running.canTogglePause)
        assertFalse(running.waitingForSlot)
    }
}
