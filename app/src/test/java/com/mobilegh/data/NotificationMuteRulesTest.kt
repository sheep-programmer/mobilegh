package com.mobilegh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationMuteRulesTest {
    private fun notif(title: String, fullName: String = "octocat/repo", owner: String = "octocat") = Notification(
        id = "1",
        subject = Subject(title = title, type = "PullRequest"),
        repository = Repo(name = fullName.substringAfter('/'), fullName = fullName, owner = User(login = owner)),
    )

    @Test fun mutesBotRepoAndKeywordRules() {
        val rules = NotificationMuteConfig(
            muteBots = true,
            mutedRepos = setOf("noisy/monorepo"),
            mutedKeywords = listOf("chore(deps)"),
        )
        assertTrue(NotificationMuteRules.isMuted(notif("Bump okio from 3.8 to 3.9 by dependabot[bot]"), rules))
        assertTrue(NotificationMuteRules.isMuted(notif("Update lockfile", fullName = "noisy/monorepo"), rules))
        assertTrue(NotificationMuteRules.isMuted(notif("chore(deps): update gradle"), rules))
        assertFalse(NotificationMuteRules.isMuted(notif("Fix SVG rendering crash"), rules))
    }

    @Test fun inactiveRulesNeverMute() {
        assertFalse(NotificationMuteRules.isMuted(notif("dependabot[bot] update"), NotificationMuteConfig()))
    }

    @Test fun issueAndPrRemindersDefaultOffAndRespectMuteRules() {
        val issue = notif("Bug report").copy(subject = Subject(title = "Bug report", type = "Issue"))
        val pr = notif("Add feature")
        val release = notif("v1.0.0").copy(subject = Subject(title = "v1.0.0", type = "Release"))

        val defaults = NotificationMuteConfig()
        assertFalse(NotificationMuteRules.shouldRemind(issue, defaults))
        assertFalse(NotificationMuteRules.shouldRemind(pr, defaults))
        assertTrue(NotificationMuteRules.shouldRemind(release, defaults))

        val enabled = NotificationMuteConfig(notifyIssues = true, notifyPrs = true, mutedRepos = setOf("noisy/repo"))
        assertTrue(NotificationMuteRules.shouldRemind(issue, enabled))
        assertTrue(NotificationMuteRules.shouldRemind(pr, enabled))
        assertFalse(NotificationMuteRules.shouldRemind(notif("Noisy", fullName = "noisy/repo"), enabled))
    }
}
