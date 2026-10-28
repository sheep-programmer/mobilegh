package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import kotlin.coroutines.cancellation.CancellationException

/**
 * 要写进 packfile 的一个对象：git 类型号 + 未压缩长度 + 怎么再打开一次内容。
 * 长度必须写进对象头，所以调用方要先流式扫一遍拿到精确长度（见 [GitUpload.hash]）。
 * [open] 必须可以反复调用：请求体可能被重发，不能依赖一次性流。
 */
class PackObject(val type: Int, val size: Long, val open: () -> InputStream?)

/**
 * 用 git 自己的传输（smart HTTP 的 `git-receive-pack`）把对象推进仓库。
 *
 * 只有这条路能到 GitHub 的真实上限。实测三条 REST 写对象的路径全部在 **40 MiB**
 * （41,943,040 字节）拒收，报 422「Sorry, your input was too large to process」：
 * `POST /git/blobs`、`POST /git/trees` 的内联 content、`PUT /contents/{path}`；
 * 而仓库本身允许到 **100 MiB**（实测 100 MiB 推得进去，101 MiB 被 GH001 硬拒）。
 * 所以大文件不能再走 REST 写对象。
 *
 * 关键技巧：我们只想「让对象进仓库」，不想这一步动任何分支，于是借一条**当前确实存在**的
 * ref 发一行 old == new 的空操作指令。实测 GitHub 回 `unpack ok` 并把对象全部落库，
 * 而分支 sha 一位都没变。之后再用 Data API：在基准树上叠加新树 → 生成提交 → 快进分支。
 * 「哪些文件要保留」始终由服务端 `base_tree` 合并决定，不会因为自己拼树漏掉没选中的文件。
 */
object GitPack {
    /** git 对象类型号（写在对象头的高 3 位） */
    const val TYPE_COMMIT = 1
    const val TYPE_TREE = 2
    const val TYPE_BLOB = 3

    /** git 表示「该引用还不存在」的全零 oid：建分支时 old 就是它 */
    const val ZERO_OID = "0000000000000000000000000000000000000000"

    /**
     * 单个对象（≈仓库单文件）的硬上限。实测 100 MiB 能推；101 MiB 被
     * `pre-receive hook declined` 拒（GH001: exceeds GitHub's file size limit of 100.00 MB）。
     * GitHub 另外会在 50 MB 以上给一条 warning，但不拦。
     */
    const val MAX_OBJECT_BYTES = 100L * 1024 * 1024

    /** 一次读多少本地字节；也是 deflate 的喂入粒度 */
    private const val CHUNK = 64 * 1024

    /**
     * git 协议端点固定用官方域名。
     * 这里**不能**用 [Net.apiBase]：用户自建的 API 反向代理只转发 REST，不保证承载 smart HTTP，
     * 而且那是另一台主机，把凭据发过去等于泄露。
     */
    private const val GIT_HOST = "https://github.com"

    private val GIT_TYPE = "application/x-git-receive-pack-request".toMediaType()

    /** 0 字节：pkt-line 分隔、capabilities 行的前导符 */
    private val NUL: Char = 0.toChar()

