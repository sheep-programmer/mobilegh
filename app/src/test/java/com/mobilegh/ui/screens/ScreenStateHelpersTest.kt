package com.mobilegh.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateHelpersTest {
    // 星标列表的刷新策略：超过 5 分钟才在重新进入时刷新
    @Test fun starredDataIsFreshForFiveMinutesThenStale() {
        val loaded = 1_000_000L
        assertFalse(starListNeedsRefresh(loaded, loaded))
        assertFalse(starListNeedsRefresh(loaded, loaded + STAR_REFRESH_MAX_AGE_MS))
        assertTrue(starListNeedsRefresh(loaded, loaded + STAR_REFRESH_MAX_AGE_MS + 1))
    }

    // 星标列表选择器：只有写入成功才收起弹层，失败保留弹层与错误
    @Test fun pickerClosesOnlyAfterSuccessfulWriteThatWasRequested() {
        assertTrue(pickerDismissesAfterWrite(closeRequested = true, failed = false))
        assertFalse(pickerDismissesAfterWrite(closeRequested = true, failed = true))
        assertFalse(pickerDismissesAfterWrite(closeRequested = false, failed = false))
        assertFalse(pickerDismissesAfterWrite(closeRequested = false, failed = true))
    }

    // 邀请：已处理的 key 只在服务端不再列出时才清理，其它前缀保持不变
    @Test fun hiddenInvitesStayHiddenWhileServerStillListsThem() {
        assertEquals(
            setOf("r2", "o9"),
            pruneHiddenInvites(setOf("r1", "r2", "o9"), "r", setOf("r2")),
        )
        assertEquals(
            setOf("r1"),
            pruneHiddenInvites(setOf("r1"), "o", emptySet()),
        )
        assertEquals(
            setOf("o7"),
            pruneHiddenInvites(setOf("o7"), "r", setOf("r3")),
        )
    }
}
