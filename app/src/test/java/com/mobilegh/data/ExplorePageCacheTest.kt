package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExplorePageCacheTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun restoresAnOlderPublicPageAfterReopeningTheCache() {
        val file = folder.newFile("explore.json")
        val result = SearchResult(totalCount = 124, items = listOf(
            Repo(fullName = "org/public", permissions = Permissions(admin = true)),
            Repo(fullName = "org/private", isPrivate = true),
        ))
        ExplorePageCache(file).write("scope", result, now = 1_000)

        val cached = checkNotNull(ExplorePageCache(file).read("scope", now = 901_000))
        assertEquals(listOf(Repo(fullName = "org/public")), cached.result.items)
        assertEquals(124, cached.result.totalCount)
        assertTrue(cached.stale)
        assertNull(ExplorePageCache(file).read("different-scope", now = 901_000))
        assertNull(ExplorePageCache(file).read("scope", now = 86_401_001))
    }
}
