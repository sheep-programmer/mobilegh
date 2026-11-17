package com.mobilegh.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ImportPhase {
    Creating,
    CreationUnknown,
    Cloning,
    Pushing,
    UpdatingDefaultBranch,
    Completed,
    Failed,
    Cancelled,
    Interrupted,
}

@Serializable
data class ImportDestination(
    val owner: String,
    val name: String,
    val cloneUrl: String,
    val defaultBranch: String,
)

@Serializable
data class ImportTask(
    val id: String,
    val accountLogin: String,
    val sourceUrl: String,
    val owner: String,
    val name: String,
    val description: String = "",
    @SerialName("private") val isPrivate: Boolean,
    val organization: String? = null,
    val sourceRequiresCredentials: Boolean = false,
    val destination: ImportDestination? = null,
    val phase: ImportPhase = ImportPhase.Creating,
    val message: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

class ImportTasks(
    root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val records = File(root, "tasks")
    private val workspaces = File(root, "workspaces")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun create(
        accountLogin: String,
        source: ImportSource,
        request: ImportDestinationRequest,
        sourceRequiresCredentials: Boolean = false,
    ): ImportTask {
        require(accountLogin.isNotBlank()) { "An account is required" }
        require(request.owner.isNotBlank() && request.name.isNotBlank()) { "A destination is required" }
        require(request.organization == null || request.organization == request.owner) {
            "The organization must own the destination"
        }
        val timestamp = now()
        val task = ImportTask(
            id = ids(),
            accountLogin = accountLogin,
            sourceUrl = source.url,
            owner = request.owner,
            name = request.name,
            description = request.description,
            isPrivate = request.isPrivate,
            organization = request.organization,
            sourceRequiresCredentials = sourceRequiresCredentials,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        persist(task)
        return task
    }

    @Synchronized
    fun get(id: String): ImportTask? {
        val file = record(id) ?: return null
        if (!file.isFile) return null
        return try {
            json.decodeFromString<ImportTask>(file.readText()).takeIf { it.id == id }
        } catch (_: Exception) {
            null
        }
    }

    fun get(id: String, accountLogin: String): ImportTask? =
        get(id)?.takeIf { it.accountLogin == accountLogin }

    @Synchronized
    fun list(accountLogin: String): List<ImportTask> {
        val files = records.listFiles()?.filter { it.extension == "json" }.orEmpty()
        return files.mapNotNull { get(it.nameWithoutExtension) }
            .filter { it.accountLogin == accountLogin }
            .sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun update(id: String, transform: (ImportTask) -> ImportTask): ImportTask {
        val current = requireNotNull(get(id)) { "Import task not found" }
        return transform(current).also { updated ->
            require(updated.id == id && updated.accountLogin == current.accountLogin) {
                "Task identity cannot be changed"
            }
            persist(updated.copy(updatedAt = now()))
        }.let { requireNotNull(get(id)) }
    }

    fun update(id: String, accountLogin: String, transform: (ImportTask) -> ImportTask): ImportTask {
        require(get(id, accountLogin) != null) { "Import task does not belong to this account" }
        return update(id, transform)
    }

    fun destinationCreated(id: String, repo: Repo): ImportTask {
        val task = requireNotNull(get(id)) { "Import task not found" }
        val owner = task.owner
        val name = task.name
        val destination = ImportDestination(
            owner = owner,
            name = name,
            cloneUrl = repo.cloneUrl?.takeIf { it.isNotBlank() }
                ?: "${Net.loginBase}/${pathSegment(owner)}/${pathSegment(name)}.git",
            defaultBranch = repo.defaultBranch,
        )
        return update(id) {
            it.copy(destination = destination, phase = ImportPhase.Cloning, message = null)
        }
    }

    fun creationUnknown(id: String, message: String): ImportTask = update(id) {
        it.copy(destination = null, phase = ImportPhase.CreationUnknown, message = message)
    }

    @Synchronized
    fun recoverActive(): List<ImportTask> {
        val active = setOf(
            ImportPhase.Creating,
            ImportPhase.CreationUnknown,
            ImportPhase.Cloning,
            ImportPhase.Pushing,
            ImportPhase.UpdatingDefaultBranch,
        )
        val candidates = records.listFiles()?.mapNotNull { file ->
            if (file.extension == "json") get(file.nameWithoutExtension) else null
        }.orEmpty()
        return candidates.filter { it.phase in active }.map { task ->
            update(task.id) {
                it.copy(
                    phase = ImportPhase.Interrupted,
                    message = "Import interrupted; retry to continue",
                )
            }
        }
    }

    fun workspace(id: String): File {
        requireSafeId(id)
        return File(workspaces, id)
    }

    fun prepareWorkspace(id: String): File {
        val directory = workspace(id)
        directory.deleteRecursively()
        check(directory.mkdirs()) { "Unable to prepare import workspace" }
        return directory
    }

    fun cancel(id: String): ImportTask {
        val result = update(id) { it.copy(phase = ImportPhase.Cancelled) }
        workspace(id).deleteRecursively()
        return result
    }

    @Synchronized
    fun delete(id: String) {
        record(id)?.delete()
        workspace(id).deleteRecursively()
    }

    private fun persist(task: ImportTask) {
        requireSafeId(task.id)
        records.mkdirs()
        val target = File(records, "${task.id}.json")
        val temporary = File(records, "${task.id}.tmp")
        temporary.writeText(json.encodeToString(ImportTask.serializer(), task))
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            check(temporary.renameTo(target)) { "Unable to persist import task" }
        }
    }

    private fun record(id: String): File? = try {
        requireSafeId(id)
        File(records, "$id.json")
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun requireSafeId(id: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid import task id" }
    }

    private fun pathSegment(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
