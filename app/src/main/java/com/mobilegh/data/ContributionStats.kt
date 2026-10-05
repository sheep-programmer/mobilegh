package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

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
    val truncated: Boolean = false,
    val loadedAt: Instant = Instant.now(),
)

enum class ContributionRange(val label: String, val days: Long? = null) {
    All("全部提交"), Year("最近一年", 365), HalfYear("最近半年", 182),
    NinetyDays("最近 90 天", 90), ThirtyDays("最近 30 天", 30), Custom("自定义"),
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
    val first = parse(start) ?: return ContributionDateValidation(error = "开始日期无效，请使用 YYYY-MM-DD")
    val last = parse(end) ?: return ContributionDateValidation(error = "结束日期无效，请使用 YYYY-MM-DD")
    return when {
        first.isBefore(LocalDate.of(1970, 1, 1)) -> ContributionDateValidation(error = "开始日期不能早于 1970-01-01")
        first.isAfter(last) -> ContributionDateValidation(error = "开始日期不能晚于结束日期")
        last.isAfter(today) -> ContributionDateValidation(error = "结束日期不能晚于今天（UTC）")
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

/** Bounded, cancellable polling with a separate, paginated cumulative-count fallback. */
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
            status = ContributionStatus.Pending, message = "GitHub 正在生成贡献者列表，请稍后重试")
        Api.ensureOk(response)
        if (response.code != 200) throw ApiException(response.code, "贡献者列表尚未就绪 (${response.code})")
        val records = decodeContributors(response.body).take(3)
        return ContributionSnapshot(records, ContributionSource.ContributorList,
            if (records.isEmpty()) ContributionStatus.Empty else ContributionStatus.Ready)
    }

    suspend fun load(
        owner: String,
        name: String,
        force: Boolean = false,
        request: suspend (String, Boolean) -> Resp = { path, refresh -> Api.call("GET", path, force = refresh) },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): ContributionSnapshot {
        val base = "/repos/$owner/$name"
        var status = ContributionStatus.Pending
        var message = "GitHub 仍在生成周统计，请稍后重试"
        try {
            for (attempt in 0 until 4) {
                val response = request("$base/stats/contributors", force || attempt > 0)
                when (response.code) {
                    200 -> {
                        val records = Api.json.decodeFromString(ListSerializer(ContributionRecord.serializer()), response.body)
                        return ContributionSnapshot(records, status = if (records.isEmpty()) ContributionStatus.Empty else ContributionStatus.Ready)
                    }
                    204 -> return ContributionSnapshot(status = ContributionStatus.Empty)
                    202 -> if (attempt < 3) {
                        val retryAfter = response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1L, 8L)
                        pause((retryAfter ?: (1L shl attempt)) * 1000L)
                    }
                    else -> {
                        Api.ensureOk(response)
                        throw ApiException(response.code, "周统计返回了未支持的响应 (${response.code})")
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not spend more quota or repeat authentication failures on a second endpoint.
            if (error is ApiException && error.code in listOf(401, 403, 429, -1)) throw error
            status = if (error is ApiException && error.code == 202) ContributionStatus.Pending else ContributionStatus.Unavailable
            message = if (status == ContributionStatus.Pending) "GitHub 仍在生成周统计，请稍后重试"
                else "周统计暂不可用：${error.message?.take(180) ?: "请求失败"}"
        }
        val records = mutableListOf<ContributionRecord>()
        var truncated = false
        try {
            for (page in 1..5) {
                val response = request("$base/contributors?anon=1&per_page=100&page=$page", force)
                if (response.code == 204) break
                Api.ensureOk(response)
                if (response.code != 200) throw ApiException(response.code, "贡献者列表尚未就绪 (${response.code})")
                val pageRecords = decodeContributors(response.body)
                records += pageRecords
                val link = response.headers["Link"]
                val more = if (link != null) link.contains("rel=\"next\"") else pageRecords.size >= 100
                if (!more) break
                if (page == 5) truncated = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (records.isEmpty() && status != ContributionStatus.Pending) throw error
            truncated = records.isNotEmpty()
            message += "；贡献者列表未完整加载：${error.message?.take(120) ?: "请求失败"}"
        }
        return ContributionSnapshot(records, ContributionSource.ContributorList, status, message, truncated)
    }

    private fun decodeContributors(body: String): List<ContributionRecord> {
        val array = Api.json.parseToJsonElement(body) as? JsonArray
            ?: throw IllegalStateException("贡献者列表数据格式无效")
        return array.map { element ->
            val item = element as? JsonObject ?: throw IllegalStateException("贡献者数据格式无效")
            val user = Api.json.decodeFromJsonElement(User.serializer(), JsonObject(item.filterKeys { it != "contributions" }))
            ContributionRecord(user, item["contributions"]?.jsonPrimitive?.longOrNull.validCount())
        }
    }
}
