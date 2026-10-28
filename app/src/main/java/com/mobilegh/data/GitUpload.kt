package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.InputStream
import java.security.MessageDigest

/**
 * 待上传的一个文件：本机地址 [uri]（SAF content://）对应仓库里的路径 [path]。
 * [size] 由系统给出，读不到时为 0（上传时按实际字节数重新检查）；
 * [skip] 非空表示这一项不会进入提交，界面上必须写明原因。
 */
data class UploadItem(
    val uri: String,
    val name: String,
    val path: String,
    val size: Long = 0,
    val skip: String? = null,
)

/** 仓库基准树里已有的一个文件：blob sha 与权限位 */
data class RepoBlob(val sha: String, val mode: String)

/** 递归读到的仓库现有文件表；[truncated] 为 true 时这份表不完整，不能用来判断「有没有变化」 */
data class TreeIndex(val blobs: Map<String, RepoBlob>, val truncated: Boolean)

/** 要写进新树的一个条目 */
data class TreeWrite(val path: String, val mode: String, val sha: String) {
    fun payload() = JsonObject(
        linkedMapOf(
            "path" to JsonPrimitive(path),
            "mode" to JsonPrimitive(mode),
            "type" to JsonPrimitive("blob"),
            "sha" to JsonPrimitive(sha),
        )
    )
}

/**
 * 上传阶段。字节数只在 [Reading] 是「已经读了多少」，能算成真实百分比；
 * [Sending] 阶段要先把 deflate 填满才能确认写出字节数，OkHttp 又自己缓冲，
 * 本机拿不到可靠的已发送量——界面在这一步得改用不确定进度条，
 * 否则会出现「进度 100% 但还在上传」这种自相矛盾的显示。
 */
enum class UploadStage { Reading, Sending, Finalizing }

/** 上传进度：已处理文件数、总数、已读字节、总字节、当前在做的件事、处于哪个阶段 */
data class UploadProgress(
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val totalBytes: Long,
    val current: String,
    val stage: UploadStage = UploadStage.Reading,
)

/** 上传结果：新提交 sha、真正写入的文件数、与仓库内容相同而跳过的数量、落在哪个分支 */
data class UploadResult(val sha: String, val written: Int, val unchanged: Int, val branch: String)

/** 本机要推的一个对象：blob 的 sha、大小、怎么打开内容 */
internal class Pending(val sha: String, val size: Long, val open: () -> InputStream?)

/**
 * 上传文件（夹）：本机内容用 **git 自己的传输**写成对象，再用 Data API 在当前树之上
 * 生成新树和一个提交，最后把分支快进过去。一次上传只产生**一个**提交。
 *
 * 为什么大文件必须走 git 协议：GitHub 的仓库单文件上限是 100 MiB（实测 100 MiB 可推、
 * 101 MiB 被 GH001 硬拒），但三条 REST 写对象的路径全都在 40 MiB 拒收（422
 * “your input was too large”）。所以这里不再用 `PUT /contents/{path}`（一次只能写一个
 * 文件、多选会产生同样数量的提交），也不用 `POST /git/blobs`；对象一律用 packfile 送进去。
 *
 * 组合方式：pack 只负责「把对象放进仓库」，用一行 old == new 的空操作指令完成，
 * 实测分支 sha 不会变；之后 `createTree(base_tree, …)` 由**服务端**决定哪些原有文件
 * 保留，所以我们不需要自己重建树，也不可能漏掉没选中的文件。
 */
object GitUpload {
    /** GitHub 仓库的单文件硬上限，也是这里允许的最大值 */
    const val MAX_FILE_BYTES = GitPack.MAX_OBJECT_BYTES

    /** 一次 tree 请求最多带多少条目，超出时分批在同一棵树上继续叠加 */
    const val TREE_BATCH = 500

    /** 一次 receive-pack 大概带多少原始字节；分小点能让长连接不至于跑太久，失败也只重传这一批 */
    const val PACK_BATCH_BYTES = 64L * 1024 * 1024

