package com.mobilegh.data

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ImportSource(
    val url: String,
    val defaultName: String,
    val isInsecureHttp: Boolean,
) {
    companion object {
        fun parse(raw: String, allowInsecureHttp: Boolean = false): ImportSource {
            require(raw == raw.trim() && raw.isNotEmpty()) { "Enter a Git repository URL" }
            val uri = try {
                URI(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("Enter a valid HTTP or HTTPS Git URL", e)
            }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "https" || scheme == "http") { "Only HTTP and HTTPS Git URLs are supported" }
            require(uri.rawUserInfo == null) { "Credentials must not be embedded in the repository URL" }
            require(uri.rawQuery == null) { "Repository URL query parameters are not supported" }
            require(!uri.host.isNullOrBlank()) { "The Git URL must include a host" }
            if (scheme == "http" && !allowInsecureHttp) {
                throw InsecureImportSourceException()
            }
            val path = uri.path.orEmpty()
            val name = path.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
            require(name.isNotBlank() && name != "." && name != "..") { "The Git URL must include a repository name" }
            val withoutFragment = URI(
                scheme,
                null,
                uri.host,
                uri.port,
                path,
                null,
                null,
            ).toASCIIString()
            return ImportSource(withoutFragment, name, scheme == "http")
        }
    }
}

class InsecureImportSourceException : IllegalArgumentException(
    "HTTP sends repository contents and credentials without transport encryption; confirm to continue",
)

/** Ephemeral UI/call state only. Do not add this type to task persistence or navigation. */
data class ImportSourceCredentials(
    val username: String,
    val password: String,
    val token: String = "",
)

data class ImportDestinationRequest(
    val owner: String,
    val name: String,
    val description: String = "",
    val isPrivate: Boolean,
    val organization: String? = null,
)

interface ImportRepositoryGateway {
    suspend fun create(
        organization: String?,
        name: String,
        description: String,
        private: Boolean,
        autoInit: Boolean,
        token: String?,
    ): Repo

    /** Null means that reconciliation proved the destination does not exist. */
    suspend fun find(owner: String, name: String, token: String?): Repo?

    /** Explicit heads/tags evidence; repository size alone does not prove that refs are empty. */
    suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean
}

private fun importAuthorization(token: String?): Map<String, String> =
    token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

object GitHubImportRepositoryGateway : ImportRepositoryGateway {
    override suspend fun create(
        organization: String?,
        name: String,
        description: String,
        private: Boolean,
        autoInit: Boolean,
        token: String?,
    ): Repo = GitHub.createRepo(
        organization,
        name,
        description,
        private,
        autoInit,
        headers = importAuthorization(token),
    )

