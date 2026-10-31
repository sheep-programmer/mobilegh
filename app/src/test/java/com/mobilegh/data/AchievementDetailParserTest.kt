package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用 GitHub 真实返回的悬停卡片片段（Pull Shark）验证说明和解锁历史的解析。 */
class AchievementDetailParserTest {
    private val fragment = javaClass.classLoader!!.getResource("achievement-detail-pull-shark.html")!!.readText()

    @Test fun descriptionIsTheTextUnderTheTitle() {
        val detail = Web.parseAchievementDetail(fragment)
        assertEquals("@torvalds opened pull requests that have been merged.", detail.description)
    }

    @Test fun historyListsTheUnlockDatesAndEvidence() {
        val detail = Web.parseAchievementDetail(fragment)
        assertTrue(detail.history.any { it.contains("Bronze unlocked") })
        assertTrue(detail.history.any { it.contains("subsurface/libdc#1") })
        assertTrue(detail.history.none { it.contains("<") })
    }
}
