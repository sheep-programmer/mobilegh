package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StarListCatalogCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private fun list(id: String, owner: String, count: Int = 3) = GitHubStarList(
        id = id, name = "list-$id", description = "d", isPrivate = false,
        slug = "list-$id", owner = owner, count = count,
    )

    @Test fun roundTripsListsForTheSameAccountAndViewedUser() {
        val cache = StarListCatalogCache(folder.root)
        val lists = listOf(list("1", "alice"), list("2", "alice", count = 9))

        cache.write("me", "alice", lists)

        assertEquals(lists, cache.read("me", "alice"))
    }

    @Test fun accountMatchIsCaseInsensitive() {
        val cache = StarListCatalogCache(folder.root)
        val lists = listOf(list("1", "Alice"))

        cache.write("Me", "Alice", lists)

        assertEquals(lists, cache.read("me", "alice"))
    }

    @Test fun differentAccountOrViewedUserNeverReadsAnotherEntry() {
        val cache = StarListCatalogCache(folder.root)
        cache.write("me", "alice", listOf(list("1", "alice")))

        assertNull(cache.read("me", "bob"))
        assertNull(cache.read("someone-else", "alice"))
    }

    @Test fun oversizedWriteIsSkippedInsteadOfStored() {
        val cache = StarListCatalogCache(folder.root)
        val huge = List(4_000) { i -> list("$i", "alice").copy(description = "x".repeat(1_000)) }

        cache.write("me", "alice", huge)

        assertNull(cache.read("me", "alice"))
    }
}
