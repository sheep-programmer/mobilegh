package com.mobilegh.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributorSearchTest {
    private val nextLink = "<https://api.github.com/repositories/1/contributors?anon=1&per_page=100&page=2>; rel=\"next\", " +
        "<https://api.github.com/repositories/1/contributors?anon=1&per_page=100&page=9>; rel=\"last\""
    private val noNextLink = "<https://api.github.com/repositories/1/contributors?anon=1&per_page=100&page=1>; rel=\"prev\", " +
        "<https://api.github.com/repositories/1/contributors?anon=1&per_page=100&page=1>; rel=\"first\""

    private fun rec(login: String) = ContributionRecord(User(login = login), total = 1)
    private fun page(vararg logins: String, hasNext: Boolean) = ContributorPage(logins.map { rec(it) }, hasNext)

    @Test fun queryIsTrimmedAndLowercased() {
        assertEquals("mona lisa", normalizeContributorQuery("  Mona Lisa "))
        assertEquals("", normalizeContributorQuery("   "))
    }

    @Test fun matchesLoginOrDisplayNameCaseInsensitivelyAsSubstring() {
        val author = User(login = "Octo-Cat", name = "Mona Lisa")
        assertTrue(contributorMatches(author, "octo"))
        assertTrue(contributorMatches(author, "-cat"))
        assertTrue(contributorMatches(author, "mona lisa"))
        assertTrue(contributorMatches(author, "isa"))
        assertFalse(contributorMatches(author, "hubot"))
    }

    @Test fun emptyNeedleMatchesEveryoneAndAnonymousAuthorsOnlyByName() {
        assertTrue(contributorMatches(null, ""))
        assertFalse(contributorMatches(null, "octo"))
        assertFalse(contributorMatches(User(login = "", name = null), "x"))
        assertTrue(contributorMatches(User(login = "", name = "Anonymous Dev"), "dev"))
    }

    @Test fun nextPageComesFromTheLinkHeaderWhenPresent() {
        assertTrue(hasNextContributorPage(nextLink, pageRecordCount = 3))
        // A full page without rel="next" is the last page.
        assertFalse(hasNextContributorPage(noNextLink, pageRecordCount = 100))
        assertFalse(hasNextContributorPage(nextLink, pageRecordCount = 0))
    }

    @Test fun withoutALinkHeaderAFullPageMeansMore() {
        assertTrue(hasNextContributorPage(null, pageRecordCount = 100))
        assertFalse(hasNextContributorPage(null, pageRecordCount = 99))
        assertTrue(hasNextContributorPage(null, pageRecordCount = 5, pageSize = 5))
    }

    @Test fun onlyOneRequestRunsAndAFailureKeepsTheCursorForRetry() {
        val inFlight = ContributorListState(nextPage = 2).beginPage()!!
        assertTrue(inFlight.busy)
        assertNull(inFlight.beginPage())

        val failed = inFlight.failPage("boom")
        assertFalse(failed.busy)
        assertEquals(2, failed.nextPage)
        assertNull("a failed page is not retried automatically", failed.beginPage())

        val resumed = failed.resume()
        assertEquals(2, resumed.beginPage()!!.nextPage)
    }

    @Test fun finishingPagesAppendRecordsAndStopAfterTheLastPage() {
        val afterFirst = ContributorListState(records = listOf(rec("a")), nextPage = 2)
            .beginPage()!!.finishPage(page("b", "c", hasNext = true))
        assertEquals(listOf("a", "b", "c"), afterFirst.records.map { it.author?.login })
        assertEquals(3, afterFirst.nextPage)
        assertFalse(afterFirst.busy)

        val last = afterFirst.beginPage()!!.finishPage(page("d", hasNext = false))
        assertEquals(4, last.records.size)
        assertNull(last.nextPage)
        assertNull(last.beginPage())
    }

    @Test fun abandoningACancelledRequestClearsBusyWithoutAnError() {
        val abandoned = ContributorListState(nextPage = 2).beginPage()!!.abandonPage()
        assertFalse(abandoned.busy)
        assertNull(abandoned.error)
        assertEquals(2, abandoned.nextPage)
    }

    @Test fun scanPhaseFollowsQueryStopAndCompletion() {
        val list = ContributorListState(nextPage = 2)
        assertEquals(ContributorScanPhase.Off, list.scanPhase)

        val searching = list.withQuery("  Octo ")
        assertEquals("octo", searching.query)
        assertEquals(ContributorScanPhase.Scanning, searching.scanPhase)

        val stopped = searching.stopScan()
        assertEquals(ContributorScanPhase.Stopped, stopped.scanPhase)
        // The same query, differently typed, keeps the stop; a new query restarts the scan.
        assertEquals(ContributorScanPhase.Stopped, stopped.withQuery(" OCTO").scanPhase)
        assertEquals(ContributorScanPhase.Scanning, stopped.withQuery("octocat").scanPhase)
        assertEquals(ContributorScanPhase.Scanning, stopped.resume().scanPhase)

        assertEquals(ContributorScanPhase.Failed, searching.beginPage()!!.failPage("x").scanPhase)
        assertEquals(ContributorScanPhase.Complete, ContributorListState(query = "x").scanPhase)
        assertFalse(ContributorListState(query = "x").stopScan().stopped)
        assertEquals(ContributorScanPhase.Off, searching.withQuery("").scanPhase)
    }

    @Test fun scanFetchesPagesOneAtATimeAndCompletesAtTheLastPage() = runBlocking {
        var inFlight = 0
        var maxInFlight = 0
        val requested = mutableListOf<Int>()
        val pager = ContributorPager(this, ContributorListState(records = listOf(rec("first")), nextPage = 2), { p ->
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            requested += p
            yield()
            inFlight--
            ContributorPage(listOf(rec("user$p")), hasNext = p < 4)
        }, pauseMillis = 0L)

        pager.setQuery("user")
        withTimeout(5_000) { pager.state.first { it.scanPhase == ContributorScanPhase.Complete } }
        assertEquals(listOf(2, 3, 4), requested)
        assertEquals(1, maxInFlight)
        assertEquals(4, pager.state.value.records.size)
        assertNull(pager.state.value.nextPage)
        pager.close()
    }

    @Test fun failedPageKeepsMatchesAndRetryResumesFromThatPage() = runBlocking {
        var failuresLeft = 1
        val requested = mutableListOf<Int>()
        val pager = ContributorPager(this, ContributorListState(nextPage = 2), { p ->
            requested += p
            if (p == 3 && failuresLeft-- > 0) throw ApiException(500, "boom")
            ContributorPage(listOf(rec("user$p")), hasNext = p < 4)
        }, pauseMillis = 0L)

        pager.setQuery("user")
        withTimeout(5_000) { pager.state.first { it.scanPhase == ContributorScanPhase.Failed } }
        assertEquals(1, pager.state.value.records.size)
        assertEquals(3, pager.state.value.nextPage)
        assertTrue(pager.state.value.error!!.contains("第 3 页"))

        pager.resume()
        withTimeout(5_000) { pager.state.first { it.scanPhase == ContributorScanPhase.Complete } }
        assertEquals(listOf(2, 3, 3, 4), requested)
        assertEquals(3, pager.state.value.records.size)
        pager.close()
    }

    @Test fun stoppingKeepsLoadedPagesAndResumingContinuesFromTheCursor() = runBlocking {
        val requested = mutableListOf<Int>()
        val pager = ContributorPager(this, ContributorListState(nextPage = 2), { p ->
            requested += p
            ContributorPage(listOf(rec("user$p")), hasNext = true)
        }, pauseMillis = 60_000L)

        pager.setQuery("user")
        withTimeout(5_000) { pager.state.first { it.records.size == 1 } }
        pager.stopScan()
        delay(20)
        assertEquals(ContributorScanPhase.Stopped, pager.state.value.scanPhase)
        assertEquals(listOf(2), requested)
        assertEquals(3, pager.state.value.nextPage)

        pager.resume()
        withTimeout(5_000) { pager.state.first { it.scanPhase == ContributorScanPhase.Scanning && it.records.size == 2 } }
        assertEquals(listOf(2, 3), requested)
        pager.close()
    }
}
