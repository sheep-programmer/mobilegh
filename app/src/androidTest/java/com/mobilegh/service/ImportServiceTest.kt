package com.mobilegh.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.ImportDestination
import com.mobilegh.data.ImportDestinationRequest
import com.mobilegh.data.ImportPhase
import com.mobilegh.data.ImportRepositoryGateway
import com.mobilegh.data.ImportSource
import com.mobilegh.data.ImportSourceCredentials
import com.mobilegh.data.ImportTask
import com.mobilegh.data.ImportTasks
import com.mobilegh.data.Repo
import com.mobilegh.data.RepositoryImportCoordinator
import com.mobilegh.data.Session
import com.mobilegh.data.User
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.ProgressMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportServiceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var tasks: ImportTasks
    private lateinit var execution: RecordingExecution
    private var nextId = 0

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "import-service-${System.nanoTime()}").apply { mkdirs() }
        tasks = ImportTasks(root, ids = { "service-${++nextId}" })
        execution = RecordingExecution(tasks)
        ImportServices.runtime = ImportRuntime(tasks, execution)
        Session.signIn("destination-token", User(login = "octo"))
    }

    @After fun tearDown() {
        context.stopService(android.content.Intent(context, ImportService::class.java))
        ImportServices.runtime = null
        PendingImportCredentials.clear()
        if (Session.login == "octo" || Session.login == "other") Session.signOut()
        root.deleteRecursively()
    }

    @Test fun intentCarriesOnlyTaskIdAndExecutionBindsCurrentSessionAndEphemeralSourceCredentials() {
        val task = createTask(requiresCredentials = true)
        AndroidImportUiActions.start(
            context,
            task.id,
            ImportSourceCredentials("source-user", "source-password"),
        )

        assertTrue(execution.started.await(10, TimeUnit.SECONDS))
        assertEquals(task.id, execution.calls.single().id)
        assertEquals("octo", execution.calls.single().accountLogin)
        assertEquals("destination-token", execution.calls.single().destinationToken)
        assertEquals(ImportSourceCredentials("source-user", "source-password"), execution.calls.single().sourceCredentials)
        assertTrue(PendingImportCredentials.isEmpty())
        execution.release.complete(Unit)
        assertTrue(execution.finished.await(10, TimeUnit.SECONDS))
        assertFalse(File(root, "tasks/${task.id}.json").readText().contains("source-password"))
        assertFalse(File(root, "tasks/${task.id}.json").readText().contains("destination-token"))
    }

    @Test fun duplicateStartsAndAnotherAccountCannotRunOrMutateTheTask() {
        val task = createTask()
        AndroidImportUiActions.start(context, task.id, null)
        assertTrue(execution.started.await(10, TimeUnit.SECONDS))
        AndroidImportUiActions.start(context, task.id, null)
        Session.signIn("other-token", User(login = "other"))
        AndroidImportUiActions.start(context, task.id, null)

        assertEquals(1, execution.calls.size)
        assertEquals(task, tasks.get(task.id))
        execution.release.complete(Unit)
        assertTrue(execution.finished.await(10, TimeUnit.SECONDS))
    }

    @Test fun cancellationActionStopsExecutionAndRemovesWorkspace() {
        val task = createTask()
        tasks.prepareWorkspace(task.id)
        AndroidImportUiActions.start(context, task.id, null)
        assertTrue(execution.started.await(10, TimeUnit.SECONDS))

        AndroidImportUiActions.cancel(context, task.id)

        assertTrue(execution.finished.await(10, TimeUnit.SECONDS))
        assertTrue(awaitPhase(task.id, ImportPhase.Cancelled))
        assertFalse(tasks.workspace(task.id).exists())
    }

    @Test fun immediateCompletionCannotLeaveStaleJobsOrSuppressIdle() {
        val taskCount = 64
        val created = List(taskCount) { createTask() }
        val idle = CountDownLatch(1)
        var observeIdle = false
        val runtime = ImportRuntime(
            tasks,
            ImportExecution { id, _, _, _, _ -> tasks.update(id) { it.copy(phase = ImportPhase.Completed) } },
            onIdle = { if (observeIdle) idle.countDown() },
        )

        created.forEach { task -> runBlocking(Dispatchers.Default) { runtime.start(task.id, null) } }
        created.forEach { assertTrue(awaitPhase(it.id, ImportPhase.Completed)) }
        val finalTask = createTask()
        observeIdle = true
        runBlocking(Dispatchers.Default) { runtime.start(finalTask.id, null) }

        assertTrue(idle.await(20, TimeUnit.SECONDS))
        assertTrue(awaitPhase(finalTask.id, ImportPhase.Completed))
        assertFalse(runtime.hasActive)
        assertTrue((created + finalTask).none { runtime.isActive(it.id) })
    }

    @Test fun rejectedForegroundStartConsumesCredentialsWithoutRuntime() {
        ImportServices.runtime = null
        val rejecting = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("background start blocked")
            }
        }

        AndroidImportUiActions.start(
            rejecting,
            "missing-runtime",
            ImportSourceCredentials("source-user", "source-password"),
        )

        assertTrue(PendingImportCredentials.isEmpty())
    }

    @Test fun requestedStartIsActiveUntilRuntimeRegistersJob() {
        val task = createTask()
        val runtime = requireNotNull(ImportServices.runtime)
        ImportServices.runtime = null
        val intercepted = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName =
                ComponentName(context, ImportService::class.java)
        }

        try {
            AndroidImportUiActions.start(intercepted, task.id, null)
            ImportServices.runtime = runtime

            assertFalse(runtime.isActive(task.id))
            assertTrue(AndroidImportUiActions.isActive(task.id))

            runtime.start(task.id, null)
            assertTrue(execution.started.await(10, TimeUnit.SECONDS))
            assertTrue(AndroidImportUiActions.isActive(task.id))
        } finally {
            ImportServices.runtime = runtime
            if (!runtime.isActive(task.id)) runtime.start(task.id, null)
            execution.release.complete(Unit)
            execution.finished.await(10, TimeUnit.SECONDS)
        }

        assertTrue(awaitInactive(task.id))
        assertFalse(AndroidImportUiActions.isActive(task.id))
    }

    @Test fun taskWithoutJobOrRequestedStartIsInactive() {
        val task = createTask()
        tasks.update(task.id) { it.copy(phase = ImportPhase.CreationUnknown, destination = null) }

        assertFalse(requireNotNull(ImportServices.runtime).isActive(task.id))
        assertFalse(AndroidImportUiActions.isActive(task.id))
    }

    @Test fun transferProgressReportsSafeCloneAndPushPhases() {
        val task = createTask()
        val idle = CountDownLatch(1)
        var cloneProgress: ImportTask? = null
        var pushProgress: ImportTask? = null
        val runtime = ImportRuntime(
            tasks,
            ImportExecution { id, _, _, _, progress ->
                progress.beginTask("remote: https://source-user:source-password@example.com/repo.git", 2)
                progress.update(1)
                cloneProgress = tasks.get(id)
                progress.beginTask("MobileGH: push", 2)
                progress.update(1)
                pushProgress = tasks.get(id)
                tasks.update(id) { it.copy(phase = ImportPhase.Completed, message = null) }
            },
            onIdle = { idle.countDown() },
        )

        runtime.start(task.id, null)

        assertTrue(idle.await(10, TimeUnit.SECONDS))
        assertEquals(ImportPhase.Cloning, cloneProgress?.phase)
        assertEquals(ImportPhase.Pushing, pushProgress?.phase)
        listOf(cloneProgress, pushProgress).forEach { snapshot ->
            assertTrue(snapshot?.message.orEmpty().contains("1/2"))
            assertFalse(snapshot?.message.orEmpty().contains("source-password"))
            assertFalse(snapshot?.message.orEmpty().contains("source.example"))
        }
    }

    @Test fun runtimeCancellationDuringCreationPreservesUnknownState() {
        val task = tasks.create(
            "octo",
            ImportSource("https://source.example/repo.git", "repo", false),
            ImportDestinationRequest("octo", "imported", isPrivate = false),
        )
        val requestStarted = CountDownLatch(1)
        val idle = CountDownLatch(1)
        val gateway = object : ImportRepositoryGateway {
            override suspend fun create(
                organization: String?,
                name: String,
                description: String,
                private: Boolean,
                autoInit: Boolean,
                token: String?,
            ): Repo {
                requestStarted.countDown()
                awaitCancellation()
            }

            override suspend fun find(owner: String, name: String, token: String?): Repo? = error("not used")

            override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean = error("not used")
        }
        val executor = ImportExecutor(
            tasks,
            RepositoryImportCoordinator(tasks, gateway) { _, _, _, _ -> },
            ImportTransfer { _, _, _, _, _ -> error("must not transfer") },
        )
        val runtime = ImportRuntime(
            tasks,
            ImportExecution { id, login, token, credentials, progress ->
                executor.execute(id, login, token, credentials, progress)
            },
            onIdle = { idle.countDown() },
        )
        ImportServices.runtime = runtime

        runtime.start(task.id, null)
        assertTrue(requestStarted.await(10, TimeUnit.SECONDS))
        runtime.cancel(task.id)

        assertTrue(idle.await(10, TimeUnit.SECONDS))
        assertEquals(ImportPhase.CreationUnknown, tasks.get(task.id)?.phase)
        assertFalse(tasks.workspace(task.id).exists())
    }

    private fun awaitPhase(id: String, phase: ImportPhase): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (tasks.get(id)?.phase == phase) return true
            Thread.sleep(20)
        }
        return tasks.get(id)?.phase == phase
    }

    private fun awaitInactive(id: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (!AndroidImportUiActions.isActive(id) &&
                ImportServices.runtime?.isActive(id) != true
            ) return true
            Thread.sleep(20)
        }
        return !AndroidImportUiActions.isActive(id) && ImportServices.runtime?.isActive(id) != true
    }

    private fun createTask(requiresCredentials: Boolean = false): ImportTask = tasks.create(
        "octo",
        ImportSource("https://source.example/repo.git", "repo", false),
        ImportDestinationRequest("octo", "imported", isPrivate = false),
        sourceRequiresCredentials = requiresCredentials,
    ).let { created ->
        tasks.update(created.id) {
            it.copy(
                destination = ImportDestination("octo", "imported", "https://github.com/octo/imported.git", "main"),
                phase = ImportPhase.Cloning,
            )
        }
    }
}

private data class ExecutionCall(
    val id: String,
    val accountLogin: String,
    val destinationToken: String,
    val sourceCredentials: ImportSourceCredentials?,
)

private class RecordingExecution(
    private val tasks: ImportTasks,
) : ImportExecution {
    val calls = Collections.synchronizedList(mutableListOf<ExecutionCall>())
    val started = CountDownLatch(1)
    val release = CompletableDeferred<Unit>()
    val finished = CountDownLatch(1)

    override suspend fun execute(
        id: String,
        accountLogin: String,
        destinationToken: String,
        sourceCredentials: ImportSourceCredentials?,
        progress: ProgressMonitor,
    ): ImportTask {
        calls += ExecutionCall(id, accountLogin, destinationToken, sourceCredentials)
        started.countDown()
        try {
            if (progress.isCancelled) awaitCancellation()
            release.await()
            return tasks.update(id) { it.copy(phase = ImportPhase.Completed) }
        } finally {
            finished.countDown()
        }
    }
}
