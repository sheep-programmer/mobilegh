package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test

internal class MemoryRecords : LocalRecordStorage {
    val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun read(key: String) = values[key]
    override fun write(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
class HistoryStoreTest {
    private val storage = MemoryRecords()
    private val store = HistoryStore(storage)
    @Test fun searchesRememberTheirTypeDeduplicateAndMoveToTheFront() {
        store.search("alice", " kotlin ", 0)
        store.search("alice", "other", 1)
        store.search("alice", "kotlin", 0)
        assertEquals(listOf(SearchHistoryEntry("kotlin", 0), SearchHistoryEntry("other", 1)), store.read("ALICE").searches)
        store.search("alice", "kotlin", 4)
        assertEquals(3, store.read("alice").searches.size)
    }
    @Test fun historiesAreBoundedAndInvalidInputsDoNotReplaceThem() {
        repeat(25) { store.search("alice", "query $it", 0) }
        assertEquals(20, store.read("alice").searches.size)
        store.search("alice", "invalid", 5)
        store.search("alice", "x".repeat(513), 0)
        store.search("alice", "", 0)
        assertEquals("query 24", store.read("alice").searches.first().query)
    }
    @Test fun repositoriesIgnoreCaseForIdentityAndKeepMostRecentlyUsedOrder() {
        store.repository("alice", "mona", "repo")
        store.repository("alice", "mona", "other")
        store.repository("alice", "MONA", "REPO")
        assertEquals(listOf("MONA/REPO", "mona/other"), store.read("alice").repositories.map { it.fullName })
        repeat(60) { store.repository("alice", "mona", "repo$it") }
        assertEquals(50, store.read("alice").repositories.size)
    }
    @Test fun accountsAndHistoryKindsStayIndependentWhenCleared() {
        store.search("alice", "secret-repo", 0)
        store.repository("alice", "mona", "repo")
        store.search("bob", "public", 0)
        store.clearSearches("alice")
        assertEquals(1, store.read("alice").repositories.size)
        assertEquals("public", store.read("bob").searches.single().query)
        store.clearRepositories("alice")
        assertTrue(store.read("alice").repositories.isEmpty())
    }
    @Test fun corruptHistoryDoesNotBreakOpeningTheApp() {
        storage.values["history:alice"] = "{invalid"
        assertEquals(HistoryData(), store.read("alice"))
        assertEquals("{invalid", storage.values["history:alice"])
    }
}
