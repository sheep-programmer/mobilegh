package com.mobilegh.ui.screens

import com.mobilegh.data.Notification
import com.mobilegh.data.Subject
import com.mobilegh.data.Repo
import com.mobilegh.data.User
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBulkStopTest {
    @Test fun matchNotifTypeHandlesVariousKeys() {
        fun makeNotif(type: String) = Notification(
            id = "1",
            repository = Repo(id = 1, name = "r", fullName = "o/r", owner = User(id = 1, login = "o")),
            subject = Subject(title = "t", url = null, latestCommentUrl = null, type = type),
            reason = "subscribed",
            unread = true,
            updatedAt = "2026-11-08T00:00:00Z",
        )

        val issue = makeNotif("Issue")
        val pr = makeNotif("PullRequest")
        val workflow = makeNotif("WorkflowRun")
        val checkSuite = makeNotif("CheckSuite")
        val discussion = makeNotif("Discussion")
        val release = makeNotif("Release")

        // null -> matches all
        assertTrue(matchNotifType(issue, null))
        assertTrue(matchNotifType(workflow, null))

        // issues_prs -> matches both Issue and PullRequest
        assertTrue(matchNotifType(issue, "issues_prs"))
        assertTrue(matchNotifType(pr, "issues_prs"))
        assertFalse(matchNotifType(workflow, "issues_prs"))

        // WorkflowRun -> matches WorkflowRun and CheckSuite
        assertTrue(matchNotifType(workflow, "WorkflowRun"))
        assertTrue(matchNotifType(checkSuite, "WorkflowRun"))
        assertFalse(matchNotifType(issue, "WorkflowRun"))

        // Exact matches
        assertTrue(matchNotifType(discussion, "Discussion"))
        assertFalse(matchNotifType(discussion, "Release"))
        assertTrue(matchNotifType(release, "Release"))
    }

    @Test fun matchNotifFiltersMultiSelectWorks() {
        fun makeNotif(type: String, reason: String, repo: String) = Notification(
            id = "1",
            repository = Repo(id = 1, name = repo.substringAfter('/'), fullName = repo, owner = User(id = 1, login = repo.substringBefore('/'))),
            subject = Subject(title = "t", url = null, latestCommentUrl = null, type = type),
            reason = reason,
            unread = true,
            updatedAt = "2026-11-08T00:00:00Z",
        )

        val n1 = makeNotif("Issue", "mention", "owner/repo-a")
        val n2 = makeNotif("PullRequest", "review_requested", "owner/repo-b")
        val n3 = makeNotif("WorkflowRun", "ci_activity", "owner/repo-c")

        // Empty sets -> matches everything
        assertTrue(matchNotifFilters(n1, emptySet(), emptySet(), emptySet()))
        assertTrue(matchNotifFilters(n2, emptySet(), emptySet(), emptySet()))
        assertTrue(matchNotifFilters(n3, emptySet(), emptySet(), emptySet()))

        // Multi-select types: Issue + PullRequest
        val typeFilter = setOf("Issue", "PullRequest")
        assertTrue(matchNotifFilters(n1, typeFilter, emptySet(), emptySet()))
        assertTrue(matchNotifFilters(n2, typeFilter, emptySet(), emptySet()))
        assertFalse(matchNotifFilters(n3, typeFilter, emptySet(), emptySet()))

        // Multi-select reasons: mention + ci_activity
        val reasonFilter = setOf("mention", "ci_activity")
        assertTrue(matchNotifFilters(n1, emptySet(), reasonFilter, emptySet()))
        assertFalse(matchNotifFilters(n2, emptySet(), reasonFilter, emptySet()))
        assertTrue(matchNotifFilters(n3, emptySet(), reasonFilter, emptySet()))

        // Combined filtering
        assertTrue(matchNotifFilters(n1, setOf("Issue"), setOf("mention"), setOf("owner/repo-a")))
        assertFalse(matchNotifFilters(n1, setOf("Issue"), setOf("mention"), setOf("owner/repo-b")))
    }

    @Test fun itemsNotYetStartedAreSkippedAfterCancel() = runBlocking {
        val started = mutableListOf<String>()
        var cancelled = false
        val result = runBulk(listOf("a", "b", "c"), {}, shouldStop = { cancelled }) { id ->
            started.add(id)
            // 第一条发出后用户点了取消：之后尚未发起的条目不再调用接口
            cancelled = true
        }
        assertEquals(listOf("a"), started)
        assertEquals(1, result.ok)
        assertEquals(2, result.skipped)
        assertEquals(0, result.failed)
    }

    @Test fun withoutStopEveryItemRuns() = runBlocking {
        val result = runBulk(listOf("x", "y"), {}) { }
        assertEquals(2, result.ok)
        assertEquals(0, result.skipped)
    }
}
