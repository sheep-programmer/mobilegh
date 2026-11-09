package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test

class HomeLayoutTest {
    private val storage = MemoryRecords()
    private val store = HomeLayoutStore(storage)
    private val defaults = BuiltinShortcut.entries.map { it.id }

    @Test fun neverEditedLayoutEqualsTheCurrentDefaultShortcuts() {
        val l = store.read("alice")
        assertNull(l.shortcuts)
        assertEquals(defaults, l.visibleSlots().map { it.id })
        assertEquals(listOf("activity", "issues", "pulls", "favorites", "saved", "repos", "orgs", "stars", "lists", "gists", "recent", "projects", "drafts", "invitations"), defaults)
    }

    @Test fun shortcutsCanBeHiddenReorderedAndReset() {
        store.setShortcutVisible("alice", "orgs", false)
        store.moveShortcut("alice", defaults.size - 1, -(defaults.size - 1))
        val l = store.read("alice")
        assertEquals(listOf(defaults.last()) + defaults.dropLast(1), l.slots().map { it.id })
        assertFalse(l.slots().first { it.id == "orgs" }.visible)
        assertFalse("orgs" in l.visibleSlots().map { it.id })
        store.moveShortcut("alice", 0, -1)
        assertEquals(defaults.last(), store.read("alice").slots().first().id)
        store.resetShortcuts("alice")
        assertEquals(defaults, store.read("alice").visibleSlots().map { it.id })
    }

    @Test fun unknownIdsAreDroppedAndMissingBuiltinsAppended() {
        val l = HomeLayout(shortcuts = listOf(ShortcutSlot("pulls"), ShortcutSlot("gone"), ShortcutSlot("pulls", false)))
        assertEquals(listOf("pulls") + defaults.filterNot { it == "pulls" }, l.slots().map { it.id })
    }

    @Test fun savedSearchesAppearAsShortcutsAndAreRemovedCleanly() {
        val q = IssueQuery(pulls = true, repo = "mona/repo", labels = listOf("bug"))
        val s = store.addSearch("alice", " 我的 Bug ", "bug", q)
        assertEquals("我的 Bug", s.name)
        var l = store.read("alice")
        assertEquals(defaults + HomeLayout.searchSlotId(s.id), l.visibleSlots().map { it.id })
        assertEquals(q, l.search(HomeLayout.searchSlotId(s.id))!!.query)
        store.moveShortcut("alice", defaults.size, -defaults.size)
        assertEquals(HomeLayout.searchSlotId(s.id), store.read("alice").slots().first().id)
        store.removeSearch("alice", s.id)
        l = store.read("alice")
        assertTrue(l.searches.isEmpty())
        assertEquals(defaults, l.slots().map { it.id })
    }

    @Test fun savedSearchValidatesNameAndIcon() {
        assertThrows(IllegalArgumentException::class.java) { store.addSearch("alice", "  ", "bug", IssueQuery()) }
        assertThrows(IllegalArgumentException::class.java) { store.addSearch("alice", "x".repeat(HomeLayout.MAX_NAME + 1), "bug", IssueQuery()) }
        assertEquals("search", store.addSearch("alice", "ok", "not-an-icon", IssueQuery()).icon)
    }

    @Test fun favoritesToggleIgnoreCaseAndMoveWithinBounds() {
        assertTrue(store.toggleFavorite("alice", "mona", "one"))
        assertTrue(store.toggleFavorite("alice", "mona", "two"))
        assertTrue(store.toggleFavorite("alice", "mona", "three"))
        store.moveFavorite("alice", 2, -1)
        assertEquals(listOf("mona/one", "mona/three", "mona/two"), store.read("alice").favorites.map { it.fullName })
        store.moveFavorite("alice", 0, -1)
        assertEquals("mona/one", store.read("alice").favorites.first().fullName)
        assertFalse(store.toggleFavorite("alice", "MONA", "ONE"))
        assertEquals(listOf("mona/three", "mona/two"), store.read("alice").favorites.map { it.fullName })
        store.removeFavorite("alice", FavoriteRepo("mona", "two"))
        assertTrue(store.read("alice").isFavorite("Mona", "Three"))
        assertTrue(store.read("bob").favorites.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.toggleFavorite("alice", "bad owner", "x") }
    }

