package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiscussionsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private val category = """{"id":"cat","name":"Questions","emoji":":question:","description":null,"isAnswerable":true}"""
    private val discussion = """{
        "id":"discussion","number":42,"title":"How to build Android?","url":"https://github.com/o/r/discussions/42",
        "author":{"login":"octocat","avatarUrl":"https://avatars.githubusercontent.com/u/1"},
        "category":$category,"createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z",
        "upvoteCount":3,"isAnswered":null,"locked":false,"closed":false,"comments":{"totalCount":2}
    }"""
    private val comment = """{
        "id":"comment","url":"https://github.com/o/r/discussions/42#discussioncomment-1","author":null,
        "createdAt":"2026-10-02T00:00:00Z","bodyHTML":"<p><strong>Answer</strong></p>",
        "isAnswer":true,"isMinimized":false,"replies":{"totalCount":4}
    }"""

    private fun connection(nodes: String, cursor: String = "\"next\"", hasNext: Boolean = true) =
        """{"nodes":[$nodes],"pageInfo":{"endCursor":$cursor,"hasNextPage":$hasNext},"totalCount":25}"""

    private suspend fun loadedDiscussion(): Discussion = DiscussionsClient { _, _ ->
        json("""{"repository":{"discussions":${connection(discussion)}}}""")
    }.list("o", "r").items.single()

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        fail("Expected failure")
        error("unreachable")
    }

    @Test fun listUsesVariablesForCursorAndFiltersAndKeepsMetadataOnly() = runBlocking {
        var captured: Map<String, Any?> = emptyMap()
        var query = ""
        val owner = "owner\"with quotes"
        val client = DiscussionsClient { q, variables ->
            query = q
            captured = variables
            json("""{"repository":{"discussions":${connection("$discussion,null")}}}""")
        }
        val page = client.list(owner, "r", "previous", "cat", answered = false)
        assertEquals(owner, captured["owner"])
        assertEquals("previous", captured["after"])
        assertEquals("cat", captured["categoryId"])
        assertEquals(false, captured["answered"])
        assertTrue(query.contains("\$owner"))
        assertFalse(query.contains(owner))
        assertFalse(query.contains("bodyHTML"))
        assertEquals(1, page.items.size)
        assertEquals("next", page.endCursor)
        assertTrue(page.hasNextPage)
        assertNull(page.items.single().bodyHtml)
        assertFalse(page.items.single().answered)
    }

    @Test fun categoriesArePagedAndDeletedNodesAreSkipped() = runBlocking {
        val client = DiscussionsClient { _, variables ->
            assertEquals("previous", variables["after"])
            json("""{"repository":{"discussionCategories":${connection("$category,null", "null", false)}}}""")
        }
        val page = client.categories("o", "r", "previous")
        assertEquals("Questions", page.items.single().name)
        assertFalse(page.hasNextPage)
        assertNull(page.endCursor)
    }

    @Test fun detailUsesServerHtmlAndFetchesTheAnswerSeparately() = runBlocking {
        val base = json(discussion)
        val fields = base.toMutableMap().apply {
            put("bodyHTML", JsonPrimitive("<p>GitHub-rendered <em>Markdown</em></p>"))
            put("body", JsonPrimitive("<script>raw Markdown is not HTML</script>"))
            put("comments", json(connection(comment)))
            put("answer", json(comment))
        }
        val client = DiscussionsClient { query, _ ->
            assertEquals(1, Regex("comments\\s*\\(").findAll(query).count())
            assertFalse(query.contains("viewerCanComment"))
            assertFalse(query.contains("viewerCanCreateDiscussion"))
            json("""{"repository":{"id":"repo","hasDiscussionsEnabled":true,"isArchived":false,"discussion":${JsonObject(fields)}}}""")
        }
        val thread = client.detail("o", "r", 42)
        assertEquals("<p>GitHub-rendered <em>Markdown</em></p>", thread.discussion.bodyHtml)
        assertEquals("<p><strong>Answer</strong></p>", thread.comments.items.single().bodyHtml)
        assertNull(thread.answer!!.author)
        assertTrue(thread.answer.isAnswer)
        assertEquals(4, thread.answer.replyCount)
    }

    @Test fun commentsAndThreadedRepliesHaveIndependentCursors() = runBlocking {
        val calls = mutableListOf<Map<String, Any?>>()
        val client = DiscussionsClient { query, variables ->
            calls += variables
            if (query.contains("DiscussionReplies")) json("""{"node":{"replies":${connection(comment)}}}""")
            else json("""{"repository":{"discussion":{"comments":${connection(comment)}}}}""")
        }
        client.comments("o", "r", 42, "comment-cursor")
        client.replies("comment", "reply-cursor")
        assertEquals("comment-cursor", calls[0]["after"])
        assertEquals(42, calls[0]["number"])
        assertEquals("reply-cursor", calls[1]["after"])
        assertEquals("comment", calls[1]["id"])
    }

    @Test fun missingRepositoryOrDiscussionIsAnErrorInsteadOfAnEmptyPage() = runBlocking {
        assertEquals(404, (failure { DiscussionsClient { _, _ -> json("""{"repository":null}""") }.list("o", "r") } as ApiException).code)
        assertEquals(404, (failure { DiscussionsClient { _, _ -> json("""{"repository":{"discussion":null}}""") }.detail("o", "r", 42) } as ApiException).code)
    }

    @Test fun incompleteConnectionsAndStalledCursorsFail() = runBlocking {
        listOf(
            """{"nodes":[],"totalCount":0}""",
            connection("", "null", true),
            connection("", "\"previous\"", true),
        ).forEach { broken ->
            val error = failure { DiscussionsClient { _, _ -> json("""{"repository":{"discussions":$broken}}""") }.list("o", "r", "previous") }
            assertEquals(502, (error as ApiException).code)
        }
    }

    @Test fun createRequiresPermissionAndAUsableRepositoryBeforeAnyRequest() = runBlocking {
        var calls = 0
        val client = DiscussionsClient { _, _ -> calls++; json("{}") }
        listOf(
            DiscussionRepository("repo", true, false) to false,
            DiscussionRepository("repo", true, true) to true,
            DiscussionRepository("repo", false, false) to true,
        ).forEach { (repository, allowed) ->
            assertEquals(403, (failure { client.create(repository, "cat", "Title", "Body", allowed) } as ApiException).code)
        }
        assertEquals(0, calls)
    }

    @Test fun blankDraftsNeverSendMutations() = runBlocking {
        var calls = 0
        val client = DiscussionsClient { _, _ -> calls++; json("{}") }
        val repository = DiscussionRepository("repo", true, false)
        assertTrue(failure { client.create(repository, "cat", " ", "body", true) } is IllegalArgumentException)
        assertTrue(failure { client.create(repository, "", "title", "body", true) } is IllegalArgumentException)
        assertTrue(failure { client.reply(repository, loadedDiscussion(), " \n", true) } is IllegalArgumentException)
        assertEquals(0, calls)
    }

    @Test fun createSendsUserTextAsStructuredInputExactlyOnce() = runBlocking {
        var calls = 0
        val body = "## Heading\n\n`code` and \"quoted\" text"
        val client = DiscussionsClient { query, variables ->
            calls++
            assertTrue(query.contains("CreateDiscussionInput!"))
            val input = variables["input"] as JsonObject
            assertEquals("repo", input.str("repositoryId"))
            assertEquals("cat", input.str("categoryId"))
            assertEquals("Title", input.str("title"))
            assertEquals(body, input.str("body"))
            json("""{"createDiscussion":{"discussion":$discussion}}""")
        }
        assertEquals(42, client.create(DiscussionRepository("repo", true, false), "cat", " Title ", body, true).number)
        assertEquals(1, calls)
    }

    @Test fun repliesRespectReadOnlyLockedClosedAndArchivedStates() = runBlocking {
        var calls = 0
        val client = DiscussionsClient { _, _ -> calls++; json("{}") }
        val repository = DiscussionRepository("repo", true, false)
        val discussion = loadedDiscussion()
        listOf(discussion.copy(locked = true), discussion.copy(closed = true)).forEach {
            assertEquals(403, (failure { client.reply(repository, it, "reply", true) } as ApiException).code)
        }
        assertEquals(403, (failure { client.reply(repository, discussion, "reply", false) } as ApiException).code)
        assertEquals(403, (failure { client.reply(repository.copy(archived = true), discussion, "reply", true) } as ApiException).code)
        assertEquals(0, calls)
    }

    @Test fun replyToIsOptionalAndSentOnlyForTheChosenComment() = runBlocking {
        val inputs = mutableListOf<JsonObject>()
        val client = DiscussionsClient { _, variables ->
            inputs += variables["input"] as JsonObject
            json("""{"addDiscussionComment":{"comment":$comment}}""")
        }
        val repository = DiscussionRepository("repo", true, false)
        val discussion = loadedDiscussion()
        client.reply(repository, discussion, "  Markdown\n", true)
        client.reply(repository, discussion, "Reply", true, "comment")
        assertFalse(inputs[0].containsKey("replyToId"))
        assertEquals("  Markdown\n", inputs[0].str("body"))
        assertEquals("comment", inputs[1].str("replyToId"))
        assertEquals("discussion", inputs[1].str("discussionId"))
    }

    @Test fun unconfirmedMutationsAreNotRetried() = runBlocking {
        var calls = 0
        val client = DiscussionsClient { _, _ -> calls++; json("""{"createDiscussion":null}""") }
        val error = failure { client.create(DiscussionRepository("repo", true, false), "cat", "title", "body", true) }
        assertTrue(error.message!!.contains("刷新检查"))
        assertEquals(1, calls)
    }

    @Test fun cancellationAndTransportErrorsPropagateUnchanged() = runBlocking {
        val cancelled = CancellationException("navigated away")
        assertSame(cancelled, failure { DiscussionsClient { _, _ -> throw cancelled }.list("o", "r") })
        assertSame(cancelled, failure { DiscussionsClient { _, _ -> throw cancelled }.create(DiscussionRepository("repo", true, false), "cat", "title", "body", true) })
        val denied = ApiException(403, "Token cannot write Discussions")
        assertSame(denied, failure { DiscussionsClient { _, _ -> throw denied }.list("o", "r") })
    }

    @Test fun localSearchCombinesTermsWithoutNetworkRequests() = runBlocking {
        val discussion = loadedDiscussion()
        assertTrue(discussion.matches("ANDROID octocat #42"))
        assertTrue(discussion.matches("Questions build"))
        assertTrue(discussion.matches("   "))
        assertFalse(discussion.matches("Android missing"))
    }

    @Test fun boundedWindowsDeduplicateAndRefreshReplacesOlderPages() {
        val window = DiscussionWindow<String>(3) { it }
        window.accept(DiscussionPage(listOf("1", "2"), "first", true, 9))
        window.accept(DiscussionPage(listOf("2", "3", "4"), "second", true, 9))
        assertEquals(listOf("1", "2", "3"), window.items)
        assertTrue(window.limited)
        assertFalse(window.hasNextPage)
        window.accept(DiscussionPage(listOf("8"), null, false, 1), replace = true)
        assertEquals(listOf("8"), window.items)
        assertFalse(window.limited)
        assertEquals(1, window.totalCount)
    }

    @Test fun exactMemoryLimitDoesNotPretendThereAreHiddenResults() {
        val window = DiscussionWindow<String>(2) { it }
        window.accept(DiscussionPage(listOf("1", "2"), "end", false, 2))
        assertFalse(window.limited)
        assertFalse(window.hasNextPage)
    }
}
