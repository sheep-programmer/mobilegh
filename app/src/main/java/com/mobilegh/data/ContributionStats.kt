package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** Nullable counts preserve the distinction between a missing metric and a reported zero. */
@Serializable
data class ContributionWeek(
    @SerialName("w") val start: Long = 0,
    @SerialName("c") val commits: Long? = null,
    @SerialName("a") val additions: Long? = null,
    @SerialName("d") val deletions: Long? = null,
)

@Serializable
data class ContributionRecord(
    val author: User? = null,
    val total: Long? = null,
    val weeks: List<ContributionWeek> = emptyList(),
)

enum class ContributionSource { Statistics, ContributorList }
enum class ContributionStatus { Ready, Empty, Pending, Unavailable }

data class ContributionSnapshot(
    val records: List<ContributionRecord> = emptyList(),
    val source: ContributionSource = ContributionSource.Statistics,
    val status: ContributionStatus = ContributionStatus.Ready,
    val message: String? = null,
    /** True while the contributor list has further pages that have not been loaded yet. */
    val truncated: Boolean = false,
    val loadedAt: Instant = Instant.now(),
    /** Next page the screen should request; null when the list is complete. */
    val nextPage: Int? = null,
    /**
     * 列表由默认分支最近的提交逐页汇总而来（超大仓库）：每一行的数字是最近这段历史里的提交数，
     * 不是累计提交数；后续页要用 [ContributionStats.commitAuthorPage] 读取。
     */
    val commitPaged: Boolean = false,
)

/** One page of GET /contributors; [hasNext] comes from the Link header (or a full page when absent). */
data class ContributorPage(
    val records: List<ContributionRecord>,
    val hasNext: Boolean,
    /** 这一步实际读取的上游页数（提交分页时一步可能读多页）；游标按它前进。 */
    val pagesRead: Int = 1,
)

enum class ContributionRange(private val labelZh: String, val days: Long? = null) {
    All("全部提交"), Year("最近一年", 365), HalfYear("最近半年", 182),
    NinetyDays("最近 90 天", 90), ThirtyDays("最近 30 天", 30), Custom("自定义");
    val label: String get() = tr(labelZh)
}

/** Dates are inclusive in the UI. All calculations use UTC, matching GitHub's weekly buckets. */
data class ContributionDates(val start: LocalDate, val end: LocalDate) {
    init { require(!start.isAfter(end)) }
}

data class ContributionDateValidation(val dates: ContributionDates? = null, val error: String? = null)

fun validateContributionDates(start: String, end: String, today: LocalDate): ContributionDateValidation {
    fun parse(value: String): LocalDate? {
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value.trim())) return null
        return try { LocalDate.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE) }
        catch (_: DateTimeParseException) { null }
    }
    val first = parse(start) ?: return ContributionDateValidation(error = tr("开始日期无效，请使用 YYYY-MM-DD"))
    val last = parse(end) ?: return ContributionDateValidation(error = tr("结束日期无效，请使用 YYYY-MM-DD"))
    return when {
        first.isBefore(LocalDate.of(1970, 1, 1)) -> ContributionDateValidation(error = tr("开始日期不能早于 1970-01-01"))
        first.isAfter(last) -> ContributionDateValidation(error = tr("开始日期不能晚于结束日期"))
        last.isAfter(today) -> ContributionDateValidation(error = tr("结束日期不能晚于今天（UTC）"))
        else -> ContributionDateValidation(ContributionDates(first, last))
    }
}

data class ContributionPoint(val start: LocalDate, val commits: Long?)

data class ContributorContribution(
    val key: String,
    val author: User?,
    val commits: Long?,
    val additions: Long?,
    val deletions: Long?,
    val share: Double?,
    val weekly: List<ContributionPoint>,
    val activeWeeks: Int?,
    val firstActiveWeek: LocalDate?,
    val lastActiveWeek: LocalDate?,
    val linesCoverAllCommits: Boolean,
)