    @Test fun undoingARemovedFavoriteRestoresItsOriginalPosition() {
        store.toggleFavorite("alice", "mona", "one")
        store.toggleFavorite("alice", "mona", "two")
        store.toggleFavorite("alice", "mona", "three")
        val two = FavoriteRepo("mona", "two")
        store.removeFavorite("alice", two)
        assertEquals(listOf("mona/one", "mona/three"), store.read("alice").favorites.map { it.fullName })
        store.restoreFavorite("alice", 1, two)
        assertEquals(listOf("mona/one", "mona/two", "mona/three"), store.read("alice").favorites.map { it.fullName })
        // 已经存在时不重复加入
        store.restoreFavorite("alice", 0, two)
        assertEquals(3, store.read("alice").favorites.size)
    }

    @Test fun corruptOrInvalidStoredDataFallsBackSafely() {
        storage.values["home:alice"] = "{broken"
        assertEquals(HomeLayout(), store.read("alice"))
        storage.values["home:alice"] = """{"favorites":[{"owner":"ok","name":"repo"},{"owner":"../x","name":"y"}],"searches":[{"id":"1","name":"n","icon":"weird"}]}"""
        val l = store.read("alice")
        assertEquals(listOf("ok/repo"), l.favorites.map { it.fullName })
        assertEquals("search", l.searches.single().icon)
    }

    @Test fun layoutRoundTripsThroughStorage() {
        store.toggleFavorite("alice", "mona", "repo")
        store.addSearch("alice", "PR", "pr", IssueQuery(pulls = true, involves = Involvement.ReviewRequested, sort = IssueSort.Updated))
        val again = HomeLayoutStore(storage).read("ALICE")
        assertEquals(1, again.favorites.size)
        assertEquals(Involvement.ReviewRequested, again.searches.single().query.involves)
        assertEquals(IssueSort.Updated, again.searches.single().query.sort)
    }

    @Test fun movedHelperIgnoresOutOfRange() {
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(0, -1))
        assertEquals(listOf(2, 1, 3), listOf(1, 2, 3).moved(0, 1))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).moved(5, 1))
    }

    @Test fun syncWithPinnedPopulatesEmptyFavoritesAndMergesCleanly() {
        val pins = listOf(
            PinnedRepo(name = "pinned-1", owner = GqlOwner(login = "alice")),
            PinnedRepo(name = "pinned-2", owner = GqlOwner(login = "alice")),
        )
        assertFalse(store.read("alice").pinnedSynced)
        assertTrue(store.read("alice").favorites.isEmpty())

        // Initial sync when empty populates favorites
        store.syncWithPinned("alice", pins, merge = false)
        val l1 = store.read("alice")
        assertTrue(l1.pinnedSynced)
        assertEquals(listOf("alice/pinned-1", "alice/pinned-2"), l1.favorites.map { it.fullName })

        // Add a local favorite
        store.toggleFavorite("alice", "mona", "local-repo")
        assertEquals(3, store.read("alice").favorites.size)

        // New pinned list from GitHub: pinned-2 and pinned-3
        val updatedPins = listOf(
            PinnedRepo(name = "pinned-2", owner = GqlOwner(login = "alice")),
            PinnedRepo(name = "pinned-3", owner = GqlOwner(login = "alice")),
        )
        // With merge = true, local-repo is kept, pinned-3 is appended, pinned-2 is not duplicated
        store.syncWithPinned("alice", updatedPins, merge = true)
        val l2 = store.read("alice")
        assertEquals(listOf("alice/pinned-1", "alice/pinned-2", "mona/local-repo", "alice/pinned-3"), l2.favorites.map { it.fullName })

        // With merge = false, completely overwrites with updatedPins
        store.syncWithPinned("alice", updatedPins, merge = false)
        val l3 = store.read("alice")
        assertEquals(listOf("alice/pinned-2", "alice/pinned-3"), l3.favorites.map { it.fullName })
    }
}
