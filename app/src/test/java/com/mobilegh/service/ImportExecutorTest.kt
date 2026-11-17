package com.mobilegh.service

import com.mobilegh.data.ImportDestination
import com.mobilegh.data.ImportDestinationRequest
import com.mobilegh.data.ImportPhase
import com.mobilegh.data.ImportRefResult
import com.mobilegh.data.ImportRefStatus
import com.mobilegh.data.ImportRepositoryGateway
import com.mobilegh.data.ImportSource
import com.mobilegh.data.ImportSourceCredentials
import com.mobilegh.data.ImportTask
import com.mobilegh.data.ImportTasks
import com.mobilegh.data.ImportTransferResult
import com.mobilegh.data.Repo
import com.mobilegh.data.RepositoryImportCoordinator
import com.mobilegh.data.Session
import com.mobilegh.data.User
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportExecutorTest {
    private lateinit var root: File
    private lateinit var tasks: ImportTasks

    @Before
    fun setUp() {
        root = Files.createTempDirectory("import-executor").toFile()
        tasks = ImportTasks(root, now = { 10L }, ids = { "task-1" })
    }

    @Test
    fun resumeReusesDestinationAndBindsTargetTokenAtExecution() = runTest {
        val task = createTask(destination = true)
        var gatewayCalls = 0
        val gateway = object : ImportRepositoryGateway {
            override suspend fun create(
                organization: String?,
                name: String,
                description: String,
                private: Boolean,
                autoInit: Boolean,
                token: String?,
            ): Repo {
                gatewayCalls++
                error("must not create")
            }

            override suspend fun find(owner: String, name: String, token: String?): Repo? {
                gatewayCalls++
                error("must not reconcile a recorded destination")
            }

            override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean = error("not used")
        }
        var receivedToken: String? = null
        var receivedSource: ImportSourceCredentials? = null
        val executor = ImportExecutor(
            tasks = tasks,
            coordinator = RepositoryImportCoordinator(tasks, gateway) { _, _, _, _ -> },
            transfer = ImportTransfer { transferred, source, token, _, _ ->
                assertEquals(task.destination, transferred.destination)
                receivedSource = source
                receivedToken = token
                ImportTransferResult(
                    "trunk",
                    listOf(ImportRefResult("refs/heads/trunk", ImportRefStatus.Pushed)),
                )
            },
        )
        val source = ImportSourceCredentials("source-user", "source-secret")

        val result = executor.execute(task.id, "octo", "destination-secret", source)

        assertEquals(ImportPhase.Completed, result.phase)
        assertEquals("trunk", result.destination?.defaultBranch)
        assertEquals(0, gatewayCalls)
        assertEquals("destination-secret", receivedToken)
        assertEquals(source, receivedSource)
        assertFalse(tasks.workspace(task.id).exists())
    }

    @Test
    fun interruptedPrivateSourceRequiresCredentials() {
        val task = createTask(destination = true, requiresCredentials = true)
        tasks.update(task.id) { it.copy(phase = ImportPhase.Interrupted) }
        var transfers = 0
        val executor = executor(transfer = ImportTransfer { _, _, _, _, _ ->
            transfers++
            ImportTransferResult("trunk", emptyList())
        })

        assertThrows(IllegalArgumentException::class.java) {
            runTest { executor.execute(task.id, "octo", "destination-secret") }
        }

        assertEquals(0, transfers)
        assertEquals(ImportPhase.Interrupted, tasks.get(task.id)?.phase)
    }

    @Test
    fun accountMismatchCannotResumeOrMutateTask() {
        val task = createTask(destination = true)
        var transfers = 0
        val executor = executor(transfer = ImportTransfer { _, _, _, _, _ ->
            transfers++
            ImportTransferResult("trunk", emptyList())
        })

        assertThrows(IllegalArgumentException::class.java) {
            runTest { executor.execute(task.id, "other", "destination-secret") }
        }

        assertEquals(0, transfers)
        assertEquals(ImportPhase.Cloning, tasks.get(task.id)?.phase)
        assertEquals(task.destination, tasks.get(task.id)?.destination)
    }

    @Test
    fun rejectedTransferStaysFailedAndDoesNotUpdateDefaultBranch() = runTest {
        val task = createTask(destination = true)
        var defaultBranchUpdates = 0
        val executor = ImportExecutor(
            tasks = tasks,
            coordinator = RepositoryImportCoordinator(tasks, unusedGateway()) { _, _, _, _ ->
                defaultBranchUpdates++
            },
            transfer = ImportTransfer { _, _, _, _, _ ->
                ImportTransferResult(
                    "trunk",
                    listOf(ImportRefResult("refs/heads/trunk", ImportRefStatus.Rejected, "non-fast-forward")),
                )
            },
        )

        val result = executor.execute(task.id, "octo", "destination-secret")

        assertEquals(ImportPhase.Failed, result.phase)
        assertTrue(result.message.orEmpty().contains("non-fast-forward"))
        assertEquals(0, defaultBranchUpdates)
    }

    @Test
    fun rejectedTagDoesNotBlockDefaultBranchAndRemainsVisible() = runTest {
        val task = createTask(destination = true)
        var updatedBranch: String? = null
        val executor = ImportExecutor(
            tasks = tasks,
            coordinator = RepositoryImportCoordinator(tasks, unusedGateway()) { _, _, branch, _ ->
                updatedBranch = branch
            },
            transfer = ImportTransfer { _, _, _, _, _ ->
                ImportTransferResult(
                    "trunk",
                    listOf(
                        ImportRefResult("refs/heads/trunk", ImportRefStatus.Pushed),
                        ImportRefResult("refs/tags/v1", ImportRefStatus.Rejected, "tag already exists"),
                    ),
                )
            },
        )

        val result = executor.execute(task.id, "octo", "destination-secret")

        assertEquals(ImportPhase.Completed, result.phase)
        assertEquals("trunk", updatedBranch)
        assertEquals("trunk", result.destination?.defaultBranch)
        assertTrue(result.message.orEmpty().contains("refs/tags/v1"))
        assertTrue(result.message.orEmpty().contains("tag already exists"))
    }

    @Test
    fun emptySourceDoesNotUpdateAnUnbornDefaultBranch() = runTest {
        val task = createTask(destination = true)
        var defaultBranchUpdates = 0
        val executor = ImportExecutor(
            tasks = tasks,
            coordinator = RepositoryImportCoordinator(tasks, unusedGateway()) { _, _, _, _ ->
                defaultBranchUpdates++
            },
            transfer = ImportTransfer { _, _, _, _, _ -> ImportTransferResult("trunk", emptyList()) },
        )

        val result = executor.execute(task.id, "octo", "destination-secret")

        assertEquals(ImportPhase.Completed, result.phase)
        assertEquals(0, defaultBranchUpdates)
        assertEquals("main", result.destination?.defaultBranch)
    }

    @Test
    fun tagOnlySourceDoesNotUpdateAMissingDefaultBranch() = runTest {
        val task = createTask(destination = true)
        var defaultBranchUpdates = 0
        val executor = ImportExecutor(
            tasks = tasks,
            coordinator = RepositoryImportCoordinator(tasks, unusedGateway()) { _, _, _, _ ->
                defaultBranchUpdates++
            },
            transfer = ImportTransfer { _, _, _, _, _ ->
                ImportTransferResult(
                    "trunk",
                    listOf(ImportRefResult("refs/tags/v1", ImportRefStatus.Pushed)),
                )
            },
        )

        val result = executor.execute(task.id, "octo", "destination-secret")

        assertEquals(ImportPhase.Completed, result.phase)
        assertEquals(0, defaultBranchUpdates)
        assertEquals("main", result.destination?.defaultBranch)
    }

    @Test
    fun restResumeAndDefaultBranchStayOnCapturedAccountAfterSessionSwitch() = runTest {
        val previousToken = Session.token
        val previousLogin = Session.login
        val task = createTask(destination = false)
        setToken("account-b-token")
        Session.login = "other"
        val restTokens = mutableListOf<String?>()
        val gateway = object : ImportRepositoryGateway {
            override suspend fun create(
                organization: String?,
                name: String,
                description: String,
                private: Boolean,
                autoInit: Boolean,
                token: String?,
            ): Repo {
                restTokens += token
                return Repo(
                    name = name,
                    fullName = "octo/$name",
                    owner = User(login = "octo"),
                    isPrivate = private,
                    permissions = com.mobilegh.data.Permissions(push = true),
                    cloneUrl = "https://github.com/octo/$name.git",
                    defaultBranch = "main",
                )
            }

            override suspend fun find(owner: String, name: String, token: String?): Repo? {
                restTokens += token
                return null
            }

            override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean = false
        }
        val executor = ImportExecutor(
            tasks = tasks,
            coordinator = RepositoryImportCoordinator(tasks, gateway) { _, _, _, token ->
                restTokens += token
            },
            transfer = ImportTransfer { _, _, _, _, _ ->
                ImportTransferResult(
                    "trunk",
                    listOf(ImportRefResult("refs/heads/trunk", ImportRefStatus.Pushed)),
                )
            },
        )

        try {
            val result = executor.execute(task.id, "octo", "account-a-token")

            assertEquals(ImportPhase.Completed, result.phase)
            assertEquals(
                listOf("account-a-token", "account-a-token"),
                restTokens,
            )
        } finally {
            setToken(previousToken)
            Session.login = previousLogin
        }
    }

    private fun setToken(value: String?) {
        Session::class.java.getDeclaredField("token").apply {
            isAccessible = true
            set(null, value)
        }
    }

    @Test
    fun cancellationDuringCreationStaysUnknownForReconciliation() = runTest {
        val task = createTask(destination = false)
        val requestStarted = CompletableDeferred<Unit>()
        var createCalls = 0
        val gateway = object : ImportRepositoryGateway {
            override suspend fun create(
                organization: String?,
                name: String,
                description: String,
                private: Boolean,
                autoInit: Boolean,
                token: String?,
            ): Repo {
                createCalls++
                requestStarted.complete(Unit)
                awaitCancellation()
            }

            override suspend fun find(owner: String, name: String, token: String?): Repo? = error("not used")

            override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean = error("not used")
        }
        val executor = ImportExecutor(
            tasks = tasks,
            coordinator = RepositoryImportCoordinator(tasks, gateway) { _, _, _, _ -> },
            transfer = ImportTransfer { _, _, _, _, _ -> error("must not transfer") },
        )
        val job = launch { executor.execute(task.id, "octo", "destination-secret") }
        withTimeout(5_000) { requestStarted.await() }

        job.cancelAndJoin()

        assertEquals(ImportPhase.CreationUnknown, tasks.get(task.id)?.phase)
        assertNull(tasks.get(task.id)?.destination)
        assertEquals(1, createCalls)
    }

    @Test
    fun failuresAreSanitizedAndWorkspaceIsRemoved() = runTest {
        val task = createTask(destination = true)
        tasks.prepareWorkspace(task.id).resolve("partial").writeText("partial")
        val executor = executor(transfer = ImportTransfer { _, _, _, _, _ ->
            throw IOException("source-secret destination-secret https://source-user:source-secret@example.com/repo.git")
        })

        val result = executor.execute(
            task.id,
            "octo",
            "destination-secret",
            ImportSourceCredentials("source-user", "source-secret"),
        )

        assertEquals(ImportPhase.Failed, result.phase)
        assertFalse(result.message.orEmpty().contains("source-secret"))
        assertFalse(result.message.orEmpty().contains("destination-secret"))
        assertFalse(result.message.orEmpty().contains("source-user:"))
        assertFalse(tasks.workspace(task.id).exists())
    }

    @Test
    fun retryAfterTransferFailureRunsTheTransferAgain() = runTest {
        val task = createTask(destination = true)
        var transfers = 0
        val executor = executor(transfer = ImportTransfer { _, _, _, _, _ ->
            transfers++
            if (transfers == 1) throw IOException("temporary network failure")
            ImportTransferResult(
                "trunk",
                listOf(ImportRefResult("refs/heads/trunk", ImportRefStatus.Pushed)),
            )
        })

        val failed = executor.execute(task.id, "octo", "destination-secret")
        assertEquals(ImportPhase.Failed, failed.phase)

        val retried = executor.execute(task.id, "octo", "destination-secret")

        assertEquals(2, transfers)
        assertEquals(ImportPhase.Completed, retried.phase)
    }

    private fun executor(transfer: ImportTransfer) = ImportExecutor(
        tasks = tasks,
        coordinator = RepositoryImportCoordinator(tasks, unusedGateway()) { _, _, _, _ -> },
        transfer = transfer,
    )

    private fun createTask(destination: Boolean, requiresCredentials: Boolean = false): ImportTask {
        val task = tasks.create(
            accountLogin = "octo",
            source = ImportSource("https://source.example/repo.git", "repo", false),
            request = ImportDestinationRequest("octo", "imported", isPrivate = false),
            sourceRequiresCredentials = requiresCredentials,
        )
        return if (destination) {
            tasks.update(task.id) {
                it.copy(
                    destination = ImportDestination(
                        "octo",
                        "imported",
                        "https://github.com/octo/imported.git",
                        "main",
                    ),
                    phase = ImportPhase.Cloning,
                )
            }
        } else {
            task
        }
    }

    private fun unusedGateway() = object : ImportRepositoryGateway {
        override suspend fun create(
            organization: String?,
            name: String,
            description: String,
            private: Boolean,
            autoInit: Boolean,
            token: String?,
        ): Repo = error("not used")

        override suspend fun find(owner: String, name: String, token: String?): Repo? = error("not used")

        override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean = error("not used")
    }
}
