package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StarListsStoreTest {
    private class MemoryStorage : StarListStorage {
        val values = linkedMapOf<String, String>()
        var failWrite = false
        var afterRead: (() -> Unit)? = null
        override fun read(key: String): String? = values[key].also { afterRead?.invoke() }
        override fun write(key: String, value: String): Boolean {
            if (failWrite) return false
            values[key] = value
            return true
        }
    }

    private val storage = MemoryStorage()
    private val alice = StarListAccount("Alice", 1)
    private var active: StarListAccount? = alice
    private val store = LocalStarListsStore(storage) { active }
    private fun repo(id: Int) = ListedRepository(id.toLong(), "mona/repository-$id")

    @Test fun nativeCrudPersistsIdentityAndIndependentMemberships() {
        val first = store.create(alice, "  Reading  ", "  For later  ").single()
        assertEquals("Reading", first.name)
        assertEquals("For later", first.description)
        val second = store.create(alice, "Tools").last()
        store.addRepositories(alice, first.id, listOf(repo(1), repo(1), repo(2)))
        store.addRepositories(alice, second.id, listOf(repo(1)))
        store.update(alice, first.id, "Read next", "new description")
        store.removeRepository(alice, first.id, 1)
        val reopened = LocalStarListsStore(storage) { active }.read(alice)
        assertEquals(listOf(repo(2)), reopened.first().repositories)
        assertEquals(first.id, reopened.first().id)
        assertEquals("Read next", reopened.first().name)
        assertEquals(listOf(repo(1)), reopened.last().repositories)
        assertEquals(listOf(second.id), store.delete(alice, first.id).map { it.id })
    }

    @Test fun switchingAccountsAndLogoutPreventReadsWritesAndOldJobs() {
        store.create(alice, "Alice only")
        val bob = StarListAccount("Bob", 2)
        active = bob
        assertThrows(IllegalStateException::class.java) { store.read(alice) }
        assertThrows(IllegalStateException::class.java) { store.create(alice, "stale") }
        assertTrue(store.read(bob).isEmpty())
        store.create(bob, "Bob only")
        active = StarListAccount("alice", 3)
        assertEquals("Alice only", store.read(active!!).single().name)
        assertThrows(IllegalStateException::class.java) { store.read(alice) }
        active = null
        val persisted = storage.values.toMap()
        assertThrows(IllegalStateException::class.java) { store.create(bob, "logged out") }
        assertEquals(persisted, storage.values)
        assertEquals(setOf("account:alice", "account:bob"), storage.values.keys)
    }

    @Test fun accountSwitchDuringStorageReadCannotCommitAStaleMutation() {
        store.create(alice, "Original")
        val persisted = storage.values.toMap()
        storage.afterRead = { active = StarListAccount("Bob", 2) }
        assertThrows(IllegalStateException::class.java) { store.create(alice, "stale") }
        assertEquals(persisted, storage.values)
    }

    @Test fun atomicRepositoryPickerMaintainsOtherRepositoriesAndCanRemoveAllMemberships() {
        val first = store.create(alice, "First").single()
        val second = store.create(alice, "Second").last()
        store.addRepositories(alice, first.id, listOf(repo(2)))
        store.setRepositoryLists(alice, repo(1), setOf(first.id, second.id), emptySet())
        assertEquals(listOf(repo(2), repo(1)), store.read(alice).first().repositories)
        val result = store.setRepositoryLists(alice, repo(1), emptySet(), setOf(first.id, second.id))
        assertEquals(listOf(repo(2)), result.first().repositories)
        assertTrue(result.last().repositories.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { store.setRepositoryLists(alice, repo(1), setOf("deleted-list"), emptySet()) }
    }

    @Test fun stalePickerSnapshotsCannotUndoNewMembershipsOrRestoreRemovedOnes() {
        val first = store.create(alice, "First").single()
        val second = store.create(alice, "Second").last()
        val third = store.create(alice, "Third").last()
        store.setRepositoryLists(alice, repo(1), setOf(first.id), emptySet())
        val staleOriginal = setOf(first.id)
        store.setRepositoryLists(alice, repo(1), setOf(first.id, second.id), staleOriginal)
        store.setRepositoryLists(alice, repo(1), setOf(first.id, third.id), staleOriginal)
        assertTrue(store.read(alice).all { repo(1) in it.repositories })
        val all = setOf(first.id, second.id, third.id)
        store.setRepositoryLists(alice, repo(1), setOf(second.id, third.id), all)
        store.setRepositoryLists(alice, repo(1), all, all)
        assertTrue(store.read(alice).first().repositories.isEmpty())
        assertTrue(store.read(alice).drop(1).all { repo(1) in it.repositories })
    }

    @Test fun perListLimitFailureIsAtomic() {
        val list = store.create(alice, "Large").single()
        store.addRepositories(alice, list.id, (1..LocalStarListsStore.MAX_REPOSITORIES).map(::repo))
        val persisted = storage.values.toMap()
        assertThrows(IllegalArgumentException::class.java) { store.addRepositories(alice, list.id, listOf(repo(201))) }
        assertEquals(persisted, storage.values)
        assertEquals(200, store.read(alice).single().repositories.size)
    }

    @Test fun aggregateMembershipLimitIsEnforcedAcrossLists() {
        repeat(5) { number ->
            val list = store.create(alice, "List $number").last()
            store.addRepositories(alice, list.id, (1..200).map(::repo))
        }
        val last = store.create(alice, "Overflow").last()
        val persisted = storage.values.toMap()
        assertThrows(IllegalArgumentException::class.java) { store.addRepositories(alice, last.id, listOf(repo(1))) }
        assertEquals(persisted, storage.values)
    }

    @Test fun listCountAndFieldLimitsDoNotSilentlyTruncate() {
        assertThrows(IllegalArgumentException::class.java) { store.create(alice, " ") }
        assertThrows(IllegalArgumentException::class.java) { store.create(alice, "x".repeat(LocalStarListsStore.MAX_NAME + 1)) }
        assertThrows(IllegalArgumentException::class.java) { store.create(alice, "bad\nname") }
        assertThrows(IllegalArgumentException::class.java) { store.create(alice, "Fine", "x".repeat(LocalStarListsStore.MAX_DESCRIPTION + 1)) }
        repeat(LocalStarListsStore.MAX_LISTS) { store.create(alice, "List $it") }
        assertThrows(IllegalArgumentException::class.java) { store.create(alice, "Too many") }
        assertEquals(LocalStarListsStore.MAX_LISTS, store.read(alice).size)
    }

    @Test fun namesAreUniqueIgnoringCaseAndIdsSurviveRenames() {
        val first = store.create(alice, "Reading").single()
        val second = store.create(alice, "Tools").last()
        assertThrows(IllegalArgumentException::class.java) { store.create(alice, "READING") }
        assertThrows(IllegalArgumentException::class.java) { store.update(alice, second.id, "reading", "") }
        assertEquals(first.id, store.update(alice, first.id, "READING", "").first().id)
    }

    @Test fun invalidRepositoryIdentitiesCannotBePersisted() {
        val list = store.create(alice, "Safe").single()
        listOf(ListedRepository(0, "mona/repo"), ListedRepository(1, "mona/repo/path"), ListedRepository(1, "mona/.."), ListedRepository(1, "https://other.example/repo")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.addRepositories(alice, list.id, listOf(invalid)) }
        }
        assertTrue(store.read(alice).single().repositories.isEmpty())
    }

    @Test fun corruptOrOversizedStorageIsReportedWithoutOverwritingIt() {
        storage.values["account:alice"] = "{invalid"
        assertThrows(IllegalStateException::class.java) { store.read(alice) }
        assertThrows(IllegalStateException::class.java) { store.create(alice, "Do not overwrite") }
        assertEquals("{invalid", storage.values["account:alice"])
        val large = "x".repeat(LocalStarListsStore.MAX_BYTES + 1)
        storage.values["account:alice"] = large
        assertThrows(IllegalArgumentException::class.java) { store.read(alice) }
        assertEquals(large, storage.values["account:alice"])
    }

    @Test fun diskFailureIsReportedAndNoAuthenticationMaterialIsStored() {
        storage.failWrite = true
        assertThrows(IllegalStateException::class.java) { store.create(alice, "Disk failure") }
        assertTrue(storage.values.isEmpty())
        storage.failWrite = false
        store.create(alice, "Saved")
        val raw = storage.values.values.single()
        assertFalse(raw.contains("token"))
        assertFalse(raw.contains("cookie"))
    }
}
