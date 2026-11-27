package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementTextTest {
    @Test fun knownSlugUsesChineseNameAndUnknownKeepsGitHubName() {
        assertEquals("拉取鲨鱼", AchievementText.name("pull-shark", "Pull Shark"))
        assertEquals("大胆合并 (YOLO)", AchievementText.name("yolo", "YOLO"))
        assertEquals("Some New Badge", AchievementText.name("some-new-badge", "Some New Badge"))
    }

    @Test fun achievementDescriptionsAreTranslatedAndUnknownTextIsKept() {
        assertEquals("@torvalds 的拉取请求已被合并。", AchievementText.description("@torvalds opened pull requests that have been merged."))
        assertEquals("@octocat 创建了获得大量 Star 的仓库。", AchievementText.description("@octocat created a repository that has many stars."))
        assertEquals("@octocat 在已合并的拉取请求中参与了共同提交。", AchievementText.description("@octocat coauthored commits on merged pull requests."))
        assertEquals("@yyx990803 在讨论中提供了被采纳的回答。", AchievementText.description("@yyx990803 answered discussions."))
        assertEquals("@nat 已赞助 44 位用户或组织。", AchievementText.description("@nat has sponsored 44 organizations or users."))
        assertEquals("@mona 在未经代码审查的情况下合并了拉取请求。", AchievementText.description("@mona merged a pull request without a review."))
        assertEquals("@mona 在创建后 5 分钟内关闭了 Issue 或拉取请求。", AchievementText.description("@mona closed an issue or pull request within 5 minutes of opening."))
        assertEquals("Something else entirely", AchievementText.description("Something else entirely"))
    }

    @Test fun historyTiersDatesAndEventsAreTranslated() {
        assertEquals("铜级已解锁 · 首次解锁 2017-01-17 01:43:49 UTC",
            AchievementText.historyLine("Bronze unlocked · First unlocked 2017-01-17 01:43:49 UTC"))
        assertEquals("铜级、银级已解锁 · 首次解锁 2021-01-30 13:16:47 UTC",
            AchievementText.historyLine("Bronze and Silver unlocked · First unlocked 2021-01-30 13:16:47 UTC"))
        assertEquals("100% 已解锁 · 解锁于 2020-02-02 08:00:00 UTC",
            AchievementText.historyLine("100% unlocked · Unlocked 2020-02-02 08:00:00 UTC"))
        assertEquals("第 2 个已合并的拉取请求", AchievementText.historyLine("2nd pull request merged"))
        assertEquals("vitejs/vite#1742 · 第 2 个被采纳的回答", AchievementText.historyLine("vitejs/vite#1742 · 2nd accepted answer"))
        assertEquals("octocat/Hello-World · ⭐️ 16 Star", AchievementText.historyLine("octocat/Hello-World · ⭐️ 16 stars"))
        assertEquals("JacobLinCool/Bang-C#35 · 与 @JacobLinCool 共同创作", AchievementText.historyLine("JacobLinCool/Bang-C#35 · Coauthored with @JacobLinCool"))
        assertEquals("不可访问的仓库 · 与未知用户共同创作", AchievementText.historyLine("inaccessible · Coauthored with an unknown user"))
        assertEquals("@hzoo · 第 1 次赞助", AchievementText.historyLine("@hzoo · 1st sponsorship"))
        assertEquals("subsurface/libdc#1", AchievementText.historyLine("subsurface/libdc#1"))
    }
}
