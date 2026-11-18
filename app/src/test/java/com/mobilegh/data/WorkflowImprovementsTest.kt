package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowImprovementsTest {
    @Test fun searchCompletionUsesLocalHistoryAndKeepsTextAfterTheCursor() {
        val history = HistoryData(repositories = listOf(RecentRepository("octo", "repo")))
        val query = "fix -repo:octo/typo status:open"
        val completion = requireNotNull(searchCompletion(query, "fix -repo:octo/".length, SearchType.ISSUE, history, null))
        assertEquals(listOf("octo/repo"), completion.values)
        assertEquals("fix -repo:octo/repo status:open" to "fix -repo:octo/repo ".length, completion.apply(query, "octo/repo"))
        assertNull(searchCompletion("\"repo:octo/\"", 10, SearchType.ISSUE, history, null))
    }

    @Test fun searchCompletionQuotesMultiwordLabelsAndLimitsPrStatesToPrs() {
        val query = "label:\"help w"
        val completion = requireNotNull(searchCompletion(query, query.length, SearchType.ISSUE, HistoryData(), null))
        assertEquals("label:\"help wanted\" ", completion.apply(query, completion.values.single()).first)
        assertFalse("draft" in requireNotNull(searchCompletion("is:", 3, SearchType.ISSUE, HistoryData(), null)).values)
        assertTrue("draft" in requireNotNull(searchCompletion("is:", 3, SearchType.PR, HistoryData(), null)).values)
    }

    @Test fun failureNavigationChoosesFirstFailureAndFindsAnErrorInsideItsStep() {
        val step = Step(number = 2, conclusion = "failure", startedAt = "2024-01-01T00:00:02Z", completedAt = "2024-01-01T00:00:04Z")
        val timedOut = Job(id = 2, conclusion = "timed_out", completedAt = "2024-01-01T00:00:05Z")
        val failed = Job(id = 1, conclusion = "failure", steps = listOf(step.copy(number = 6), step))
        assertEquals(failed to step, firstFailedJob(listOf(timedOut, failed, Job(conclusion = "cancelled"))))
        val times = listOf("2024-01-01T00:00:01Z", "2024-01-01T00:00:02.500Z", "2024-01-01T00:00:03Z", "2024-01-01T00:00:05Z")
        assertEquals(2, logStepTarget(times, step, listOf(0, 2, 3), focusFailure = true))
        assertEquals(1, logStepTarget(times, step, listOf(0, 2, 3), focusFailure = false))
        assertNull(logStepTarget(times, step.copy(startedAt = null), listOf(0), focusFailure = true))
    }

    @Test fun navigationWrapsAndCanStartBetweenTwoMatches() {
        val targets = listOf(2, 4, 7)
        assertEquals(2, nextLogTarget(targets, 7, true))
        assertEquals(7, nextLogTarget(targets, 2, false))
        assertEquals(7, nextLogTarget(targets, 6, true))
        assertEquals(4, nextLogTarget(targets, 6, false))
        assertNull(nextLogTarget(emptyList(), 6, true))
    }

    @Test fun reviewPagesRespectCursorAndDismissedFilesAreNotViewed() = runBlocking {
        val cursors = mutableListOf<Any?>()
        val progress = ReviewProgressApi.read("test", "fixture", 1) { query, vars ->
            assertTrue("viewerViewedState" in query)
            cursors.add(vars["cursor"])
            if (vars["cursor"] == null) reviewPage("head", "a.kt", "VIEWED", "p2")
            else reviewPage("head", "b.kt", "DISMISSED", null)
        }
        assertEquals(listOf(null, "p2"), cursors)
        assertEquals(mapOf("a.kt" to "sha-a"), progress.viewedFiles(listOf(CommitFile(filename = "a.kt", sha = "sha-a"), CommitFile(filename = "b.kt", sha = "sha-b"))))
        assertFalse(progress.matches("new-head", "base"))
    }

    @Test fun reviewReadRejectsChangedRevisionsAndRepeatingCursors() = runBlocking {
        for (changedHead in listOf(false, true)) {
            val result = runCatching {
                ReviewProgressApi.read("test", "fixture", 1) { _, vars ->
                    reviewPage(if (vars["cursor"] != null && changedHead) "new" else "head", "a.kt", "VIEWED", "repeated")
                }
            }
            assertEquals(if (changedHead) 409 else 403, (result.exceptionOrNull() as? ApiException)?.code)
        }
    }

    @Test fun markAndUnmarkUsePublicMutationsWithPathVariables() = runBlocking {
        val progress = ReviewProgress("fixture-id", "head", "base", emptyMap())
        for (viewed in listOf(true, false)) {
            var called = false
            ReviewProgressApi.setViewed(progress, "src/some file.kt", viewed, Session.generation) { query, vars ->
                val operation = if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"
                assertTrue("{ $operation(" in query)
                assertEquals(json("""{"pullRequestId":"fixture-id","path":"src/some file.kt"}"""), vars["input"])
                called = true
                json("""{"$operation":{"pullRequest":{"id":"fixture-id","headRefOid":"head","baseRefOid":"base"}}}""")
            }
            assertTrue(called)
        }
    }

    @Test fun localReviewPositionDoesNotReuseARowAfterFileOrModeChanges() {
        val position = ReviewPosition("file.kt", "current", 4, 10, false)
        val file = CommitFile(filename = "file.kt", sha = "current")
        assertNotNull(position.rowFor(file, false))
        assertNull(position.rowFor(file.copy(sha = "changed"), false))
        assertNull(position.rowFor(file, true))
    }

    private fun reviewPage(head: String, path: String, state: String, cursor: String?) = json(
        """{"repository":{"pullRequest":{"id":"fixture-id","headRefOid":"$head","baseRefOid":"base","files":{"nodes":[{"path":"$path","viewerViewedState":"$state"}],"pageInfo":{"hasNextPage":${cursor != null},"endCursor":${cursor?.let { "\"$it\"" } ?: "null"}}}}}}""",
    )

    private fun json(text: String) = Api.plain.parseToJsonElement(text).jsonObject
}
