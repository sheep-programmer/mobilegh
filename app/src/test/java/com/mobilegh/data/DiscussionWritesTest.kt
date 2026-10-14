package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiscussionWritesTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private class Call(val query: String, val variables: Map<String, Any?>) {
        val input get() = variables["input"] as JsonObject
    }

    private fun client(response: String, calls: MutableList<Call>) = DiscussionsClient { q, v ->
        calls += Call(q, v)
        json(response)
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        fail("Expected failure")
        error("unreachable")
    }

    @Test fun upvoteUsesSubjectIdAndReturnsServerState() = runBlocking {
        val calls = mutableListOf<Call>()
        val add = client("""{"addUpvote":{"subject":{"upvoteCount":8,"viewerHasUpvoted":true}}}""", calls)
        assertEquals(8 to true, add.upvote("D_1", true))
        assertTrue(calls[0].query.contains("addUpvote"))
        assertEquals("D_1", calls[0].input.str("subjectId"))

        val remove = client("""{"removeUpvote":{"subject":{"upvoteCount":7,"viewerHasUpvoted":false}}}""", calls)
        assertEquals(7 to false, remove.upvote("DC_2", false))
        assertTrue(calls[1].query.contains("removeUpvote"))
        assertFalse(calls[1].query.contains("addUpvote"))
    }

    @Test fun unconfirmedUpvoteIsAnError() = runBlocking {
        val error = failure { client("""{"addUpvote":null}""", mutableListOf()).upvote("D_1", true) }
        assertTrue(error is ApiException)
    }

    @Test fun markAndUnmarkAnswerTargetTheComment() = runBlocking {
        val calls = mutableListOf<Call>()
        assertTrue(client("""{"markDiscussionCommentAsAnswer":{"discussion":{"id":"D","isAnswered":true}}}""", calls).markAnswer("DC_1", true))
        assertFalse(client("""{"unmarkDiscussionCommentAsAnswer":{"discussion":{"id":"D","isAnswered":false}}}""", calls).markAnswer("DC_1", false))
        assertEquals("DC_1", calls[0].input.str("id"))
        assertTrue(calls[0].query.contains("markDiscussionCommentAsAnswer"))
        assertTrue(calls[1].query.contains("unmarkDiscussionCommentAsAnswer"))
    }

    @Test fun editsAndDeletesSendStructuredInput() = runBlocking {
        val calls = mutableListOf<Call>()
        client("""{"updateDiscussion":{"discussion":{"id":"D"}}}""", calls).update("D", "  New title ", "body \"quoted\"")
        client("""{"updateDiscussionComment":{"comment":{"id":"DC"}}}""", calls).updateComment("DC", "edited")
        client("""{"deleteDiscussion":{"discussion":{"id":"D"}}}""", calls).delete("D")
        client("""{"deleteDiscussionComment":{"comment":{"id":"DC"}}}""", calls).deleteComment("DC")
        assertEquals("D", calls[0].input.str("discussionId"))
        assertEquals("New title", calls[0].input.str("title"))
        assertEquals("body \"quoted\"", calls[0].input.str("body"))
        assertEquals("DC", calls[1].input.str("commentId"))
        assertEquals("edited", calls[1].input.str("body"))
        assertEquals("D", calls[2].input.str("id"))
        assertEquals("DC", calls[3].input.str("id"))
        calls.forEach { assertFalse(it.query.contains("\"D\"")) }
    }

    @Test fun blankEditsNeverSendMutations() = runBlocking {
        val calls = mutableListOf<Call>()
        val c = client("{}", calls)
        assertTrue(failure { c.update("D", " ", "body") } is IllegalArgumentException)
        assertTrue(failure { c.updateComment("DC", "\n") } is IllegalArgumentException)
        assertEquals(0, calls.size)
    }

    @Test fun commentsCarryViewerStateAndFirstPageOfReplies() = runBlocking {
        val reply = """{"id":"R1","url":"u#r1","author":{"login":"b","avatarUrl":null},"createdAt":null,"body":"hi","bodyHTML":"<p>hi</p>",
            "isAnswer":false,"isMinimized":false,"upvoteCount":1,"viewerHasUpvoted":true,"viewerCanUpvote":true,
            "viewerCanUpdate":false,"viewerCanDelete":false,"viewerCanMarkAsAnswer":false,"replies":{"totalCount":0}}"""
        val top = """{"id":"C1","url":"u#c1","author":{"login":"a","avatarUrl":null},"createdAt":null,"body":"**md**","bodyHTML":"<p><b>md</b></p>",
            "isAnswer":false,"isMinimized":false,"upvoteCount":3,"viewerHasUpvoted":false,"viewerCanUpvote":true,
            "viewerCanUpdate":true,"viewerCanDelete":true,"viewerCanMarkAsAnswer":true,"viewerCanUnmarkAsAnswer":false,
            "replies":{"pageInfo":{"endCursor":"rc","hasNextPage":true},"totalCount":6,"nodes":[$reply]}}"""
        var query = ""
        val c = DiscussionsClient { q, _ ->
            query = q
            json("""{"repository":{"discussion":{"comments":{"pageInfo":{"endCursor":null,"hasNextPage":false},"totalCount":1,"nodes":[$top]}}}}""")
        }
        val comment = c.comments("o", "r", 1, null).items.single()
        assertTrue(query.contains("replies(first: ${DiscussionsClient.REPLY_PREVIEW})"))
        assertEquals("**md**", comment.body)
        assertEquals(3, comment.upvoteCount)
        assertTrue(comment.viewerCanMarkAsAnswer && comment.viewerCanUpdate && comment.viewerCanDelete)
        assertEquals(6, comment.replyCount)
        val preview = comment.replies
        assertNotNull(preview)
        preview!!
        assertEquals("rc", preview.endCursor)
        assertTrue(preview.hasNextPage)
        val first = preview.items.single()
        assertEquals("R1", first.id)
        assertTrue(first.viewerHasUpvoted)
        assertNull(first.replies)
    }
}
