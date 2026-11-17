package com.mobilegh.data

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStoreTest {
    private fun week(vararg counts: Int) = ContribWeek(counts.mapIndexed { i, c -> ContribDay("2026-01-0${i + 1}", c, if (c > 0) "FIRST_QUARTILE" else "NONE", i) })

    @Test fun recentWeeksKeepsTailAndRecountsTotal() {
        val cal = ContribCalendar(totalContributions = 999, weeks = List(30) { week(1, 0, 2) } + week(5))
        val recent = WidgetStore.recentWeeks(cal, 20)
        assertEquals(20, recent.weeks.size)
        assertEquals(19 * 3 + 5, recent.totalContributions)
        assertEquals(5, recent.weeks.last().contributionDays.single().contributionCount)
    }

    @Test fun recentWeeksHandlesShortCalendars() {
        val recent = WidgetStore.recentWeeks(ContribCalendar(weeks = listOf(week(3))), 20)
        assertEquals(1, recent.weeks.size)
        assertEquals(3, recent.totalContributions)
    }

    @Test fun inboxTakesLatestUnreadSubjects() {
        fun n(id: String, unread: Boolean, at: String) = Notification(
            id, unread, updatedAt = at,
            subject = Subject("标题 $id", "https://api.github.com/repos/o/r/issues/$id"),
            repository = Repo(fullName = "o/r"),
        )
        val list = listOf(n("1", true, "2026-01-01T00:00:00Z"), n("2", false, "2026-01-05T00:00:00Z"), n("3", true, "2026-01-03T00:00:00Z"),
            n("4", true, "2026-01-04T00:00:00Z"), n("5", true, "2026-01-02T00:00:00Z"))
        val snap = WidgetStore.inboxOf("octocat", list, more = false, now = 42)
        assertEquals(4, snap.unread)
        assertEquals(listOf("标题 4", "标题 3", "标题 5"), snap.items.map { it.title })
        assertEquals("o/r", snap.items.first().repo)
        assertTrue(snap.items.first().url!!.endsWith("/issues/4"))
        assertEquals(42L, snap.at)
    }

    @Test fun pullRequestSnapshotFiltersSortsAndBuildsNativeUrls() {
        val pulls = (1..7).map { number ->
            Issue(
                number = number,
                title = "PR $number",
                state = if (number == 7) "closed" else "open",
                updatedAt = "2026-01-0${number}T00:00:00Z",
                htmlUrl = "https://github.com/octo/repo/pull/$number",
                repositoryUrl = "https://api.github.com/repos/octo/repo",
            )
        }
        val snap = WidgetStore.pullRequestsOf("octo", pulls, generation = 3, now = 42)
        assertEquals(5, snap.items.size)
        assertEquals(listOf(6, 5, 4, 3, 2), snap.items.map { it.number })
        assertEquals("octo/repo", snap.items.first().repo)
        assertEquals("2026-01-06T00:00:00Z", snap.items.first().updatedAt)
        assertEquals("https://github.com/octo/repo/pull/6", snap.items.first().url)
        assertEquals(3, snap.generation)
        assertEquals(42L, snap.at)
    }

    @Test fun emptyPullRequestsAndShortcutsRemainRealEmptySnapshots() {
        assertTrue(WidgetStore.pullRequestsOf("octo", emptyList(), 1).items.isEmpty())
        assertTrue(WidgetStore.shortcutsOf("octo", HomeLayout(shortcuts = BuiltinShortcut.entries.map { ShortcutSlot(it.id, visible = false) }), 1).items.isEmpty())
    }

    @Test fun shortcutsFollowVisibleHomeLayoutAndSearchTargets() {
        val search = SavedSearch("abc", "Reviews", query = IssueQuery(pulls = true))
        val layout = HomeLayout(
            shortcuts = listOf(ShortcutSlot("issues"), ShortcutSlot("pulls", visible = false), ShortcutSlot(HomeLayout.searchSlotId(search.id))) +
                BuiltinShortcut.entries.filterNot { it.id == "issues" || it.id == "pulls" }.map { ShortcutSlot(it.id, visible = false) },
            searches = listOf(search),
        )
        val snap = WidgetStore.shortcutsOf("octo", layout, generation = 4)
        assertEquals(listOf("issues", "search:abc"), snap.items.map { it.id })
        assertEquals("", snap.items.first().label)
        assertEquals("Issues", snap.items.first().labelKey)
        assertEquals("Reviews", snap.items.last().label)
        assertEquals(null, snap.items.last().labelKey)
        assertEquals("mobilegh://shortcut/issues", snap.items.first().url)
        assertEquals("mobilegh://shortcut/search/abc", snap.items.last().url)
        assertEquals(4, snap.generation)
    }

    @Test fun everyBuiltInShortcutHasAStableTranslationKey() {
        val items = BuiltinShortcut.entries.map { builtIn ->
            val layout = HomeLayout(shortcuts = BuiltinShortcut.entries.map { ShortcutSlot(it.id, visible = it.id == builtIn.id) })
            WidgetStore.shortcutsOf("octo", layout, 1).items.single()
        }
        assertEquals(BuiltinShortcut.entries.map { it.id }, items.map { it.id })
        assertTrue(items.all { it.label.isEmpty() && !it.labelKey.isNullOrBlank() })
        assertEquals(BuiltinShortcut.entries.size, items.map { it.labelKey }.distinct().size)
    }

    @Test fun shortcutsAreBounded() {
        val layout = HomeLayout(shortcuts = BuiltinShortcut.entries.map { ShortcutSlot(it.id) })
        assertEquals(WidgetStore.SHORTCUT_ITEMS, WidgetStore.shortcutsOf("octo", layout, 1).items.size)
    }

    @Test fun snapshotsRejectOtherAccountsAndGenerations() {
        val pulls = PullRequestSnapshot("octo", 2, listOf(PullRequestWidgetItem("o/r", 1, "One", "now", "https://github.com/o/r/pull/1")))
        val shortcuts = ShortcutSnapshot("octo", 2, listOf(ShortcutWidgetItem("issues", "Issues", "mobilegh://shortcut/issues")))
        assertEquals(pulls, pulls.visibleTo("OCTO", 2))
        assertEquals(null, pulls.visibleTo("other", 2))
        assertEquals(null, pulls.visibleTo("octo", 3))
        assertEquals(shortcuts, shortcuts.visibleTo("octo", 2))
        assertEquals(null, shortcuts.visibleTo("other", 2))
        assertEquals(null, shortcuts.visibleTo("octo", 3))
    }

    @Test fun blankPullRequestUrlUsesTheConfiguredEnterpriseWebOrigin() {
        val pull = Issue(
            number = 7,
            title = "Enterprise PR",
            state = "open",
            repositoryUrl = "https://api.git.example.corp/repos/octo/repo",
        )
        val snapshot = WidgetStore.pullRequestsOf(
            "octo",
            listOf(pull),
            generation = 3,
            webBase = "https://git.example.corp/",
        )
        assertEquals("https://git.example.corp/octo/repo/pull/7", snapshot.items.single().url)
    }

    @Test fun sameAccountSnapshotSurvivesProcessRecreation() {
        val persisted = PullRequestSnapshot("octo", 7)
        val shortcuts = ShortcutSnapshot("octo", 7)

        assertEquals(persisted, persisted.visibleTo("octo", 0))
        assertEquals(shortcuts, shortcuts.visibleTo("octo", 0))
        assertEquals(null, persisted.visibleTo("other", 0))
    }

    @Test fun deadPullRequestSaveEntryPointIsNotExposed() {
        assertTrue(WidgetStore::class.java.declaredMethods.none { it.name == "savePullRequests" })
    }

    @Test fun unchangedContributionSnapshotSkipsPreferenceWriteAndRefresh() {
        val field = WidgetStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val previousPrefs = field.get(WidgetStore)
        val previousListener = WidgetStore.listener
        val preferences = RecordingPreferences()
        var refreshes = 0
        try {
            field.set(WidgetStore, preferences.delegate)
            WidgetStore.listener = { if (it == WidgetStore.Kind.Contributions) refreshes++ }
            val calendar = ContribCalendar(weeks = listOf(week(1)))

            WidgetStore.saveContributions("octo", calendar)
            assertEquals(1, preferences.putStringCount)
            assertEquals(1, refreshes)

            WidgetStore.saveContributions("octo", calendar)
            assertEquals("an unchanged snapshot must not rewrite preferences", 1, preferences.putStringCount)
            assertEquals("an unchanged snapshot must not refresh widgets", 1, refreshes)

            WidgetStore.saveContributions("octo", ContribCalendar(weeks = listOf(week(2))))
            assertEquals(2, preferences.putStringCount)
            assertEquals(2, refreshes)
        } finally {
            WidgetStore.listener = previousListener
            field.set(WidgetStore, previousPrefs)
        }
    }

    @Test fun widgetKindsRemainIndependent() {
        assertEquals(setOf(WidgetStore.Kind.Contributions, WidgetStore.Kind.Inbox, WidgetStore.Kind.PullRequests, WidgetStore.Kind.Shortcuts), WidgetStore.Kind.entries.toSet())
    }
}

private class RecordingPreferences {
    var putStringCount = 0
    private val values = mutableMapOf<String, String?>()
    private lateinit var editor: SharedPreferences.Editor

    val delegate: SharedPreferences

    init {
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    putStringCount++
                    values[args[0] as String] = args[1] as String?
                    editor
                }
                "apply" -> null
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        delegate = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0] as String]
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}