data class ContributionSummary(
    val contributors: List<ContributorContribution>,
    val commits: Long?,
    val additions: Long?,
    val deletions: Long?,
    val weekly: List<ContributionPoint>,
    val requestedDates: ContributionDates?,
    val availableDates: ContributionDates?,
    val includedDates: ContributionDates?,
    val rangeUnavailable: Boolean,
    val partialCoverage: Boolean,
    val boundaryWeeksIncluded: Boolean,
    val linesCoverAllCommits: Boolean,
)

private fun Long?.validCount() = this?.takeIf { it >= 0 }

// Fail closed on overflow rather than displaying a wrapped, negative total or inventing a value.
private fun sumCounts(values: List<Long?>): Long? {
    var sum = 0L
    for (value in values) {
        val count = value.validCount() ?: return null
        if (Long.MAX_VALUE - sum < count) return null
        sum += count
    }
    return sum
}

private fun weekDate(start: Long, today: LocalDate): LocalDate? {
    if (start <= 0) return null
    val day = try { Instant.ofEpochSecond(start).atOffset(ZoneOffset.UTC).toLocalDate() }
    catch (_: java.time.DateTimeException) { return null }
    return day.takeIf { !it.isAfter(today) }
}

/**
 * Cumulative commits come only from /stats/contributors.total (or the fallback list).
 * Weekly sums are never promoted to all-history counts. Date filters include whole
 * overlapping weeks; additions/deletions always describe only the returned buckets.
 */
