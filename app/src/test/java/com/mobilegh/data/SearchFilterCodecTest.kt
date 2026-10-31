package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterCodecTest {
    @Test fun structuredFiltersSurviveAnEncodeDecodeRoundTrip() {
        val original = SearchFilters(
            repoOwner = "JetBrains", repoLanguage = "Kotlin", minStars = 100, minForks = 10,
            topic = "android", archived = 2, userType = 2, location = "China", minFollowers = 50,
            userLanguage = "Rust", state = 1, author = "octocat", labels = "bug,help wanted",
            minComments = 5, issueRepo = "octo/hello", issueOwner = "octo", issueLanguage = "Go",
            updatedDays = 30, codeRepo = "octo/hello", codeOwner = "", codeLanguage = "Kotlin",
            path = "src/main", filename = "build.gradle",
        )
        assertEquals(original, SearchFilterCodec.decode(SearchFilterCodec.encode(original)))
    }

    @Test fun restoredFiltersBuildTheSameSearchQuery() {
        val original = SearchFilters(repoLanguage = "Kotlin", minStars = 10, topic = "compose")
        val restored = SearchFilterCodec.decode(SearchFilterCodec.encode(original))
        assertEquals(
            SearchQuery.build(SearchType.REPO, "ui", original, includeType = false),
            SearchQuery.build(SearchType.REPO, "ui", restored!!, includeType = false),
        )
    }

    @Test fun missingAndUnknownFieldsFallBackToDefaults() {
        assertEquals(SearchFilters(), SearchFilterCodec.decode("{}"))
        assertEquals(SearchFilters(minStars = 5), SearchFilterCodec.decode("""{"minStars":5,"fromNewerVersion":true}"""))
    }

    @Test fun absentOrBrokenStoredValuesDecodeToNull() {
        assertNull(SearchFilterCodec.decode(null))
        assertNull(SearchFilterCodec.decode(""))
        assertNull(SearchFilterCodec.decode("   "))
        assertNull(SearchFilterCodec.decode("not json"))
    }

    @Test fun issueSortDefaultsMatchTheOrderTheScreenUsedBefore() {
        assertEquals("created", IssueSearchSort.keys(pulls = false).first())
        assertEquals("updated", IssueSearchSort.keys(pulls = true).first())
    }

    @Test fun everyIssueSortOptionIsASortKeyGitHubSupports() {
        val supported = setOf("created", "updated", "comments", "reactions", "interactions")
        assertTrue(IssueSearchSort.keys(pulls = false).all { it in supported })
        assertTrue(IssueSearchSort.keys(pulls = true).all { it in supported })
    }
}
