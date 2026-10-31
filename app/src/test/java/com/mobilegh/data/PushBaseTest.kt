package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PushBaseTest {
    private fun event(payload: String) = Event(
        id = "1", type = "PushEvent", actor = EventActor(login = "mona"), repo = EventRepo("mona/mobilegh"),
        payload = Json.parseToJsonElement(payload) as JsonObject, createdAt = "2026-10-31T01:00:00Z",
    )

    @Test fun existingBranchComparesFromBeforeSha() {
        val s = pushSummary(event("""{"ref":"refs/heads/main","before":"aaa111","head":"bbb222",
            "commits":[{"sha":"ccc333","message":"x"}]}"""))!!
        assertEquals("aaa111", s.baseSha)
    }

    @Test fun newBranchUsesParentOfFirstPushedCommit() {
        val s = pushSummary(event("""{"ref":"refs/heads/new","before":"0000000000000000000000000000000000000000",
            "head":"bbb222","commits":[{"sha":"ccc333","message":"x"},{"sha":"bbb222","message":"y"}]}"""))!!
        assertEquals("ccc333^", s.baseSha)
    }
}
