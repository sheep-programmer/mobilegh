package com.mobilegh.data

import org.junit.Assert.*
import org.junit.Test

class DraftsTest {
    private val storage = MemoryRecords()
    private val store = DraftStore(storage)
    @Test fun draftsSurviveReopeningAndAreIsolatedByAccountAndThread() {
        val value = DraftValue("title", "body", "category")
        store.save("alice", "discussion:mona/repo:1", value)
        assertEquals(value, DraftStore(storage).load("ALICE", "discussion:mona/repo:1"))
        assertTrue(store.load("bob", "discussion:mona/repo:1").empty)
        assertTrue(store.load("alice", "discussion:mona/repo:2").empty)
    }
    @Test fun emptyDraftAndSuccessfulSubmissionRemoveOnlyTheCurrentDraft() {
        store.save("alice", "one", DraftValue(body = "one"))
        store.save("alice", "two", DraftValue(body = "two"))
        store.save("alice", "one", DraftValue())
        assertTrue(store.load("alice", "one").empty)
        assertEquals("two", store.load("alice", "two").body)
    }
    @Test fun oversizedDraftDoesNotOverwriteSavedText() {
        store.save("alice", "one", DraftValue(body = "saved"))
        assertThrows(IllegalArgumentException::class.java) { store.save("alice", "one", DraftValue(body = "x".repeat(65_537))) }
        assertEquals("saved", store.load("alice", "one").body)
    }
    @Test fun outgoingCompositionCannotRestoreAnAlreadySubmittedDraft() {
        val value = DraftValue(body = "posted")
        store.save("alice", "one", value)
        val controller = DraftController(store, "alice", "one")
        controller.submitted(value)
        controller.update(value)
        controller.flush()
        Thread.sleep(400)
        assertTrue(store.load("alice", "one").empty)
    }
    @Test fun submittingOldTextDoesNotDeleteTextTypedWhileSending() {
        val controller = DraftController(store, "alice", "one")
        controller.update(DraftValue(body = "new text"))
        controller.submitted(DraftValue(body = "old text"))
        controller.flush()
        val deadline = System.nanoTime() + 2_000_000_000
        while (store.load("alice", "one").body != "new text" && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("new text", store.load("alice", "one").body)
    }
    @Test fun typingTheSameCommentAgainAfterClearingCanCreateANewDraft() {
        val value = DraftValue(body = "Thanks!")
        store.save("alice", "one", value)
        val controller = DraftController(store, "alice", "one")
        controller.submitted(value)
        controller.update(DraftValue())
        controller.update(value)
        controller.flush()
        val deadline = System.nanoTime() + 2_000_000_000
        while (store.load("alice", "one").body != value.body && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(value, store.load("alice", "one"))
    }
}
