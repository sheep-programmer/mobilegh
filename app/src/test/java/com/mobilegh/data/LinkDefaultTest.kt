package com.mobilegh.data

import com.mobilegh.data.LinkDefault.Status
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDefaultTest {
    @Test fun unaskedAndSkippedAreAskedAgainUntilChosen() {
        assertTrue(LinkDefault.askDecision(Status.Unasked, alreadySet = false))
        // 跳过：下次启动再问
        assertTrue(LinkDefault.askDecision(Status.Skipped, alreadySet = false))
    }

    @Test fun acceptOrDeclineStopsAsking() {
        assertFalse(LinkDefault.askDecision(Status.Accepted, alreadySet = false))
        // 拒绝：之后不再问（设置里仍可手动设置）
        assertFalse(LinkDefault.askDecision(Status.Declined, alreadySet = false))
    }

    @Test fun alreadyConfiguredNeverAsks() {
        Status.entries.forEach { assertFalse(it.name, LinkDefault.askDecision(it, alreadySet = true)) }
    }
}

class LinkDefaultConfiguredTest {
    private val hosts = listOf("github.com", "www.github.com", "gist.github.com")

    @Test fun selectedDomainCountsAsConfigured() {
        assertTrue(LinkDefault.configured(true, mapOf("github.com" to 1), hosts, 1))
        assertTrue(LinkDefault.configured(true, mapOf("gist.github.com" to 1), hosts, 1))
        assertTrue(LinkDefault.configured(true, mapOf("github.com" to 2), hosts, 1))
    }

    @Test fun noneDomainOrBlockedLinkHandlingIsNotConfigured() {
        assertFalse(LinkDefault.configured(true, mapOf("github.com" to 0), hosts, 1))
        assertFalse(LinkDefault.configured(true, emptyMap(), hosts, 1))
        assertFalse(LinkDefault.configured(false, mapOf("github.com" to 1), hosts, 1))
    }

    @Test fun unrelatedHostsDoNotCount() {
        assertFalse(LinkDefault.configured(true, mapOf("example.com" to 1), hosts, 1))
    }
}
