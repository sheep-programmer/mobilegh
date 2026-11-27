package com.mobilegh.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationActionTest {
    @Test fun parsesIssueAndPullRequestSubjectUrls() {
        assertEquals(
            Triple("octocat", "hello-world", 42),
            NotificationActionReceiver.parseIssueTarget("https://api.github.com/repos/octocat/hello-world/issues/42", "Issue"),
        )
        assertEquals(
            Triple("octocat", "hello-world", 99),
            NotificationActionReceiver.parseIssueTarget("https://api.github.com/repos/octocat/hello-world/pulls/99", "PullRequest"),
        )
        assertNull(NotificationActionReceiver.parseIssueTarget("https://api.github.com/repos/octocat/hello-world/commits/abc", "Commit"))
        assertNull(NotificationActionReceiver.parseIssueTarget("https://api.github.com/repos/octocat/hello-world/issues/42", "Release"))
    }
}
