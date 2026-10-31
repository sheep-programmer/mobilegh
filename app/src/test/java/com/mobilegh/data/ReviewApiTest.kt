package com.mobilegh.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewApiTest {
    private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun review(id: Long, state: String, login: String?) =
        Review(id = id, state = state, user = login?.let { User(login = it) })

    // ---- 创建待提交审查：不带 event ----

    @Test fun createBodyCarriesHeadShaAndNoEvent() {
        val body = ReviewApi.createBody("abc123")
        assertEquals("abc123", body.s("commit_id"))
        assertFalse(body.containsKey("event"))
        assertEquals(setOf("commit_id"), body.keys)
    }

    @Test fun createBodyWithoutShaIsEmpty() {
        assertTrue(ReviewApi.createBody(null).isEmpty())
        assertTrue(ReviewApi.createBody("  ").isEmpty())
    }

    // ---- 加入待提交审查的行内评论 ----

    @Test fun lineCommentCarriesPathLineSideAndSha() {
        val body = ReviewApi.commentBody("looks off", "sha1", "src/A.kt", 12, "LEFT")
        assertEquals("looks off", body.s("body"))
        assertEquals("sha1", body.s("commit_id"))
        assertEquals("src/A.kt", body.s("path"))
        assertEquals("12", body.s("line"))
        assertEquals("LEFT", body.s("side"))
        assertFalse(body.containsKey("subject_type"))
        assertFalse(body.containsKey("start_line"))
    }

    @Test fun fileCommentUsesSubjectTypeFileWithoutLine() {
        val body = ReviewApi.commentBody("file note", "sha1", "src/A.kt", null)
        assertEquals("file", body.s("subject_type"))
        assertFalse(body.containsKey("line"))
        assertFalse(body.containsKey("side"))
    }

    @Test fun multiLineStartIsKeptOnlyWhenBeforeLine() {
        val multi = ReviewApi.commentBody("x", "s", "f.kt", 20, "RIGHT", startLine = 15, startSide = null)
        assertEquals("15", multi.s("start_line"))
        assertEquals("RIGHT", multi.s("start_side"))

        val single = ReviewApi.commentBody("x", "s", "f.kt", 20, "RIGHT", startLine = 20)
        assertFalse(single.containsKey("start_line"))
        assertFalse(single.containsKey("start_side"))
    }

    // ---- 提交审查 ----

    @Test fun submitBodyKeepsEventAndBody() {
        val body = ReviewApi.submitBody("REQUEST_CHANGES", "please fix")
        assertEquals("REQUEST_CHANGES", body.s("event"))
        assertEquals("please fix", body.s("body"))
    }

    @Test fun submitBodyOmitsBlankBody() {
        val body = ReviewApi.submitBody("APPROVE", "   ")
        assertEquals(setOf("event"), body.keys)
        assertEquals("APPROVE", body.s("event"))
    }

    @Test fun submitBodyRejectsUnknownEvent() {
        assertThrows(IllegalArgumentException::class.java) { ReviewApi.submitBody("DISMISS", "") }
        assertThrows(IllegalArgumentException::class.java) { ReviewApi.submitBody("", "x") }
    }

    // ---- 选择当前用户的待提交审查 ----

    @Test fun findPendingPicksOwnPendingReviewIgnoringCase() {
        val reviews = listOf(
            review(1, "APPROVED", "alice"),
            review(2, "PENDING", "bob"),
            review(3, "PENDING", "Alice"),
        )
        assertEquals(3L, ReviewApi.findPending(reviews, "alice")?.id)
    }

    @Test fun findPendingIgnoresSubmittedReviewsAndOtherUsers() {
        val reviews = listOf(review(1, "COMMENTED", "alice"), review(2, "PENDING", "bob"))
        assertNull(ReviewApi.findPending(reviews, "alice"))
        assertNull(ReviewApi.findPending(reviews, null))
        assertNull(ReviewApi.findPending(reviews, " "))
    }

    // ---- PR 列表的审查与 CI 状态 ----

    @Test fun reviewDecisionMapsToReviewState() {
        assertEquals(PullReviewState.APPROVED, PullStatusQuery.reviewStateOf("APPROVED"))
        assertEquals(PullReviewState.CHANGES_REQUESTED, PullStatusQuery.reviewStateOf("CHANGES_REQUESTED"))
        assertEquals(PullReviewState.REVIEW_REQUIRED, PullStatusQuery.reviewStateOf("REVIEW_REQUIRED"))
        assertEquals(PullReviewState.NONE, PullStatusQuery.reviewStateOf(null))
        assertEquals(PullReviewState.NONE, PullStatusQuery.reviewStateOf(""))
    }

    @Test fun rollupStateMapsToCiState() {
        assertEquals(PullCiState.SUCCESS, PullStatusQuery.ciStateOf("SUCCESS"))
        assertEquals(PullCiState.FAILURE, PullStatusQuery.ciStateOf("FAILURE"))
        assertEquals(PullCiState.FAILURE, PullStatusQuery.ciStateOf("ERROR"))
        assertEquals(PullCiState.PENDING, PullStatusQuery.ciStateOf("PENDING"))
        assertEquals(PullCiState.PENDING, PullStatusQuery.ciStateOf("EXPECTED"))
        assertEquals(PullCiState.NONE, PullStatusQuery.ciStateOf(null))
        assertEquals(PullCiState.NONE, PullStatusQuery.ciStateOf("SOMETHING_NEW"))
    }

    @Test fun queryHasOneAliasPerPullAndOneRepositoryField() {
        val query = PullStatusQuery.build(listOf(7, 9))
        assertTrue(query.startsWith("query MobileGHPullStatuses(\$owner: String!, \$name: String!)"))
        assertTrue(query.contains("p7: pullRequest(number: 7)"))
        assertTrue(query.contains("p9: pullRequest(number: 9)"))
        assertEquals(2, Regex("pullRequest\\(").findAll(query).count())
        assertEquals(1, Regex("repository\\(").findAll(query).count())
    }

    @Test fun parseMapsEachAliasToItsPullNodeId() {
        val data = json(
            """{"repository":{
              "p7":{"id":"PR_1","reviewDecision":"APPROVED","commits":{"nodes":[{"commit":{"statusCheckRollup":{"state":"FAILURE"}}}]}},
              "p9":{"id":"PR_2","reviewDecision":null,"commits":{"nodes":[{"commit":{"statusCheckRollup":null}}]}},
              "p11":null
            }}""",
        )
        val out = PullStatusQuery.parse(data, listOf(7, 9, 11))
        assertEquals(PullStatus(PullReviewState.APPROVED, PullCiState.FAILURE), out["PR_1"])
        assertEquals(PullStatus(PullReviewState.NONE, PullCiState.NONE), out["PR_2"])
        assertEquals(2, out.size)
    }

    @Test fun parseSkipsPullsWithoutIdAndMissingRepository() {
        assertTrue(PullStatusQuery.parse(json("""{"other":1}"""), listOf(1)).isEmpty())
        val noId = json("""{"repository":{"p1":{"reviewDecision":"APPROVED"}}}""")
        assertTrue(PullStatusQuery.parse(noId, listOf(1)).isEmpty())
    }
}
