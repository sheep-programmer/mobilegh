package com.mobilegh.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.lib.NullProgressMonitor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitRepositoryImporterTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("mobilegh-git-import").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun cloneAndPushMigratesFullHistoryBranchesTagsAndSourceDefaultBranch() = runTest {
        val fixture = createSourceRepository()
        val destination = File(root, "destination.git")
        git(root, "init", "--bare", destination.absolutePath)
        val task = importTask(fixture.source, destination)
        val workspace = File(root, "workspace").apply { mkdirs() }

        val result = GitRepositoryImporter().cloneAndPush(
            task = task,
            sourceCredentials = null,
            destinationCredentials = UsernamePasswordCredentialsProvider("unused", "unused"),
            workspace = workspace,
            progress = NullProgressMonitor.INSTANCE,
        )

        assertEquals("trunk", result.sourceDefaultBranch)
        assertTrue(result.isComplete)
        assertEquals(
            setOf(
                "refs/heads/trunk",
                "refs/heads/feature",
                "refs/tags/v1.0",
                "refs/tags/latest",
            ),
            result.refs.map { it.ref }.toSet(),
        )
        assertEquals(fixture.firstCommit, revParse(destination, "${fixture.firstCommit}^{commit}"))
        assertEquals(fixture.trunkCommit, revParse(destination, "refs/heads/trunk"))
        assertEquals(fixture.featureCommit, revParse(destination, "refs/heads/feature"))
        assertEquals(fixture.trunkCommit, revParse(destination, "refs/tags/latest"))
        assertEquals("tag", git(destination, "cat-file", "-t", "refs/tags/v1.0"))
        assertEquals("2", git(destination, "rev-list", "--count", "refs/heads/trunk"))
        assertFalse(git(destination, "show-ref").lineSequence().any { "refs/pull/" in it })
        assertFalse(result.refs.any { "refs/pull/" in it.ref })
    }

    @Test
    fun retryReusesWorkspaceAndTreatsIdenticalRefsAsUpToDate() = runTest {
        val fixture = createSourceRepository()
        val destination = File(root, "destination.git")
        git(root, "init", "--bare", destination.absolutePath)
        val task = importTask(fixture.source, destination)
        val workspace = File(root, "workspace").apply { mkdirs() }
        val importer = GitRepositoryImporter()

        val first = importer.cloneAndPush(
            task, null,
            UsernamePasswordCredentialsProvider("unused", "unused"),
            workspace, NullProgressMonitor.INSTANCE,
        )
        assertTrue(first.isComplete)

        val second = importer.cloneAndPush(
            task, null,
            UsernamePasswordCredentialsProvider("unused", "unused"),
            workspace, NullProgressMonitor.INSTANCE,
        )

        assertTrue(second.isComplete)
        assertEquals(4, second.refs.size)
        assertTrue(second.refs.all { it.status == ImportRefStatus.UpToDate })
        assertEquals(fixture.trunkCommit, revParse(destination, "refs/heads/trunk"))
        assertFalse(File(workspace, "repository").exists())
    }

    @Test
    fun nonFastForwardKeepsDestinationAndPartialSuccessIsRetryable() = runTest {
        val fixture = createSourceRepository()
        val destination = File(root, "destination.git")
        git(root, "init", "--bare", destination.absolutePath)
        val destinationWork = File(root, "destination-work")
        git(root, "clone", destination.absolutePath, destinationWork.absolutePath)
        git(destinationWork, "config", "user.name", "Destination Test")
        git(destinationWork, "config", "user.email", "destination@example.com")
        File(destinationWork, "destination-only.txt").writeText("do not overwrite\n")
        git(destinationWork, "add", "destination-only.txt")
        git(destinationWork, "commit", "-m", "unrelated destination history")
        git(destinationWork, "push", "origin", "HEAD:refs/heads/trunk")
        val destinationCommit = revParse(destinationWork, "HEAD")
        val task = importTask(fixture.source, destination)
        val workspace = File(root, "workspace").apply { mkdirs() }
        val importer = GitRepositoryImporter()

        val first = importer.cloneAndPush(
            task, null,
            UsernamePasswordCredentialsProvider("unused", "unused"),
            workspace, NullProgressMonitor.INSTANCE,
        )

        assertFalse(first.isComplete)
        assertEquals(
            ImportRefStatus.Rejected,
            first.refs.single { it.ref == "refs/heads/trunk" }.status,
        )
        assertEquals(
            ImportRefStatus.Pushed,
            first.refs.single { it.ref == "refs/heads/feature" }.status,
        )
        assertEquals(destinationCommit, revParse(destination, "refs/heads/trunk"))
        assertEquals(fixture.featureCommit, revParse(destination, "refs/heads/feature"))

        val retry = importer.cloneAndPush(
            task, null,
            UsernamePasswordCredentialsProvider("unused", "unused"),
            workspace, NullProgressMonitor.INSTANCE,
        )

        assertFalse(retry.isComplete)
        assertEquals(
            ImportRefStatus.Rejected,
            retry.refs.single { it.ref == "refs/heads/trunk" }.status,
        )
        assertEquals(
            ImportRefStatus.UpToDate,
            retry.refs.single { it.ref == "refs/heads/feature" }.status,
        )
        assertEquals(destinationCommit, revParse(destination, "refs/heads/trunk"))
        assertFalse(File(workspace, "repository").exists())
    }

    @Test
    fun cancelledMonitorStopsTransferAndCleansWorkspace() {
        val fixture = createSourceRepository()
        val destination = File(root, "destination.git")
        git(root, "init", "--bare", destination.absolutePath)
        val workspace = File(root, "workspace").apply { mkdirs() }
        val cancelled = object : ProgressMonitor {
            override fun start(totalTasks: Int) = Unit
            override fun beginTask(title: String?, totalWork: Int) = Unit
            override fun update(completed: Int) = Unit
            override fun endTask() = Unit
            override fun isCancelled(): Boolean = true
            override fun showDuration(enabled: Boolean) = Unit
        }

        assertThrows(CancellationException::class.java) {
            runTest {
                GitRepositoryImporter().cloneAndPush(
                    importTask(fixture.source, destination), null,
                    UsernamePasswordCredentialsProvider("unused", "unused"),
                    workspace, cancelled,
                )
            }
        }

        assertFalse(File(workspace, "repository").exists())
        assertFalse(git(destination, "for-each-ref").contains("refs/heads/trunk"))
    }

    @Test
    fun transportFailureRedactsUrlUserinfoAndCredentials() {
        val destination = File(root, "destination.git")
        git(root, "init", "--bare", destination.absolutePath)
        val task = importTask(File(root, "unused.git"), destination).copy(
            sourceUrl = "https://source-user:source-secret@127.0.0.1:1/private.git",
        )

        val error = assertThrows(Exception::class.java) {
            runTest {
                GitRepositoryImporter().cloneAndPush(
                    task,
                    UsernamePasswordCredentialsProvider("source-user", "source-secret"),
                    UsernamePasswordCredentialsProvider("destination-user", "destination-secret"),
                    File(root, "workspace").apply { mkdirs() },
                    NullProgressMonitor.INSTANCE,
                )
            }
        }

        val message = error.message.orEmpty()
        assertFalse(message, message.contains("source-secret"))
        assertFalse(message, message.contains("source-user:source-secret"))
        assertFalse(message, message.contains("destination-secret"))
    }

    private fun createSourceRepository(): SourceFixture {
        val source = File(root, "source.git")
        val work = File(root, "work")
        git(root, "init", "--bare", source.absolutePath)
        git(root, "init", "-b", "trunk", work.absolutePath)
        git(work, "config", "user.name", "Import Test")
        git(work, "config", "user.email", "import@example.com")

        File(work, "history.txt").writeText("first\n")
        git(work, "add", "history.txt")
        git(work, "commit", "-m", "first")
        val firstCommit = revParse(work, "HEAD")
        git(work, "tag", "-a", "v1.0", "-m", "annotated release", firstCommit)

        File(work, "history.txt").appendText("second\n")
        git(work, "commit", "-am", "second")
        val trunkCommit = revParse(work, "HEAD")
        git(work, "tag", "latest", trunkCommit)

        git(work, "checkout", "-b", "feature")
        File(work, "feature.txt").writeText("feature\n")
        git(work, "add", "feature.txt")
        git(work, "commit", "-m", "feature commit")
        val featureCommit = revParse(work, "HEAD")
        git(work, "checkout", "trunk")

        git(work, "remote", "add", "origin", source.absolutePath)
        git(work, "push", "origin", "--all")
        git(work, "push", "origin", "--tags")
        git(work, "push", "origin", "refs/heads/trunk:refs/pull/1/head")
        git(source, "symbolic-ref", "HEAD", "refs/heads/trunk")
        return SourceFixture(source, firstCommit, trunkCommit, featureCommit)
    }

    private fun importTask(source: File, destination: File) = ImportTask(
        id = "task-1",
        accountLogin = "octo",
        sourceUrl = source.toURI().toString(),
        owner = "octo",
        name = "imported",
        isPrivate = false,
        destination = ImportDestination(
            owner = "octo",
            name = "imported",
            cloneUrl = destination.toURI().toString(),
            defaultBranch = "main",
        ),
        phase = ImportPhase.Cloning,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun revParse(repository: File, ref: String) = git(repository, "rev-parse", ref)

    private fun git(directory: File, vararg arguments: String): String {
        val command = if (directory.extension == "git") {
            listOf("git", "--git-dir", directory.absolutePath) + arguments
        } else {
            listOf("git") + arguments
        }
        val process = ProcessBuilder(command)
            .directory(if (directory.extension == "git") root else directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Git command timed out: ${command.joinToString(" ")}" }
        check(process.exitValue() == 0) {
            "Git command failed (${process.exitValue()}): ${command.joinToString(" ")}\n$output"
        }
        return output.trim()
    }

    private data class SourceFixture(
        val source: File,
        val firstCommit: String,
        val trunkCommit: String,
        val featureCommit: String,
    )
}
