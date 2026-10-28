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
import com.mobilegh.i18n.tr

enum class NodeType { Direct, JsDelivr, Prefix }

@Serializable
data class CdnNode(val id: String, val name: String, val type: NodeType, val base: String, val custom: Boolean = false)

/** 一次下载尝试：使用哪个节点、该节点上的实际地址 */
@Serializable
data class DownloadTarget(val nodeId: String, val nodeName: String, val url: String)

/**
 * 网络加速：为 raw 文件、README 图片、Release 下载、克隆地址选择最快的 CDN / 代理节点。
 *
 * 安全约束：加速节点只用于公开内容，请求中永远不会携带 Token（见 Api 拦截器的域名白名单）；
 * 私有仓库或加速失败时自动回退为直连 GitHub。
 */
object Net {
    val DIRECT = CdnNode("direct", tr("直连 GitHub"), NodeType.Direct, "")
    private val JSD = CdnNode("jsd", "jsDelivr", NodeType.JsDelivr, "https://cdn.jsdelivr.net")

    /**
     * 内置节点。每个都经过多轮实测（原文件、Release 下载、git clone 入口各取多次样本）后才收录；
     * 反复出错或几乎不可用的（如 ghproxy.net：下载与克隆接近 0%）已经移除。
     * 实际使用哪个由启动时的自动测速决定，失败的节点会被自动降级。
     */
    val builtins = listOf(
        DIRECT,
        JSD,
        CdnNode("jsd-fastly", "jsDelivr · Fastly", NodeType.JsDelivr, "https://fastly.jsdelivr.net"),
        CdnNode("jsd-gcore", "jsDelivr · Gcore", NodeType.JsDelivr, "https://gcore.jsdelivr.net"),
        CdnNode("jsd-cf", "jsDelivr · Cloudflare", NodeType.JsDelivr, "https://testingcf.jsdelivr.net"),
        CdnNode("jsd-mirror", "jsDelivr · jsdmirror.com", NodeType.JsDelivr, "https://cdn.jsdmirror.com"),
        CdnNode("jsd-mirror-cn", "jsDelivr · jsdmirror.cn", NodeType.JsDelivr, "https://cdn.jsdmirror.cn"),
        CdnNode("ghfast", "ghfast.top", NodeType.Prefix, "https://ghfast.top/"),
        CdnNode("gh-proxy", "gh-proxy.com", NodeType.Prefix, "https://gh-proxy.com/"),
        CdnNode("gh-proxy-org", "gh-proxy.org", NodeType.Prefix, "https://gh-proxy.org/"),
        CdnNode("llkk", "gh.llkk.cc", NodeType.Prefix, "https://gh.llkk.cc/"),
        CdnNode("monlor", "gh.monlor.com", NodeType.Prefix, "https://gh.monlor.com/"),
        CdnNode("ghm", "ghm.078465.xyz", NodeType.Prefix, "https://ghm.078465.xyz/"),
        CdnNode("imciel", "ghproxy.imciel.com", NodeType.Prefix, "https://ghproxy.imciel.com/"),
    )

    /** 未测速前的默认值：取自实测延迟最低且稳定的节点 */
    private const val DEFAULT_RAW = "jsd-cf"
    private const val DEFAULT_DL = "gh-proxy"

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

    /** 保存的节点 id 已从内置列表下线时回退到默认值，避免一直用一个连不上的地址 */
    private fun validOr(id: String?, fallback: String): String =
        id?.takeIf { saved -> nodes.any { it.id == saved } } ?: fallback

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("net", Context.MODE_PRIVATE)
        mode = prefs.getInt("mode", 0)
        apiProxy = prefs.getString("api", "") ?: ""
        lastTest = prefs.getLong("lastTest", 0)
        // 先恢复自定义节点，否则下面校验 id 时会把自定义节点误判成已下线
        prefs.getString("custom", null)?.let {
            runCatching { custom.addAll(Api.plain.decodeFromString(ListSerializer(CdnNode.serializer()), it)) }
        }
        // 保存过的节点可能已经下线（内置列表里删掉了），回退到默认值而不是留着一个用不了的 id
        manualId = validOr(prefs.getString("manual", DEFAULT_RAW), DEFAULT_RAW)
        bestRawId = validOr(prefs.getString("bestRaw", DEFAULT_RAW), DEFAULT_RAW)
        bestDlId = validOr(prefs.getString("bestDl", DEFAULT_DL), DEFAULT_DL)
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

