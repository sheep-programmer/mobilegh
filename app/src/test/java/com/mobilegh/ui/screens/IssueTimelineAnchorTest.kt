package com.mobilegh.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class IssueTimelineAnchorTest {
    @Test fun issueTimelineOffsetIncludesOnlyVisibleEnhancementCards() {
        assertEquals(4, threadTimelineOffset(isPull = false, hasHierarchy = false, hasRelated = false, hasClosingIssues = false))
        assertEquals(6, threadTimelineOffset(isPull = false, hasHierarchy = true, hasRelated = true, hasClosingIssues = false))
        assertEquals(7, threadTimelineOffset(isPull = false, hasHierarchy = true, hasRelated = true, hasDependencies = true, hasClosingIssues = false))
    }

    @Test fun pullRequestTimelineOffsetIncludesClosingCardOnlyWhenShown() {
        assertEquals(4, threadTimelineOffset(isPull = true, hasHierarchy = false, hasRelated = false, hasClosingIssues = false))
        assertEquals(5, threadTimelineOffset(isPull = true, hasHierarchy = false, hasRelated = false, hasClosingIssues = true))
        assertEquals(4, threadTimelineOffset(isPull = true, hasHierarchy = true, hasRelated = true, hasClosingIssues = false))
    }
}
