package com.mobilegh.ui.screens

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBulkStopTest {
    @Test fun itemsNotYetStartedAreSkippedAfterCancel() = runBlocking {
        val started = mutableListOf<String>()
        var cancelled = false
        val result = runBulk(listOf("a", "b", "c"), {}, shouldStop = { cancelled }) { id ->
            started.add(id)
            // 第一条发出后用户点了取消：之后尚未发起的条目不再调用接口
            cancelled = true
        }
        assertEquals(listOf("a"), started)
        assertEquals(1, result.ok)
        assertEquals(2, result.skipped)
        assertEquals(0, result.failed)
    }

    @Test fun withoutStopEveryItemRuns() = runBlocking {
        val result = runBulk(listOf("x", "y"), {}) { }
        assertEquals(2, result.ok)
        assertEquals(0, result.skipped)
    }
}
