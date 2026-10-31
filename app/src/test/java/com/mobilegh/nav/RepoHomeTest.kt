package com.mobilegh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.mobilegh.nav.Navigator

class RepoHomeTest {
    @Test fun repoScopedPagesBelongToTheirRepositoryHome() {
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.Releases("octo", "hello")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.IssueDetail("octo", "hello", 7, false)))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.CommitDetail("octo", "hello", "abc")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.RunDetail("octo", "hello", 1L)))
    }

    @Test fun screensOutsideARepositoryHaveNoRepoHome() {
        assertNull(repoHomeOf(Screen.Profile("octo")))
        assertNull(repoHomeOf(Screen.Gists(null)))
        assertNull(repoHomeOf(Screen.Repo("octo", "hello")))
    }
}

class LinkStackTest {
    private fun stack(nav: Navigator) = nav.stack.map { it.screen }

    @Test fun aReleaseOpenedFromALinkSitsAboveItsRepositoryHome() {
        val nav = Navigator()
        nav.pushLink(Screen.Releases("octo", "hello"))
        assertEquals(listOf(Screen.Repo("octo", "hello"), Screen.Releases("octo", "hello")), stack(nav))
    }

    @Test fun tappingTheRepositoryNameReturnsToItsHomeWithoutDuplicatingIt() {
        val nav = Navigator()
        nav.pushLink(Screen.Releases("octo", "hello"))
        nav.toRepoHome("octo", "hello")
        assertEquals(listOf(Screen.Repo("octo", "hello")), stack(nav))
    }

    @Test fun pushingAnEntryTwiceDoesNotStackTheHomeTwice() {
        val nav = Navigator()
        nav.pushLink(Screen.Repo("octo", "hello"))
        nav.pushLink(Screen.Releases("octo", "hello"))
        assertEquals(listOf(Screen.Repo("octo", "hello"), Screen.Releases("octo", "hello")), stack(nav))
    }
}
