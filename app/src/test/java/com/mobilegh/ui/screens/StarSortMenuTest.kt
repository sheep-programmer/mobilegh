package com.mobilegh.ui.screens

import com.mobilegh.data.GitHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarSortMenuTest {
    /** 服务端真正认的 sort 值：REST 对其它值会静默回退成默认顺序，菜单里出现就是骗用户 */
    private val honoured = setOf("created", "updated", "stars")

    @Test fun everyStarMenuChoiceUsesASortTheServerReallyHonours() {
        for (choice in STAR_SORTS) {
            assertTrue(
                "菜单项「${choice.label}」用了服务端不支持的 sort=${choice.value}",
                choice.value in honoured,
            )
        }
    }

    @Test fun theMenuOffersStarTimeUpdatedAndStarCount() {
        assertEquals(listOf("Star 时间", "更新时间", "Star 数"), STAR_SORTS.map { it.label })
        assertEquals("Star 数", STAR_SORTS[2].label)
        assertEquals("stars", STAR_SORTS[2].value)
        // 选到每一项时默认降序（最近 / 最多的在前）
        assertTrue(STAR_SORTS.all { it.descending })
    }

    @Test fun starCountSortAsksTheRestEndpointForStars() {
        assertTrue(GitHub.starredUrl(null, 1, STAR_SORTS[2].value, "desc").contains("sort=stars"))
    }

    @Test fun theFirstChoiceIsStarTimeSoTheRssFeedPathStillApplies() {
        // 界面用 starSort == 0 判断「是否走 GraphQL 星标连接」，第一项必须仍是 Star 时间
        assertEquals("created", STAR_SORTS[0].value)
    }
}