    /** 文件夹递归展开的深度与数量上限，防止异常目录结构把手机卡住 */
    const val MAX_DEPTH = 24
    const val MAX_FOLDER_FILES = 3000

    /** 提交里的应用署名（git trailer） */
    const val SIGNATURE = "Co-authored-by: MobileGH <noreply@github.com>"

    /**
     * commit 对象里的身份行：`名字 <邮箱> <秒级时间> <时区>`。
     *
     * 邮箱不是摆设：GitHub 只认 `<用户号>+<登录名>@users.noreply.github.com` 这个形式会
     * 把提交关联到账号（实测：用用户号能关联上，拿占位的 43 就只显示文本、`author` 为 null）。
     * 拿不到用户号时退回不带前缀的写法：提交仍然合法，只是不关联账号。
     * [epochSeconds] 由调用方传入而不是这里取当前时间：时间进到对象字节里，要能复算 sha 就得可控制。
     */
    internal fun identOf(login: String, id: Long, epochSeconds: Long): String {
        val mail = if (id > 0) "$id+$login@users.noreply.github.com" else "$login@users.noreply.github.com"
        return "$login <$mail> $epochSeconds +0000"
    }

    const val UNREADABLE = "打不开该文件，可能已被移动、删除，或应用失去了访问权限"

    /** 本机名称 → 仓库里的一段路径。任何一段不能用就整体返回 null，由调用标成「路径无效」 */
    fun sanitizeName(raw: String?): String? = raw?.replace('/', '_')?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." }

    /** 目标路径：当前浏览的目录 [dir] + 文件夹内部分级 [segments] + 文件名 [fileName]；非法路径返回 null */
    fun pathOf(dir: String, segments: List<String>, fileName: String): String? {
        val file = sanitizeName(fileName) ?: return null
        val segs = segments.map { sanitizeName(it) ?: return null }
        val parts = dir.split('/').filter { it.isNotBlank() } + segs + file
        val path = parts.joinToString("/")
        return if (CodeReading.validPath(path)) path else null
    }

    /**
     * 整理清单：重复路径、非法路径直接去掉（先加入的胜出），超过 GitHub 单文件硬上限的保留但标出原因。
     * [Triage.dropped] 是被直接去掉的数量，界面上要说明，否则用户会以为已经传上去了。
     */
    fun triage(items: List<UploadItem>): Triage {
        val seen = HashSet<String>()
        var dropped = 0
        val kept = items.map { it.copy(path = it.path.trim().trim('/')) }.filter { it.path.isNotEmpty() }
            .mapNotNull { i ->
                val name = i.path.substringAfterLast('/')
                val base = i.copy(name = name)
                when {
                    !seen.add(i.path.lowercase()) -> { dropped++; null }
                    !CodeReading.validPath(i.path) -> { dropped++; null }
                    i.size > MAX_FILE_BYTES -> base.copy(skip = "超过 GitHub 的 ${MAX_FILE_BYTES / 1024 / 1024} MB 单文件上限，任何人都无法上传（请用 Git LFS）")
                    else -> base
                }
            }
        return Triage(kept, dropped)
    }

    /** [items] 保留下来的项（可能带 skip 原因），[dropped] 是被直接去掉的数量 */
    class Triage(val items: List<UploadItem>, val dropped: Int)

    /** 会真正进入提交的文件 */
    fun usable(items: List<UploadItem>): List<UploadItem> = items.filter { it.skip == null }

    /** 默认提交信息，与 GitHub 网页写法一致：单文件用文件名，多文件用数量 */
    fun defaultMessage(items: List<UploadItem>): String {
        val ok = usable(items)
        return when (ok.size) {
            1 -> "Add " + ok[0].path.substringAfterLast('/')
            0 -> ""
            else -> "Add ${ok.size} files"
        }
    }