    /** 本次运行中最近一次下载成功的节点；失败过的节点在下次选择时排到最后 */
    @Volatile private var downloadOkId: String? = null
    private val downloadFailed = Collections.synchronizedSet(HashSet<String>())

    fun markDownloadOk(id: String) { downloadOkId = id; downloadFailed.remove(id) }
    fun markDownloadFail(id: String) { downloadFailed.add(id); if (downloadOkId == id) downloadOkId = null }

    /**
     * 下载候选地址：按节点顺序排列，第一个失败就自动换下一个，直连 GitHub 永远兜底。
     *
     * 手动选定的节点只按用户的选择来（不擅自换），关闭加速时只有直连。
     */
    fun downloadTargets(url: String): List<DownloadTarget> {
        if (mode == 2 || !isGithubDownload(url)) return listOf(DownloadTarget(DIRECT.id, DIRECT.name, url))
        val preferred = dlNode()
        val candidates = if (mode == 1) listOf(preferred) else nodes
        return orderDownloadTargets(candidates, url, latency, downloadFailed, downloadOkId ?: preferred.id)
    }

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

    /**
     * 按地址类型分别记住图片节点的表现。
     *
     * 同一个代理对 raw 文件、头像和 camo 图片的支持完全不同：实测 ghfast 等代理对
     * `avatars.githubusercontent.com` 直接返回 403，gh-proxy 对 camo 返回 404。如果只用一个
     * 全局「最近成功节点」，头像就会反复先撞上 raw 上很快、对头像却不可用的节点，每张图都要
     * 白白等一次失败；反过来，raw 图片也不该被头像的结果带偏。
     */
    private val imageOk = Collections.synchronizedMap(HashMap<String, String>())
    private val imageFailedByClass = Collections.synchronizedMap(HashMap<String, MutableMap<String, Long>>())

    /** 失败只是暂时降级：网络抖动后过一会儿仍会重新尝试，避免一个瞬时超时永久废掉一个节点 */
    private const val IMAGE_FAIL_TTL_MS = 2 * 60 * 1000L

    fun imageClass(url: String): String {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return "other"
        return when {
            host == "raw.githubusercontent.com" || host == "gist.githubusercontent.com" -> "raw"
            host == "avatars.githubusercontent.com" -> "avatar"
            host == "camo.githubusercontent.com" -> "camo"
            else -> "other"
        }
    }

    @Synchronized
    fun markImageOk(url: String, id: String) {
        val cls = imageClass(url)
        imageOk[cls] = id
        imageFailedByClass[cls]?.remove(id)
    }

    @Synchronized
    fun markImageFail(url: String, id: String) {
        val cls = imageClass(url)
        imageFailedByClass.getOrPut(cls) { HashMap() }[id] = System.currentTimeMillis()
        if (imageOk[cls] == id) imageOk.remove(cls)
    }

    /** 当前仍在「冷却」中的失败节点 */
    private fun currentFailures(cls: String): Set<String> {
        val now = System.currentTimeMillis()
        val map = imageFailedByClass[cls] ?: return emptySet()
        map.entries.removeAll { now - it.value > IMAGE_FAIL_TTL_MS }
        return map.keys.toSet()
    }

    private fun isGithubImageHost(host: String) = host == "avatars.githubusercontent.com" ||
        host == "camo.githubusercontent.com" ||
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
     *
     * 本轮已经被证明与同类资源不兼容的节点（见 [markImageFail]）会被直接跳过，不再白等一次失败。
     */
    fun imageCandidates(url: String): List<Pair<String, String>> {
        if (mode == 2 || requiresDirectImage(url)) return listOf(DIRECT.id to url)
        val raw = parseRaw(url)
        val host = runCatching { URI(url).host }.getOrNull() ?: return listOf(DIRECT.id to url)
        if (raw == null && !isGithubImageHost(host) &&
            !(host == "github.com" && url.contains("/user-attachments/"))
        ) return listOf(DIRECT.id to url)
        if (raw != null && isPrivate(raw[0], raw[1])) return listOf(DIRECT.id to url)
        val cls = imageClass(url)
        val failed = synchronized(this) { currentFailures(cls) }
        val okId = synchronized(this) { imageOk[cls] }
        val list = imageCandidates(url, raw, failed, okId)
        // camo（README 里外链图片的缓存地址）只由 GitHub 自己提供；实测公共代理对它一律 403/404，
        // 先试代理只会给每张 README 图片白加几秒延迟，因此直连优先。
        return if (cls == "camo") list.sortedBy { if (it.first == DIRECT.id) 0 else 1 } else list
    }

