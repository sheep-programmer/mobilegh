package com.mobilegh.ui

import com.mobilegh.data.HomeLayout
import com.mobilegh.ui.screens.AchievementRowState
import com.mobilegh.ui.screens.OrgRowTarget
import com.mobilegh.ui.screens.achievementRowState
import com.mobilegh.ui.screens.orgRowTarget
import com.mobilegh.ui.screens.savedSearchNameOverflow
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeProfileRowLogicTest {
    @Test
    fun achievementRowIsLoadingBeforeAnyResult() {
        assertEquals(AchievementRowState.Loading, achievementRowState(null, hasError = false))
    }

    @Test
    fun achievementRowShowsErrorOnlyWhenNothingLoaded() {
        assertEquals(AchievementRowState.Error, achievementRowState(null, hasError = true))
    }

    @Test
    fun achievementRowShowsEmptyForZeroBadges() {
        assertEquals(AchievementRowState.Empty, achievementRowState(0, hasError = false))
    }

    @Test
    fun achievementRowKeepsBadgesWhenRefreshFailed() {
        // 已经读到的徽章优先于后续刷新的错误
        assertEquals(AchievementRowState.Badges, achievementRowState(3, hasError = true))
        assertEquals(AchievementRowState.Badges, achievementRowState(5, hasError = false))
    }

    @Test
    fun orgRowAlwaysGoesToMyOrgsForSelf() {
        assertEquals(OrgRowTarget.MyOrgs, orgRowTarget(isSelf = true, orgCount = 0))
        assertEquals(OrgRowTarget.MyOrgs, orgRowTarget(isSelf = true, orgCount = 3))
    }

    @Test
    fun orgRowOpensSingleOrgDirectly() {
        assertEquals(OrgRowTarget.Single, orgRowTarget(isSelf = false, orgCount = 1))
    }

    @Test
    fun orgRowOpensListForSeveralOrEmpty() {
        assertEquals(OrgRowTarget.List, orgRowTarget(isSelf = false, orgCount = 0))
        assertEquals(OrgRowTarget.List, orgRowTarget(isSelf = false, orgCount = 2))
    }

    @Test
    fun savedSearchNameOverflowCountsOnlyCharactersOverLimit() {
        val max = HomeLayout.MAX_NAME
        assertEquals(0, savedSearchNameOverflow("", max))
        assertEquals(0, savedSearchNameOverflow("a".repeat(max), max))
        assertEquals(1, savedSearchNameOverflow("a".repeat(max + 1), max))
        assertEquals(8, savedSearchNameOverflow("a".repeat(max + 8), max))
    }

    @Test
    fun savedSearchNameOverflowIgnoresSurroundingWhitespace() {
        val max = HomeLayout.MAX_NAME
        assertEquals(0, savedSearchNameOverflow("  " + "a".repeat(max) + "  ", max))
    }

    @Test
    fun savedSearchNameOverflowDefaultsToHomeLimit() {
        assertEquals(0, savedSearchNameOverflow("a".repeat(HomeLayout.MAX_NAME)))
        assertEquals(1, savedSearchNameOverflow("a".repeat(HomeLayout.MAX_NAME + 1)))
    }
}
