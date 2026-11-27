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
}
