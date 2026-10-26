package com.mobilegh.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarSortMenuTest {
    /** /user/starred 只支持 created / updated；其它值会被静默忽略并回退成默认顺序。 */
    private val honoured = setOf("created", "updated")

    @Test fun everyStarMenuChoiceUsesASortTheServerReallyHonours() {
        for (choice in STAR_SORTS) {
            assertTrue(
                "菜单项「${choice.label}」用了服务端不支持的 sort=${choice.value}",
                choice.value in honoured,
            )
        }
    }

    @Test fun theMenuOnlyOffersStarTimeAndUpdateTime() {
        assertEquals(listOf("Star 时间", "更新时间"), STAR_SORTS.map { it.label })
        assertTrue(STAR_SORTS.all { it.descending })
    }

    @Test fun restoredSortIndexesAreClampedAfterMenuChoicesChange() {
        assertEquals(1, restoredStarSortIndex(2))
        assertEquals(0, restoredStarSortIndex(-1))
        assertEquals(1, restoredStarSortIndex(1))
    }

    @Test fun theFirstChoiceIsStarTimeSoTheGraphQlFeedPathStillApplies() {
        assertEquals("created", STAR_SORTS[0].value)
        assertEquals("updated", STAR_SORTS[1].value)
    }
}
