package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 成就缓存：新鲜度判断、磁盘格式的读写 */
class WebCacheTest {
    @Test fun freshWindowIsFiveMinutes() {
        assertTrue(Web.isFresh(0))
        assertTrue(Web.isFresh(4 * 60_000L))
        assertFalse(Web.isFresh(5 * 60_000L + 1))
        // 时钟被往回调（读取时间在未来）：不当作新鲜，重新联网
        assertFalse(Web.isFresh(-1))
    }

    @Test fun staleCacheIsUsableForAWeekThenDropped() {
        assertTrue(Web.isUsableStale(6 * 24 * 3600_000L))
        assertFalse(Web.isUsableStale(8 * 24 * 3600_000L))
        assertFalse(Web.isUsableStale(-5))
    }

    @Test fun cacheRoundTripKeepsEveryField() {
        val badges = listOf(
            Achievement("Pull Shark", "https://github.githubassets.com/a.png", "gold", 3, "pull-shark"),
            Achievement("YOLO", "https://github.githubassets.com/b.png"),
        )
        val back = Web.decodeCache(Web.encodeCache(Web.CachedAchievements(1234L, badges)))
        assertNotNull(back)
        assertEquals(1234L, back!!.at)
        assertEquals(badges, back.badges)
    }

    @Test fun emptyListIsCachedToo() {
        // 没有成就的用户也要缓存，否则每次打开都重新联网确认「没有」
        val back = Web.decodeCache(Web.encodeCache(Web.CachedAchievements(1L, emptyList())))
        assertNotNull(back)
        assertTrue(back!!.badges.isEmpty())
    }

    @Test fun corruptedCacheIsIgnoredNotCrashing() {
        assertNull(Web.decodeCache("not json"))
        assertNull(Web.decodeCache("""{"at":"x"}"""))
    }
}
