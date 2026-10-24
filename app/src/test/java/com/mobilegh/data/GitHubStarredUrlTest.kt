package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubStarredUrlTest {
    @Test fun ownStarsUseTheViewerEndpoint() {
        assertEquals(
            "/user/starred?sort=created&direction=desc&per_page=100&page=1",
            GitHub.starredUrl(null, 1, "created", "desc"),
        )
    }

    @Test fun otherPeopleStarsUseTheirLogin() {
        assertEquals(
            "/users/Alice/starred?sort=stars&direction=desc&per_page=100&page=2",
            GitHub.starredUrl("Alice", 2, "stars", "desc"),
        )
    }

    @Test fun starCountAndUpdatedAreForwardedVerbatim() {
        // REST 会静默忽略不认识的 sort 并回退成默认顺序，所以必须逐个确认真的发出去了
        assertTrue(GitHub.starredUrl(null, 1, "stars", "desc").contains("sort=stars"))
        assertTrue(GitHub.starredUrl(null, 1, "updated", "asc").contains("sort=updated"))
        assertTrue(GitHub.starredUrl(null, 1, "created", "asc").contains("sort=created"))
    }

    @Test fun ascendingAndDescendingAreForwarded() {
        assertTrue(GitHub.starredUrl(null, 1, "stars", "asc").contains("direction=asc"))
        assertTrue(GitHub.starredUrl(null, 1, "stars", "desc").contains("direction=desc"))
    }

    @Test fun thePageSizeIsOneHundredSoDeepStarListsNeedFewerPages() {
        // 2230 个 Star：per_page=30 要 75 页，per_page=100 只要 23 页
        assertTrue(GitHub.starredUrl(null, 1, "created", "desc").contains("per_page=100"))
        assertEquals(100, GitHub.STARRED_PER)
    }
}