    override suspend fun find(owner: String, name: String, token: String?): Repo? = try {
        GitHub.repo(owner, name, force = true, headers = importAuthorization(token))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    override suspend fun hasAnyRef(owner: String, name: String, token: String?): Boolean =
        GitHub.branches(owner, name, page = 1, force = true, headers = importAuthorization(token)).isNotEmpty() ||
            GitHub.tags(owner, name, page = 1, force = true, headers = importAuthorization(token)).isNotEmpty()
}

fun interface ImportDefaultBranchUpdater {
    suspend fun update(owner: String, name: String, branch: String, token: String?)
}

object GitHubImportDefaultBranchUpdater : ImportDefaultBranchUpdater {
    override suspend fun update(owner: String, name: String, branch: String, token: String?) {
        GitHub.updateRepo(
            owner,
            name,
            JsonObject(mapOf("default_branch" to JsonPrimitive(branch))),
            headers = importAuthorization(token),
        )
    }
}

class RepositoryImportCoordinator(
    private val tasks: ImportTasks,
    private val gateway: ImportRepositoryGateway = GitHubImportRepositoryGateway,
    private val defaultBranchUpdater: ImportDefaultBranchUpdater = GitHubImportDefaultBranchUpdater,
) {
    suspend fun create(
        accountLogin: String,
        source: ImportSource,
        destination: ImportDestinationRequest,
        sourceRequiresCredentials: Boolean = false,
        destinationToken: String? = Session.token,
    ): ImportTask {
        val task = tasks.create(accountLogin, source, destination, sourceRequiresCredentials)
        return createDestination(task.id, destinationToken)
    }

    suspend fun createDestination(id: String, destinationToken: String? = Session.token): ImportTask {
        val task = requireNotNull(tasks.get(id)) { "Import task not found" }
        require(task.destination == null) { "The destination was already recorded" }
        return try {
            val repo = gateway.create(
                organization = task.organization,
                name = task.name,
                description = task.description,
                private = task.isPrivate,
                autoInit = false,
                token = destinationToken,
            )
            // Persist the parsed creation response before clone/push can fail.
            tasks.destinationCreated(id, repo)
        } catch (e: CancellationException) {
            // POST may already have reached GitHub; only reconciliation can determine its outcome.
            tasks.creationUnknown(id, "Repository creation was interrupted; reconcile before retrying")
            throw e
        } catch (e: ApiException) {
            if (e.code == -1) {
                tasks.creationUnknown(id, safeMessage(e))
            } else {
                tasks.update(id) { it.copy(phase = ImportPhase.Failed, message = e.message) }
            }
        } catch (e: Exception) {
            // Once POST may have reached GitHub, transport and parse outcomes are unknown.
            tasks.creationUnknown(id, safeMessage(e))
        }
    }

    suspend fun resume(id: String, destinationToken: String? = Session.token): ImportTask {
        val task = requireNotNull(tasks.get(id)) { "Import task not found" }
        if (task.destination != null) return task
        if (task.phase == ImportPhase.Creating) return createDestination(id, destinationToken)
        val existing = try {
            gateway.find(task.owner, task.name, destinationToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return tasks.creationUnknown(id, safeMessage(e))
        }
        if (existing == null) return createDestination(id, destinationToken)
        val matches = existing.owner.login.equals(task.owner, ignoreCase = true) &&
            existing.name.equals(task.name, ignoreCase = true) &&
            existing.fullName.equals("${task.owner}/${task.name}", ignoreCase = true) &&
            existing.isPrivate == task.isPrivate &&
            existing.permissions?.push == true &&
            existing.size == 0L
        val empty = if (matches) {
            try {
                !gateway.hasAnyRef(task.owner, task.name, destinationToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return tasks.creationUnknown(id, safeMessage(e))
            }
        } else {
            false
        }
        return if (empty) {
            tasks.destinationCreated(id, existing)
        } else {
            tasks.creationUnknown(
                id,
                "The destination already exists but is not a matching, writable, empty repository; no push was attempted",
            )
        }
    }

    suspend fun updateDefaultBranch(
        id: String,
        sourceDefaultBranch: String?,
        destinationToken: String? = Session.token,
    ): ImportTask {
        val task = requireNotNull(tasks.get(id)) { "Import task not found" }
        requireNotNull(task.destination) { "Import destination was not created" }
        if (sourceDefaultBranch == null) {
            return tasks.update(id) { it.copy(phase = ImportPhase.Completed, message = null) }
        }
        tasks.update(id) { it.copy(phase = ImportPhase.UpdatingDefaultBranch, message = null) }
        return try {
            defaultBranchUpdater.update(task.owner, task.name, sourceDefaultBranch, destinationToken)
            tasks.update(id) {
                it.copy(
                    phase = ImportPhase.Completed,
                    destination = it.destination?.copy(defaultBranch = sourceDefaultBranch),
                    message = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tasks.update(id) {
                it.copy(phase = ImportPhase.UpdatingDefaultBranch, message = safeMessage(e))
            }
        }
    }

    private fun safeMessage(error: Exception) =
        error.message?.take(500) ?: error.javaClass.simpleName
}
