package com.mobilegh.nav

import com.mobilegh.data.BuiltinShortcut
import com.mobilegh.data.HomeLayout
import com.mobilegh.data.IssueQuery
import com.mobilegh.data.SavedSearch
import com.mobilegh.data.ShortcutSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class WidgetRoutesTest {
    @Test fun opensAddShortcutPageFromEmptyWidget() {
        assertEquals(WidgetDestination(Tab.Home, Screen.NewShortcut), WidgetRoutes.resolve("mobilegh://shortcut/new", HomeLayout(), "octo"))
    }

    @Test fun builtinShortcutsMatchHomeDestinations() {
        assertEquals(WidgetDestination(Tab.Home, Screen.MyIssues(pulls = true)), WidgetRoutes.resolve("mobilegh://shortcut/pulls", HomeLayout(), "octo"))
        assertEquals(WidgetDestination(Tab.Repos), WidgetRoutes.resolve("mobilegh://shortcut/repos", HomeLayout(), "octo"))
        assertEquals(WidgetDestination(Tab.Star), WidgetRoutes.resolve("mobilegh://shortcut/stars", HomeLayout(), "octo"))
        assertEquals(WidgetDestination(Tab.Home, Screen.Feed), WidgetRoutes.resolve("mobilegh://shortcut/activity", HomeLayout(), "octo"))
    }

    @Test fun savedSearchUsesCurrentAccountLayout() {
        val search = SavedSearch("abc", "Reviews", query = IssueQuery(pulls = true, text = "cache"))
        val layout = HomeLayout(searches = listOf(search))
        assertEquals(WidgetDestination(Tab.Home, Screen.MyIssues(pulls = true, preset = search.query)), WidgetRoutes.resolve("mobilegh://shortcut/search/abc", layout, "octo"))
        assertNull(WidgetRoutes.resolve("mobilegh://shortcut/search/missing", layout, "octo"))
    }

    @Test fun ordinaryGithubLinksAndUnknownShortcutsAreNotIntercepted() {
        assertNull(WidgetRoutes.resolve("https://github.com/o/r/pull/1", HomeLayout(), "octo"))
        assertNull(WidgetRoutes.resolve("mobilegh://shortcut/unknown", HomeLayout(), "octo"))
    }

    @Test fun everyBuiltinHasAWidgetDestination() {
        BuiltinShortcut.entries.forEach {
            val target = WidgetRoutes.resolve("mobilegh://shortcut/${it.id}", HomeLayout(), "octo")
            assertNotNull("Missing destination for ${it.id}", target)
        }
    }
}
