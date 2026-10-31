package com.mobilegh.ui.screens

import org.junit.Assert.*
import org.junit.Test

class NewIssueDraftTest {
    @Test fun blankFormHasNoContentToSave() {
        assertTrue(newIssueDraft("", "", NewIssueMeta()).empty)
    }

    @Test fun metadataAloneIsContentAndSurvivesRestore() {
        val meta = NewIssueMeta(
            labels = listOf(MetaOpt("bug", "bug", color = "ff0000")),
            assignees = listOf(MetaOpt("alice", "alice", avatar = "https://example.test/a.png")),
            milestone = MetaOpt("3", "v1"),
        )
        val draft = newIssueDraft("", "", meta)
        assertFalse(draft.empty)

        val restored = draft.toNewIssueMeta()
        assertEquals(listOf("bug"), restored.labels.map { it.key })
        assertEquals("ff0000", restored.labels.single().color)
        assertEquals(listOf("alice"), restored.assignees.map { it.key })
        assertEquals("https://example.test/a.png", restored.assignees.single().avatar)
        assertEquals("3", restored.milestone?.key)
        assertEquals("v1", restored.milestone?.label)
    }

    @Test fun titleAndBodyStillRoundTrip() {
        val draft = newIssueDraft("Crash on start", "steps", NewIssueMeta())
        assertEquals("Crash on start", draft.title)
        assertEquals("steps", draft.body)
        assertTrue(draft.toNewIssueMeta().labels.isEmpty())
    }
}
