package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载记录保留：只按已结束的记录计数，超出 20 条的较早记录才会被移出列表。
 * 进行中的任务不占名额，也不会被移出。
 */
class DownloadHistoryTest {
    private fun task(i: Int, state: DownloadState = DownloadState.Complete) =
        DownloadTask(key = "k$i", url = "https://example.com/$i", filename = "f$i.apk", state = state)

    @Test fun nothingDroppedWithinTheLimit() {
        assertTrue(historyOverflow((0 until HISTORY_KEEP).map { task(it) }).isEmpty())
    }

    @Test fun oldestFinishedRecordsBeyondTheLimitAreReturned() {
        // 列表按新到旧排列：前 20 条保留，后面的 3 条是较早的记录
        val overflow = historyOverflow((0 until 23).map { task(it) })
        assertEquals(listOf("k20", "k21", "k22"), overflow.map { it.key })
    }

    @Test fun activeTasksDoNotCountTowardsTheLimit() {
        val list = listOf(task(0, DownloadState.Running)) + (1..HISTORY_KEEP).map { task(it) }
        assertTrue(historyOverflow(list).isEmpty())
    }

    @Test fun activeTasksAreNeverReturnedAsOverflow() {
        val list = listOf(task(0, DownloadState.Queued)) + (1..21).map { task(it) } + task(22, DownloadState.Waiting)
        assertEquals(listOf("k21"), historyOverflow(list).map { it.key })
    }
}
