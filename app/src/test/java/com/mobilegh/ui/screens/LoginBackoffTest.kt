package com.mobilegh.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** 设备授权轮询的网络重试：指数退避但有上限，并能给出倒计时 */
class LoginBackoffTest {
    @Test fun delayDoublesFromThePollIntervalUntilTheCap() {
        var delay = 5_000L
        val steps = mutableListOf<Long>()
        repeat(6) {
            delay = nextRetryDelayMs(5_000L, delay)
            steps += delay
        }
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L), steps)
    }

    @Test fun totalSilenceAfterFiveFailuresIsBounded() {
        var delay = 5_000L
        var total = 0L
        repeat(5) {
            delay = nextRetryDelayMs(5_000L, delay)
            total += delay
        }
        // 原来约 5 分钟（315 秒），现在是 120 秒
        assertEquals(10_000L + 20_000L + 30_000L * 3, total)
    }

    @Test fun retryNeverWaitsLessThanThePollInterval() {
        // 多次 slow_down 后轮询间隔已超过上限，重试不能比正常轮询更频繁
        assertEquals(35_000L, nextRetryDelayMs(35_000L, 5_000L))
        assertEquals(LOGIN_RETRY_CAP_MS, nextRetryDelayMs(5_000L, 20_000L))
    }

    @Test fun countdownRoundsUpAndStopsAtZero() {
        assertEquals(0, retrySecondsLeft(0L, 1_000L))
        assertEquals(6, retrySecondsLeft(10_000L, 4_001L))
        assertEquals(5, retrySecondsLeft(10_000L, 5_000L))
        assertEquals(0, retrySecondsLeft(10_000L, 10_000L))
        assertEquals(0, retrySecondsLeft(10_000L, 12_000L))
    }
}
