package com.mobilegh.ui.screens

import com.mobilegh.data.Notification
import com.mobilegh.data.Repo
import com.mobilegh.data.Subject
import com.mobilegh.data.User
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationFiltersTest {
    @Test fun documentedReasonsAreSelectableWithoutLoadingAMatchingThread() {
        val missingReasons = setOf(
            "manual", "approval_requested", "agent_session_finished", "member_feature_requested", "security_advisory_credit",
        )

        val options = notificationReasonOptions(emptyList(), emptySet())

        assertEquals(emptySet<String>(), missingReasons - options.map { it.id }.toSet())
    }

    @Test fun reasonOptionsIncludeObservedAndSelectedReasonsWithoutDuplicates() {
        val options = notificationReasonOptions(
            listOf(thread("1", reason = "manual"), thread("2", reason = "future_reason"), thread("3", reason = "")),
            selected = setOf("future_reason", "no_longer_loaded"),
        )

        assertEquals(1, options.count { it.id == "manual" })
        assertEquals(listOf("future_reason", "no_longer_loaded"), options.takeLast(2).map { it.label })
    }

    @Test fun organizationOptionsUseTypedOwnersAndKeepSelectedOrganizationsAvailable() {
        val options = notificationOrganizationOptions(
            listOf(
                thread("1", login = "Octo", ownerType = "Organization"),
                thread("2", login = "octo", ownerType = "Organization"),
                thread("3", login = "Personal", ownerType = "User"),
                thread("4", login = "", ownerType = "Organization"),
            ),
            selected = setOf("previous-org"),
        )

        assertEquals(listOf("octo" to "Octo", "previous-org" to "previous-org"), options.map { it.id to it.label })
    }

    @Test fun organizationsMatchAcrossRepositoriesAndCombineWithExistingFilters() {
        val notifications = listOf(
            thread("1", "Octo", "Organization", repo = "one"),
            thread("2", "octo", "Organization", repo = "two"),
            thread("3", "Partner", "Organization", repo = "two"),
            thread("4", "Octo", "User", repo = "one"),
            thread("5", "Octo", "Organization", repo = "one", reason = "subscribed"),
            thread("6", "Octo", "Organization", repo = "one", type = "PullRequest"),
        )
        val orgs = setOf("octo", "partner")

        assertEquals(listOf("1", "2", "3", "5", "6"), notifications.filter {
            matchNotifFilters(it, emptySet(), emptySet(), emptySet(), orgs)
        }.map { it.id })
        assertEquals(listOf("1"), notifications.filter {
            matchNotifFilters(it, setOf("Issue"), setOf("manual"), setOf("Octo/one"), orgs)
        }.map { it.id })
    }

    private fun thread(
        id: String,
        login: String = "example",
        ownerType: String = "User",
        repo: String = "repo",
        reason: String = "manual",
        type: String = "Issue",
    ) = Notification(
        id = id,
        reason = reason,
        subject = Subject(type = type),
        repository = Repo(fullName = "$login/$repo", owner = User(login = login, type = ownerType)),
    )
}
