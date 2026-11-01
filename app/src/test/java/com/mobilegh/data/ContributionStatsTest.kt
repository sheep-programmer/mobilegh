package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
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
    /** 统计接口的流式读取，用同一套假响应：200 解析、204 为空、202 抛出，其余按 Api 的规则判错。 */
    private fun streamVia(request: suspend (String, Boolean) -> Resp): StatsStreamer = { path, force, parse ->
        val r = request(path, force)
        when (r.code) {
            204 -> null
            202 -> throw ApiException(202, "computing")
            else -> parse(ByteArrayInputStream(Api.ensureOk(r).body.toByteArray()))
        }
    }

    private suspend fun loadStats(
        owner: String,
        name: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp,
        cacheDir: File? = null,
    ): ContributionSnapshot = ContributionStats.load(owner, name, force = force, request = request,
        cacheDir = cacheDir, streamStats = streamVia(request))


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

    @Test fun smallRepositoryUsesWeeklyStatisticsAndNoListPaging() = runBlocking {
        val paths = mutableListOf<String>()
        val result = loadStats("owner", "repo", request = { path, _ ->
            paths += path
            if (path.contains("/stats/contributors")) {
                response(200, """[{"author":{"login":"alice"},"total":20,"weeks":[{"w":1791072000,"c":2}]}]""")
            } else response(200, """[{"login":"alice","id":1,"contributions":20}]""")
        })
        assertEquals(2, paths.size)
        assertTrue(paths.none { it.contains("per_page=100") })
        assertEquals(ContributionSource.Statistics, result.source)
        assertEquals(ContributionStatus.Ready, result.status)
        assertEquals(20L, result.records.single().total)
        assertTrue(result.records.single().weeks.isNotEmpty())
        assertFalse(result.truncated)
        assertTrue(result.personDetails)
    }

    @Test fun statisticsStillPendingFallsBackToCommitListWithMessage() = runBlocking {
        val paths = mutableListOf<String>()
        val result = loadStats("owner", "repo", request = { path, _ ->
            paths += path
            when {
                path.contains("/stats/contributors") -> response(202)
                else -> response(200, """[{"login":"alice","id":1,"contributions":20}]""")
            }
        })
        // 探测 + 4 次统计（含重试）+ 第 1 页列表
        assertEquals(6, paths.size)
        assertEquals(ContributionSource.ContributorList, result.source)
        assertEquals(ContributionStatus.Pending, result.status)
        assertEquals(20L, result.records.single().total)
        assertNotNull(result.message)
    }
    @Test fun longListIsPagedAndNeverLoadsStatistics() = runBlocking {
        val paths = mutableListOf<String>()
        val result = loadStats("owner", "repo", request = { path, _ ->
            paths += path
            response(200, """[{"login":"octocat","id":1,"contributions":900}]""",
                Headers.Builder().add("Link", "<https://api.github.com/x?anon=1&per_page=1&page=2>; rel=\"next\", <https://api.github.com/x?anon=1&per_page=1&page=321>; rel=\"last\"").build())
        })
        assertTrue(paths.none { it.endsWith("/stats/contributors") })
        assertEquals(ContributionSource.ContributorList, result.source)
        assertEquals(ContributionStatus.Ready, result.status)
        assertEquals(900L, result.records.single().total)
        assertTrue(result.truncated)
        assertEquals(2, result.nextPage)
        assertNotNull(result.message)
        assertTrue(result.personDetails)
    }
    @Test fun tooLargeListFallsBackToOneStatisticsRequestRankedByCommitsWithoutWeeks() = runBlocking {
        val paths = mutableListOf<String>()
        val result = loadStats("owner", "repo", cacheDir = null, request = { path, _ ->
            paths += path
            if (path.contains("/contributors?")) response(403, """{"message":"The history or contributor list is too large"}""")
            else response(200, """[
                {"author":{"login":"small"},"total":5,"weeks":[{"w":1791072000,"a":1,"d":1,"c":5}]},
                {"author":{"login":"leader"},"total":9,"weeks":[{"w":1791072000,"a":2,"d":2,"c":9}]}
            ]""")
        })
        assertEquals(2, paths.size)
        assertEquals(1, paths.count { it.endsWith("/stats/contributors") })
        assertEquals(ContributionSource.ContributorList, result.source)
        assertEquals(ContributionStatus.Ready, result.status)
        assertEquals(listOf("leader", "small"), result.records.map { it.author!!.login })
        assertTrue(result.records.all { it.weeks.isEmpty() })
        assertTrue(result.message!!.contains("仓库过大"))
        assertFalse(result.personDetails)
    }

    @Test fun topRankingIsCachedForAnHourUnlessForced() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("rank-cache").toFile()
        var statsCalls = 0
        var listCalls = 0
        val request: suspend (String, Boolean) -> Resp = { path, _ ->
            if (path.contains("/contributors?")) { listCalls++; response(403, """{"message":"too large"}""") } else {
                statsCalls++
                response(200, """[{"author":{"login":"leader"},"total":9,"weeks":[]}]""")
            }
        }
        loadStats("owner", "bigrepo", request = request, cacheDir = dir)
        assertEquals(1, statsCalls)
        assertEquals(1, listCalls)
        val cached = loadStats("owner", "bigrepo", request = request, cacheDir = dir)
        // 命中缓存时既不读统计接口，也不再请求会返回 403 的 /contributors
        assertEquals(1, statsCalls)
        assertEquals(1, listCalls)
        assertEquals("leader", cached.records.single().author!!.login)
        loadStats("owner", "bigrepo", force = true, request = request, cacheDir = dir)
        assertEquals(2, statsCalls)
    }

    @Test fun tooLargeListWithStatisticsStillPendingReturnsPendingWithoutPolling() = runBlocking {
        var calls = 0
        val result = loadStats("owner", "repo", cacheDir = null, request = { path, _ ->
            calls++
            if (path.contains("/contributors?")) response(403, """{"message":"too large"}""") else response(202)
        })
        assertEquals(2, calls)
        assertEquals(ContributionStatus.Pending, result.status)
        assertTrue(result.records.isEmpty())
    }

    @Test fun tooLargeListWithEmptyStatisticsIsEmptyNotAnError() = runBlocking {
        val result = loadStats("owner", "repo", cacheDir = null, request = { path, _ ->
            if (path.contains("/contributors?")) response(403, """{"message":"too large"}""") else response(204)
        })
        assertEquals(ContributionStatus.Empty, result.status)
    }

    @Test fun emptyAndPendingContributorListsAreReportedWithoutFallback() = runBlocking {
        var calls = 0
        val empty = loadStats("owner", "repo", cacheDir = null, request = { _, _ -> calls++; response(204) })
        assertEquals(ContributionStatus.Empty, empty.status)
        val pending = loadStats("owner", "repo", cacheDir = null, request = { _, _ -> calls++; response(202) })
        assertEquals(ContributionStatus.Pending, pending.status)
        assertEquals(2, calls)
    }

    @Test fun unexpectedSuccessCodeIsAnErrorNotAnEmptyRepository() = runBlocking {
        val failed = runCatching {
            loadStats("owner", "repo", cacheDir = null, request = { _, _ -> response(201, "[]") })
        }
        assertTrue(failed.isFailure)
    }

    @Test fun authenticationAndRateLimitFailuresAreRaisedOnTheFirstRequest() = runBlocking {
        for (code in listOf(401, 429)) {
            val paths = mutableListOf<String>()
            try {
                loadStats("owner", "repo", cacheDir = null, request = { path, _ -> paths += path; response(code, "{}") })
                fail("Expected API failure")
            } catch (error: ApiException) {
                assertEquals(code, error.code)
            }
            assertEquals(1, paths.size)
        }
    }

    @Test fun laterPageFailureIsReportedWhenTheScreenRequestsIt() = runBlocking {
        val result = loadStats("owner", "repo", request = { path, _ ->
            if (path.contains("/stats/contributors")) response(500, "{}")
            else response(200, """[{"login":"alice","contributions":20}]""",
                Headers.Builder().add("Link", "<https://api.github.com/next>; rel=\"next\"").build())
        })
        assertEquals(20L, result.records.single().total)
        assertTrue(result.truncated)
        assertEquals(2, result.nextPage)
        val later = runCatching {
            ContributionStats.contributorPage("owner", "repo", 2, request = { _, _ -> response(503, "{}") })
        }
        assertTrue(later.isFailure)
    }
    @Test fun anonymousAuthorsAreIncludedInTheFirstPage() = runBlocking {
        val result = loadStats("owner", "repo", request = { path, _ ->
            if (path.contains("/stats/contributors")) response(500, "{}")
            else response(200, """[{"name":"Anonymous","type":"Anonymous","contributions":8}]""")
        })
        assertEquals(1, result.records.size)
        assertTrue(result.records.all { it.author!!.login.isBlank() })
    }
    @Test fun personActivityBucketsRecentCommitsByUtcSundayWeek() = runBlocking {
        val commits = """[
            {"commit":{"author":{"date":"2026-10-05T10:00:00Z"}}},
            {"commit":{"author":{"date":"2026-10-04T00:00:01Z"}}},
            {"commit":{"author":{"date":"2026-09-27T23:59:59Z"}}}
        ]"""
        val activity = ContributionStats.personActivity("owner", "repo", "octocat",
            request = { path, _ -> assertTrue(path.contains("author=octocat")); response(200, commits) }, now = now)
        assertEquals(52, activity.weekly.size)
        assertFalse(activity.truncated)
        assertEquals(LocalDate.parse("2026-10-04"), activity.weekly.last().start)
        assertEquals(2L, activity.weekly.last().commits)
        assertEquals(1L, activity.weekly[activity.weekly.size - 2].commits)
        assertEquals(0L, activity.weekly.first().commits)
    }

    @Test fun authorCommitCountComesFromLastPageOfOneItemPages() = runBlocking {
        val last = Headers.Builder().add("Link", "<https://api.github.com/x?per_page=1&page=2>; rel=\"next\", <https://api.github.com/x?per_page=1&page=42>; rel=\"last\"").build()
        assertEquals(42L, ContributionStats.authorCommits("owner", "repo", "octocat", request = { _, _ -> response(200, "[{}]", last) }))
        assertEquals(0L, ContributionStats.authorCommits("owner", "repo", "nobody", request = { _, _ -> response(200, "[]") }))
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
