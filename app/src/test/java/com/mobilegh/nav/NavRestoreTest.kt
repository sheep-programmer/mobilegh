package com.mobilegh.nav

import com.mobilegh.MainDispatcherRule
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.IssueStateFilter
import com.mobilegh.data.ProjectOwnerKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavRestoreTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()
    private val stack = listOf(
        Screen.Repo("octo", "hello"),
        Screen.FileView("octo", "hello", "README.md", "main", 3..9),
        Screen.Search(type = 2, query = "kind"),
        Screen.Projects(ProjectOwnerKind.Org, "octo", ""),
        Screen.Issues("octo", "hello", pulls = true,
            preset = IssueQuery(pulls = true, repo = "octo/hello", state = IssueStateFilter.Closed, text = "bug")),
        Screen.EditRelease("octo", "hello", 42L),
        Screen.Users(UserKind.Followers, "octo"),
        Screen.Repos(RepoKind.Org, "octo"),
        Screen.StarLists("octo", local = true),
        Screen.GistEditor(null),
        Screen.Settings,
        Screen.ImportRepo,
    )

    @Test fun newIssueParentIdentitySurvivesNavigationRestore() {
        val parent = Screen.NewIssue("octo", "hello", parentNodeId = "I_parent", parentNumber = 42)
        assertEquals(parent, NavRestore.decode(NavRestore.encode(Tab.Repos, listOf(parent), "octo"), "octo")?.screens?.single())
        val old = NavRestore.decode("""{"v":1,"login":"octo","tab":"Repos","stack":[{"_screen":"com.mobilegh.nav.Screen.NewIssue","owner":"octo","name":"hello"}]}""", "octo")
        assertEquals(Screen.NewIssue("octo", "hello"), old?.screens?.single())
    }

    @Test fun roundTripKeepsTabAndEveryScreen() {
        val text = NavRestore.encode(Tab.Repos, stack, "octo")
        assertEquals(RestoredNav(Tab.Repos, stack), NavRestore.decode(text, "octo"))
    }

    @Test fun actionsSettingsSectionsSurviveNavigationRestore() {
        ActionsSettingsSection.entries.forEach { section ->
            val screen = Screen.ActionsSettings("octo", "hello", section)
            val restored = NavRestore.decode(
                NavRestore.encode(Tab.Repos, listOf(screen), "octo"),
                "octo",
            )
            assertEquals(screen, restored?.screens?.single())
        }
    }

    @Test fun encodeDropsTheRootEntry() {
        val text = NavRestore.encode(Tab.Home, listOf(Screen.Root, Screen.Repo("a", "b")), null)
        assertEquals(RestoredNav(Tab.Home, listOf(Screen.Repo("a", "b"))), NavRestore.decode(text, null))
    }

    @Test fun unknownTypesAndBrokenItemsAreDroppedAndTheRestKept() {
        val text = """{"v":1,"login":"octo","tab":"Notifications","stack":[
            {"_screen":"com.mobilegh.nav.Screen.Repo","owner":"a","name":"b"},
            {"_screen":"com.mobilegh.nav.Screen.Removed","owner":"x"},
            {"_screen":"com.mobilegh.nav.Screen.Repo","owner":"a"},
            {"_screen":"com.mobilegh.nav.Screen.Settings"}]}"""
        assertEquals(
            RestoredNav(Tab.Notifications, listOf(Screen.Repo("a", "b"), Screen.Settings)),
            NavRestore.decode(text, "octo"),
        )
    }

    @Test fun validEnvelopeWithOnlyUnknownItemsKeepsTheSavedTab() {
        val text = """{"v":1,"login":null,"tab":"Explore","stack":[{"_screen":"com.mobilegh.nav.Screen.Gone"}]}"""
        assertEquals(RestoredNav(Tab.Explore, emptyList()), NavRestore.decode(text, null))
    }

    @Test fun emptyStackOnRootTabRoundTrips() {
        val text = NavRestore.encode(Tab.Me, emptyList(), "octo")
        assertEquals(RestoredNav(Tab.Me, emptyList()), NavRestore.decode(text, "octo"))
    }

    @Test fun missingOrBlankInputFallsBackToHome() {
        assertEquals(RestoredNav(Tab.Home, emptyList()), NavRestore.decode(null, "octo"))
        assertEquals(RestoredNav(Tab.Home, emptyList()), NavRestore.decode("", null))
        assertEquals(RestoredNav(Tab.Home, emptyList()), NavRestore.decode("   ", null))
    }

    @Test fun corruptInputFallsBackToHome() {
        val home = RestoredNav(Tab.Home, emptyList())
        assertEquals(home, NavRestore.decode("{not json", "octo"))
        assertEquals(home, NavRestore.decode("[1,2,3]", "octo"))
        assertEquals(home, NavRestore.decode("42", "octo"))
        assertEquals(home, NavRestore.decode("""{"v":1,"login":"octo","tab":"Repos"}""", "octo"))
        assertEquals(home, NavRestore.decode("""{"v":1,"login":"octo","tab":"Repos","stack":"oops"}""", "octo"))
    }

    @Test fun unsupportedVersionOrUnknownTabFallsBackToHome() {
        val home = RestoredNav(Tab.Home, emptyList())
        assertEquals(home, NavRestore.decode("""{"v":2,"login":null,"tab":"Repos","stack":[]}""", null))
        assertEquals(home, NavRestore.decode("""{"login":null,"tab":"Repos","stack":[]}""", null))
        assertEquals(home, NavRestore.decode("""{"v":1,"login":null,"tab":"Nope","stack":[]}""", null))
    }

    @Test fun savedAccountMustMatchTheCurrentLogin() {
        val home = RestoredNav(Tab.Home, emptyList())
        val text = NavRestore.encode(Tab.Repos, stack, "octo")
        assertEquals(home, NavRestore.decode(text, "someone-else"))
        assertEquals(home, NavRestore.decode(text, null))
    }

    @Test fun navigatorRestoreRebuildsTabAndStack() {
        val nav = Navigator()
        val restored = RestoredNav(Tab.Repos, listOf(Screen.Repo("octo", "hello"), Screen.Files("octo", "hello", "src", null)))
        nav.restore(restored)
        assertEquals(Tab.Repos, nav.tab)
        assertEquals(restored.screens, nav.stack.map { it.screen })
    }
}
