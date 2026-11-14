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
    private suspend fun loadStats(
        owner: String,
        name: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp,
    ): ContributionSnapshot = ContributionStats.load(owner, name, force = force, request = request, pause = {})

    private fun commitsJson(vararg logins: String?) = "[" + logins.joinToString(",") {
        if (it == null) """{"author":null}""" else """{"author":{"login":"$it"}}"""
    } + "]"

    private fun tooLarge(path: String) = path.contains("/contributors?") && !path.contains("/stats/")

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
    }
    @Test fun tooLargeListReadsOneBatchOfRecentCommitsAndNeverStatistics() = runBlocking {
        val paths = mutableListOf<String>()
        // 100 条提交来自 40 位作者：一页就够 30 位，不再多读
        val page = (0 until 100).map { "Dev${it % 40}" }.toTypedArray()
        val result = loadStats("owner", "linux", request = { path, _ ->
            paths += path
            if (tooLarge(path)) response(403, """{"message":"The history or contributor list is too large to list contributors for this repository via the API."}""")
            else response(200, commitsJson(*page))
        })
        // 探测一次 + 最近提交的第 1 页；绝不读统计接口，也不预取后面的页
        assertEquals(2, paths.size)
        assertTrue(paths.none { it.contains("/stats/") })
        assertTrue(paths.last().contains("/commits?per_page=100&page=1"))
        assertEquals(ContributionStatus.Ready, result.status)
        assertTrue(result.commitPaged)
        assertTrue(result.truncated)
        assertEquals(2, result.nextPage)
        assertEquals(40, result.records.size)
        // 保持出现顺序，不按次数重排
        assertEquals("Dev0", result.records.first().author!!.login)
        assertEquals(3L, result.records.first().total)
    }

    @Test fun fewAuthorsMakeABatchReadMorePagesUpToALimit() = runBlocking {
        val paths = mutableListOf<String>()
        val result = loadStats("owner", "linux", request = { path, _ ->
            paths += path
            if (tooLarge(path)) response(403, """{"message":"too large"}""")
            else response(200, commitsJson(*Array(100) { if (it < 70) "Alice" else "bob" }))
        })
        // 探测 + 最多 3 页提交，游标跳到第 4 页
        assertEquals(1 + 3, paths.size)
        assertEquals(4, result.nextPage)
        assertEquals(listOf("Alice", "bob"), result.records.map { it.author!!.login })
        assertEquals(listOf(210L, 90L), result.records.map { it.total })
    }

    @Test fun authorsAlreadyListedDoNotCountTowardTheNextBatch() = runBlocking {
        var calls = 0
        val page = ContributionStats.commitAuthorPage("owner", "linux", 5, known = setOf("alice"),
            request = { _, _ -> calls++; response(200, commitsJson(*Array(100) { "Alice" })) })
        // 只有已列出的作者，凑不够新作者，读满 3 页后停下
        assertEquals(3, calls)
        assertEquals(3, page.pagesRead)
        assertTrue(page.hasNext)
    }

    @Test fun laterPageFailureInABatchKeepsWhatWasAlreadyRead() = runBlocking {
        var calls = 0
        val page = ContributionStats.commitAuthorPage("owner", "linux", 1, request = { _, _ ->
            if (++calls == 1) response(200, commitsJson(*Array(100) { "Alice" })) else response(502, "{}")
        })
        assertEquals(listOf("Alice"), page.records.map { it.author!!.login })
        assertEquals(1, page.pagesRead)
        assertTrue(page.hasNext)
    }

    @Test fun recentCommitPageSkipsUnlinkedAuthorsAndStopsAtAShortPage() = runBlocking {
        val page = ContributionStats.commitAuthorPage("owner", "linux", 7,
            request = { path, _ -> assertTrue(path.contains("page=7")); response(200, commitsJson("a", null, "A", "b")) })
        assertEquals(listOf("a", "b"), page.records.map { it.author!!.login })
        assertEquals(listOf(2L, 1L), page.records.map { it.total })
        assertFalse(page.hasNext)
    }

    @Test fun fullRecentCommitPageReportsAnotherPage() = runBlocking {
        val page = ContributionStats.commitAuthorPage("owner", "linux", 1,
            request = { _, _ -> response(200, commitsJson(*Array(100) { "dev$it" })) })
        assertEquals(100, page.records.size)
        assertTrue(page.hasNext)
    }

    @Test fun recentCommitPagesMergeTheSameAuthorAcrossPages() {
        val first = listOf(record("alice", 3), record("bob", 2))
        val merged = mergeAuthorCounts(first, listOf(record("BOB", 4), record("carol", 1)))
        assertEquals(listOf("alice", "bob", "carol"), merged.map { it.author!!.login })
        assertEquals(listOf(3L, 6L, 1L), merged.map { it.total })
        // 经分页状态追加时同样合并，且只在开启 mergeByLogin 时
        val state = ContributorListState(records = first, nextPage = 2, busy = true, mergeByLogin = true)
            .finishPage(ContributorPage(listOf(record("alice", 5)), hasNext = true))
        assertEquals(listOf("alice", "bob"), state.records.map { it.author!!.login })
        assertEquals(8L, state.records.first().total)
        assertEquals(3, state.nextPage)
        val skipped = ContributorListState(records = first, nextPage = 2, busy = true, mergeByLogin = true)
            .finishPage(ContributorPage(listOf(record("carol", 1)), hasNext = true, pagesRead = 3))
        assertEquals(5, skipped.nextPage)
        val plain = ContributorListState(records = first, nextPage = 2, busy = true)
            .finishPage(ContributorPage(listOf(record("alice", 5)), hasNext = true))
        assertEquals(3, plain.records.size)
    }

    @Test fun tooLargeListWithoutAnyLinkedAuthorsStillOffersLaterPages() = runBlocking {
        val result = loadStats("owner", "linux", request = { path, _ ->
            if (tooLarge(path)) response(403, """{"message":"too large"}""")
            else response(200, commitsJson(*Array(100) { null }))
        })
        assertTrue(result.records.isEmpty())
        assertEquals(ContributionStatus.Ready, result.status)
        // 一批读满 3 页仍没有可显示的作者，游标跳到第 4 页
        assertEquals(4, result.nextPage)
    }

    @Test fun tooLargeListOfAnEmptyRepositoryIsEmpty() = runBlocking {
        val result = loadStats("owner", "repo", request = { path, _ ->
            if (tooLarge(path)) response(403, """{"message":"too large"}""") else response(409, """{"message":"Git Repository is empty."}""")
        })
        assertEquals(ContributionStatus.Empty, result.status)
        assertNull(result.nextPage)
    }

    @Test fun tooLargeListRateLimitIsRaisedNotHidden() = runBlocking {
        val failed = runCatching {
            loadStats("owner", "linux", request = { path, _ ->
                if (tooLarge(path)) response(403, """{"message":"too large"}""") else response(429, "{}")
            })
        }
        assertTrue(failed.exceptionOrNull() is ApiException)
    }

    @Test fun tooLargeListWhenCommitsAreUnavailableIsReportedWithoutThrowing() = runBlocking {
        val result = loadStats("owner", "linux", request = { path, _ ->
            if (tooLarge(path)) response(403, """{"message":"too large"}""") else response(500, "{}")
        })
        assertEquals(ContributionStatus.Unavailable, result.status)
        assertTrue(result.records.isEmpty())
    }

    @Test fun emptyAndPendingContributorListsAreReportedWithoutFallback() = runBlocking {
        var calls = 0
        val empty = loadStats("owner", "repo", request = { _, _ -> calls++; response(204) })
        assertEquals(ContributionStatus.Empty, empty.status)
        val pending = loadStats("owner", "repo", request = { _, _ -> calls++; response(202) })
        assertEquals(ContributionStatus.Pending, pending.status)
        assertEquals(2, calls)
    }

    @Test fun unexpectedSuccessCodeIsAnErrorNotAnEmptyRepository() = runBlocking {
        val failed = runCatching {
            loadStats("owner", "repo", request = { _, _ -> response(201, "[]") })
        }
        assertTrue(failed.isFailure)
    }

    @Test fun authenticationAndRateLimitFailuresAreRaisedOnTheFirstRequest() = runBlocking {
        for (code in listOf(401, 429)) {
            val paths = mutableListOf<String>()
            try {
                loadStats("owner", "repo", request = { path, _ -> paths += path; response(code, "{}") })
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
        // 默认只取最近 3 周
        val defaultActivity = ContributionStats.personActivity("owner", "repo", "octocat",
            request = { path, _ -> assertTrue(path.contains("author=octocat")); response(200, commits) }, now = now)
        assertEquals(3, defaultActivity.weekly.size)
        assertFalse(defaultActivity.truncated)
        assertEquals(LocalDate.parse("2026-10-04"), defaultActivity.weekly.last().start)
        assertEquals(2L, defaultActivity.weekly.last().commits)

        // 翻到 52 周时支持获取 52 周
        val activity = ContributionStats.personActivity("owner", "repo", "octocat", weeksCount = 52,
            request = { path, _ -> assertTrue(path.contains("author=octocat")); response(200, commits) }, now = now)
        assertEquals(52, activity.weekly.size)
        assertFalse(activity.truncated)
        assertEquals(LocalDate.parse("2026-10-04"), activity.weekly.last().start)
        assertEquals(2L, activity.weekly.last().commits)
        assertEquals(1L, activity.weekly[activity.weekly.size - 2].commits)
        assertEquals(0L, activity.weekly.first().commits)
    }

    @Test fun personActivityReadsEveryPageOfTheYearAndReportsProgress() = runBlocking {
        val pages = mutableListOf<Int>()
        val progress = mutableListOf<PersonActivity>()
        fun commit(date: String) = """{"commit":{"author":{"date":"$date"}}}"""
        val full = "[" + (1..100).joinToString(",") { commit("2026-10-05T10:00:00Z") } + "]"
        val activity = ContributionStats.personActivity("owner", "linux", "gregkh", weeksCount = 52, now = now,
            onProgress = { progress += it },
            request = { path, _ ->
                val page = Regex("&page=(\\d+)").find(path)!!.groupValues[1].toInt()
                pages += page
                if (page < 5) response(200, full) else response(200, "[" + commit("2026-03-01T00:00:00Z") + "]")
            })
        // 超过 3 页也继续读，直到一页不满为止；读到的每一页之后都交出过中间结果
        assertEquals(listOf(1, 2, 3, 4, 5), pages)
        assertEquals(4, progress.size)
        assertTrue(progress.all { it.truncated })
        assertFalse(activity.truncated)
        assertEquals(400L, activity.weekly.last().commits)
        assertEquals(1L, activity.weekly.single { it.start == LocalDate.parse("2026-03-01") }.commits)
        assertEquals(0L, activity.weekly.first().commits)
    }

    @Test fun personActivityMoreOnlyRequestsTheNewWindowAndKeepsEarlierWeeks() = runBlocking {
        fun commit(date: String) = """{"commit":{"author":{"date":"$date"}}}"""
        val paths = mutableListOf<String>()
        val history = PersonHistory()
        val first = ContributionStats.personActivityMore("o", "r", "me", history, addWeeks = 3, now = now,
            request = { path, _ -> paths += path; response(200, "[" + commit("2026-10-05T10:00:00Z") + "]") })
        assertEquals(3, first.weekly.size)
        assertEquals(3, history.loadedWeeks)
        assertEquals(1L, first.weekly.last().commits)
        assertFalse(paths.single().contains("until="))

        // 往回再加载 3 周：只请求更早的窗口（带 until），已读的最近 3 周不会重新请求
        val second = ContributionStats.personActivityMore("o", "r", "me", history, addWeeks = 3, now = now,
            request = { path, _ -> paths += path; response(200, "[" + commit("2026-09-10T10:00:00Z") + "]") })
        assertEquals(2, paths.size)
        assertTrue(paths[1].contains("until="))
        assertEquals(6, history.loadedWeeks)
        assertEquals(6, second.weekly.size)
        // 最近一周的数据沿用，没有被重新读取或丢失
        assertEquals(1L, second.weekly.last().commits)
        assertEquals(1L, second.weekly.single { it.start == LocalDate.parse("2026-09-06") }.commits)
    }

    @Test fun personActivityMoreBusyAuthorStillLoadsMoreThanFivePagesPerWindow() = runBlocking {
        fun commit(date: String) = """{"commit":{"author":{"date":"$date"}}}"""
        val full = "[" + (1..100).joinToString(",") { commit("2026-10-05T10:00:00Z") } + "]"
        var pages = 0
        val activity = ContributionStats.personActivityMore("o", "r", "busy", PersonHistory(), now = now,
            request = { _, _ -> pages++; if (pages <= 8) response(200, full) else response(200, "[]") })
        assertEquals(9, pages)
        assertEquals(800L, activity.weekly.last().commits)
        assertFalse(activity.truncated)
    }

    @Test fun authorCommitCountIsReportedUnavailableWhenGitHubOmitsTheLastPage() = runBlocking {
        val noLast = Headers.Builder().add("Link", "<https://api.github.com/x?per_page=1&page=3>; rel=\"next\", <https://api.github.com/x?per_page=1&page=1>; rel=\"first\"").build()
        val failed = runCatching { ContributionStats.authorCommits("owner", "linux", "gregkh", request = { _, _ -> response(200, "[{}]", noLast) }) }
        assertTrue(failed.isFailure)
    }

    @Test fun keepOrderPreservesTheSnapshotSequence() {
        val snap = snapshot(record("zed", 1), record("amy", 9))
        assertEquals(listOf("zed", "amy"), aggregateContributions(snap, ContributionRange.All, keepOrder = true).contributors.map { it.author!!.login })
        assertEquals(listOf("amy", "zed"), aggregateContributions(snap, ContributionRange.All).contributors.map { it.author!!.login })
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

    @Test fun homepagePreviewFetchesTopThreeCumulativeCountsWithoutHeavyStatistics() = runBlocking {
        var calls = 0
        val result = ContributionStats.preview("owner", "repo", force = true, request = { path, force ->
            calls++
            assertEquals("/repos/owner/repo/contributors?anon=1&per_page=3&page=1", path)
            assertTrue(force)
            response(200, """[{"login":"alice","contributions":60},{"login":"bob","contributions":100},{"login":"carol","contributions":40},{"login":"dave","contributions":10}]""")
        })
        assertEquals(1, calls)
        // 取提交数最多的三位（顺序按提交数降序），多余的即使接口给了也不展示
        assertEquals(listOf(100L, 60L, 40L), result.records.map { it.total })
        assertTrue(result.records.all { it.weeks.isEmpty() })
        assertEquals(ContributionSource.ContributorList, result.source)
    }

    @Test fun homepagePreviewFallsBackToAnyThreeWhenCountsAreMissing() = runBlocking {
        val result = ContributionStats.preview("owner", "repo", request = { _, _ ->
            response(200, """[{"login":"alice"},{"login":"bob"},{"login":"carol"},{"login":"dave"}]""")
        })
        // 拿不到提交数时保持接口顺序，随便取三位
        assertEquals(listOf("alice", "bob", "carol"), result.records.map { it.author?.login })
    }

    @Test fun emptyHomepagePreviewHasNoStatisticsOrFallbackRequests() = runBlocking {
        var calls = 0
        val result = ContributionStats.preview("owner", "repo", request = { _, _ -> calls++; response(204) })
        assertEquals(1, calls)
        assertEquals(ContributionStatus.Empty, result.status)
    }
}
