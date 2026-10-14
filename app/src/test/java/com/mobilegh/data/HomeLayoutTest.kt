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
        assertEquals(listOf("issues", "pulls", "repos", "orgs", "stars", "lists", "gists", "recent"), defaults)
    }

    @Test fun shortcutsCanBeHiddenReorderedAndReset() {
        store.setShortcutVisible("alice", "orgs", false)
        store.moveShortcut("alice", 7, -7)
        val l = store.read("alice")
        assertEquals(listOf("recent", "issues", "pulls", "repos", "orgs", "stars", "lists", "gists"), l.slots().map { it.id })
        assertFalse(l.slots().first { it.id == "orgs" }.visible)
        assertFalse("orgs" in l.visibleSlots().map { it.id })
        store.moveShortcut("alice", 0, -1)
        assertEquals("recent", store.read("alice").slots().first().id)
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
        store.moveShortcut("alice", 8, -8)
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
}
