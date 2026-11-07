package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvolvedFilterTest {
    @Test fun relatedIssuesUseTheInvolvesQualifierAndSearch() {
        assertEquals("is:issue state:open involves:@me archived:false", IssueQuery(involves = Involvement.Involved).searchQuery())
        assertEquals("is:pr state:open involves:@me archived:false", IssueQuery(pulls = true, involves = Involvement.Involved).searchQuery())
        assertTrue(IssueQuery(involves = Involvement.Involved).needsSearch)
    }

    @Test fun relatedSitsBetweenMentionedAndReviewRequested() {
        assertEquals(
            listOf(Involvement.Created, Involvement.Assigned, Involvement.Mentioned, Involvement.Involved, Involvement.ReviewRequested),
            Involvement.entries,
        )
    }
}
