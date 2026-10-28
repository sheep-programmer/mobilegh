package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/** 一个待上传到 LFS 的对象：sha256（oid）与字节数 */
class LfsRef(val oid: String, val size: Long)

/** 本机要放进 LFS 的文件：[path] 是仓库里的路径，内容由 [open] 重新打开读取（可以反复调用） */
class LfsFile(val path: String, val name: String, val oid: String, val size: Long, val open: () -> InputStream?)

/** 批量接口给出的一个后续动作（上传或校验）：地址与必须原样带上的请求头 */
class LfsAction(val href: String, val headers: Map<String, String>)

/** 批量接口里一个对象的结果：[upload] 为空表示服务端已经有了；[error] 非空表示被拒绝 */
class LfsObject(val oid: String, val size: Long, val upload: LfsAction?, val verify: LfsAction?, val error: String?)

class LfsDigest(val oid: String, val size: Long)

/**
 * Git LFS 上传。GitHub 仓库单文件上限 100 MiB，更大的文件只能放进 LFS：
 * 仓库里提交的是一个几十字节的**指针文件**，真正的内容传到 LFS 存储。
 *
 * 流程（basic 传输，与官方 git-lfs 一致）：
 * 1. `POST {仓库}.git/info/lfs/objects/batch` 告诉服务端要传哪些对象（oid=sha256）；
 * 2. 服务端对每个还没有的对象返回上传地址（通常是对象存储的预签名 URL）和一组请求头；
 * 3. `PUT` 原始字节到那个地址，**只带服务端给的请求头**——GitHub 令牌只发给 github.com，
 *    绝不会发到存储服务器；
 * 4. 服务端若给了 verify 地址，再 `POST {oid,size}` 确认。
 */
object GitLfs {
    /** GitHub 免费账号的 LFS 单文件上限：2 GiB（更大的需要付费计划，这里按保守值拦截） */
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024

    const val POINTER_VERSION = "https://git-lfs.github.com/spec/v1"
    private const val MEDIA = "application/vnd.git-lfs+json"
    private val MEDIA_TYPE = MEDIA.toMediaType()

    /** 进度回调的最小字节间隔：2 GiB 的文件不能每 64 KiB 刷一次界面 */
    private const val REPORT_STEP = 512L * 1024

    /** 指针文件内容，格式固定，末尾有换行 */
    fun pointer(oid: String, size: Long): ByteArray =
        "version $POINTER_VERSION\noid sha256:$oid\nsize $size\n".toByteArray(Charsets.UTF_8)

    internal fun batchUrl(owner: String, name: String) = "${GitPack.GIT_HOST}/$owner/$name.git/info/lfs/objects/batch"