fun aggregateContributions(
    snapshot: ContributionSnapshot,
    range: ContributionRange,
    custom: ContributionDates? = null,
    now: Instant = snapshot.loadedAt,
    /** 为 true 时保持快照里的先后顺序（边滚动边追加的列表不能因数字变化而换位）。 */
    keepOrder: Boolean = false,
): ContributionSummary {
    val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
    val requested = when (range) {
        ContributionRange.All -> null
        ContributionRange.Custom -> custom
        else -> ContributionDates(today.minusDays(range.days!! - 1), today)
    }
    val invalidRange = range == ContributionRange.Custom &&
        (custom == null || custom.end.isAfter(today) || custom.start.isBefore(LocalDate.of(1970, 1, 1)))
    val groups = snapshot.records.withIndex().groupBy { (index, record) ->
        val user = record.author
        when {
            user != null && user.id > 0 -> "id:${user.id}"
            !user?.login.isNullOrBlank() -> "login:${user.login.lowercase(Locale.ROOT)}"
            else -> "anonymous:$index"
        }
    }
    data class Normalized(val key: String, val user: User?, val total: Long?, val weeks: Map<LocalDate, ContributionWeek>)
    val normalized = groups.map { (key, records) ->
        // Repeated entries/buckets are snapshots, not extra contributions. Last bucket wins.
        val weeks = records.flatMap { it.value.weeks }.mapNotNull { week ->
            weekDate(week.start, today)?.let { it to week }
        }.toMap().toSortedMap()
        Normalized(key, records.last().value.author,
            records.mapNotNull { it.value.total.validCount() }.maxOrNull(), weeks)
    }
    val availableStarts = normalized.flatMap { it.weeks.keys }.distinct().sorted()
    val available = availableStarts.firstOrNull()?.let {
        ContributionDates(it, minOf(availableStarts.last().plusDays(6), today))
    }
    val selectedStarts = if (invalidRange) emptyList() else availableStarts.filter { start ->
        requested == null || (!start.isAfter(requested.end) && !start.plusDays(6).isBefore(requested.start))
    }
    // Keep a shared, continuous axis so absent/zero weeks do not slide other contributors' bars.
    val axis = if (selectedStarts.isEmpty()) emptyList() else generateSequence(selectedStarts.first()) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(selectedStarts.last()) }.toList()
    val rangeUnavailable = invalidRange || (range != ContributionRange.All && (available == null || axis.isEmpty()))
    val commitTotal = if (normalized.isEmpty() && snapshot.status != ContributionStatus.Empty) null else sumCounts(normalized.map { row ->
        if (range == ContributionRange.All) row.total else if (rangeUnavailable) null
        else sumCounts(axis.map { row.weeks[it]?.commits })
    })
    val largeRepository = normalized.any { (it.total ?: 0L) >= 10_000L } ||
        (sumCounts(normalized.map { it.total }) ?: 0L) >= 10_000L
    val suppressedLines = largeRepository && normalized.flatMap { it.weeks.values }
        .all { (it.additions ?: 0L) == 0L && (it.deletions ?: 0L) == 0L }
    val rows = normalized.map { row ->
        val weeks = axis.map { row.weeks[it] }
        val points = axis.map { day ->
            ContributionPoint(day, row.weeks[day]?.commits.validCount())
        }
        val weeklyCommits = sumCounts(weeks.map { it?.commits })
        val commits = if (range == ContributionRange.All) row.total else if (rangeUnavailable) null else weeklyCommits
        val hasWeeks = row.weeks.isNotEmpty() && axis.isNotEmpty()
        val active = points.filter { (it.commits ?: 0L) > 0 }
        val complete = row.total != null && row.total == sumCounts(row.weeks.values.map { it.commits })
        ContributorContribution(
            row.key, row.user, commits,
            if (hasWeeks && !suppressedLines) sumCounts(weeks.map { it?.additions }) else null,
            if (hasWeeks && !suppressedLines) sumCounts(weeks.map { it?.deletions }) else null,
            if (commits != null && commitTotal != null && commitTotal > 0) commits.toDouble() / commitTotal else null,
            points, if (points.isEmpty() || points.any { it.commits == null }) null else active.size,
            active.firstOrNull()?.start, active.lastOrNull()?.start, complete,
        )
    }.filter { range == ContributionRange.All || it.commits == null || it.commits > 0 ||
        (it.additions ?: 0L) > 0 || (it.deletions ?: 0L) > 0 }
        .let { list ->
            if (keepOrder) list
            else list.sortedWith(compareByDescending<ContributorContribution> { it.commits ?: -1 }
                .thenBy { it.author?.login?.lowercase(Locale.ROOT).orEmpty() }.thenBy { it.key })
        }
    val included = axis.firstOrNull()?.let { ContributionDates(it, minOf(axis.last().plusDays(6), today)) }
    val partial = requested != null && (available == null || requested.start.isBefore(available.start) ||
        requested.end.isAfter(available.end) || axis.any { it !in availableStarts })
    val boundary = requested != null && included != null &&
        (included.start.isBefore(requested.start) || included.end.isAfter(requested.end))
    return ContributionSummary(
        rows, if (rangeUnavailable) null else commitTotal,
        if (axis.isEmpty() || suppressedLines) null else sumCounts(rows.map { it.additions }),
        if (axis.isEmpty() || suppressedLines) null else sumCounts(rows.map { it.deletions }),
        axis.map { day -> ContributionPoint(day, sumCounts(normalized.map { row ->
            row.weeks[day]?.commits.validCount()
        })) },
        requested, available, included, rangeUnavailable, partial, boundary,
        rows.isNotEmpty() && rows.all { it.linesCoverAllCommits },
    )
}

/** 一年内的每周提交，给大仓库里展开的那一个人用；[truncated] 表示只取到了最近的一部分提交。 */
data class PersonActivity(val weekly: List<ContributionPoint>, val truncated: Boolean)

