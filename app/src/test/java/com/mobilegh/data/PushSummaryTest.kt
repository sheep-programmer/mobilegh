package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSummaryTest {
    private fun event(type: String, payload: String) = Event(
        id = "1", type = type, actor = EventActor(login = "mona"), repo = EventRepo("mona/mobilegh"),
        payload = Json.parseToJsonElement(payload) as JsonObject, createdAt = "2026-10-31T01:00:00Z",
    )

    @Test fun pushToBranchUsesHeadCommitAndRealSize() {
        val s = pushSummary(event("PushEvent", """{"ref":"refs/heads/feature/x","head":"abcdef1234","size":3,
            "commits":[{"sha":"111","message":"older"},{"sha":"abcdef1234","message":"Fix login\nlong body"}]}"""))!!
        assertEquals("feature/x", s.ref)
        assertFalse(s.isTag)
        assertEquals(3, s.commitCount)
        assertEquals("abcdef1234", s.headSha)
        assertEquals("Fix login", s.headMessage)
    }

    @Test fun tagPushIsMarkedAsTag() {
        val s = pushSummary(event("PushEvent", """{"ref":"refs/tags/v1.0","head":"aaa","commits":[]}"""))!!
        assertEquals("v1.0", s.ref)
        assertTrue(s.isTag)
        assertNull(s.commitCount)
    }

    @Test fun countFallsBackToCommitListWhenSizeMissing() {
        val s = pushSummary(event("PushEvent", """{"ref":"refs/heads/main","commits":[{"sha":"a","message":"m1"},{"sha":"b","message":"m2"}]}"""))!!
        assertEquals(2, s.commitCount)
        assertEquals("m2", s.headMessage)
    }

    @Test fun nonPushEventsAndMissingRefAreIgnored() {
        assertNull(pushSummary(event("IssuesEvent", """{"ref":"refs/heads/main"}""")))
        assertNull(pushSummary(event("PushEvent", """{"head":"a"}""")))
    }
}
