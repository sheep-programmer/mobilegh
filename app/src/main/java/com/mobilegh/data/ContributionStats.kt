package com.mobilegh.data

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import java.io.File
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.decodeFromStream
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
    /** Next /contributors page the screen should request; null when the list is complete. */
    val nextPage: Int? = null,
)

/** One page of GET /contributors; [hasNext] comes from the Link header (or a full page when absent). */
data class ContributorPage(val records: List<ContributionRecord>, val hasNext: Boolean)

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
        .sortedWith(compareByDescending<ContributorContribution> { it.commits ?: -1 }
            .thenBy { it.author?.login?.lowercase(Locale.ROOT).orEmpty() }.thenBy { it.key })
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

/** Bounded, cancellable polling with a separate, paginated cumulative-count fallback. */
/** 读取统计接口的数组：把输入流交给 [parse] 解析；204 返回 null。生产环境用 [Api.streamJson]，测试里可替换。 */
typealias StatsStreamer = suspend (path: String, force: Boolean, parse: (InputStream) -> List<ContributionRecord>) -> List<ContributionRecord>?

object ContributionStats {
    /** The repository homepage needs only three cumulative counts, never weekly statistics. */
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
        Api.ensureOk(response)
        if (response.code != 200) throw ApiException(response.code, trf("贡献者列表尚未就绪 ({0})", response.code))
        val records = decodeContributors(response.body).take(3)
        return ContributionSnapshot(records, ContributionSource.ContributorList,
            if (records.isEmpty()) ContributionStatus.Empty else ContributionStatus.Ready)
    }

    /**
     * 贡献者列表：只读累计提交数（/contributors 分页），不加载周统计，所以打开仓库不会触发大量请求。
     * 点开某一位贡献者时才由 [personActivity] 单独统计其最近一年的周趋势。
     */
    suspend fun load(
        owner: String,
        name: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
        cacheDir: File? = appCacheDir(),
        streamStats: StatsStreamer = { path, refresh, parse -> Api.streamJson(path, refresh, parse) },
    ): ContributionSnapshot {
        val base = "/repos/$owner/$name"
        // 已缓存的超大仓库排名直接返回：不再请求 /contributors（它会返回 403）
        if (!force) readRankCache(cacheDir, base, System.currentTimeMillis())?.let { return topSnapshot(it) }
        val response = request("$base/contributors?anon=1&per_page=$CONTRIBUTOR_PAGE_SIZE&page=1", force)
        when {
            // 超大仓库 GitHub 不给出分页列表，只能退而读一次统计接口的累计排名
            response.code == 403 && response.body.contains("too large", ignoreCase = true) ->
                return loadTopContributorsFromStatistics(base, force, streamStats, cacheDir)
            response.code == 504 -> return ContributionSnapshot(
                source = ContributionSource.ContributorList, status = ContributionStatus.Unavailable,
                message = tr("GitHub 处理贡献者列表超时，请稍后重试"),
            )
            response.code == 202 -> return ContributionSnapshot(
                source = ContributionSource.ContributorList, status = ContributionStatus.Pending,
                message = tr("GitHub 正在生成贡献者列表，请稍后重试"),
            )
            response.code == 204 -> return ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Empty)
        }
        Api.ensureOk(response)
        if (response.code != 200) throw ApiException(response.code, trf("贡献者列表尚未就绪 ({0})", response.code))
        val records = decodeContributors(response.body)
        val hasNext = hasNextContributorPage(response.headers["Link"], records.size)
        return ContributionSnapshot(
            records, ContributionSource.ContributorList,
            if (records.isEmpty()) ContributionStatus.Empty else ContributionStatus.Ready,
            if (hasNext) tr("贡献者很多，已按累计提交排名分页加载；向下滚动可加载更多，搜索时会继续检索其余贡献者。") else null,
            truncated = hasNext, nextPage = if (hasNext) 2 else null,
        )
    }

    /** 超大仓库的排名缓存保留时间：统计接口计算很慢，同一个仓库一小时内不重复读取。 */
    private const val RANK_CACHE_MILLIS = 60 * 60 * 1000L

    @Serializable
    private data class RankCache(val savedAt: Long, val records: List<ContributionRecord>)

    /** 缓存文件放在应用缓存目录下，设置里的「清除缓存」会一并删除。缓存目录不可用时静默跳过。 */
    private fun rankCacheFile(dir: File?, base: String): File? = runCatching {
        dir?.let { File(File(it, "contributors").apply { mkdirs() }, base.removePrefix("/repos/").replace('/', '_') + ".json") }
    }.getOrNull()

    private fun readRankCache(dir: File?, base: String, now: Long): List<ContributionRecord>? = runCatching {
        val file = rankCacheFile(dir, base)?.takeIf { it.exists() } ?: return null
        val cached = Api.json.decodeFromString(RankCache.serializer(), file.readText())
        cached.records.takeIf { now - cached.savedAt in 0..RANK_CACHE_MILLIS }
    }.getOrNull()

    private fun writeRankCache(dir: File?, base: String, records: List<ContributionRecord>, now: Long) {
        runCatching {
            rankCacheFile(dir, base)?.writeText(Api.json.encodeToString(RankCache.serializer(), RankCache(now, records)))
        }
    }

    /** 应用缓存目录；单元测试里没有初始化时返回 null，即不缓存。 */
    private fun appCacheDir(): File? = runCatching { Api.cacheDir }.getOrNull()

    private fun topSnapshot(records: List<ContributionRecord>) = ContributionSnapshot(
        records, ContributionSource.ContributorList, ContributionStatus.Ready,
        trf("GitHub 不提供这个仓库的完整贡献者列表（仓库过大）。此处是统计接口返回的 {0} 位贡献者，按累计提交数排序；点开某人可查看其最近一年的周趋势。", records.size),
        truncated = true,
    )

    /**
     * /contributors 对超大仓库返回 403 时调用一次 /stats/contributors：流式解析，只保留登录名和累计提交数，
     * 周数据不分配内存，也不把 28 MB 的响应整个读进字符串。不轮询；结果缓存一小时。
     */
    private suspend fun loadTopContributorsFromStatistics(
        base: String,
        force: Boolean,
        streamStats: StatsStreamer,
        cacheDir: File?,
    ): ContributionSnapshot {
        val now = System.currentTimeMillis()
        val records = try {
            streamStats("$base/stats/contributors", force, ::decodeTopContributors)
        } catch (error: ApiException) {
            return when (error.code) {
                202 -> ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Pending,
                    message = tr("GitHub 正在计算这个仓库的贡献统计，稍后刷新即可"))
                401, 403, 429, -1 -> throw error
                else -> ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Unavailable,
                    message = trf("贡献统计暂不可用 ({0})", error.code))
            }
        }
            ?: return ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Empty)
        if (records.isEmpty()) return ContributionSnapshot(source = ContributionSource.ContributorList, status = ContributionStatus.Empty)
        writeRankCache(cacheDir, base, records, now)
        return topSnapshot(records)
    }

    /** 只解析需要的字段；weeks 数组由解析器跳过，不会变成对象。 */
    @Serializable
    private data class TopRow(val author: User? = null, val total: Long? = null)

    private fun decodeTopContributors(input: InputStream): List<ContributionRecord> =
        Api.json.decodeFromStream(ListSerializer(TopRow.serializer()), input)
            .sortedByDescending { it.total ?: -1L }
            .map { ContributionRecord(author = it.author, total = it.total) }

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

    /** 展开某一位贡献者时调用：只取最近一年的提交（每页 100，最多 3 页），按周数。 */
    suspend fun personActivity(
        owner: String,
        name: String,
        login: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
        now: Instant = Instant.now(),
    ): PersonActivity {
        val since = now.minus(Duration.ofDays(365)).truncatedTo(ChronoUnit.SECONDS)
        val counts = mutableMapOf<LocalDate, Long>()
        var truncated = false
        for (page in 1..3) {
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
            if (items.size < 100) break
            truncated = page == 3
        }
        // 截断后，最早那一周及更早的数据可能不完整，显示为缺失，而不是 0
        val oldest = counts.keys.minOrNull()
        val currentWeek = weekStart(now.atOffset(ZoneOffset.UTC).toLocalDate())
        val weekly = (51 downTo 0).map { back ->
            val start = currentWeek.minusWeeks(back.toLong())
            ContributionPoint(start, if (truncated && (oldest == null || !start.isAfter(oldest))) null else counts[start] ?: 0L)
        }
        return PersonActivity(weekly, truncated)
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
