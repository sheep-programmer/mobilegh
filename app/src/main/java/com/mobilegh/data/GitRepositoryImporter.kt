package com.mobilegh.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import kotlin.coroutines.coroutineContext

internal const val IMPORT_PUSH_PROGRESS_TASK = "MobileGH: push"

enum class ImportRefStatus {
    Pushed,
    UpToDate,
    Rejected,
}

data class ImportRefResult(
    val ref: String,
    val status: ImportRefStatus,
    val message: String? = null,
)

data class ImportTransferResult(
    val sourceDefaultBranch: String?,
    val refs: List<ImportRefResult>,
) {
    val isComplete: Boolean
        get() = refs.all { it.status != ImportRefStatus.Rejected }
}

class GitRepositoryImporter {
    suspend fun cloneAndPush(
        task: ImportTask,
        sourceCredentials: CredentialsProvider?,
        destinationCredentials: CredentialsProvider,
        workspace: File,
        progress: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): ImportTransferResult = withContext(Dispatchers.IO) {
        val destination = requireNotNull(task.destination) { "Import destination was not created" }
        val repositoryDirectory = File(workspace, "repository")
        val job = coroutineContext.job
        val monitor = object : ProgressMonitor {
            override fun start(totalTasks: Int) = progress.start(totalTasks)
            override fun beginTask(title: String?, totalWork: Int) = progress.beginTask(title, totalWork)
            override fun update(completed: Int) = progress.update(completed)
            override fun endTask() = progress.endTask()
            override fun isCancelled(): Boolean = progress.isCancelled || !job.isActive
            override fun showDuration(enabled: Boolean) = progress.showDuration(enabled)
        }
        var failure: Throwable? = null
        try {
            if (repositoryDirectory.exists() && !repositoryDirectory.deleteRecursively()) {
                throw IOException("Unable to clear the previous import workspace")
            }
            if (!workspace.isDirectory && !workspace.mkdirs()) {
                throw IOException("Unable to create the import workspace")
            }
            coroutineContext.ensureActive()
            Git.cloneRepository()
                .setURI(task.sourceUrl)
                .setDirectory(repositoryDirectory)
                .setBare(true)
                .setCloneAllBranches(true)
                .setCloneSubmodules(false)
                .setCredentialsProvider(sourceCredentials)
                .setTransportConfigCallback(ImportTransportSecurity)
                .setProgressMonitor(monitor)
                .call().use { cloned ->
                    coroutineContext.ensureActive()
                    val repository = cloned.repository
                    val sourceDefaultBranch = repository.fullBranch
                        ?.takeIf { it.startsWith(Constants.R_HEADS) }
                        ?.removePrefix(Constants.R_HEADS)
                    val refs = (
                        repository.refDatabase.getRefsByPrefix(Constants.R_HEADS) +
                            repository.refDatabase.getRefsByPrefix(Constants.R_TAGS)
                        ).sortedBy { it.name }
                    val specs = refs.map { RefSpec("${it.name}:${it.name}") }
                    val results = if (specs.isEmpty()) {
                        emptyList()
                    } else {
                        monitor.beginTask(IMPORT_PUSH_PROGRESS_TASK, specs.size)
                        Git(repository).push()
                            .setRemote(destination.cloneUrl)
                            .setRefSpecs(specs)
                            .setForce(false)
                            .setCredentialsProvider(destinationCredentials)
                            .setTransportConfigCallback(ImportTransportSecurity)
                            .setProgressMonitor(monitor)
                            .call().flatMap { it.remoteUpdates }
                    }
                    coroutineContext.ensureActive()
                    ImportTransferResult(
                        sourceDefaultBranch = sourceDefaultBranch,
                        refs = results.map { update ->
                            ImportRefResult(
                                ref = update.remoteName,
                                status = when (update.status) {
                                    RemoteRefUpdate.Status.OK -> ImportRefStatus.Pushed
                                    RemoteRefUpdate.Status.UP_TO_DATE -> ImportRefStatus.UpToDate
                                    else -> ImportRefStatus.Rejected
                                },
                                message = update.message,
                            )
                        },
                    )
                }
        } catch (error: Throwable) {
            val mapped = if (error is CancellationException) {
                error
            } else if (error is Exception && monitor.isCancelled) {
                CancellationException("Import cancelled", error)
            } else {
                error
            }
            failure = mapped
            throw mapped
        } finally {
            try {
                if (repositoryDirectory.exists() && !repositoryDirectory.deleteRecursively()) {
                    throw IOException("Unable to clean the import workspace")
                }
            } catch (cleanup: Throwable) {
                if (failure != null) {
                    failure.addSuppressed(cleanup)
                } else {
                    throw cleanup
                }
            }
        }
    }
}
