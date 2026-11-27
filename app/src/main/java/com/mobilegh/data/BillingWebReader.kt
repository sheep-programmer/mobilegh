package com.mobilegh.data

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * 从 GitHub 网页端计费页面提取 Actions 分钟数、存储与 Packages 用量。
 * 供新版计费平台在 REST `/settings/billing/actions` 返回 410 且 Token 未授予 `Plan:read` 时兜底使用；
 * 支持解析服务端 SSR 嵌入的 JSON 以及 WebView 渲染后的页面文本。
 */
object BillingWebReader {
    const val BILLING_URL = "https://github.com/settings/billing"
    private val CANDIDATE_URLS = listOf(
        "https://github.com/settings/billing",
        "https://github.com/settings/billing/usage",
    )

    suspend fun fetchFromWeb(
        planName: String? = null,
        cookieHeader: () -> String? = { runCatching { CookieManager.getInstance().getCookie("https://github.com") }.getOrNull() },
        client: OkHttpClient? = null,
    ): BillingUsage? = withContext(Dispatchers.IO) {
        val cookie = cookieHeader()
        if (!WebAttachmentUploader.hasSession(cookie)) return@withContext null
        val http = (client ?: Api.http).routed()
        for (url in CANDIDATE_URLS) {
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookie.orEmpty())
                .header("Accept", "text/html,application/xhtml+xml,application/json")
                .build()
            val html = runCatching {
                http.newCall(req).await().use { resp ->
                    if (resp.code in 200..299 && !resp.request.url.encodedPath.contains("/login")) {
                        resp.body.string()
                    } else null
                }
            }.getOrNull() ?: continue
            parseBillingHtml(html, planName)?.let { return@withContext it }
        }
        null
    }

    /** 解析计费网页 HTML（React embeddedData JSON 或 SSR 文本）。 */
    fun parseBillingHtml(html: String, planName: String? = null): BillingUsage? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html, "https://github.com")
        doc.select("script[data-target=react-app.embeddedData], script[type=application/json]")
            .mapNotNull { el -> runCatching { Api.plain.parseToJsonElement(el.data()) }.getOrNull() }
            .forEach { json ->
                parseEmbeddedJson(json, planName)?.let { return it }
            }
        return parseBillingText(doc.text(), planName)
    }

    /** 解析 GitHub 计费页面可见文本（如 WebView DOM innerText 或纯文本摘录）。 */
    fun parseBillingText(text: String, planName: String? = null): BillingUsage? {
        if (text.isBlank()) return null
        val normalized = text.replace(",", "").replace("\u00A0", " ")
        val actions = extractActionsFromText(normalized, planName)
        val storage = extractStorageFromText(normalized)
        val packages = extractPackagesFromText(normalized, planName)
        if (actions == null && storage == null && packages == null) return null
        return BillingUsage(actions = actions, storage = storage, packages = packages)
    }

    private fun extractActionsFromText(text: String, planName: String?): ActionsBilling? {
        val section = sliceSection(text, listOf("GitHub Actions", "Actions"), 400) ?: return null
        val pair = matchUsedAndLimit(section, listOf("min", "minute", "minutes", "分钟"))
            ?: matchUsedAndLimitGeneric(section, "\\bmin(?:utes)?\\b|\\bincluded minutes\\b")
            ?: return null
        val used = pair.first.toInt()
        val limit = pair.second.toInt().coerceAtLeast(1)
        val paid = (used - limit).coerceAtLeast(0)
        return ActionsBilling(
            totalMinutesUsed = used,
            totalPaidMinutesUsed = paid,
            includedMinutes = limit.takeIf { it > 0 } ?: BillingUsage.planIncludedActionsMinutes(planName),
        )
    }

    private fun extractStorageFromText(text: String): SharedStorageBilling? {
        val section = sliceSection(text, listOf("Shared Storage", "Actions and Packages storage", "Storage"), 350) ?: return null
        val mbMatch = Regex("""(\d+(?:\.\d+)?)\s*MB\s*(?:/|of)\s*(\d+(?:\.\d+)?)\s*(?:included\s*)?(MB|GB)""", RegexOption.IGNORE_CASE)
            .find(section)
        if (mbMatch != null) {
            val usedGb = (mbMatch.groupValues[1].toDoubleOrNull() ?: 0.0) / 1024.0
            return SharedStorageBilling(
                daysLeftInBillingCycle = daysLeftInCycle(),
                estimatedStorageForMonth = ((usedGb * 100.0).toInt() / 100.0),
            )
        }
        val gbMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:GB-hours?|GB)\s*(?:/|of)\s*(\d+(?:\.\d+)?)\s*(?:included\s*)?(?:GB-hours?|GB)""", RegexOption.IGNORE_CASE)
            .find(section)
            ?: Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*(?:included\s*)?(?:GB-hours?|GB)""", RegexOption.IGNORE_CASE).find(section)
            ?: return null
        val used = gbMatch.groupValues[1].toDoubleOrNull() ?: return null
        return SharedStorageBilling(
            daysLeftInBillingCycle = daysLeftInCycle(),
            estimatedStorageForMonth = ((used * 100.0).toInt() / 100.0),
        )
    }

    private fun extractPackagesFromText(text: String, planName: String?): PackagesBilling? {
        val section = sliceSection(text, listOf("Packages", "GitHub Packages"), 350) ?: return null
        val match = Regex("""(\d+(?:\.\d+)?)\s*(?:GB)?\s*(?:/|of)\s*(\d+(?:\.\d+)?)\s*(?:included\s*)?GB""", RegexOption.IGNORE_CASE)
            .find(section) ?: return null
        val used = match.groupValues[1].toDoubleOrNull() ?: return null
        val limit = match.groupValues[2].toDoubleOrNull() ?: BillingUsage.planIncludedPackagesGb(planName)
        return PackagesBilling(totalGigabytesBandwidthUsed = used, includedGigabytesBandwidth = limit)
    }

    private fun sliceSection(text: String, anchors: List<String>, maxLen: Int): String? {
        for (anchor in anchors) {
            val idx = text.indexOf(anchor, ignoreCase = true)
            if (idx >= 0) return text.substring(idx, (idx + maxLen).coerceAtMost(text.length))
        }
        return null
    }

    private fun matchUsedAndLimit(section: String, units: List<String>): Pair<Double, Double>? {
        val unitAlt = units.joinToString("|") { Regex.escape(it) }
        val r1 = Regex("""(\d+(?:\.\d+)?)\s*(?:$unitAlt)?\s*(?:/|of)\s*(\d+(?:\.\d+)?)\s*(?:included\s*)?(?:$unitAlt)""", RegexOption.IGNORE_CASE)
        r1.find(section)?.let { m ->
            val a = m.groupValues[1].toDoubleOrNull()
            val b = m.groupValues[2].toDoubleOrNull()
            if (a != null && b != null) return a to b
        }
        val r2 = Regex("""Used\s*[:：]?\s*(\d+(?:\.\d+)?)\s*(?:$unitAlt)?.*?Included\s*[:：]?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        r2.find(section)?.let { m ->
            val a = m.groupValues[1].toDoubleOrNull()
            val b = m.groupValues[2].toDoubleOrNull()
            if (a != null && b != null) return a to b
        }
        return null
    }

    private fun matchUsedAndLimitGeneric(section: String, unitRegex: String): Pair<Double, Double>? {
        val r = Regex("""(\d+(?:\.\d+)?)\s*(?:/|of)\s*(\d+(?:\.\d+)?)\s*(?:$unitRegex)""", RegexOption.IGNORE_CASE)
        val m = r.find(section) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        return a to b
    }

    private fun parseEmbeddedJson(root: JsonElement, planName: String?): BillingUsage? {
        findUsageItemsArray(root)?.let { items ->
            val decoded = runCatching {
                Api.plain.decodeFromJsonElement(BillingUsageReport.serializer(), JsonObject(mapOf("usageItems" to items)))
            }.getOrNull()
            if (decoded != null && decoded.usageItems.isNotEmpty()) {
                return BillingUsage.fromEnhancedReport(decoded, planName)
            }
        }
        val actionsObj = findObjectWithKeys(root, setOf("includedMinutes", "included_minutes"), setOf("totalMinutesUsed", "total_minutes_used", "minutesUsed", "minutes_used"))
        if (actionsObj != null) {
            val used = intField(actionsObj, "totalMinutesUsed", "total_minutes_used", "minutesUsed", "minutes_used") ?: 0
            val inc = intField(actionsObj, "includedMinutes", "included_minutes") ?: BillingUsage.planIncludedActionsMinutes(planName)
            val paid = intField(actionsObj, "totalPaidMinutesUsed", "total_paid_minutes_used") ?: (used - inc).coerceAtLeast(0)
            return BillingUsage(actions = ActionsBilling(used, paid, inc))
        }
        return null
    }

    private fun findUsageItemsArray(el: JsonElement): JsonArray? = when (el) {
        is JsonObject -> (el["usageItems"] as? JsonArray ?: el["usage_items"] as? JsonArray)?.takeIf { it.isNotEmpty() }
            ?: el.values.firstNotNullOfOrNull { findUsageItemsArray(it) }
        is JsonArray -> el.firstNotNullOfOrNull { findUsageItemsArray(it) }
        else -> null
    }

    private fun findObjectWithKeys(el: JsonElement, groupA: Set<String>, groupB: Set<String>): JsonObject? = when (el) {
        is JsonObject -> {
            if (el.keys.any { it in groupA } && el.keys.any { it in groupB }) el
            else el.values.firstNotNullOfOrNull { findObjectWithKeys(it, groupA, groupB) }
        }
        is JsonArray -> el.firstNotNullOfOrNull { findObjectWithKeys(it, groupA, groupB) }
        else -> null
    }

    private fun intField(obj: JsonObject, vararg names: String): Int? =
        names.firstNotNullOfOrNull { k -> (obj[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } }

    private fun daysLeftInCycle(): Int {
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        return (today.lengthOfMonth() - today.dayOfMonth).coerceAtLeast(0)
    }
}
