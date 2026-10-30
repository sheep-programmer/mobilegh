package com.mobilegh.ui.screens

import com.mobilegh.data.Repo
import org.junit.Assert.assertEquals
import org.junit.Test

class RepoStarCountTest {
    private fun repo(stars: Int) = Repo(id = 1, name = "mobilegh", stargazersCount = stars)

    @Test fun starIncrementsCount() {
        assertEquals(1001, repoWithStar(repo(1000), on = true).stargazersCount)
    }

    @Test fun unstarDecrementsCount() {
        assertEquals(999, repoWithStar(repo(1000), on = false).stargazersCount)
    }

    @Test fun countNeverGoesBelowZero() {
        assertEquals(0, repoWithStar(repo(0), on = false).stargazersCount)
    }
}
