package com.mobilegh.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit

enum class NodeType { Direct, JsDelivr, Prefix }

@Serializable
data class CdnNode(val id: String, val name: String, val type: NodeType, val base: String, val custom: Boolean = false)

/**
 * 网络加速：为 raw 文件、README 图片、Release 下载、克隆地址选择最快的 CDN / 代理节点。
 *
 * 安全约束：加速节点只用于公开内容，请求中永远不会携带 Token（见 Api 拦截器的域名白名单）；
 * 私有仓库或加速失败时自动回退为直连 GitHub。
 */
object Net {
    val DIRECT = CdnNode("direct", "直连 GitHub", NodeType.Direct, "")
    private val JSD = CdnNode("jsd", "jsDelivr", NodeType.JsDelivr, "https://cdn.jsdelivr.net")

    val builtins = listOf(
        DIRECT,
        JSD,
        CdnNode("jsd-fastly", "jsDelivr · Fastly", NodeType.JsDelivr, "https://fastly.jsdelivr.net"),
        CdnNode("jsd-gcore", "jsDelivr · Gcore", NodeType.JsDelivr, "https://gcore.jsdelivr.net"),
        CdnNode("jsd-cf", "jsDelivr · Cloudflare", NodeType.JsDelivr, "https://testingcf.jsdelivr.net"),
        CdnNode("ghfast", "ghfast.top", NodeType.Prefix, "https://ghfast.top/"),
        CdnNode("ghproxy-net", "ghproxy.net", NodeType.Prefix, "https://ghproxy.net/"),
        CdnNode("gh-proxy", "gh-proxy.com", NodeType.Prefix, "https://gh-proxy.com/"),
        CdnNode("gh-proxy-org", "gh-proxy.org", NodeType.Prefix, "https://gh-proxy.org/"),
        CdnNode("llkk", "gh.llkk.cc", NodeType.Prefix, "https://gh.llkk.cc/"),
        CdnNode("gh-jason", "gh.jasonzeng.dev", NodeType.Prefix, "https://gh.jasonzeng.dev/"),
    )

    /** 未测速前的默认值：最稳定的节点 */
    private const val DEFAULT_RAW = "jsd"
    private const val DEFAULT_DL = "ghfast"

    private const val TEST_PATH = "github/gitignore/main/Global/macOS.gitignore"
    private const val TEST_MARK = "DS_Store"

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var mode by mutableIntStateOf(0) // 0 自动 1 手动 2 关闭
    var manualId by mutableStateOf(DEFAULT_RAW)
    var apiProxy by mutableStateOf("")
    val custom = mutableStateListOf<CdnNode>()

    /** 最近一次测速结果（毫秒，-1 表示不可用） */
    val latency = mutableStateMapOf<String, Long>()
    var testing by mutableStateOf(false)
    var bestRawId by mutableStateOf(DEFAULT_RAW)
    var bestDlId by mutableStateOf(DEFAULT_DL)
    var lastTest by mutableLongStateOf(0L)

    private val privateRepos = Collections.synchronizedSet(HashSet<String>())

