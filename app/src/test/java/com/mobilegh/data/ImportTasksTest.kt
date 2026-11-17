package com.mobilegh.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class ImportTasksTest {
    private lateinit var root: File
    private lateinit var store: ImportTasks

    @Before fun setUp() {
        root = Files.createTempDirectory("import-tasks-test").toFile()
        store = ImportTasks(
            root,
            now = { 42L },
            ids = generateSequence(1) { it + 1 }.map { "task-$it" }.iterator()::next,
        )
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun taskPersistenceContainsNoSourceCredentialsOrTokens() {
        val username = "{{USERNAME_7hm4p9qk}}"
        val password = "secret"
        val token = "{{PASSWORD_58xk3vaw}}"
        // These values are deliberately local call state, never ImportSource or ImportTask fields.
        val credentials = ImportSourceCredentials(username, password, token)
        assertEquals(username, credentials.username)
        val task = store.create("octo", source(), request())
        val reloaded = ImportTasks(root).get(task.id)
        assertEquals(task, reloaded)
        val allFiles = root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(allFiles.contains(username))
        assertFalse(allFiles.contains(password))
        assertFalse(allFiles.contains(token))
        assertFalse(allFiles.contains("password", ignoreCase = true))
        assertFalse(allFiles.contains("token", ignoreCase = true))
    }

    @Test fun restartingStoreInterruptsEveryActivePhaseButPreservesTerminalTasks() {
        val active = listOf(
            ImportPhase.Creating,
            ImportPhase.CreationUnknown,
            ImportPhase.Cloning,
            ImportPhase.Pushing,
            ImportPhase.UpdatingDefaultBranch,
        )
        val tasks = (active + listOf(ImportPhase.Completed, ImportPhase.Failed, ImportPhase.Cancelled, ImportPhase.Interrupted))
            .map { phase ->
                store.create("octo", source(), request()).let {
                    store.update(it.id) { task -> task.copy(phase = phase) }
                }
            }
        val reopened = ImportTasks(root)
        reopened.recoverActive()
        tasks.forEachIndexed { index, original ->
            val actual = reopened.get(original.id)!!
            if (index < active.size) {
                assertEquals(ImportPhase.Interrupted, actual.phase)
                assertTrue(actual.message!!.contains("interrupted", ignoreCase = true))
            } else {
                assertEquals(original.phase, actual.phase)
            }
        }
    }

    @Test fun accountMismatchHidesAndRejectsMutationOfATask() {
        val task = store.create("octo", source(), request())
        assertEquals(task, store.get(task.id, "octo"))
        assertNull(store.get(task.id, "other"))
        assertThrows(IllegalArgumentException::class.java) {
            store.update(task.id, "other") { it.copy(phase = ImportPhase.Cancelled) }
        }
        assertEquals(ImportPhase.Creating, store.get(task.id)!!.phase)
    }

    @Test fun corruptRecordIsIgnoredWithoutDroppingValidTasks() {
        val valid = store.create("octo", source(), request(name = "valid"))
        val corrupt = store.create("octo", source(), request(name = "corrupt"))
        File(root, "tasks/${corrupt.id}.json").writeText("{ definitely-not-json")
        val reopened = ImportTasks(root)
        assertNull(reopened.get(corrupt.id))
        assertEquals(listOf(valid.id), reopened.list("octo").map { it.id })
        val replacement = reopened.create("octo", source(), request(name = "after-corruption"))
        assertNotNull(reopened.get(valid.id))
        assertNotNull(reopened.get(replacement.id))
    }

    @Test fun cancelAndDeleteCleanOnlyTheirIsolatedWorkspace() {
        val cancelled = store.create("octo", source(), request(name = "cancel"))
        val retained = store.create("octo", source(), request(name = "retain"))
        File(store.workspace(cancelled.id), "nested").mkdirs()
        File(store.workspace(cancelled.id), "nested/object").writeText("git data")
        File(store.workspace(retained.id), "nested").mkdirs()
        File(store.workspace(retained.id), "nested/object").writeText("keep")
        store.cancel(cancelled.id)
        assertEquals(ImportPhase.Cancelled, store.get(cancelled.id)!!.phase)
        assertFalse(store.workspace(cancelled.id).exists())
        assertTrue(store.workspace(retained.id).exists())
        store.delete(cancelled.id)
        assertNull(store.get(cancelled.id))
        assertNotNull(store.get(retained.id))
    }

    @Test fun startingAttemptClearsStaleWorkspaceBeforeClone() {
        val task = store.create("octo", source(), request())
        File(store.workspace(task.id), "stale").mkdirs()
        File(store.workspace(task.id), "stale/old").writeText("old")
        store.prepareWorkspace(task.id)
        assertTrue(store.workspace(task.id).exists())
        assertEquals(emptyList<File>(), store.workspace(task.id).listFiles()!!.toList())
    }

    private fun source() = ImportSource.parse("https://git.example.com/octo/source.git")

    private fun request(name: String = "imported") = ImportDestinationRequest(
        owner = "octo",
        name = name,
        description = "",
        isPrivate = true,
        organization = null,
    )
}
