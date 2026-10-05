package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Achievement(
    val name: String,
    val imageUrl: String,
    val tier: String = "default",
    /** GitHub's displayed badge multiplier, not a contribution/event count. */
    val count: Int = 1,
    val slug: String = "",
)

/** Public GitHub webpage list; never a local list and never written by this scraper. */
data class StarList(
    val name: String,
    val slug: String,
    val description: String? = null,
    val count: Int = 0,
    val url: String,
)

/**
 * Best-effort public HTML reads. No PAT, WebView cookies, login session, or CSRF writes.
 * Achievements may be hidden; GitHub lists also have a documented GraphQL UserList API:
 * https://docs.github.com/en/graphql/reference/users#userlist
 */
object Web {
    internal const val MAX_HTML_BYTES = 2 * 1024 * 1024
    private const val MAX_BADGES = 32
    private const val MAX_LISTS = 100
    private val github = "https://github.com/".toHttpUrl()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun html(login: String, stars: Boolean): String = withContext(Dispatchers.IO) {
        require(LOGIN.matches(login)) { "无效的 GitHub 用户名" }
        val url = github.newBuilder().addPathSegment(login).apply {
            if (stars) addQueryParameter("tab", "stars")
        }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; MobileGH; public profile reader)")
            .header("Accept", "text/html")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "公开网页加载失败 (${response.code})")
            val finalUrl = response.request.url
            if (finalUrl.host != "github.com" || finalUrl.pathSegments.firstOrNull() in setOf("login", "session", "sessions")) {
                throw ApiException(403, "此内容需要 GitHub 网页登录；这里只读取公开内容")
            }
            val body = response.body
            if (body.contentType()?.subtype?.contains("html") != true) {
                throw ApiException(502, "GitHub 返回了无法识别的网页")
            }
            if (body.contentLength() > MAX_HTML_BYTES) throw ApiException(413, "GitHub 网页超过读取上限")
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (bytes.size() + n > MAX_HTML_BYTES) throw ApiException(413, "GitHub 网页超过读取上限")
                    bytes.write(buffer, 0, n)
                }
            }
            bytes.toByteArray().toString(body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
        }
    }

    // Retain the existing profile call signature. self never grants access to browser cookies.
    @Suppress("UNUSED_PARAMETER")
    suspend fun achievements(login: String, self: Boolean = false): List<Achievement> {
        val page = html(login, stars = false)
        return withContext(Dispatchers.Default) { parseAchievements(page) }
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun starLists(login: String, self: Boolean = false): List<StarList> {
        val page = html(login, stars = true)
        return withContext(Dispatchers.Default) { parseLists(page, login) }
    }

    private val LOGIN = Regex("[A-Za-z0-9-]{1,39}")
    private val SLUG = Regex("[a-zA-Z0-9_-]{1,160}")
    private val LIST_SLUG = Regex("[\\p{L}\\p{N}_-]{1,160}")
    private const val TAG_ATTRIBUTES = "(?:\"[^\"]*\"|'[^']*'|[^'\">]++)*"
    private val ANCHOR = Regex("<a\\b($TAG_ATTRIBUTES)>(.*?)</a\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val IMAGE = Regex("<img\\b($TAG_ATTRIBUTES)>", RegexOption.IGNORE_CASE)
    private val ATTRIBUTE = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val TIER = Regex("-(default|bronze|silver|gold)(?:-|\\.)", RegexOption.IGNORE_CASE)
    // Lookahead also finds a label/description nested inside layout wrappers.
    private val LABEL = Regex("(?=<([a-z][a-z0-9]*)\\b($TAG_ATTRIBUTES)>(.*?)</\\1\\s*>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val MULTIPLIER = Regex("(?:[x×]\\s*)?([0-9]{1,9})", RegexOption.IGNORE_CASE)
    private val HEADING = Regex("<h[1-6]\\b$TAG_ATTRIBUTES>(.*?)</h[1-6]\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val REPOSITORY_COUNT = Regex("([0-9][0-9,]*)\\s+repositor(?:y|ies)\\b", RegexOption.IGNORE_CASE)
    private val IGNORED = Regex("<!--.*?-->|<(script|style)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TAG = Regex("<$TAG_ATTRIBUTES>")
    private val SPACE = Regex("\\s+")
    private val ENTITY = Regex("&(#(?:[xX][0-9a-fA-F]+|[0-9]+)|amp|lt|gt|quot|apos|nbsp);")

    /** Only GitHub's achievement image markup, deduplicated by slug across responsive layouts. */
    internal fun parseAchievements(page: String): List<Achievement> {
        val out = LinkedHashMap<String, Achievement>()
        for (anchor in ANCHOR.findAll(IGNORED.replace(page.take(MAX_HTML_BYTES), ""))) {
            val url = githubUrl(attributes(anchor.groupValues[1])["href"] ?: continue) ?: continue
            val slug = (url.queryParameter("achievement")
                ?: url.pathSegments.dropWhile { it != "achievements" }.getOrNull(1))
                ?.takeIf { SLUG.matches(it) }?.lowercase(Locale.ROOT) ?: continue
            if (anchor.groupValues[2].length > 16_384) continue
            val inner = anchor.groupValues[2]
            val img = IMAGE.findAll(inner).map { attributes(it.groupValues[1]) }.firstOrNull {
                it["data-hovercard-type"] == "achievement" ||
                    it["class"].orEmpty().split(SPACE).any { c -> c.startsWith("achievement-badge") }
            } ?: continue
            val name = img["alt"]?.substringAfter("Achievement:", "")?.trim()?.take(100)?.takeIf { it.isNotEmpty() } ?: continue
            val imageUrl = github.resolve(img["src"] ?: img["data-src"] ?: continue)?.takeIf {
                it.scheme == "https" && it.host in setOf("github.githubassets.com", "github.com") &&
                    it.username.isEmpty() && it.password.isEmpty() && it.port == 443
            } ?: continue
            val tier = TIER.find(imageUrl.encodedPath)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: "default"
            val label = LABEL.findAll(inner).firstOrNull {
                attributes(it.groupValues[2])["class"].orEmpty().split(SPACE).contains("achievement-tier-label")
            }
            val count = label?.let { MULTIPLIER.matchEntire(text(it.groupValues[3]))?.groupValues?.get(1)?.toIntOrNull() }
                ?.coerceAtLeast(1) ?: 1
            val badge = Achievement(name, imageUrl.toString(), tier, count, slug)
            val old = out[slug]
            if (old == null || tierRank(badge.tier) > tierRank(old.tier) ||
                (badge.tier == old.tier && badge.count > old.count)
            ) out[slug] = badge
            if (out.size >= MAX_BADGES) break
        }
        return out.values.toList()
    }

    internal fun parseLists(page: String, login: String): List<StarList> {
        if (!LOGIN.matches(login)) return emptyList()
        val out = LinkedHashMap<String, StarList>()
        for (anchor in ANCHOR.findAll(IGNORED.replace(page.take(MAX_HTML_BYTES), ""))) {
            val url = githubUrl(attributes(anchor.groupValues[1])["href"] ?: continue) ?: continue
            val path = url.pathSegments
            if (path.size != 4 || path[0] != "stars" || !path[1].equals(login, true) || path[2] != "lists" || !LIST_SLUG.matches(path[3])) continue
            val slug = path[3]
            val inner = anchor.groupValues[2].take(16_384)
            val plain = text(inner)
            val name = HEADING.find(inner)?.groupValues?.get(1)?.let(::text)?.takeIf { it.isNotEmpty() }
                ?: plain.replace(REPOSITORY_COUNT, "").trim().takeIf { it.isNotEmpty() }
                ?: slug.replace('-', ' ')
            val count = REPOSITORY_COUNT.find(plain)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull() ?: 0
            val desc = LABEL.findAll(inner).firstOrNull {
                "color-fg-muted" in attributes(it.groupValues[2])["class"].orEmpty().split(SPACE) &&
                    !REPOSITORY_COUNT.containsMatchIn(text(it.groupValues[3]))
            }?.groupValues?.get(3)?.let(::text)?.take(280)?.takeIf { it.isNotEmpty() }
            val canonical = github.newBuilder().addPathSegment("stars").addPathSegment(login)
                .addPathSegment("lists").addPathSegment(slug).build().toString()
            val list = StarList(name.take(100), slug, desc, count, canonical)
            val old = out[slug]
            if (old == null || list.count > old.count || (old.description == null && list.description != null)) out[slug] = list
            if (out.size >= MAX_LISTS) break
        }
        return out.values.toList()
    }

    private fun tierRank(tier: String) = when (tier) { "gold" -> 3; "silver" -> 2; "bronze" -> 1; else -> 0 }

    private fun githubUrl(value: String): HttpUrl? = github.resolve(value)?.takeIf {
        it.scheme == "https" && it.host == "github.com" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
    }

    private fun attributes(value: String): Map<String, String> = ATTRIBUTE.findAll(value).associate {
        it.groupValues[1].lowercase(Locale.ROOT) to unescape(it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty())
    }

    private fun text(value: String) = SPACE.replace(unescape(TAG.replace(value, " ")), " ").trim()

    internal fun unescape(value: String): String = ENTITY.replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
            else -> {
                val number = if (entity.startsWith("#x", true)) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                number?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF && it > 0 }
                    ?.let { String(Character.toChars(it)) } ?: match.value
            }
        }
    }
}
