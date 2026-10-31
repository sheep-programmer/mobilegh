package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementTextTest {
    @Test fun knownSlugUsesChineseNameAndUnknownKeepsGitHubName() {
        assertEquals("拉取鲨鱼", AchievementText.name("pull-shark", "Pull Shark"))
        assertEquals("Some New Badge", AchievementText.name("some-new-badge", "Some New Badge"))
    }

    @Test fun pullSharkDescriptionIsTranslatedAndUnknownTextIsKept() {
        assertEquals("@torvalds 的拉取请求已被合并。", AchievementText.description("@torvalds opened pull requests that have been merged."))
        assertEquals("Something else entirely", AchievementText.description("Something else entirely"))
    }

    @Test fun historyTiersDatesAndMergeCountsAreTranslated() {
        assertEquals("铜级已解锁 · 首次解锁 2017-01-17 01:43:49 UTC",
            AchievementText.historyLine("Bronze unlocked · First unlocked 2017-01-17 01:43:49 UTC"))
        assertEquals("第 2 个已合并的拉取请求", AchievementText.historyLine("2nd pull request merged"))
        assertEquals("subsurface/libdc#1", AchievementText.historyLine("subsurface/libdc#1"))
    }
}
