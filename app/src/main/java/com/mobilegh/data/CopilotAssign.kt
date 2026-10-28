package com.mobilegh.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobilegh.i18n.tr

/**
 * 把 Issue 指派给 Copilot 编程助手（copilot-swe-agent）。
 * 先用 suggestedActors 确认仓库启用了 Copilot，再用 replaceActorsForAssignable 在保留现有指派人的同时加上 Copilot。
 */
object CopilotAssign {
    const val BOT_LOGIN = "copilot-swe-agent"
    const val DOCS_URL = "https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/start-copilot-sessions"

    data class Actor(val login: String, val id: String, val type: String)

    sealed interface Result {
        data object Assigned : Result
        data object AlreadyAssigned : Result
        data object Unavailable : Result
    }

    private const val QUERY = """
        query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!) {
          repository(owner: ${'$'}owner, name: ${'$'}name) {
            suggestedActors(capabilities: [CAN_BE_ASSIGNED], first: 100) {
              nodes { login __typename ... on Bot { id } ... on User { id } }
            }
            issue(number: ${'$'}number) {
              id
              assignedActors(first: 100) {
                nodes { __typename ... on Actor { login } ... on Node { id } }
              }
            }
          }
        }"""

    private const val MUTATION = """
        mutation(${'$'}assignableId: ID!, ${'$'}actorIds: [ID!]!) {
          replaceActorsForAssignable(input: { assignableId: ${'$'}assignableId, actorIds: ${'$'}actorIds }) {
            assignable { ... on Issue { id } }
          }
        }"""

    /** REST 接口里 Copilot 显示为 Copilot（Bot），GraphQL 里是 copilot-swe-agent */
    fun isCopilot(login: String?): Boolean =
        login != null && (login.equals(BOT_LOGIN, true) || login.equals("Copilot", true))

    fun parseActors(nodes: JsonArray?): List<Actor> = nodes.orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val login = o.str("login") ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        Actor(login, id, o.str("__typename").orEmpty())
    }

    fun findBot(actors: List<Actor>): Actor? =
        actors.firstOrNull { it.type == "Bot" && it.login.equals(BOT_LOGIN, true) }

    /** 现有指派人在前、Copilot 在后，去重；Copilot 已在其中时返回 null */
    fun actorIds(existing: List<Actor>, bot: Actor): List<String>? {
        if (existing.any { it.id == bot.id || isCopilot(it.login) }) return null
        return (existing.map { it.id } + bot.id).distinct()
    }

    suspend fun assign(owner: String, name: String, number: Int): Result {
        val repo = Api.graphql(QUERY, mapOf("owner" to owner, "name" to name, "number" to number)).obj("repository")
            ?: throw ApiException(404, tr("仓库不存在或没有权限"))
        val bot = findBot(parseActors(repo.obj("suggestedActors").arr("nodes"))) ?: return Result.Unavailable
        val issue = repo.obj("issue") ?: throw ApiException(404, tr("Issue 不存在"))
        val issueId = issue.str("id") ?: throw ApiException(404, tr("Issue 不存在"))
        val ids = actorIds(parseActors(issue.obj("assignedActors").arr("nodes")), bot) ?: return Result.AlreadyAssigned
        val done = Api.graphql(MUTATION, mapOf("assignableId" to issueId, "actorIds" to JsonArray(ids.map(::JsonPrimitive))))
        if (done.obj("replaceActorsForAssignable") == null) throw ApiException(403, tr("指派失败：可能没有此仓库的写入权限"))
        return Result.Assigned
    }
}
