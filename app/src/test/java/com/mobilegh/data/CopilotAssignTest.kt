package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotAssignTest {
    private fun nodes(json: String) = Json.parseToJsonElement(json).jsonArray

    private val suggested = nodes(
        """[
          {"login":"copilot-swe-agent","__typename":"Bot","id":"BOT_kgDOC9w8XQ"},
          {"login":"octocat","__typename":"User","id":"MDQ6VXNlcjE="},
          {"login":"ghost-org","__typename":"Organization"},
          {"__typename":"User","id":"U_noLogin"}
        ]""",
    )

    @Test fun parsesOnlyActorsWithLoginAndId() {
        val actors = CopilotAssign.parseActors(suggested)
        assertEquals(listOf("copilot-swe-agent", "octocat"), actors.map { it.login })
        assertEquals("Bot", actors[0].type)
        assertEquals(emptyList<CopilotAssign.Actor>(), CopilotAssign.parseActors(null))
    }

    @Test fun findsCopilotBotAmongSuggestedActors() {
        val bot = CopilotAssign.findBot(CopilotAssign.parseActors(suggested))
        assertEquals("BOT_kgDOC9w8XQ", bot?.id)
    }

    @Test fun userNamedLikeTheBotIsNotTheBot() {
        val actors = listOf(CopilotAssign.Actor("copilot-swe-agent", "U_1", "User"), CopilotAssign.Actor("octocat", "U_2", "User"))
        assertNull(CopilotAssign.findBot(actors))
        assertNull(CopilotAssign.findBot(emptyList()))
    }

    @Test fun keepsExistingAssigneesAndAppendsBot() {
        val bot = CopilotAssign.Actor("copilot-swe-agent", "BOT_1", "Bot")
        val existing = listOf(CopilotAssign.Actor("octocat", "U_1", "User"), CopilotAssign.Actor("hubot", "U_2", "User"), CopilotAssign.Actor("octocat", "U_1", "User"))
        assertEquals(listOf("U_1", "U_2", "BOT_1"), CopilotAssign.actorIds(existing, bot))
        assertEquals(listOf("BOT_1"), CopilotAssign.actorIds(emptyList(), bot))
    }

    @Test fun returnsNullWhenCopilotIsAlreadyAssigned() {
        val bot = CopilotAssign.Actor("copilot-swe-agent", "BOT_1", "Bot")
        assertNull(CopilotAssign.actorIds(listOf(CopilotAssign.Actor("octocat", "U_1", "User"), bot), bot))
        // REST 与部分 GraphQL 结果中 Copilot 显示为 "Copilot"
        assertNull(CopilotAssign.actorIds(listOf(CopilotAssign.Actor("Copilot", "BOT_1", "Bot")), bot))
    }

    @Test fun recognisesCopilotLogins() {
        assertTrue(CopilotAssign.isCopilot("Copilot"))
        assertTrue(CopilotAssign.isCopilot("copilot-swe-agent"))
        assertFalse(CopilotAssign.isCopilot("copilot-fan"))
        assertFalse(CopilotAssign.isCopilot(null))
    }
}
