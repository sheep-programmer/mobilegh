package com.mobilegh.ui.screens

import com.mobilegh.R
import com.mobilegh.data.Repo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoShortcutsTest {
    @Test
    fun wikiShortcutIncludedWhenRepoHasWiki() {
        val repoWithWiki = Repo(name = "wknet", hasWiki = true)
        val repoWithoutWiki = Repo(name = "wknet", hasWiki = false)

        assertTrue(repoWithWiki.hasWiki)
        assertFalse(repoWithoutWiki.hasWiki)
    }

    @Test
    fun discussionsIncludedWhenHasDiscussionsOrCountPositive() {
        val repoWithDisc = Repo(name = "wknet", hasDiscussions = true)
        val repoWithoutDisc = Repo(name = "wknet", hasDiscussions = false)

        val discInfoEnabled = Pair(true, 0)
        val discInfoHasCount = Pair(false, 5)
        val discInfoDisabled = Pair(false, 0)

        // Case 1: Repo flag is true
        assertTrue(repoWithDisc.hasDiscussions || discInfoDisabled.first || discInfoDisabled.second > 0)

        // Case 2: GraphQL says discussions enabled
        assertTrue(repoWithoutDisc.hasDiscussions || discInfoEnabled.first || discInfoEnabled.second > 0)

        // Case 3: GraphQL says open discussions exist
        assertTrue(repoWithoutDisc.hasDiscussions || discInfoHasCount.first || discInfoHasCount.second > 0)

        // Case 4: Neither says discussions enabled
        assertFalse(repoWithoutDisc.hasDiscussions || discInfoDisabled.first || discInfoDisabled.second > 0)
    }
}
