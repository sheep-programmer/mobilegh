package com.mobilegh.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.mobilegh.data.ProjectOwnerKind
import com.mobilegh.nav.Navigator

class RepoHomeTest {
    @Test fun repoScopedPagesBelongToTheirRepositoryHome() {
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.Releases("octo", "hello")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.IssueDetail("octo", "hello", 7, false)))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.CommitDetail("octo", "hello", "abc")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.RunDetail("octo", "hello", 1L)))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.JobLog("octo", "hello", 2L, "build")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.Files("octo", "hello", "src", "main")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.FileView("octo", "hello", "src/A.kt", "main")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.Projects(ProjectOwnerKind.Repo, "octo", "hello")))
        assertEquals(Screen.Repo("octo", "hello"), repoHomeOf(Screen.Users(UserKind.Stargazers, "octo", "hello")))
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

    @Test fun aLinkToAnotherIssueReturnsToTheIssueItWasOpenedFrom() {
        val nav = Navigator()
        nav.pushLink(Screen.IssueDetail("octo", "hello", 314, false))
        nav.pushLink(Screen.IssueDetail("octo", "hello", 31, false))
        // 不能插入仓库主页：#31 的下一层必须还是 #314
        assertEquals(
            listOf(
                Screen.Repo("octo", "hello"),
                Screen.IssueDetail("octo", "hello", 314, false),
                Screen.IssueDetail("octo", "hello", 31, false),
            ),
            stack(nav),
        )
        nav.pop()
        assertEquals(
            listOf(Screen.Repo("octo", "hello"), Screen.IssueDetail("octo", "hello", 314, false)),
            stack(nav),
        )
    }

    @Test fun aLinkToAnotherRepositoryReturnsToThePageItWasOpenedFrom() {
        val nav = Navigator()
        nav.push(Screen.IssueDetail("octo", "hello", 314, false))
        nav.pushLink(Screen.IssueDetail("other", "world", 7, true))
        // 跨仓库也一样：返回应该回到原来的 Issue，而不是先经过另一个仓库主页
        assertEquals(
            listOf(Screen.IssueDetail("octo", "hello", 314, false), Screen.IssueDetail("other", "world", 7, true)),
            stack(nav),
        )
        nav.pop()
        assertEquals(listOf(Screen.IssueDetail("octo", "hello", 314, false)), stack(nav))
    }

    @Test fun linksInsideARepositoryDoNotReopenItsHome() {
        val nav = Navigator()
        nav.push(Screen.Repo("octo", "hello"))
        nav.push(Screen.Files("octo", "hello", "src", "main"))
        nav.pushLink(Screen.FileView("octo", "hello", "src/A.kt", "main"))
        assertEquals(
            listOf(
                Screen.Repo("octo", "hello"),
                Screen.Files("octo", "hello", "src", "main"),
                Screen.FileView("octo", "hello", "src/A.kt", "main"),
            ),
            stack(nav),
        )
    }

    @Test fun aLinkToThePageThatIsAlreadyOpenIsIgnored() {
        val nav = Navigator()
        val issue = Screen.IssueDetail("octo", "hello", 31, false)
        nav.pushLink(issue)
        nav.pushLink(issue)
        assertEquals(listOf(Screen.Repo("octo", "hello"), issue), stack(nav))
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