    private fun imageCandidates(
        url: String,
        raw: List<String>?,
        failed: Set<String>,
        okId: String?,
    ): List<Pair<String, String>> {
        val ordered = nodes.filter { it.type != NodeType.Direct && it.id !in failed }.sortedWith(
            compareBy<CdnNode>(
                { it.id != okId },
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

/**
 * Release 资源的下载地址。
 *
 * github.com/…/releases/download/… 是网页地址，私有仓库只认浏览器会话；带 API token
 * 请求一律返回 404（公开仓库正常 302）。私有仓库必须走 API 资源端点，并带
 * `Accept: application/octet-stream`，见 [Downloads.resolvePrivateUrl]。
 */
fun releaseAssetDownloadUrl(owner: String, name: String, assetId: Long, browserUrl: String, isPrivate: Boolean): String =
    if (isPrivate && assetId > 0) "https://api.github.com/repos/$owner/$name/releases/assets/$assetId" else browserUrl

/** 文件下载地址：私有仓库的 github.com/…/raw/… 同样只认浏览器会话，改为 raw 主机 + token */
fun repoFileDownloadUrl(owner: String, name: String, ref: String, path: String, isPrivate: Boolean): String {
    // ref 和路径里的 / 是分隔符，其余保留字符必须编码，否则 #、? 会改变 URL 的含义。
    val encodedRef = Api.encodePath(ref)
    val encodedPath = Api.encodePath(path)
    return if (isPrivate) "https://raw.githubusercontent.com/$owner/$name/$encodedRef/$encodedPath"
    else "https://github.com/$owner/$name/raw/$encodedRef/$encodedPath"
}

/**
 * 源码压缩包下载地址，支持 zip 和 tar.gz。
 *
 * 公开仓库走网页 archive 地址，转发类加速节点同样能改写；私有仓库换成 API 的
 * zipball/tarball 端点，和 raw 一样带 Token 请求网页地址不可靠。
 * 压缩包是 GitHub 收到请求时现打包的，地址本身不能预测大小。
 */
fun repoArchiveDownloadUrl(owner: String, name: String, ref: String, extension: String, isPrivate: Boolean): String {
    require(extension == "zip" || extension == "tar.gz") { tr("不支持的源码压缩包格式") }
    // 保留 ref 里的 / 分隔，其余保留字符必须编码，否则 # 会变成 URL 片段。
    val encodedRef = Api.encodePath(ref)
    if (isPrivate) {
        val endpoint = if (extension == "zip") "zipball" else "tarball"
        return "https://api.github.com/repos/$owner/$name/$endpoint/$encodedRef"
    }
    return "https://github.com/$owner/$name/archive/$encodedRef.$extension"
}

fun repoZipDownloadUrl(owner: String, name: String, ref: String, isPrivate: Boolean): String =
    repoArchiveDownloadUrl(owner, name, ref, "zip", isPrivate)

/**
 * 私有下载要发的 Accept。
 *
 * Actions 产物和源码打包端点都只接受 JSON（源码打包端发 octet-stream 会得 415，
 * 实测 API 会直接拒绝整个请求），其余下载端点要 octet-stream。
 */
fun privateDownloadAccept(url: String): String =
    if (url.contains("/actions/artifacts/") || url.contains("/zipball/") || url.contains("/tarball/")) "application/vnd.github+json"
    else "application/octet-stream"

/**
 * 下载地址按节点排序：上次成功的 → 当前选中的 → 测速快的 → 未测速的 → 失败过的，
 * 直连 GitHub 永远排在最后兜底。
 *
 * 只有转发类（Prefix）节点能代理 Release 资源和源码包：jsDelivr 不提供这些文件，
 * 直连节点不需要改写地址。
 */
fun orderDownloadTargets(
    candidates: List<CdnNode>,
    url: String,
    latency: Map<String, Long>,
    failedNodes: Set<String>,
    preferredId: String,
    direct: CdnNode = CdnNode("direct", tr("直连 GitHub"), NodeType.Direct, ""),
): List<DownloadTarget> {
    val ordered = candidates.filter { it.type == NodeType.Prefix }.sortedWith(
        compareBy<CdnNode>(
            { it.id in failedNodes },
            { it.id != preferredId },
            { latency[it.id]?.takeIf { l -> l >= 0 } ?: Long.MAX_VALUE },
        ),
    )
    val list = ordered.map { DownloadTarget(it.id, it.name, it.base + url) }
    return (list + DownloadTarget(direct.id, direct.name, url)).distinctBy { it.url }
}