    /**
     * 上传用的客户端。压缩整包 + 传输可能远超 API 那组超时（实测 40 MiB 一个对象
     * GitHub 就要 20–30 秒），用默认值会把已经成功的上传报成失败。
     */
    private val http by lazy {
        Api.http.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 对象头：首字节高 3 位是类型、低 4 位是长度低位，之后每字节按 **7 位**继续（git 的变长整数）。
     * 与真 git 的输出对齐过：0 字节 → `30`，12 字节 → `3c`，17 字节 → `b1 01`（长度低位 1 在首字节，
     * 剩余 1 在第二字节的低 7 位）。
     */
    internal fun objectHeader(type: Int, size: Long): ByteArray {
        var c = (type shl 4) or (size.toInt() and 0x0F)
        var n = size ushr 4
        val out = ArrayList<Byte>(10)
        while (n != 0L) {
            out.add((c or 0x80).toByte())
            c = (n and 0x7F).toInt()
            n = n ushr 7
        }
        out.add(c.toByte())
        return ByteArray(out.size) { out[it] }
    }

    /**
     * receive-pack 的一条更新指令：`<old> <new> <ref>` 之后要用 NUL 分隔再接能力，
     * 结尾一个换行。少了「NUL + report-status」服务端根本解析不了这行，
     * 实测 GitHub 回 `unpack no voting servers succeeded` —— 看着像内部故障，
     * 其实是命令不合法，很容易被误判成 GitHub 抽风。
     */
    internal fun updateCommand(oldOid: String, newOid: String, refName: String, vararg capabilities: String): String {
        val caps = capabilities.toList().ifEmpty { listOf("report-status") }
        return "$oldOid $newOid $refName" + NUL + caps.joinToString(" ") + "\n"
    }

    /** pkt-line：4 位十六进制总长（含这 4 个字符自己）+ 内容 */
    internal fun pktLine(payload: String): ByteArray {
        val body = payload.toByteArray(Charsets.UTF_8)
        return "%04x".format(body.size + 4).toByteArray(Charsets.US_ASCII) + body
    }

    internal val FLUSH = byteArrayOf('0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '0'.code.toByte())

    private fun be32(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** pack 的固定头部：`PACK` + version 2 + 对象数 */
    internal fun packHeader(count: Int): ByteArray =
        byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 'K'.code.toByte()) + be32(2) + be32(count)

    /** git 的 HTTP 认证只认 basic（用户名固定 x-access-token），不是 API 用的 Bearer */
    private fun basicAuth(token: String): String =
        "Basic " + Base64.getEncoder().encodeToString("x-access-token:$token".toByteArray(Charsets.UTF_8))

    /** 一个解开的对象，用来回读自己写出的 pack */
    internal class ParsedObject(val type: Int, val size: Int, val bytes: ByteArray)

    /** 完整 packfile（给小对象与测试用）；大文件走 [ReceiveBody] 流式写出，不进内存 */
    internal fun buildPack(objects: List<PackObject>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(packHeader(objects.size))
        for (o in objects) {
            out.write(objectHeader(o.type, o.size))
            out.write(deflateAll(o))
        }
        val body = out.toByteArray()
        return body + MessageDigest.getInstance("SHA-1").digest(body)
    }

    /** 压一个对象的内容；实际长度必须与 [PackObject.size] 一致，不然是文件被改过 */
    private fun deflateAll(o: PackObject): ByteArray {
        val raw = o.open()?.use { it.readBytes() } ?: throw ApiException(-1, GitUpload.UNREADABLE)
        if (raw.size.toLong() != o.size) throw ApiException(-1, CHANGE_MIDWAY)
        val out = ByteArrayOutputStream()
        val def = DeflaterOutputStream(out, Deflater(Deflater.DEFAULT_COMPRESSION), CHUNK)
        def.write(raw); def.close()
        return out.toByteArray()
    }

    /**
     * 解析 pack（自己写的、以及 git 生成的非 delta pack），逐个对象还原出来。
     * 顺带校验 trailer：它必须是前面所有字节的 sha1，写错一个字节这里就会炸。
     */
    internal fun parsePack(pack: ByteArray): List<ParsedObject> {
        if (pack.size < 32 || String(pack, 0, 4, Charsets.US_ASCII) != "PACK") throw ApiException(-1, "不是 pack 数据")
        val version = be32At(pack, 4)
        require(version == 2) { "只支持 pack v2，收到 v$version" }
        val count = be32At(pack, 8)
        require(count in 0..1_000_000) { "pack 对象数异常：$count" }
        val out = ArrayList<ParsedObject>(count)
        var i = 12
        repeat(count) {
            var b = pack[i].toInt() and 0xFF; i++
            val type = (b ushr 4) and 0x07
            require(type in 1..5) { "pack 里有未知对象类型 $type" }
            var size = (b and 0x0F).toLong(); var shift = 4
            while (b and 0x80 != 0) {
                b = pack[i].toInt() and 0xFF; i++
                size = size or ((b and 0x7F).toLong() shl shift)
                shift += 7
            }
            val want = size.toInt()
            val (consumed, bytes) = inflate(pack, i, want)
            i += consumed
            out.add(ParsedObject(type, want, bytes))
        }
        val body = pack.copyOfRange(0, pack.size - 20)
        if (!MessageDigest.getInstance("SHA-1").digest(body).contentEquals(pack.copyOfRange(pack.size - 20, pack.size)))
            throw ApiException(-1, "pack 校验和不符")
        return out
    }

    /**
     * 从 [at] 开始解压一个对象，返回（吃掉多少输入字节，解出的内容）。
     *
     * 空对象也必须把 zlib 流走完：真 git 给 0 字节文件留了 8 个字节
     * （`78 9c 03 00 00 00 00 01`）。如果因为「输出长度为 0」就不调 inflate，
     * [Inflater.bytesRead] 会停在 0，解析位置落在压缩字节上，下一个对象会被当成
     * 未知类型（实测报 type 7）。用 1 字节暂存区调一次即可把头与结束块吃掉。
     */
    private fun inflate(pack: ByteArray, at: Int, want: Int): Pair<Int, ByteArray> {
        val inf = Inflater()
        try {
            inf.setInput(pack, at, pack.size - at)
            val out = ByteArray(want)
            var pos = 0
            while (true) {
                val n = try {
                    inf.inflate(if (want > 0) out else ByteArray(1), pos, if (want > 0) want - pos else 1)
                } catch (e: java.util.zip.DataFormatException) {
                    throw ApiException(-1, "pack 解压失败")
                }
                pos += n.coerceAtLeast(0)
                if (inf.finished()) break
                if (n == 0 && inf.needsInput()) throw ApiException(-1, "pack 里的对象解压不完整")
                if (want > 0 && pos >= want) break
            }
            if (want > 0 && pos != want) throw ApiException(-1, "pack 里的对象解压不完整")
            if (want == 0 && !inf.finished()) throw ApiException(-1, "空对象的 zlib 流没走完")
            return Pair(inf.bytesRead.toInt(), out)
        } finally {
            inf.end()
        }
    }

    private fun be32At(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    /** 把 sha 和长度喂进 md，再原样写到下游；pack 的每一字节都要进校验和 */
    private class HashedSink(private val sink: OutputStream, private val md: MessageDigest) : OutputStream() {
        override fun write(b: Int) { md.update(b.toByte()); sink.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { md.update(b, off, len); sink.write(b, off, len) }
        override fun flush() = sink.flush()
        override fun close() { /* 由外层控制关闭 */ }
    }

    /**
     * 请求体：指令 + flush + pack。边读本地文件边压缩边写，整份文件不会驻留内存。
     *
     * pack 的 trailer 只覆盖 **pack 自己的字节**（`PACK` 头部 + 各对象），不含前面那两行
     * pkt-line。所以指令和 flush 必须绕过校验和直接写出去：把它们也喂进摘要的话 trailer
     * 就是错的，服务端会整包 `unpack index-pack failed`（测试里用真 git 的包字节核对过）。
     */
    internal class ReceiveBody(
        private val command: String,
        private val objects: List<Pair<String, PackObject>>,
        private val onObject: suspend (Int, String) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = GIT_TYPE

        /** 长度提前不可知 → OkHttp 用 chunked。实测 GitHub 接受 chunked 的 receive-pack */
        override fun contentLength(): Long = -1L

        /** 每个流都能重开，允许 OkHttp 在连接被重置时重试 */
        override fun isOneShot(): Boolean = false

        override fun writeTo(sink: BufferedSink) = runBlocking {
            val raw = sink.outputStream()
            raw.write(pktLine(command))
            raw.write(FLUSH)

            val md = MessageDigest.getInstance("SHA-1")
            val counted = HashedSink(raw, md)
            fun emit(bytes: ByteArray) = counted.write(bytes)
            emit(packHeader(objects.size))
            for ((i, entry) in objects.withIndex()) {
                val (oid, obj) = entry
                onObject(i, oid)
                emit(objectHeader(obj.type, obj.size))
                val input = obj.open() ?: throw ApiException(-1, GitUpload.UNREADABLE)
                input.use { read ->
                    // 关掉 Deflater 才会把尾巴的压缩数据吐完；HashedSink.close 是空实现，不会误关 raw
                    val def = DeflaterOutputStream(counted, Deflater(Deflater.DEFAULT_COMPRESSION), CHUNK)
                    val buf = ByteArray(CHUNK)
                    var total = 0L
                    while (true) {
                        val n = read.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > obj.size) throw ApiException(-1, "文件在打包期间变大了，请重新选择")
                        def.write(buf, 0, n)
                    }
                    if (total != obj.size) throw ApiException(-1, CHANGE_MIDWAY)
                    def.close()
                }
            }
            raw.write(md.digest())
            raw.flush()
        }
    }

    private const val CHANGE_MIDWAY = "文件在打包期间被改动，请重新选择"

    /**
     * receive-pack 的回执。[lines] 必须是 [parseGitReport] 解析后的纯文本行 ——
     * 不是带 pkt-line 长度前缀的原始字节。
     */
    class Receipt(val code: Int, val lines: List<String>) {
        /** 服务端有没有把这包解开。git 的语义是「先 unpack，后更新引用」 */
        val unpackOk: Boolean get() = code in 200..299 && lines.any { it == "unpack ok" }

        /** 分支有没有真的更新/建出来：`ok <ref>` 那一行 */
        val refUpdated: Boolean get() = lines.any { it.startsWith("ok ") }

        /** 引用被拒时的原文（`ng <ref> <原因>`） */
        val declinedRef: String? get() = lines.firstOrNull { it.startsWith("ng ") }?.removePrefix("ng ")?.trim()

        /** unpack 级别的失败原文，例如 `unpack index-pack failed` */
        val unpackFailure: String? get() = lines.firstOrNull { it.startsWith("unpack ") && it != "unpack ok" }

        /**
         * 服务端原文里真正说明问题的一行。
         * 不能取第一行：回执开头往往就是 `unpack ok`，
         * 拿它当原因会变成「上传未完成。unpack ok」这种自相矛盾的话。
         */
        val detail: String?
            get() = declinedRef ?: unpackFailure ?: lines.firstOrNull { it != "unpack ok" && !it.startsWith("ok ") }

        /**
         * 整包是否被拒（对象不会落库）。实测三种情况行为不同：
         * - 借用的那条 ref 被拒成 `ng … cannot lock ref`（刚好别人先推了）：对象**仍然落库**，
         *   可以继续 —— 我们只是借一行指令把包递进去，本来就不打算让它生效；
         * - `unpack index-pack failed`（包里有重复对象等）：`unpack ok` 不出现，算被拒；
         * - `pre-receive hook declined`（实测 101 MiB 那种）：即使回了 `unpack ok`，对象也**没有**
         *   落库。只看 [unpackOk] 会把这种失败说成成功。
         */
        val declined: Boolean get() = !unpackOk ||
            lines.any { it.contains("pre-receive hook declined") || it.contains("Large files detected") }

        /**
         * 失败原因；null 表示可以当成对象已经进仓库。
         * HTTP 成功但一行也没解出来时不能说「服务端拒绝（HTTP 200）」：那句自相矛盾，
         * 会把人带去查权限而不是查我们的回执解析。
         */
        val failure: String?
            get() = if (!declined) null
            else detail ?: if (code in 200..299) "服务端回包没能解析，请重试一次" else "服务端拒绝（HTTP $code）"
    }

    /** advertise-refs 里的一行 */
    internal class Ref(val oid: String, val name: String)

    /**
     * 挑一条借来写指令的 ref：必须是服务端**当前确实存在**的 ref，oid 用它自己的值，
     * 这样 old == new 才是真正的空操作。
     * 优先与 [baseRef] 同名的分支，其次同名标签，再退到任意分支、任意 ref；一条都没有就是空仓库。
     */
    internal fun pickRef(refs: List<Ref>, baseRef: String): Ref? =
        refs.firstOrNull { it.name == "refs/heads/$baseRef" }
            ?: refs.firstOrNull { it.name == "refs/tags/$baseRef" }
            ?: refs.firstOrNull { it.name.startsWith("refs/heads/") }
            ?: refs.firstOrNull()

    /**
     * 服务发现的请求地址。
     *
     * 必须是 `/info/refs`：`service=git-receive-pack` 只是查询参数，
     * 写成 `/git-info/refs` 会 404（实测：正确地址 200、错地址 404 Not Found），
     * 界面就会变成「上传未完成。读取分支列表失败（HTTP 404）」。
     */
    internal fun infoRefsUrl(owner: String, name: String) =
        "$GIT_HOST/$owner/$name.git/info/refs?service=git-receive-pack"

    /** 推包的地址（同一服务发现的 service 名，少了 /info/refs 这一段就不是服务发现） */
    internal fun receivePackUrl(owner: String, name: String) =
        "$GIT_HOST/$owner/$name.git/git-receive-pack"

    /**
     * 问服务端当前有哪些 ref、各自指向哪里。空仓库这里一条 ref 都没有。
     *
     * 端点必须由 [infoRefsUrl] 给出并有 GitEndpointsTest 钉住：写成 `/git-info/refs` 会 404，
     * 而当时 444 个单测全绿——那行 URL 从没被执行过（E2E 里的服务发现是手工 curl 替做的），
     * 用户于是看到「上传未完成。读取分支列表失败（HTTP 404）」。
     */
    internal suspend fun advertise(owner: String, name: String): List<Ref> {
        val token = Session.token ?: throw ApiException(401, "请先登录 GitHub")
        val request = Request.Builder()
            .url(infoRefsUrl(owner, name))
            .header("Authorization", basicAuth(token))
            .get().build()
        val text = try {
            withContext(Dispatchers.IO) {
                http.routed().newCall(request).await().use { resp ->
                    if (resp.code !in 200..299) throw ApiException(resp.code, "读取分支列表失败（HTTP ${resp.code}）")
                    resp.body.string()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: Throwable) {
            throw ApiException(-1, e.networkHint() ?: "读取分支列表失败，请检查网络后重试")
        }
        return parseAdvertised(text)
    }

    /**
     * 解析 advertise-refs 响应：跳过服务发现首行，逐条 pkt-line 取「oid 空格 ref名」。
     * 带 NUL 尾巴的那条是 capabilities，不是 ref；`^{}` 结尾的是 peeled tag；
     * 末尾的 `.have` 也是伪 ref。真报文的每条 body 都以换行结尾，必须去掉，
     * 否则拼出来的更新指令会变成两行。
     */
    internal fun parseAdvertised(text: String): List<Ref> {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        val out = ArrayList<Ref>()
        var i = 0
        fun line(): String? {
            if (i + 4 > bytes.size) return null
            val len = String(bytes, i, 4, Charsets.US_ASCII).toIntOrNull(16) ?: return null
            if (len == 0) { i += 4; return line() }          // flush-pkt：跳过继续读
            if (len < 4 || i + len > bytes.size) return null
            val body = String(bytes, i + 4, len - 4, Charsets.UTF_8)
            i += len
            return body
        }
        line()  // 首行是 "# service=git-receive-pack"
        while (true) {
            val l = line() ?: break
            val parts = l.substringBefore(NUL).trim().split(' ', limit = 2)
            if (parts.size != 2) continue
            val (oid, ref) = parts
            // 只收真正的引用：`.have`、`capabilities^{}`、`shallow <oid>` 之类都不是能借用的 ref
            if (!ref.startsWith("refs/")) continue
            if (ref.endsWith("^{}")) continue
            if (!oid.matches(Regex("[0-9a-f]{40}"))) continue
            out.add(Ref(oid, ref))
        }
        return out
    }

    /** 空操作指令：借一条确实存在的 ref，old == new，分支不会被动到，只为让对象落库 */
    internal fun noOpCommand(ref: Ref) = updateCommand(ref.oid, ref.oid, ref.name)

    /**
     * 建分支指令：从 [ZERO_OID] 到 [commitSha]。实测空仓库可以一次完成「传对象 + 建分支」，
     * 不需要 Contents API：tree 与 commit 对象由 [GitTree] 本地算出来，跟着 blob 一起放进同一个 pack。
     */
    internal fun createCommand(branch: String, commitSha: String) =
        updateCommand(ZERO_OID, commitSha, "refs/heads/$branch")

    /**
     * 把 [objects] 推进仓库。每个对象是 `(sha, 内容)`；[command] 由 [noOpCommand] /
     * [createCommand] 生成，保证指令行合法。返回 [Receipt]；网络失败抛异常。
     */
    internal suspend fun push(
        owner: String,
        name: String,
        command: String,
        objects: List<Pair<String, PackObject>>,
        onObject: suspend (Int, String) -> Unit = { _, _ -> },
    ): Receipt {
        // 没东西可推时不发包。给的回执与真发过一次成功一样（带 unpack ok 与 ok <ref> 两行），
        // 免得调用方把「空包」当成失败
        if (objects.isEmpty()) return Receipt(200, listOf("unpack ok", "ok refs/heads/noop"))
        val token = Session.token ?: throw ApiException(401, "请先登录 GitHub")
        val request = Request.Builder()
            .url(receivePackUrl(owner, name))
            // 请求体是流式的，重发要能重开文件；响应按原文读，不解 side-band
            .header("Accept-Encoding", "identity")
            .header("Authorization", basicAuth(token))
            .post(ReceiveBody(command, objects, onObject))
            .build()
        val response = try {
            withContext(Dispatchers.IO) { http.routed().newCall(request).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ApiException(-1, e.networkHint() ?: "上传失败：连接中断，请重试")
        }
        return response.use { resp ->
            Receipt(resp.code, parseGitReport(withContext(Dispatchers.IO) { resp.body.string() }))
        }
    }
}

/**
 * 解析 receive-pack 的回执（report-status）。
 *
 * 每一条都是一个 pkt-line：4 位十六进制总长（含这 4 个字符自己）+ 内容（自带换行），
 * 结尾 `0000` 是 flush。本地用真 git 拓下来的实际回执：
 * `000e` `unpack ok\n` `0017` `ok refs/heads/main\n` `0000`。
 *
 * 必须先剥掉长度前缀再判。之前直接对原始字节做字符串匹配，`ok refs/…` 那一行看到的是
 * `0017ok refs/…`，以 `ok ` 开头的判断永远不成立 —— 一次成功的上传会被报成失败，
 * 而错误信息里还带着 `unpack ok`（用户看到的正是这个）。
 */
internal fun parseGitReport(raw: String): List<String> {
    // 服务端可能走 side-band：首字节 1/2/3 是通道号，去掉再按 pkt-line 读
    val text = if (raw.isNotEmpty() && raw[0].code in 1..3) raw.substring(1) else raw
    val out = ArrayList<String>()
    var i = 0
    while (i + 4 <= text.length) {
        val len = text.substring(i, i + 4).toIntOrNull(16) ?: break
        if (len == 0) { i += 4; continue }                                        // flush-pkt
        if (len < 4 || i + len > text.length) break                              // 长度不自洽：剩下的丢掉，不拼出错行
        val line = text.substring(i + 4, i + len).trim { c -> c.isWhitespace() || c.code == 0 }
        i += len
        if (line.isNotEmpty()) out.add(line)
    }
    return out
}