    /** Git 的 blob 对象 sha：`sha1("blob <长度>\0" + 内容)`，与 `git hash-object` 完全一致 */
    fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return hex(md.digest())
    }

    /** 单个文件的 git 摘要：[sha]、实际字节数、文件开头若干字节（判断脚本用） */
    class HashedFile(val sha: String, val size: Long, val head: ByteArray)

    /**
     * 流式算 blob sha，整份文件不会进内存。
     * git 把长度写在内容前面，而流式读取时长度是读到最后才知道的：系统报了大小时先按这个长度试读一次，
     * 对不上再量一次真实长度重读（普通文件只读一次）；没报大小时先量长度。
     */
    fun hash(open: () -> InputStream?, sizeHint: Long, limit: Long = MAX_FILE_BYTES): HashedFile {
        if (sizeHint > 0 && sizeHint <= limit) {
            when (val hinted = pass(open(), sizeHint, limit)) {
                is PassResult.Ok -> return hinted.file
                is PassResult.Unreadable -> throw ApiException(-1, UNREADABLE)
                is PassResult.LengthMismatch -> Unit
            }
        }
        val real = when (val m = pass(open(), -1L, limit)) {
            is PassResult.Unreadable -> throw ApiException(-1, UNREADABLE)
            else -> m.length()
        }
        return when (val again = pass(open(), real, limit)) {
            is PassResult.Ok -> again.file
            else -> throw ApiException(-1, "文件在读取期间发生了变化，请重新选择")
        }
    }

    private sealed interface PassResult {
        class Ok(val file: HashedFile) : PassResult
        class LengthMismatch(val actual: Long) : PassResult
        object Unreadable : PassResult
        fun length(): Long = when (this) {
            is Ok -> file.size
            is LengthMismatch -> actual
            Unreadable -> 0L
        }
    }

    /**
     * 按 [expect] 作为长度算 sha（[expect] 为负数表示只统计长度，不算 sha）。
     * 边读边卡上限，超过 [limit] 立刻失败，不会先把整份大文件读完。
     */
    private fun pass(input: InputStream?, expect: Long, limit: Long): PassResult {
        val stream = input ?: return PassResult.Unreadable
        stream.use {
            val md = MessageDigest.getInstance("SHA-1")
            if (expect >= 0) md.update("blob $expect\u0000".toByteArray(Charsets.UTF_8))
            val buf = ByteArray(64 * 1024)
            val head = ByteArray(16)
            var headLen = 0
            var count = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (count + n > limit) throw ApiException(413, tooBigMessage(limit))
                if (headLen < head.size) {
                    val take = minOf(n, head.size - headLen)
                    System.arraycopy(buf, 0, head, headLen, take)
                    headLen += take
                }
                md.update(buf, 0, n)
                count += n
            }
            if (expect < 0 || count != expect) return PassResult.LengthMismatch(count)
            return PassResult.Ok(HashedFile(hex(md.digest()), count, head.copyOf(headLen)))
        }
    }

    private fun tooBigMessage(limit: Long) =
        "文件超过 ${limit / 1024 / 1024} MB，超出 GitHub 的单文件上限，仓库会直接拒绝这个提交（大文件请用 Git LFS）"

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * 权限位：仓库里同路径已经是 100755 就保持可执行；否则按 shebang 和常见脚本后缀判断。
     * SAF 不携带 POSIX 权限位，这是最接近 `git add` 的结果。
     */
    fun modeOf(path: String, head: ByteArray, existing: RepoBlob?): String = when {
        head.size >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte() -> "100755"
        existing?.mode == "100755" -> "100755"
        path.lowercase().substringAfterLast('.') in EXEC_EXT -> "100755"
        else -> "100644"
    }

    private val EXEC_EXT = setOf("sh", "bash", "zsh", "fish", "py", "rb", "pl", "php", "lua")

    /** 内容和权限位都没变就不必重新写入 */
    fun needsWrite(sha: String, mode: String, existing: RepoBlob?): Boolean {
        val e = existing ?: return true
        return e.sha != sha || e.mode != mode
    }

    fun <T> batches(list: List<T>, size: Int = TREE_BATCH): List<List<T>> =
        if (list.isEmpty()) emptyList() else list.chunked(size.coerceAtLeast(1))

    /** 按原始字节数把待推对象分组，保证一次请求不会拖得太长 */
    internal fun packGroups(objects: List<Pending>, maxBytes: Long = PACK_BATCH_BYTES): List<List<Pending>> {
        if (objects.isEmpty()) return emptyList()
        val out = ArrayList<ArrayList<Pending>>()
        var current = ArrayList<Pending>()
        var sum = 0L
        for (o in objects) {
            // 单个对象就超一批上限时也要能发出去：一批至少放一个
            if (current.isNotEmpty() && sum + o.size > maxBytes) {
                out.add(current); current = ArrayList(); sum = 0
            }
            current.add(o); sum += o.size
        }
        out.add(current)
        return out
    }

    /** 提交信息末尾追加应用署名；已经有署名或空信息时保持原样 */
    fun messageWithSignature(message: String): String {
        val m = message.trim()
        if (m.isEmpty() || m.contains(SIGNATURE)) return m
        return "$m\n\n$SIGNATURE"
    }

    /** 一行清单说明：文件数 + 合计大小 + 被排除的数量 */
    fun summary(ok: Int, bytes: Long, skipped: Int): String =
        "$ok 个文件 · " + com.mobilegh.ui.components.fmtSize(bytes) + (if (skipped > 0) " · $skipped 项不上传" else "")

    /**
     * 把上传失败换成能看懂的下一步。分支引用只允许快进，所以「别人先推了」和「受保护分支」
     * 是最常见的两种真失败，必须分开说；提交已经生成而分支没跟上时要把 sha 说出来。
     * [commit] 为 null 表示提交还没生成（包括对象上传阶段）。
     */
    fun explain(error: Throwable, commit: String?): String =
        explain(error.message?.takeIf { it.isNotBlank() } ?: "", commit)

    /** [raw] 是服务端或底层错误的原文片段 */
    fun explain(raw: String, commit: String?): String {
        val saved = commit?.takeIf { it.isNotEmpty() }?.let { "内容已经生成提交 ${it.take(7)}，但分支没有更新。" } ?: ""
        val hint = when {
            raw.contains("not a fast forward", true) || raw.contains("cannot lock ref", true) ->
                "这个分支在你上传期间有了新提交。重新上传一次即可，会基于最新内容。"
            raw.contains("pre-receive hook declined", true) || raw.contains("Large files detected", true) ->
                "GitHub 拒收了这次传输。常见原因是单个文件超过 ${MAX_FILE_BYTES / 1024 / 1024} MB 的仓库硬上限；超大的文件请用 Git LFS。"
            raw.contains("index-pack failed", true) ->
                "打包数据没被接受（可能是选中的文件在上传期间被改了）。重新选一次再上传。"
            raw.contains("protected", true) || raw.contains("blocked", true) ->
                "该分支受保护。请在仓库设置里允许你的角色推送，或改为新建分支后发起拉取请求。"
            raw.contains("SAML", true) -> "该组织启用了 SAML SSO，请先在网页上为本应用授权 SSO。"
            raw.contains("Resource not accessible", true) || raw.contains("admin rights", true) ->
                "你对这个仓库或分支没有推送权限。"
            raw.contains("BadObjectState", true) ->
                "路径冲突：仓库里同名的位置已经是文件或目录。换个路径再传。"
            raw.isNotBlank() -> raw
            else -> "请检查网络后重试"
        }
        return "上传未完成。$saved$hint"
    }

    /**
     * 执行上传。[files] 必须是 [triage] 之后的可用项；[open] 按 SAF 地址打开本地文件（会在后台线程调用，
     * 并且必须可以重复调用——pack 请求体可能被重发）。
     * [newBranch] 表示目标分支还不存在，直接让新分支指向这个提交。
     * [onCommit] 在提交已经生成、但分支还没更新时回调：那一步失败时内容不会回滚，界面必须说清楚。
     */
    suspend fun run(
        owner: String,
        name: String,
        targetBranch: String,
        baseRef: String,
        files: List<UploadItem>,
        message: String,
        newBranch: Boolean,
        open: (UploadItem) -> InputStream?,
        onProgress: (UploadProgress) -> Unit = {},
        onCommit: suspend (String) -> Unit = {},
    ): UploadResult {
        if (files.isEmpty()) throw ApiException(400, "请先选择要上传的文件")
        val total = files.sumOf { it.size }.coerceAtLeast(0)

        // 基准提交。实测：空仓库（没有任何提交）时 /commits/{ref} 返回 409「Git Repository is empty」，
        // ref 不存在才是 404。空仓库没有基准可用，走 bootstrap（本地建树与建提交），不拦用户
        if (baseRef.isBlank()) throw ApiException(400, "分支名无效，请重新选择分支后再上传")
        val base = try {
            withContext(Dispatchers.IO) { GitHub.resolveSha(owner, name, baseRef) }
        } catch (e: ApiException) {
            if (e.code == 409 || e.message.contains("empty", true)) null
            else if (e.code == 404) throw ApiException(404, "找不到 $baseRef，请重新选择分支后再上传")
            else throw e
        }
        if (base == null) return bootstrap(owner, name, targetBranch, files, message, open, onProgress)

        var bytesDone = 0L
        var done = 0
        fun step(current: String, stage: UploadStage = UploadStage.Reading) =
            onProgress(UploadProgress(done, files.size, bytesDone.coerceAtMost(total), total, current, stage))

        step("读取仓库当前内容")
        val baseTree = withContext(Dispatchers.IO) { GitHub.commitTreeSha(owner, name, base) }
        // 已有文件表：拿不到或被截断时按「全部都要写」处理，最多多做无用功，不会漏掉文件
        val snapshot = runCatching { withContext(Dispatchers.IO) { GitHub.treeIndex(owner, name, baseTree) } }
            .getOrNull()?.takeIf { !it.truncated }?.blobs

        // 1) 流式算 sha，确定哪些文件真的要写入
        val scanned = scan(files, open, snapshot) { d, b, n -> done = d; bytesDone = b; step(n) }
        if (scanned.writes.isEmpty())
            throw ApiException(400, "所选文件与 $baseRef 上的内容完全一致，没有需要提交的改动")

        // 2) 用 git 传输把 blob 推进仓库：借一条确实存在的 ref 发空操作指令（实测不会动到分支）
        val ref = GitPack.pickRef(GitPack.advertise(owner, name), baseRef)
            ?: throw ApiException(409, "仓库状态变了（现在没有可借用的分支），请重新打开本页再上传")
        val groups = packGroups(scanned.pending)
        for ((i, part) in groups.withIndex()) {
            val batchNote = if (groups.size > 1) "（第 ${i + 1}/${groups.size} 批）" else ""
            step("打包上传$batchNote", UploadStage.Sending)
            val receipt = GitPack.push(
                owner, name, GitPack.noOpCommand(ref),
                part.map { it.sha to PackObject(GitPack.TYPE_BLOB, it.size, it.open) },
            ) { index, sha ->
                // 回调来自 OkHttp 的写线程，Compose state 要回主线程改
                withContext(Dispatchers.Main) {
                    step("上传 ${scanned.labelOf[sha] ?: "文件"}（${index + 1}/${part.size}）$batchNote", UploadStage.Sending)
                }
            }
            // 借的 ref 被拒不影响对象落库（见 Receipt 的实测说明）；只有整包被拒才是真失败
            // 只把服务端原文往上抛，由界面统一 explain()：这里套一层、界面再套一层会变成「上传未完成。上传未完成。」
            receipt.failure?.let { throw ApiException(-1, it) }
        }

        // 3) 让服务端在基准树上叠加新条目：没选中的文件由 base_tree 保留。
        // createTree 会校验对象确实存在（不存在直接 422 not a valid blob），不可能静默建出坏树
        var tree = baseTree
        val treeGroups = batches(scanned.writes)
        for ((i, part) in treeGroups.withIndex()) {
            step(if (treeGroups.size > 1) "整理文件 ${i + 1}/${treeGroups.size}" else "")
            tree = withContext(Dispatchers.IO) {
                try {
                    GitHub.createTree(owner, name, tree, part.map { it.payload() })
                } catch (e: ApiException) {
                    if (e.message.contains("not a valid blob", true))
                        throw ApiException(e.code, "GitHub 说文件对象还不存在（上传可能没完成），请重新上传一次")
                    throw e
                }
            }
        }

        // 4) 一个提交，然后才移动分支
        step("生成提交", UploadStage.Finalizing)
        val sha = withContext(Dispatchers.IO) {
            GitHub.createCommit(owner, name, messageWithSignature(message), tree, listOf(base))
        }
        // 到这里内容已经是一个完整提交，只是分支还没跟上来
        withContext(Dispatchers.Main) { onCommit(sha) }
        step(if (newBranch) "创建分支 $targetBranch" else "推送到 $targetBranch")
        withContext(Dispatchers.IO) {
            if (newBranch) GitHub.createRef(owner, name, targetBranch, sha)
            else GitHub.updateBranch(owner, name, targetBranch, sha)
        }
        return UploadResult(sha, scanned.writes.size, scanned.unchanged, targetBranch)
    }

    /** 一轮扫描的结果：要写入的条目、需要推的对象、sha→文件名、与仓库一致而跳过的数量 */
    private class Scanned(
        val writes: List<TreeWrite>,
        val pending: List<Pending>,
        val labelOf: Map<String, String>,
        val unchanged: Int,
    )

    /**
     * 流式算完每个文件的 blob sha 与权限位，并滤掉与仓库内容一致的项。
     * 两条上传路（普通上传、空仓库起步）都用它，避免这套要小心处理读取的逻辑出现两份。
     * [onFile] 在每开始一个文件时回调（已处理数、累计字节、文件名），界面用它显示真实进度。
     */
    private suspend fun scan(
        files: List<UploadItem>,
        open: (UploadItem) -> InputStream?,
        snapshot: Map<String, RepoBlob>?,
        onFile: (Int, Long, String) -> Unit,
    ): Scanned {
        val writes = ArrayList<TreeWrite>(files.size)
        val pending = LinkedHashMap<String, Pending>()
        val labelOf = HashMap<String, String>()
        var unchanged = 0
        var bytes = 0L
        files.forEachIndexed { i, f ->
            onFile(i, bytes, f.name)
            val hashed = try {
                withContext(Dispatchers.IO) { hash({ open(f) }, f.size) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                throw ApiException(e.code, "${f.name}：${e.message}")
            } catch (e: Throwable) {
                throw ApiException(-1, "读取 ${f.name} 失败：" + (e.message ?: "无法读取该文件"))
            }
            bytes += hashed.size
            val existing = snapshot?.get(f.path)
            val mode = modeOf(f.path, hashed.head, existing)
            if (!needsWrite(hashed.sha, mode, existing)) { unchanged++; return@forEachIndexed }
            // 内容完全相同的文件只推一个对象，和 git 一致；实测一个 pack 里放重复对象会让
            // 服务端 index-pack 整包失败，所以去重不是优化而是必须
            pending.getOrPut(hashed.sha) { Pending(hashed.sha, hashed.size) { open(f) } }
            labelOf.putIfAbsent(hashed.sha, f.name)
            writes.add(TreeWrite(f.path, mode, hashed.sha))
        }
        return Scanned(writes, pending.values.toList(), labelOf, unchanged)
    }

    /**
     * 空仓库起步。没有提交可当基准，也没有 ref 可借（借用指令要求那条 ref 真的存在），
     * 所以本地把 tree 与 commit 对象算出来，和 blob 一起放进**同一个 pack**，
     * 用 `ZERO → 新提交 refs/heads/<分支>` 的建分支指令一次推上去 —— 就是 `git push`
     * 初始化仓库时做的事，实测 GitHub 会 `unpack ok` 并把分支建出来。
     *
     * 这里自己拼树是安全的：空仓库里没有别人的文件，不可能漏保留谁。非空仓库仍然走
     * 服务端的 base_tree 合并。
     */
    private suspend fun bootstrap(
        owner: String,
        name: String,
        targetBranch: String,
        files: List<UploadItem>,
        message: String,
        open: (UploadItem) -> InputStream?,
        onProgress: (UploadProgress) -> Unit,
    ): UploadResult {
        val plan = planBootstrap(files, message, open, onProgress)
        onProgress(UploadProgress(files.size, files.size, plan.total, plan.total,
            "创建分支 $targetBranch", UploadStage.Sending))
        val receipt = GitPack.push(owner, name, GitPack.createCommand(targetBranch, plan.commitSha), plan.objects)
        // 这一条指令是真的要把分支建出来的：没建出来就是失败，不能只当对象落库就算成功
        // 只抛服务端原文，由界面统一 explain()：这里套一层、界面再套一层会变成
        // 「上传未完成。上传未完成。」
        receipt.failure?.let { throw ApiException(-1, it) }
        if (!receipt.refUpdated)
            throw ApiException(-1, receipt.detail ?: "分支 $targetBranch 没有建出来")
        return UploadResult(plan.commitSha, plan.written, plan.unchanged, targetBranch)
    }

    /** 空仓库起步要推的东西：根提交 sha、随 pack 一起送的对象、写入/跳过的数量、合计字节 */
    internal class BootPlan(
        val commitSha: String,
        val objects: List<Pair<String, PackObject>>,
        val written: Int,
        val unchanged: Int,
        val total: Long,
    )

    /**
     * 为空仓库起步准备 pack 内容：tree 与 commit 在本地算出来，和 blob 一起带上。
     * blob 仍然流式读，不进内存。
     *
     * 单独成为一个函数，是为了让 E2E（GitBootstrapE2ETest）能把**这一段真实产物**写成请求体
     * 发给 GitHub：如果测试里照抄一遍构造逻辑，那份抄的通了不等于生产的通了。
     */
    internal suspend fun planBootstrap(
        files: List<UploadItem>,
        message: String,
        open: (UploadItem) -> InputStream?,
        onProgress: (UploadProgress) -> Unit = {},
    ): BootPlan {
        val total = files.sumOf { it.size }.coerceAtLeast(0)
        val scanned = scan(files, open, null) { d, b, n ->
            onProgress(UploadProgress(d, files.size, b.coerceAtMost(total), total, n, UploadStage.Reading))
        }
        if (scanned.writes.isEmpty()) throw ApiException(400, "没有需要上传的文件")

        val entries = scanned.writes.map { GitTree.Entry(it.path.split('/'), it.mode, it.sha) }
        val built = withContext(Dispatchers.Default) { GitTree.buildRootTree(entries) }
        // 作者身份只影响首个提交显示成谁；拿不到（未登录或断网）就用登录名，GitHub 照样接受
        val ident = withContext(Dispatchers.IO) {
            val me = runCatching { GitHub.viewer() }.getOrNull()
            identOf(me?.login?.takeIf { it.isNotBlank() } ?: Session.login ?: "MobileGH", me?.id ?: 0L,
                java.time.ZonedDateTime.now().toEpochSecond())
        }
        val commit = withContext(Dispatchers.Default) {
            GitTree.commitBytes(built.sha, messageWithSignature(message), emptyList(), ident)
        }
        val commitSha = GitTree.commitSha(commit)

        val objects = scanned.pending.map {
            it.sha to PackObject(GitPack.TYPE_BLOB, it.size, it.open)
        } + built.trees.map {
            GitTree.objectSha("tree", it.bytes) to PackObject(GitPack.TYPE_TREE, it.size) { it.bytes.inputStream() }
        } + listOf(
            commitSha to PackObject(GitPack.TYPE_COMMIT, commit.size.toLong()) { commit.inputStream() },
        )
        return BootPlan(commitSha, objects, scanned.writes.size, scanned.unchanged, total)
    }
}
