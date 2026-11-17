package com.mobilegh.nav

import com.mobilegh.data.HomeLayout
import com.mobilegh.data.ProjectOwnerKind

data class WidgetDestination(val tab: Tab, val screen: Screen? = null)

/** Resolve only the app's own shortcut scheme; normal GitHub links keep using Links. */
object WidgetRoutes {
    private const val PREFIX = "mobilegh://shortcut/"

    fun resolve(url: String, layout: HomeLayout, login: String): WidgetDestination? {
        if (!url.startsWith(PREFIX)) return null
        val id = url.removePrefix(PREFIX)
        val home = { screen: Screen -> WidgetDestination(Tab.Home, screen) }
        return when (id) {
            "new" -> home(Screen.NewShortcut)
            "activity" -> home(Screen.Feed)
            "issues" -> home(Screen.MyIssues(pulls = false))
            "pulls" -> home(Screen.MyIssues(pulls = true))
            "favorites" -> home(Screen.Favorites)
            "saved" -> home(Screen.Saved)
            "repos" -> WidgetDestination(Tab.Repos)
            "orgs" -> home(Screen.Orgs)
            "stars" -> WidgetDestination(Tab.Star)
            "lists" -> home(Screen.StarLists(login))
            "gists" -> home(Screen.Gists(null))
            "recent" -> home(Screen.RecentRepositories)
            "projects" -> home(Screen.Projects(ProjectOwnerKind.User, login))
            "drafts" -> home(Screen.Drafts)
            "invitations" -> home(Screen.Invitations)
            else -> id.removePrefix("search/").takeIf { id.startsWith("search/") }
                ?.let { layout.search(HomeLayout.searchSlotId(it)) }
                ?.let { home(Screen.MyIssues(it.query.pulls, it.query)) }
        }
    }
}