object ContributionStats {
    /** 仓库首页展示累计提交最多的三位贡献者，不读周统计，也不多取。 */
    suspend fun preview(
        owner: String,
        name: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
    ): ContributionSnapshot {
        val response = request("/repos/$owner/$name/contributors?anon=1&per_page=3&page=1", force)
        if (response.code == 204) return ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Empty)
        if (response.code == 202) return ContributionSnapshot(source = ContributionSource.ContributorList,
            status = ContributionStatus.Pending, message = tr("GitHub 正在生成贡献者列表，请稍后重试"))
        // 超大仓库拿不到贡献者列表（404/403 too large）：首页只给出入口，详细页会逐页读取最近的提交
        if (response.code == 403 && response.body.contains("too large", ignoreCase = true)) {
            return ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Unavailable,
                message = tr("仓库过大，GitHub 不提供贡献者列表；点「详细统计」可按最近的提交逐页查看。"))
        }
        Api.ensureOk(response)
        if (response.code != 200) throw ApiException(response.code, trf("贡献者列表尚未就绪 ({0})", response.code))
        val decoded = decodeContributors(response.body)
        // GitHub 本就按提交数降序返回；这里再排一次，确保展示的是最多的三位。
        // 若排序键缺失，就保持接口顺序，随便取三位即可。
        val ranked = if (decoded.isNotEmpty() && decoded.all { it.total != null }) {
            decoded.sortedByDescending { it.total }
        } else {
            decoded
        }
        val records = ranked.take(3)
        return ContributionSnapshot(records, ContributionSource.ContributorList,
            if (records.isEmpty()) ContributionStatus.Empty else ContributionStatus.Ready)
    }

    /** 不超过这个人数的仓库读周统计（含增删行数与按时间筛选）；超过的只读分页的累计提交列表。 */
    private const val LARGE_REPOSITORY = 200L

    /**
     * 贡献者列表。
     * - 小仓库（不超过 [LARGE_REPOSITORY] 人）：读 /stats/contributors，有周数据、增删行数和时间筛选；统计仍在生成时退回累计提交列表。
     * - 大仓库：只读分页的累计提交数，点开某一位贡献者时才由 [personActivity] 单独统计其最近一年的周趋势。
     * - 超大仓库（/contributors 返回 403 too large）：GitHub 不提供任何完整名单，改为逐页读取默认分支最近的提交，
     *   按作者汇总最近提交的数量；一次只读一页，滚到底再由界面请求下一页。
     */
    suspend fun load(
        owner: String,
        name: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): ContributionSnapshot {
        val base = "/repos/$owner/$name"
        val probe = request("$base/contributors?anon=1&per_page=1&page=1", force)
        when {
            // 超大仓库没有贡献者列表：改读最近提交，第 1 页立即返回，其余页由界面懒加载
            probe.code == 403 && probe.body.contains("too large", ignoreCase = true) ->
                return loadCommitPaged(base, force, request)
            probe.code == 504 -> return ContributionSnapshot(
                source = ContributionSource.ContributorList, status = ContributionStatus.Unavailable,
                message = tr("GitHub 处理贡献者列表超时，请稍后重试"),
            )
            probe.code == 202 -> return ContributionSnapshot(
                source = ContributionSource.ContributorList, status = ContributionStatus.Pending,
                message = tr("GitHub 正在生成贡献者列表，请稍后重试"),
            )
            probe.code == 204 -> return ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Empty)
        }
        Api.ensureOk(probe)
        if (probe.code != 200) throw ApiException(probe.code, trf("贡献者列表尚未就绪 ({0})", probe.code))
        val count = countFromFirstPage(probe)
        var status = ContributionStatus.Ready
        var message: String? = null
        if (count in 1..LARGE_REPOSITORY) {
            val weekly = loadWeeklyStatistics(base, force, request, pause)
            if (weekly.status == ContributionStatus.Ready || weekly.status == ContributionStatus.Empty) return weekly
            // 周统计没有就绪：下面退回累计提交列表，并说明原因
            status = weekly.status
            message = weekly.message
        }
        val first = fetchContributorPage(base, 1, force, request)
        val paging = if (first.hasNext) tr("贡献者很多，已按累计提交排名分页加载；向下滚动可加载更多，搜索时会继续检索其余贡献者。") else null
        return ContributionSnapshot(
            first.records, ContributionSource.ContributorList,
            if (first.records.isEmpty() && status == ContributionStatus.Ready) ContributionStatus.Empty else status,
            listOfNotNull(message, paging).joinToString("\n").ifBlank { null },
            truncated = first.hasNext, nextPage = if (first.hasNext) 2 else null,
        )
    }

    /** 周统计（含增删行数）。GitHub 仍在计算时最多等待约 7 秒；结果的状态决定调用方是否退回累计列表。 */
    private suspend fun loadWeeklyStatistics(
        base: String,
        force: Boolean,
        request: suspend (String, Boolean) -> Resp,
        pause: suspend (Long) -> Unit,
    ): ContributionSnapshot {
        for (attempt in 0 until 4) {
            val response = request("$base/stats/contributors", force || attempt > 0)
            when (response.code) {
                200 -> {
                    val records = Api.json.decodeFromString(ListSerializer(ContributionRecord.serializer()), response.body)
                    return ContributionSnapshot(records, ContributionSource.Statistics,
                        if (records.isEmpty()) ContributionStatus.Empty else ContributionStatus.Ready)
                }
                204 -> return ContributionSnapshot(source = ContributionSource.Statistics, status = ContributionStatus.Empty)
                202 -> if (attempt < 3) {
                    val retryAfter = response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1L, 8L)
                    pause((retryAfter ?: (1L shl attempt)) * 1000L)
                }
                else -> {
                    // 认证与限流失败不再尝试其他接口
                    if (response.code in listOf(401, 403, 429)) Api.ensureOk(response)
                    return ContributionSnapshot(source = ContributionSource.Statistics, status = ContributionStatus.Unavailable,
                        message = trf("周统计暂不可用 ({0})", response.code))
                }
            }
        }
        return ContributionSnapshot(source = ContributionSource.Statistics, status = ContributionStatus.Pending,
            message = tr("GitHub 仍在生成周统计，请稍后刷新"))
    }

    /** 这些错误不再降级：鉴权失效或限流时换数据源也一样失败。 */
    private val NO_FALLBACK_CODES = setOf(401, 403, 429, -1)

    @Serializable
    private data class CommitRow(val author: User? = null)

    /**
     * 超大仓库（/contributors 返回 403 too large）：GitHub 不提供任何完整名单，统计接口又常常 504 或要读 28 MB。
     * 这里从默认分支最近的提交读起，凑够 [AUTHORS_PER_STEP]（30）位作者就停（最多读 [MAX_COMMIT_PAGES_PER_STEP] 页）；
     * 之后每一批也只在界面滚到底时才请求，所以第一次打开不会卡住，也不会一次拿完整个历史。
     */
    private suspend fun loadCommitPaged(
        base: String,
        force: Boolean,
        request: suspend (String, Boolean) -> Resp,
    ): ContributionSnapshot {
        val first = try {
            fetchCommitAuthorPage(base, 1, force, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            if (error.code in NO_FALLBACK_CODES) throw error
            return ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Unavailable,
                message = trf("这个仓库的提交记录暂时不可用 ({0})，可稍后重试", error.code))
        }
        val next = if (first.hasNext) 1 + first.pagesRead else null
        if (first.records.isEmpty()) {
            return ContributionSnapshot(source = ContributionSource.ContributorList,
                status = if (first.hasNext) ContributionStatus.Ready else ContributionStatus.Empty,
                message = tr("最近提交里没有关联 GitHub 账号的作者。"),
                truncated = first.hasNext, nextPage = next, commitPaged = true)
        }
        return ContributionSnapshot(
            first.records, ContributionSource.ContributorList, ContributionStatus.Ready,
            tr("仓库过大，GitHub 不提供完整名单，按最近的提交列出。"),
            truncated = first.hasNext, nextPage = next, commitPaged = true,
        )
    }

    /**
     * 读取超大仓库的一批贡献者：读取默认分支 [COMMIT_AUTHOR_PAGE_SIZE] 条提交，只保留这些提交的作者。
     * 没有关联 GitHub 账号的提交无法显示头像和主页，直接跳过；其余按登录名合并（不区分大小写）。
     */
    suspend fun commitAuthorPage(
        owner: String,
        name: String,
        page: Int,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
        known: Set<String> = emptySet(),
    ): ContributorPage = fetchCommitAuthorPage("/repos/$owner/$name", page, force, request, known)

    private suspend fun fetchCommitAuthorPage(
        base: String,
        page: Int,
        force: Boolean,
        request: suspend (String, Boolean) -> Resp,
        known: Set<String> = emptySet(),
    ): ContributorPage {
        val counts = LinkedHashMap<String, Pair<User, Long>>()
        var read = 0
        var hasNext = true
        // 一批凑够 AUTHORS_PER_STEP 位新作者就停；提交集中在少数人时多读几页，但有上限
        while (hasNext && read < MAX_COMMIT_PAGES_PER_STEP) {
            val response = try {
                request("$base/commits?per_page=$COMMIT_AUTHOR_PAGE_SIZE&page=${page + read}", force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                // 已经读到一部分时先交出已有的，下一批再从失败的那一页重试
                if (read == 0) throw error
                break
            }
            // 409：空仓库没有提交；204 同样表示没有内容
            if (response.code == 204 || response.code == 409) { hasNext = false; break }
            if (read > 0 && response.code !in 200..299) break
            Api.ensureOk(response)
            if (response.code != 200) throw ApiException(response.code, trf("提交记录尚未就绪 ({0})", response.code))
            val rows = Api.json.decodeFromString(ListSerializer(CommitRow.serializer()), response.body)
            read++
            for (row in rows) {
                val author = row.author?.takeIf { !it.login.isNullOrBlank() } ?: continue
                val key = author.login.lowercase(Locale.ROOT)
                val previous = counts[key]
                counts[key] = (previous?.first ?: author) to ((previous?.second ?: 0L) + 1L)
            }
            // 一页提交没满就说明已经到了最早的提交
            hasNext = rows.size >= COMMIT_AUTHOR_PAGE_SIZE
            if (counts.keys.count { it !in known } >= AUTHORS_PER_STEP) break
        }
        // 保持提交出现的先后顺序（最近活跃的在前），不按次数重排，列表只会往后追加
        return ContributorPage(
            counts.values.map { (user, n) -> ContributionRecord(author = user, total = n) },
            hasNext = hasNext, pagesRead = read.coerceAtLeast(1),
        )
    }

    /** 超大仓库每批列出的作者数：滚到底才读下一批。 */
    private const val AUTHORS_PER_STEP = 30

    /** 一批最多读取的提交页数：提交集中在少数人手里时，限制一次滚动触发的请求数。 */
    private const val MAX_COMMIT_PAGES_PER_STEP = 3

    /** 每页读取的提交数；与 /contributors 的分页大小保持一致。 */
    private const val COMMIT_AUTHOR_PAGE_SIZE = CONTRIBUTOR_PAGE_SIZE

    /**
     * Reads one further page of the cumulative contributor list (anonymous authors included).
     * The screen calls this lazily: one request at a time, as the user scrolls or searches.
     */
    suspend fun contributorPage(
        owner: String,
        name: String,
        page: Int,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
    ): ContributorPage = fetchContributorPage("/repos/$owner/$name", page, force, request)

    /** 读取 /contributors 的一页，只有累计提交数；没有上限，由调用方决定要不要继续读下一页。 */
    private suspend fun fetchContributorPage(
        base: String,
        page: Int,
        force: Boolean,
        request: suspend (String, Boolean) -> Resp,
    ): ContributorPage {
        val response = request("$base/contributors?anon=1&per_page=$CONTRIBUTOR_PAGE_SIZE&page=$page", force)
        if (response.code == 204) return ContributorPage(emptyList(), hasNext = false)
        Api.ensureOk(response)
        if (response.code != 200) throw ApiException(response.code, trf("贡献者列表尚未就绪 ({0})", response.code))
        val records = decodeContributors(response.body)
        return ContributorPage(records, hasNext = hasNextContributorPage(response.headers["Link"], records.size))
    }

    /** per_page=1 时，Link 里 rel="last" 的页码就是总数；没有 Link 头时只可能是 0 或 1 条。 */
    private fun countFromFirstPage(response: Resp): Long {
        val last = response.headers["Link"]?.let { Regex("[?&]page=(\\d+)[^>]*>;\\s*rel=\"last\"").find(it) }
        return last?.groupValues?.get(1)?.toLongOrNull() ?: if (response.body.trim() == "[]") 0L else 1L
    }

    /**
     * 展开某一位贡献者时调用：逐页读取其最近一年的提交（每页 100，最多 [MAX_PERSON_PAGES] 页），按周数。
     * 每读完一页就通过 [onProgress] 交出当前结果（还没读到的更早的周为缺失）；全部读完才不算截断。
     */
    suspend fun personActivity(
        owner: String,
        name: String,
        login: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
        now: Instant = Instant.now(),
        onProgress: (PersonActivity) -> Unit = {},
    ): PersonActivity {
        val since = now.minus(Duration.ofDays(365)).truncatedTo(ChronoUnit.SECONDS)
        val counts = mutableMapOf<LocalDate, Long>()
        var more = true
        var page = 1
        while (more && page <= MAX_PERSON_PAGES) {
            val response = request("/repos/$owner/$name/commits?author=$login&since=$since&per_page=100&page=$page", force)
            Api.ensureOk(response)
            val items = Api.json.parseToJsonElement(response.body) as? JsonArray
                ?: throw IllegalStateException(tr("提交数据格式无效"))
            items.forEach { item ->
                val date = item.jsonObject["commit"]?.jsonObject?.get("author")?.jsonObject?.get("date")?.jsonPrimitive?.contentOrNull
                    ?: return@forEach
                val week = weekStart(Instant.parse(date).atOffset(ZoneOffset.UTC).toLocalDate())
                counts[week] = (counts[week] ?: 0L) + 1
            }
            more = items.size >= 100
            page++
            if (more) onProgress(personWeekly(counts, now, incomplete = true))
        }
        return personWeekly(counts, now, incomplete = more)
    }

    /** 最近一年最多读取的提交页数：约 5000 条提交，超过的部分较早的周显示为缺失。 */
    private const val MAX_PERSON_PAGES = 50

    private fun personWeekly(counts: Map<LocalDate, Long>, now: Instant, incomplete: Boolean): PersonActivity {
        // 提交按时间从新到旧读取：没读完时，最早读到的那一周及更早的数据可能不完整，显示为缺失，而不是 0
        val oldest = counts.keys.minOrNull()
        val currentWeek = weekStart(now.atOffset(ZoneOffset.UTC).toLocalDate())
        val weekly = (51 downTo 0).map { back ->
            val start = currentWeek.minusWeeks(back.toLong())
            ContributionPoint(start, if (incomplete && (oldest == null || !start.isAfter(oldest))) null else counts[start] ?: 0L)
        }
        return PersonActivity(weekly, incomplete)
    }

    /** 搜索框找不到已加载的人时使用：只用 per_page=1 的 Link 头算出该作者在默认分支上的提交总数。 */
    suspend fun authorCommits(
        owner: String,
        name: String,
        login: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
    ): Long {
        val response = request("/repos/$owner/$name/commits?author=$login&per_page=1&page=1", force)
        Api.ensureOk(response)
        // 超大仓库的 Link 头里没有 rel="last"，页码无法换算成总数：明确报告，不能当成 1 条
        val link = response.headers["Link"]
        if (link != null && link.contains("rel=\"next\"") && !link.contains("rel=\"last\"")) {
            throw IllegalStateException(tr("GitHub 没有提供该作者在这个仓库的提交总数"))
        }
        return countFromFirstPage(response)
    }

    private fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value % 7).toLong())

    private fun decodeContributors(body: String): List<ContributionRecord> {
        val array = Api.json.parseToJsonElement(body) as? JsonArray
            ?: throw IllegalStateException(tr("贡献者列表数据格式无效"))
        return array.map { element ->
            val item = element as? JsonObject ?: throw IllegalStateException(tr("贡献者数据格式无效"))
            val user = Api.json.decodeFromJsonElement(User.serializer(), JsonObject(item.filterKeys { it != "contributions" }))
            ContributionRecord(user, item["contributions"]?.jsonPrimitive?.longOrNull.validCount())
        }
    }
}
