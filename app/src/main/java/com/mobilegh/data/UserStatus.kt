package com.mobilegh.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class UserStatus(
    val emoji: String? = null,
    val message: String? = null,
    val indicatesLimitedAvailability: Boolean = false,
    val expiresAt: String? = null,
) {
    val isEmpty: Boolean get() = emoji.isNullOrBlank() && message.isNullOrBlank()
    val displayEmoji: String get() = GitHubEmoji.decode(emoji)
}

object UserStatusApi {
    private const val VIEWER_STATUS_Q = """
        query ViewerStatus {
            viewer {
                status {
                    emoji
                    message
                    indicatesLimitedAvailability
                    expiresAt
                }
            }
        }
    """

    private const val USER_STATUS_Q = """
        query UserStatus(${'$'}login: String!) {
            user(login: ${'$'}login) {
                status {
                    emoji
                    message
                    indicatesLimitedAvailability
                    expiresAt
                }
            }
        }
    """

    private const val CHANGE_STATUS_M = """
        mutation ChangeUserStatus(${'$'}input: ChangeUserStatusInput!) {
            changeUserStatus(input: ${'$'}input) {
                status {
                    emoji
                    message
                    indicatesLimitedAvailability
                    expiresAt
                }
            }
        }
    """

    suspend fun getStatus(login: String, isViewer: Boolean = false): UserStatus? {
        val root = if (isViewer) Api.graphql(VIEWER_STATUS_Q) else Api.graphql(USER_STATUS_Q, mapOf("login" to login))
        val target = if (isViewer) root.obj("viewer") else root.obj("user")
        val statusObj = target?.obj("status") ?: return null
        return parse(statusObj)
    }

    suspend fun setStatus(
        emoji: String?,
        message: String?,
        limitedAvailability: Boolean,
        expiresAt: String?,
    ): UserStatus? {
        val input = buildJsonObject {
            put("emoji", emoji?.trim()?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
            put("message", message?.trim()?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
            put("limitedAvailability", JsonPrimitive(limitedAvailability))
            if (!expiresAt.isNullOrBlank()) {
                put("expiresAt", JsonPrimitive(expiresAt))
            } else {
                put("expiresAt", JsonNull)
            }
        }
        val root = Api.graphql(CHANGE_STATUS_M, mapOf("input" to input))
        val statusObj = root.obj("changeUserStatus")?.obj("status") ?: return null
        return parse(statusObj)
    }

    suspend fun clearStatus() {
        val input = buildJsonObject {
            put("emoji", JsonNull)
            put("message", JsonNull)
            put("expiresAt", JsonNull)
            put("limitedAvailability", JsonPrimitive(false))
        }
        Api.graphql(CHANGE_STATUS_M, mapOf("input" to input))
    }

    private fun parse(obj: JsonObject): UserStatus {
        return UserStatus(
            emoji = obj.str("emoji"),
            message = obj.str("message"),
            indicatesLimitedAvailability = obj["indicatesLimitedAvailability"]?.jsonPrimitive?.booleanOrNull ?: false,
            expiresAt = obj.str("expiresAt"),
        )
    }
}
