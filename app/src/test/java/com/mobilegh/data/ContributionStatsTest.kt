package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ContributionStatsTest {
    private val now = Instant.parse("2026-10-06T12:00:00Z")
    private val today = LocalDate.of(2026, 10, 6)
    private fun week(date: String, commits: Long? = 1, adds: Long? = 10, deletes: Long? = 2) =
        ContributionWeek(LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond(), commits, adds, deletes)
    private fun record(login: String = "octocat", total: Long? = 100, vararg weeks: ContributionWeek) =
        ContributionRecord(User(login = login), total, weeks.toList())
    private fun snapshot(vararg records: ContributionRecord) = ContributionSnapshot(records.toList(), loadedAt = now)
    private fun response(code: Int, body: String = "", headers: Headers = Headers.Builder().build()) = Resp(code, body, headers)

    @Test fun allHistoryUsesApiTotalAndLabelsShortWeeklyCoverage() {
        val result = aggregateContributions(snapshot(record(weeks = arrayOf(week("2026-09-27", 7), week("2026-10-04", 3)))), ContributionRange.All)
        assertEquals(100L, result.commits)
        assertEquals(10L, result.weekly.sumOf { it.commits!! })
        assertEquals(20L, result.additions)
        assertFalse(result.linesCoverAllCommits)
        assertEquals(LocalDate.parse("2026-09-27"), result.availableDates!!.start)
        assertEquals(today, result.availableDates.end)
    }

    @Test fun contributorWeeksAreNotArtificiallyLimitedToOneYear() {
        val starts = (0L..79L).map { LocalDate.parse("2025-01-05").plusWeeks(it) }
        val result = aggregateContributions(snapshot(record(total = 80, weeks = starts.map { week(it.toString()) }.toTypedArray())), ContributionRange.All)
        assertEquals(80, result.weekly.size)
        assertEquals(80L, result.commits)
        assertEquals(800L, result.additions)
        assertTrue(result.linesCoverAllCommits)
    }

    @Test fun recentRangeIncludesTheWholeOverlappingBoundaryWeek() {
        val data = snapshot(record(weeks = arrayOf(week("2026-08-30", 100), week("2026-09-06", 8), week("2026-09-13", 2))))
        val result = aggregateContributions(data, ContributionRange.ThirtyDays)
        assertEquals(LocalDate.parse("2026-09-07"), result.requestedDates!!.start)
        assertEquals(10L, result.commits)
        assertEquals(LocalDate.parse("2026-09-06"), result.includedDates!!.start)
        assertTrue(result.boundaryWeeksIncluded)
        assertTrue(result.partialCoverage)
    }

    @Test fun customEndDateIsInclusiveAndAdjacentWeekIsExcluded() {
        val data = snapshot(record(weeks = arrayOf(week("2026-09-06", 8), week("2026-09-13", 2), week("2026-09-20", 50))))
        val result = aggregateContributions(data, ContributionRange.Custom,
            ContributionDates(LocalDate.parse("2026-09-12"), LocalDate.parse("2026-09-13")))
        assertEquals(10L, result.commits)
        assertEquals(2, result.weekly.size)
        assertTrue(result.boundaryWeeksIncluded)
    }

    @Test fun aGapIsUnknownRatherThanAZeroCommitWeek() {
        val result = aggregateContributions(snapshot(record(weeks = arrayOf(week("2026-09-20"), week("2026-10-04")))), ContributionRange.ThirtyDays)
        assertEquals(3, result.weekly.size)
        assertNull(result.weekly[1].commits)
        assertNull(result.commits)
        assertNull(result.contributors.single().activeWeeks)
        assertTrue(result.partialCoverage)
    }

    @Test fun totalsAndSharesUseAllReturnedContributorsInsteadOfTheLeader() {
        val result = aggregateContributions(snapshot(record("alice", 60, week("2026-10-04", 1)), record("bob", 40, week("2026-10-04", 9))), ContributionRange.All)
        assertEquals(listOf("alice", "bob"), result.contributors.map { it.author!!.login })
        assertEquals(0.6, result.contributors[0].share!!, 0.00001)
        assertEquals(0.4, result.contributors[1].share!!, 0.00001)
        assertEquals(10L, result.weekly.single().commits)
    }

    @Test fun missingCumulativeTotalsAreNotInventedFromWeeklySums() {
        val result = aggregateContributions(snapshot(record(total = null, weeks = arrayOf(week("2026-10-04", 6)))), ContributionRange.All)
        assertNull(result.commits)
        assertNull(result.contributors.single().commits)
        assertNull(result.contributors.single().share)
        assertEquals(6L, result.weekly.single().commits)
    }

    @Test fun duplicateUsersAndBucketsDoNotDoubleCountAndDatesAreSorted() {
        val result = aggregateContributions(snapshot(
            record("octocat", 50, week("2026-10-04", 5), week("2026-09-27", 2)),
            record("Octocat", 100, week("2026-10-04", 9)),
        ), ContributionRange.All)
        assertEquals(1, result.contributors.size)
        assertEquals(100L, result.commits)
        assertEquals(listOf(2L, 9L), result.weekly.map { it.commits })
    }

    @Test fun unknownAuthorsAreRetainedAsDistinctContributors() {
        val result = aggregateContributions(snapshot(ContributionRecord(total = 5), ContributionRecord(total = 3)), ContributionRange.All)
        assertEquals(2, result.contributors.size)
        assertEquals(2, result.contributors.map { it.key }.distinct().size)
        assertEquals(8L, result.commits)
        assertNull(result.additions)
    }

    @Test fun invalidAndFutureWeekTimestampsAreIgnored() {
        val result = aggregateContributions(snapshot(record(weeks = arrayOf(
            week("2026-10-11", 90), ContributionWeek(0, 70), ContributionWeek(Long.MAX_VALUE, 80), week("2026-10-04", 2),
        ))), ContributionRange.All)
        assertEquals(1, result.weekly.size)
        assertEquals(2L, result.weekly.single().commits)
    }

    @Test fun sumsExceedingAnIntRemainAccurateAndLongOverflowIsUnknown() {
        val result = aggregateContributions(snapshot(record("alice", Int.MAX_VALUE.toLong(), week("2026-10-04", Int.MAX_VALUE.toLong())),
            record("bob", Int.MAX_VALUE.toLong(), week("2026-10-04", Int.MAX_VALUE.toLong()))), ContributionRange.All)
        assertEquals(4_294_967_294L, result.commits)
        assertEquals(4_294_967_294L, result.weekly.single().commits)
        val overflow = aggregateContributions(snapshot(record("alice", Long.MAX_VALUE), record("bob", 1)), ContributionRange.All)
        assertNull(overflow.commits)
        assertNull(overflow.contributors.first().share)
    }

    @Test fun missingOrNegativeMetricsStayUnknownAndExplicitZeroStaysZero() {
        val result = aggregateContributions(snapshot(record(weeks = arrayOf(week("2026-10-04", 0, null, -1)))), ContributionRange.All)
        assertNull(result.additions)
        assertNull(result.deletions)
        assertEquals(0L, result.weekly.single().commits)
        assertEquals(0, result.contributors.single().activeWeeks)
        val zero = aggregateContributions(snapshot(record(total = 1, weeks = arrayOf(week("2026-10-04", 1, 0, 0)))), ContributionRange.All)
        assertEquals(0L, zero.additions)
        assertEquals(0L, zero.deletions)
    }

    @Test fun largeRepositorySuppressedLineCountsAreNotShownAsRealZeros() {
        val result = aggregateContributions(snapshot(record(total = 10_000, weeks = arrayOf(week("2026-10-04", 30, 0, 0)))), ContributionRange.All)
        assertEquals(10_000L, result.commits)
        assertNull(result.additions)
        assertNull(result.deletions)
        assertNull(result.contributors.single().additions)
    }

    @Test fun rangesOutsideCoverageAndFallbackRangesAreUnavailableNotEmpty() {
        val dates = ContributionDates(LocalDate.parse("2020-01-01"), LocalDate.parse("2020-01-31"))
        val result = aggregateContributions(snapshot(record(weeks = arrayOf(week("2026-10-04")))), ContributionRange.Custom, dates)
        assertTrue(result.rangeUnavailable)
        assertNull(result.commits)
        val fallback = ContributionSnapshot(listOf(record()), source = ContributionSource.ContributorList, loadedAt = now)
        assertTrue(aggregateContributions(fallback, ContributionRange.Year).rangeUnavailable)
        assertEquals(100L, aggregateContributions(fallback, ContributionRange.All).commits)
    }

    @Test fun dateValidationRejectsInvalidLeapDatesReversedAndFutureRanges() {
        listOf("2026-02-29" to "2026-03-01", "2026-9-01" to "2026-10-01", "2026-10-04" to "2026-10-01",
            "2026-10-01" to "2026-10-07", "1969-12-31" to "2026-10-01", "" to "2026-10-01").forEach { (start, end) ->
            assertNotNull(validateContributionDates(start, end, today).error)
        }
        assertNotNull(validateContributionDates("2024-02-29", "2024-02-29", today).dates)
        assertNotNull(validateContributionDates(" 2026-10-06 ", "2026-10-06", today).dates)
    }

    @Test fun pollingBypassesCacheAndHonorsBoundedRetryAfter() = runBlocking {
        val forces = mutableListOf<Boolean>()
        val delays = mutableListOf<Long>()
        var attempt = 0
        val result = ContributionStats.load("owner", "repo", request = { path, force ->
            assertTrue(path.endsWith("/stats/contributors"))
            forces += force
            if (attempt++ < 2) response(202, headers = Headers.Builder().add("Retry-After", "999").build())
            else response(200, """[{"author":null,"total":6,"weeks":[{"w":1791072000,"c":2}]}]""")
        }, pause = { delays += it })
        assertEquals(listOf(false, true, true), forces)
        assertEquals(listOf(8000L, 8000L), delays)
        assertEquals(ContributionStatus.Ready, result.status)
        assertEquals(6L, result.records.single().total)
        assertNull(result.records.single().weeks.single().additions)
    }

    @Test fun exhausted202ReturnsPendingWithFallbackCountsAndNoFinalSleep() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        val result = ContributionStats.load("owner", "repo", request = { path, _ ->
            calls++
            if (path.endsWith("/stats/contributors")) response(202)
            else response(200, """[{"login":"octocat","id":1,"contributions":40}]""")
        }, pause = { delays += it })
        assertEquals(5, calls)
        assertEquals(listOf(1000L, 2000L, 4000L), delays)
        assertEquals(ContributionStatus.Pending, result.status)
        assertEquals(ContributionSource.ContributorList, result.source)
        assertEquals(40L, result.records.single().total)
    }

    @Test fun parentStyle202ExceptionAlsoPreservesPending() = runBlocking {
        val result = ContributionStats.load("owner", "repo", request = { path, _ ->
            if (path.endsWith("/stats/contributors")) throw ApiException(202, "Still calculating")
            response(200, "[]")
        }, pause = { fail("No polling delay expected") })
        assertEquals(ContributionStatus.Pending, result.status)
        assertTrue(result.records.isEmpty())
    }

    @Test fun empty204DoesNotFallBackAndIsDistinctFromPending() = runBlocking {
        var calls = 0
        val result = ContributionStats.load("owner", "repo", request = { _, _ -> calls++; response(204) }, pause = { fail("Unexpected delay") })
        assertEquals(1, calls)
        assertEquals(ContributionStatus.Empty, result.status)
    }

    @Test fun non200SuccessfulHttpCodeDoesNotBecomeAnEmptyRepository() = runBlocking {
        val result = ContributionStats.load("owner", "repo", request = { path, _ ->
            if (path.endsWith("/stats/contributors")) response(201, "[]") else response(200, "[]")
        }, pause = { fail("Unexpected delay") })
        assertEquals(ContributionStatus.Unavailable, result.status)
    }

    @Test fun fallbackPaginationIncludesAnonymousAuthorsAndMarksTruncation() = runBlocking {
        val paths = mutableListOf<String>()
        val result = ContributionStats.load("owner", "repo", request = { path, _ ->
            paths += path
            if (path.endsWith("/stats/contributors")) response(422, """{"message":"Statistics unavailable"}""")
            else response(200, """[{"name":"Anonymous","type":"Anonymous","contributions":8}]""",
                Headers.Builder().add("Link", "<https://api.github.com/next>; rel=\"next\"").build())
        }, pause = { fail("Unexpected delay") })
        assertEquals(6, paths.size)
        assertTrue(paths.drop(1).all { it.contains("anon=1&per_page=100") })
        assertTrue(result.truncated)
        assertEquals(ContributionStatus.Unavailable, result.status)
        assertEquals(5, result.records.size)
        assertTrue(result.records.all { it.author!!.login.isBlank() })
    }

    @Test fun partialFallbackFailureRetainsEarlierSuccessAndError() = runBlocking {
        val result = ContributionStats.load("owner", "repo", request = { path, _ ->
            when {
                path.endsWith("/stats/contributors") -> response(500, "{}")
                path.endsWith("page=1") -> response(200, """[{"login":"alice","contributions":20}]""",
                    Headers.Builder().add("Link", "<https://api.github.com/next>; rel=\"next\"").build())
                else -> response(503, """{"message":"Try later"}""")
            }
        }, pause = { fail("Unexpected delay") })
        assertEquals(20L, result.records.single().total)
        assertTrue(result.truncated)
        assertTrue(result.message!!.contains("Try later"))
    }

    @Test fun authenticationAndRateLimitFailuresDoNotTryAnotherEndpoint() = runBlocking {
        for (code in listOf(401, 403, 429)) {
            var calls = 0
            try {
                ContributionStats.load("owner", "repo", request = { _, _ -> calls++; response(code, "{}") })
                fail("Expected API failure")
            } catch (error: ApiException) {
                assertEquals(code, error.code)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun cancellationStopsPollingWithoutLoadingFallback() = runBlocking {
        var calls = 0
        try {
            ContributionStats.load("owner", "repo", request = { _, _ -> calls++; response(202) }, pause = { throw CancellationException("screen left") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun pendingWithoutFallbackIsUnknownRatherThanZeroCommits() {
        val result = aggregateContributions(ContributionSnapshot(status = ContributionStatus.Pending, loadedAt = now), ContributionRange.All)
        assertNull(result.commits)
        assertNull(result.additions)
    }

    @Test fun homepagePreviewFetchesOnlyThreeCumulativeCountsWithoutHeavyStatistics() = runBlocking {
        var calls = 0
        val result = ContributionStats.preview("owner", "repo", force = true, request = { path, force ->
            calls++
            assertEquals("/repos/owner/repo/contributors?anon=1&per_page=3&page=1", path)
            assertTrue(force)
            response(200, """[{"login":"alice","contributions":100},{"login":"bob","contributions":60},{"name":"Anonymous","contributions":40}]""")
        })
        assertEquals(1, calls)
        assertEquals(listOf(100L, 60L, 40L), result.records.map { it.total })
        assertTrue(result.records.all { it.weeks.isEmpty() })
        assertEquals(ContributionSource.ContributorList, result.source)
    }

    @Test fun emptyHomepagePreviewHasNoStatisticsOrFallbackRequests() = runBlocking {
        var calls = 0
        val result = ContributionStats.preview("owner", "repo", request = { _, _ -> calls++; response(204) })
        assertEquals(1, calls)
        assertEquals(ContributionStatus.Empty, result.status)
    }
}
