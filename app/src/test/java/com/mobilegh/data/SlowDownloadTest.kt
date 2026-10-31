package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 慢速判定：有进度但连续一个窗口（45 秒）平均速度低于 64 KB/s，且还有下一个候选节点，才换节点。
 * 窗口没满、没有候选、剩下的数据一个窗口内就能下完，都不换。
 */
class SlowDownloadTest {
    private val mb = 1024L * 1024L

    @Test fun fastDownloadIsLeftAlone() {
        assertFalse(isSlowDownload(windowMs = SLOW_WINDOW_MS, windowBytes = 20 * mb, remainingBytes = null, hasNextCandidate = true))
    }

    @Test fun slowDownloadWithAnotherNodeRotates() {
        // 45 秒只下了 1 MB，约 23 KB/s
        assertTrue(isSlowDownload(windowMs = SLOW_WINDOW_MS, windowBytes = 1 * mb, remainingBytes = null, hasNextCandidate = true))
        assertTrue(isSlowDownload(windowMs = SLOW_WINDOW_MS, windowBytes = 1 * mb, remainingBytes = 50 * mb, hasNextCandidate = true))
    }

    @Test fun windowMustBeFullBeforeJudging() {
        assertFalse(isSlowDownload(windowMs = SLOW_WINDOW_MS - 1, windowBytes = 0, remainingBytes = null, hasNextCandidate = true))
        // 只监控了 20 秒，速度再低也还不算数
        assertFalse(isSlowDownload(windowMs = 20_000, windowBytes = 100_000, remainingBytes = null, hasNextCandidate = true))
        assertFalse(isSlowDownload(windowMs = 0, windowBytes = 0, remainingBytes = null, hasNextCandidate = true))
    }

    @Test fun noNextCandidateNeverRotates() {
        // 私有下载、手动节点、或已经是最后一个候选：再慢也只能等
        assertFalse(isSlowDownload(windowMs = SLOW_WINDOW_MS, windowBytes = 0, remainingBytes = null, hasNextCandidate = false))
    }

    @Test fun thresholdIsExactlySixtyFourKilobytesPerSecond() {
        val atThreshold = SLOW_BYTES_PER_SEC * SLOW_WINDOW_MS / 1000
        assertEquals(65536L * 45, atThreshold)
        assertFalse(isSlowDownload(SLOW_WINDOW_MS, atThreshold, null, true))
        assertTrue(isSlowDownload(SLOW_WINDOW_MS, atThreshold - 1, null, true))
    }

    @Test fun nearlyFinishedDownloadIsNotRestarted() {
        // 23 KB/s 的速度下，剩下 100 KB 一个窗口内就能下完，换节点从头下反而慢
        assertFalse(isSlowDownload(SLOW_WINDOW_MS, 1 * mb, remainingBytes = 100_000, hasNextCandidate = true))
        // 剩下 50 MB 就不一样了
        assertTrue(isSlowDownload(SLOW_WINDOW_MS, 1 * mb, remainingBytes = 50 * mb, hasNextCandidate = true))
    }
}
