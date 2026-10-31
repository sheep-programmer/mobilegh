package com.mobilegh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 未读数角标：回到前台时刷新，但 60 秒内只拉取一次 */
class BadgeRefreshTest {
    @Test fun firstFetchIsAlwaysDue() {
        assertTrue(badgeRefreshDue(null, 0L))
    }

    @Test fun fetchIsThrottledToOncePerMinute() {
        val last = 1_000L
        assertFalse(badgeRefreshDue(last, last + BADGE_REFRESH_MIN_INTERVAL_MS - 1))
        assertTrue(badgeRefreshDue(last, last + BADGE_REFRESH_MIN_INTERVAL_MS))
        assertTrue(badgeRefreshDue(last, last + 5 * 60_000L))
    }
}