    val nodes: List<CdnNode> get() = builtins + custom

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("net", Context.MODE_PRIVATE)
        mode = prefs.getInt("mode", 0)
        manualId = prefs.getString("manual", DEFAULT_RAW) ?: DEFAULT_RAW
        apiProxy = prefs.getString("api", "") ?: ""
        bestRawId = prefs.getString("bestRaw", DEFAULT_RAW) ?: DEFAULT_RAW
        bestDlId = prefs.getString("bestDl", DEFAULT_DL) ?: DEFAULT_DL
        lastTest = prefs.getLong("lastTest", 0)
        prefs.getString("custom", null)?.let {
            runCatching { custom.addAll(Api.plain.decodeFromString(ListSerializer(CdnNode.serializer()), it)) }
        }
        prefs.getString("latency", null)?.let {
            runCatching { latency.putAll(Api.plain.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), it)) }
        }
    }

    // ---------------- API 地址 ----------------

    val apiBase: String get() = apiProxy.trim().trimEnd('/').ifEmpty { "https://api.github.com" }
    val apiHost: String get() = runCatching { URI(apiBase).host }.getOrNull() ?: "api.github.com"

    // ---------------- 节点选择 ----------------

    private fun byId(id: String) = nodes.firstOrNull { it.id == id }

    fun rawNode(): CdnNode = when (mode) {
        2 -> DIRECT
        1 -> byId(manualId) ?: DIRECT
        else -> byId(bestRawId) ?: JSD
    }

    fun dlNode(): CdnNode = when (mode) {
        2 -> DIRECT
        1 -> byId(manualId)?.takeIf { it.type != NodeType.JsDelivr } ?: byId(bestDlId) ?: DIRECT
        else -> byId(bestDlId) ?: DIRECT
    }

    /** 当前下载使用的节点，供下载日志和设置页显示。 */
    fun downloadNode(): CdnNode = dlNode()

    fun markPrivate(fullName: String) {
        privateRepos.add(fullName.lowercase())
    }

    private fun isPrivate(o: String, r: String) = "$o/$r".lowercase() in privateRepos

    /** 该仓库是否已知为私有（用于下载时决定直连+带 Token 还是走加速） */
    fun isPrivateRepo(fullName: String) = fullName.lowercase() in privateRepos

    private fun build(node: CdnNode, o: String, r: String, ref: String, path: String): String? = when (node.type) {
        NodeType.Direct -> "https://raw.githubusercontent.com/$o/$r/$ref/$path"
        NodeType.JsDelivr -> if (ref == "HEAD") null else "${node.base}/gh/$o/$r@$ref/$path"
        NodeType.Prefix -> "${node.base}https://raw.githubusercontent.com/$o/$r/$ref/$path"
    }

    /** 解析 raw.githubusercontent.com 链接 */
    private fun parseRaw(url: String): List<String>? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        if (u.host != "raw.githubusercontent.com") return null
        val segs = (u.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        return if (segs.size >= 4) segs else null
    }

    /** 图片 / 资源：返回按优先级排列的候选地址，最后一个总是直连 */
    fun rawCandidates(rawUrl: String): List<String> {
        val s = parseRaw(rawUrl) ?: return listOf(rawUrl)
        if (isPrivate(s[0], s[1])) return listOf(rawUrl)
        val node = rawNode()
        val list = ArrayList<String>(2)
        if (node.type != NodeType.Direct) build(node, s[0], s[1], s[2], s.drop(3).joinToString("/"))?.let(list::add)
        list.add(rawUrl)
        return list
    }

    // ---------------- 图片 ----------------

    /** 本次运行中最近一次成功加载图片的节点；失败过的节点排到最后 */
    @Volatile private var imageOkId: String? = null
    private val imageFailed = Collections.synchronizedSet(HashSet<String>())

    fun markImageOk(id: String) { imageOkId = id; imageFailed.remove(id) }
    fun markImageFail(id: String) { imageFailed.add(id); if (imageOkId == id) imageOkId = null }

    private fun isGithubImageHost(host: String) = host == "camo.githubusercontent.com" ||
        host == "user-images.githubusercontent.com" || host == "private-user-images.githubusercontent.com" ||
        host == "objects.githubusercontent.com"

    /** 签名 URL 本身就是凭据，不能转发给公共镜像。 */
    fun requiresDirectImage(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return true
        if (uri.scheme != "https" || uri.host == "private-user-images.githubusercontent.com" ||
            uri.host == "objects.githubusercontent.com") return true
        val privateQuery = uri.rawQuery.orEmpty().split('&').any {
            val key = runCatching { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") }.getOrDefault("").lowercase()
            key in setOf("jwt", "token", "access_token", "auth", "signature", "sig", "policy", "credential", "expires") ||
                key.startsWith("x-amz-") || key.startsWith("x-goog-")
        }
        if (privateQuery) return true
        val raw = parseRaw(url)
        return raw != null && isPrivate(raw[0], raw[1])
    }

    /**
     * 图片候选地址 (节点 id, 地址)。
     *
     * 只试一个加速节点 + 直连时，只要那个节点在当前网络不通（jsDelivr 在国内常被污染），图片就会裂开。
     * 这里依次尝试所有加速节点：上次成功的 → 当前选中的 → 按测速快慢 → 未测速的 → 失败过的，最后直连。
     */
    fun imageCandidates(url: String): List<Pair<String, String>> {
        if (mode == 2 || requiresDirectImage(url)) return listOf(DIRECT.id to url)
        val raw = parseRaw(url)
        val host = runCatching { URI(url).host }.getOrNull() ?: return listOf(DIRECT.id to url)
        if (raw == null && !isGithubImageHost(host) &&
            !(host == "github.com" && url.contains("/user-attachments/"))
        ) return listOf(DIRECT.id to url)
        if (raw != null && isPrivate(raw[0], raw[1])) return listOf(DIRECT.id to url)

        val ordered = nodes.filter { it.type != NodeType.Direct }.sortedWith(
            compareBy<CdnNode>(
                { it.id in imageFailed },
                { it.id != imageOkId },
                { it.id != rawNode().id },
                { latency[it.id]?.takeIf { l -> l >= 0 } ?: Long.MAX_VALUE },
            ),
        )
        val list = ArrayList<Pair<String, String>>()
        for (n in ordered) {
            val u = if (raw != null) {
                build(n, raw[0], raw[1], raw[2], raw.drop(3).joinToString("/"))?.let { candidate ->
                    URI(url).rawQuery?.let { "$candidate?$it" } ?: candidate
                }
            } else if (n.type == NodeType.Prefix) n.base + url else null
            if (u != null) list += n.id to u
        }
        list += DIRECT.id to url
        return list.distinctBy { it.second }
    }

    /** 源码文本：jsDelivr 会缓存分支内容，因此只使用实时转发的代理节点 */
    fun freshRawUrl(o: String, r: String, ref: String, encodedPath: String): String? {
        if (mode == 2 || isPrivate(o, r)) return null
        val node = rawNode().let { if (it.type == NodeType.JsDelivr) dlNode() else it }
        return if (node.type == NodeType.Prefix) build(node, o, r, ref, encodedPath) else null
    }

    private fun isGithubDownload(url: String) = url.startsWith("https://github.com/") || url.startsWith("https://raw.githubusercontent.com/") ||
        url.startsWith("https://gist.githubusercontent.com/") || url.startsWith("https://codeload.github.com/")

    /** Release 资源 / 源码压缩包下载链接 */
    fun download(url: String): String {
        val n = dlNode()
        return if (n.type == NodeType.Prefix && isGithubDownload(url)) n.base + url else url
    }

    /** 加速克隆地址（仅代理类节点支持 git clone） */
    fun cloneUrl(httpsUrl: String): Pair<String, String>? {
        val n = dlNode()
        return if (n.type == NodeType.Prefix) n.name to n.base + httpsUrl else null
    }

    // ---------------- 设置 ----------------

    fun changeMode(m: Int) { mode = m; prefs.edit().putInt("mode", m).apply() }
    fun setManual(id: String) { manualId = id; mode = 1; prefs.edit().putString("manual", id).putInt("mode", 1).apply() }
    fun setApi(url: String) { apiProxy = url.trim(); prefs.edit().putString("api", apiProxy).apply(); Api.clearCache() }

    fun addCustom(name: String, base: String): Boolean {
        val b = base.trim().let { if (it.endsWith("/")) it else "$it/" }
        if (!b.startsWith("https://")) return false
        val n = CdnNode("c${System.currentTimeMillis()}", name.ifBlank { URI(b).host ?: b }, NodeType.Prefix, b, custom = true)
        custom.add(n)
        saveCustom()
        speedTestAsync()
        return true
    }

    fun removeCustom(id: String) {
        custom.removeAll { it.id == id }
        saveCustom()
        if (manualId == id) changeMode(0)
    }

    private fun saveCustom() = prefs.edit().putString("custom", Api.plain.encodeToString(ListSerializer(CdnNode.serializer()), custom.toList())).apply()

    // ---------------- 测速 ----------------

    private val testClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private fun testUrl(n: CdnNode): String = when (n.type) {
        NodeType.Direct -> "https://raw.githubusercontent.com/$TEST_PATH"
        NodeType.JsDelivr -> "${n.base}/gh/github/gitignore@main/Global/macOS.gitignore"
        NodeType.Prefix -> "${n.base}https://raw.githubusercontent.com/$TEST_PATH"
    }

    private fun probe(url: String): Long {
        val start = System.nanoTime()
        return try {
            testClient.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").header("User-Agent", "MobileGH").build()).execute().use {
                val ok = it.isSuccessful && it.body.string().contains(TEST_MARK)
                if (ok) (System.nanoTime() - start) / 1_000_000 else -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** 每个节点测两次：两次都成功取平均；只成功一次视为不稳定并加罚时 */
    private fun score(n: CdnNode): Long {
        val a = probe(testUrl(n))
        val b = probe(testUrl(n))
        return when {
            a >= 0 && b >= 0 -> (a + b) / 2
            a >= 0 || b >= 0 -> maxOf(a, b) + 1500
            else -> -1
        }
    }

    fun speedTestAsync() {
        if (testing) return
        scope.launch { speedTest() }
    }

    suspend fun speedTest() {
        if (testing) return
        withContext(Dispatchers.Main) { testing = true }
        try {
            val list = nodes
            val results = coroutineScopeAll(list)
            withContext(Dispatchers.Main) {
                latency.clear()
                latency.putAll(results)
                fun pick(cands: List<CdnNode>, fallback: String): String {
                    val ok = cands.filter { (results[it.id] ?: -1) >= 0 }
                    val best = ok.minByOrNull { results[it.id]!! } ?: return fallback
                    // 直连足够快（差距 < 80ms）时优先直连：内容最新、少一跳
                    val direct = results[DIRECT.id] ?: -1
                    return if (direct >= 0 && direct - results[best.id]!! < 80) DIRECT.id else best.id
                }
                bestRawId = pick(list, DEFAULT_RAW)
                // 下载优先使用 Prefix 代理；直连只作为所有下载代理都测速失败时的后备。
                val downloadProxies = list.filter { it.type == NodeType.Prefix }
                bestDlId = downloadProxies.filter { (results[it.id] ?: -1) >= 0 }
                    .minByOrNull { results[it.id] ?: Long.MAX_VALUE }?.id ?: DIRECT.id
                lastTest = System.currentTimeMillis()
                prefs.edit()
                    .putString("bestRaw", bestRawId)
                    .putString("bestDl", bestDlId)
                    .putLong("lastTest", lastTest)
                    .putString("latency", Api.plain.encodeToString(MapSerializer(String.serializer(), Long.serializer()), results))
                    .apply()
            }
        } finally {
            withContext(Dispatchers.Main) { testing = false }
        }
    }

    private suspend fun coroutineScopeAll(list: List<CdnNode>): Map<String, Long> = kotlinx.coroutines.coroutineScope {
        list.map { n -> async(Dispatchers.IO) { n.id to score(n) } }.awaitAll().toMap()
    }

    fun nodeName(id: String) = byId(id)?.name ?: id
}