    /** 流式算 sha256 与长度，整份文件不进内存；超过 [limit] 立刻失败 */
    fun digest(open: () -> InputStream?, limit: Long = MAX_BYTES): LfsDigest {
        val input = open() ?: throw ApiException(-1, GitUpload.UNREADABLE)
        input.use {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                count += n
                if (count > limit) throw ApiException(413, trf("文件超过 {0} MB，超出 Git LFS 的单文件上限", limit / 1024 / 1024))
                md.update(buf, 0, n)
            }
            return LfsDigest(md.digest().joinToString("") { b -> "%02x".format(b) }, count)
        }
    }

    // ---------------- .gitattributes ----------------

    /**
     * 让 git-lfs 在克隆时还原大文件的规则行。只写**这个文件的精确路径**，不用通配符，
     * 避免把仓库里别的同后缀文件也改成 LFS。空格和通配符号都要转义。
     */
    fun attributesLine(path: String): String {
        val escaped = buildString {
            path.forEachIndexed { i, c ->
                when {
                    c == ' ' -> append("[[:space:]]")
                    c in "*?[]\\" -> append('\\').append(c)
                    i == 0 && (c == '#' || c == '!') -> append('\\').append(c)
                    else -> append(c)
                }
            }
        }
        return "$escaped filter=lfs diff=lfs merge=lfs -text"
    }

    /** 把 [paths] 的规则并进已有的 .gitattributes；没有任何变化时返回 null（不必再提交这个文件） */
    fun mergeAttributes(existing: String?, paths: List<String>): String? {
        val base = existing.orEmpty()
        val have = base.lineSequence().map { it.trim() }.toSet()
        val missing = paths.map(::attributesLine).distinct().filter { it !in have }
        if (missing.isEmpty()) return null
        return buildString {
            append(base)
            if (base.isNotEmpty() && !base.endsWith("\n")) append('\n')
            missing.forEach { append(it).append('\n') }
        }
    }

    // ---------------- 批量接口 ----------------

    internal fun batchBody(objects: List<LfsRef>, ref: String?): String = buildJsonObject {
        put("operation", "upload")
        put("transfers", JsonArray(listOf(JsonPrimitive("basic"))))
        ref?.let { r -> put("ref", buildJsonObject { put("name", r) }) }
        put("objects", JsonArray(objects.map { o -> buildJsonObject { put("oid", o.oid); put("size", o.size) } }))
        put("hash_algo", "sha256")
    }.toString()

    internal fun parseBatch(json: String): List<LfsObject> {
        val root = Api.json.parseToJsonElement(json).jsonObject
        return root["objects"]?.jsonArray.orEmpty().map { element ->
            val obj = element.jsonObject
            val actions = obj["actions"] as? JsonObject
            fun action(key: String): LfsAction? {
                val a = actions?.get(key) as? JsonObject ?: return null
                val href = a.str("href") ?: return null
                val headers = (a["header"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
                return LfsAction(href, headers)
            }
            val error = (obj["error"] as? JsonObject)?.let { it.str("message") ?: "code ${it.str("code")}" }
            LfsObject(
                obj.str("oid").orEmpty(), obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                action("upload"), action("verify"), error,
            )
        }
    }

    /**
     * 把 [files] 传到 LFS。相同 oid 只传一次；服务端已经有的对象跳过但算进进度。
     * [onProgress] 参数：已完成对象数、累计字节（含已存在的）、当前文件名。
     */
    suspend fun upload(
        client: OkHttpClient,
        token: String,
        owner: String,
        name: String,
        ref: String?,
        files: List<LfsFile>,
        onProgress: (done: Int, bytes: Long, current: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val unique = files.distinctBy { it.oid }
        if (unique.isEmpty()) return@withContext
        val results = batch(client, token, owner, name, ref, unique)
        var sent = 0L
        var done = 0
        for (f in unique) {
            currentCoroutineContext().ensureActive()
            val entry = results.firstOrNull { it.oid == f.oid }
                ?: throw ApiException(-1, trf("Git LFS 服务端没有返回「{0}」的上传信息，请重试", f.name))
            entry.error?.let { throw ApiException(-1, rejection(f.name, it)) }
            val upload = entry.upload
            if (upload != null) {
                val base = sent
                onProgress(done, sent, f.name)
                put(client, upload, f) { total -> onProgress(done, base + total, f.name) }
                entry.verify?.let { verify(client, it, f) }
            }
            sent += f.size
            done++
            onProgress(done, sent, f.name)
        }
    }

    private suspend fun batch(client: OkHttpClient, token: String, owner: String, name: String, ref: String?, files: List<LfsFile>): List<LfsObject> {
        val request = Request.Builder()
            .url(batchUrl(owner, name))
            .header("Authorization", GitPack.basicAuth(token))
            .header("Accept", MEDIA)
            .post(batchBody(files.map { LfsRef(it.oid, it.size) }, ref).toRequestBody(MEDIA_TYPE))
            .build()
        val (code, text) = send(client, request)
        if (code !in 200..299) throw ApiException(code, batchFailure(code, text))
        return runCatching { parseBatch(text) }.getOrElse { throw ApiException(-1, tr("Git LFS 服务端回包无法解析，请重试")) }
    }

    private suspend fun put(client: OkHttpClient, action: LfsAction, file: LfsFile, onBytes: (Long) -> Unit) {
        val body = object : RequestBody() {
            // 内容类型完全交给服务端给的请求头决定（预签名地址常把它算进签名）
            override fun contentType() = null
            override fun contentLength() = file.size
            override fun isOneShot() = false
            override fun writeTo(sink: BufferedSink) {
                val input = file.open() ?: throw ApiException(-1, GitUpload.UNREADABLE)
                input.use {
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > file.size) throw ApiException(-1, tr("文件在上传期间变大了，请重新选择"))
                        sink.write(buf, 0, n)
                        if (total - reported >= REPORT_STEP) { reported = total; onBytes(total) }
                    }
                    if (total != file.size) throw ApiException(-1, tr("文件在上传期间被改动，请重新选择"))
                    onBytes(total)
                }
            }
        }
        val builder = Request.Builder().url(action.href).put(body)
        action.headers.forEach { (k, v) -> if (!k.equals("Content-Length", true) && !k.equals("Transfer-Encoding", true)) builder.header(k, v) }
        val (code, text) = send(client, builder.build())
        if (code !in 200..299) throw ApiException(code, trf("上传「{0}」到 Git LFS 失败（HTTP {1}）{2}", file.name, code, hint(text)))
    }

    private suspend fun verify(client: OkHttpClient, action: LfsAction, file: LfsFile) {
        val builder = Request.Builder().url(action.href)
            .post(buildJsonObject { put("oid", file.oid); put("size", file.size) }.toString().toRequestBody(MEDIA_TYPE))
        action.headers.forEach { (k, v) -> builder.header(k, v) }
        builder.header("Accept", MEDIA)
        val (code, text) = send(client, builder.build())
        if (code !in 200..299) throw ApiException(code, trf("Git LFS 没有确认「{0}」已收到（HTTP {1}）{2}", file.name, code, hint(text)))
    }

    private suspend fun send(client: OkHttpClient, request: Request): Pair<Int, String> = try {
        client.newCall(request).await().use { resp -> resp.code to withContext(Dispatchers.IO) { resp.body.string() } }
    } catch (e: IOException) {
        throw ApiException(-1, e.networkHint() ?: tr("Git LFS 传输中断，请检查网络后重试"))
    }

    private fun batchFailure(code: Int, body: String): String = when (code) {
        401 -> tr("Git LFS 认证失败，请重新登录后再试")
        403, 404 -> tr("没有权限使用这个仓库的 Git LFS：需要对仓库有写权限，且仓库没有禁用 LFS")
        else -> trf("Git LFS 请求失败（HTTP {0}）{1}", code, hint(body))
    }

    private fun rejection(name: String, message: String): String =
        if (message.contains("quota", true) || message.contains("limit", true) || message.contains("budget", true))
            trf("Git LFS 存储或流量额度不足，无法上传「{0}」。GitHub 免费账号每月 1 GiB，可在 GitHub 购买额度或清理旧的 LFS 数据", name)
        else trf("Git LFS 拒绝了「{0}」：{1}", name, message)

    private fun hint(body: String): String =
        runCatching { Api.json.parseToJsonElement(body).str("message") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
}
