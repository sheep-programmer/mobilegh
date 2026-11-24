package com.mobilegh.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IssueDependency(
    val id: Long = 0,
    val number: Int = 0,
    val title: String = "",
    val state: String = "open",
    val htmlUrl: String = "",
    val repositoryUrl: String? = null,
)

object IssueDependencyPaths {
    fun blockedBy(owner: String, name: String, number: Int) = "/repos/$owner/$name/issues/$number/dependencies/blocked_by"
    fun blocking(owner: String, name: String, number: Int) = "/repos/$owner/$name/issues/$number/dependencies/blocking"
    fun removeBlockedBy(owner: String, name: String, number: Int, dependencyId: Long) =
        "${blockedBy(owner, name, number)}/$dependencyId"
}

object IssueDependencies {
    private val apiHeaders = mapOf("X-GitHub-Api-Version" to "2026-03-10")

    suspend fun blockedBy(owner: String, name: String, number: Int, force: Boolean = false): List<IssueDependency> =
        Api.get(IssueDependencyPaths.blockedBy(owner, name, number), force, headers = apiHeaders)

    suspend fun blocking(owner: String, name: String, number: Int, force: Boolean = false): List<IssueDependency> =
        Api.get(IssueDependencyPaths.blocking(owner, name, number), force, headers = apiHeaders)

    suspend fun addBlockedBy(owner: String, name: String, number: Int, dependencyId: Long) {
        Api.exec(
            "POST",
            IssueDependencyPaths.blockedBy(owner, name, number),
            kotlinx.serialization.json.buildJsonObject { put("issue_id", kotlinx.serialization.json.JsonPrimitive(dependencyId)) },
            headers = apiHeaders,
        )
    }

    suspend fun removeBlockedBy(owner: String, name: String, number: Int, dependencyId: Long) {
        Api.exec("DELETE", IssueDependencyPaths.removeBlockedBy(owner, name, number, dependencyId), headers = apiHeaders)
    }
}
