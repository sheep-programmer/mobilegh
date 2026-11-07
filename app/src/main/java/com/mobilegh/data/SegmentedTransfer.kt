package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.cancellation.CancellationException

/**
 * 分块规划（纯函数，便于单测）。
 *
 * 参考 aria2 的做法：文件切成若干固定大小的分块，多个连接从同一个队列里领取分块下载，
 * 而不是把文件切成几大段、每个连接只负责一段。这样快的连接会多下几块，慢的连接不会拖住整体，
 * 也不会因为段数少而几乎没有并行。aria2 的规则是文件不足 2 × 最小分块时不切分，这里沿用同样的门槛。
 */
object SegmentPlan {
    /** 最小分块，与 aria2 的 piece-length 默认值一致 */
    internal const val MIN_PIECE = 1L * 1024 * 1024

    /**
     * 最大分块。分块越大，暂停或断网时丢掉的未完成字节越多；镜像上单次请求首包要 2–4 秒，分块太小又会被请求开销拖慢。
     */
    internal const val MAX_PIECE = 16L * 1024 * 1024

    /** 默认并发连接数 */
    const val DEFAULT_SEGMENTS = 8

    /** 设置里能选的上限；再高容易被镜像或 GitHub 限流 */
    const val MAX_SEGMENTS = 16

    /** 设置页可选的线程数 */
    val THREAD_CHOICES = listOf(1, 2, 4, 6, 8, 12, 16)

    /** 文件至少为两个最小分块时才并行，与 aria2 的 2 × min-split-size 规则一致 */
    fun worthSplitting(total: Long): Boolean = total >= 2 * MIN_PIECE

    /**
     * 分块大小：让每个连接大约分到两块，这样快的连接能接着领下一块。
     * 夹在 [MIN_PIECE, MAX_PIECE] 之间。
     */
    fun pieceSize(total: Long, connections: Int): Long {
        val target = total / (connections.coerceIn(1, MAX_SEGMENTS) * 2L)
        return target.coerceIn(MIN_PIECE, MAX_PIECE)
    }

    /** 把 total 字节切成 pieceSize 大小的分块，最后一块可能较小 */
    fun pieces(total: Long, pieceSize: Long): List<LongRange> {
        require(pieceSize > 0)
        val out = ArrayList<LongRange>(((total + pieceSize - 1) / pieceSize).toInt())
        var start = 0L
        while (start < total) {
            val end = minOf(total, start + pieceSize) - 1
            out.add(start..end)
            start = end + 1
        }
        return out
    }
}

/**
 * 多连接并行下载（分块队列）。
 *
 * - 所有分块写进同一个预分配的临时文件（`<name>.mt`），每块有各自的起点，不会相互覆盖。
 * - 每块已写入的字节数记录在旁边的计划文件（`<name>.mt.plan`）里。暂停、退出、换网络、改线程数之后，
 *   按记录只补没下完的字节；分块大小在第一次规划时确定，之后不随线程数变化。
 * - 单块失败在本块内按退避重试（从已写入的位置续传）；重试耗尽才让整个任务失败。
 * - 全部分块写满后，临时文件改名为 [partial]，与单连接下载的产物一致。
 */
class SegmentedTransfer(
    private val client: OkHttpClient,
    /** 同时进行的连接数（工作协程数） */
    val maxSegments: Int = SegmentPlan.DEFAULT_SEGMENTS,
    private val retryBaseMillis: Long = 1000L,
) {
    /** [onProgress] 的 downloaded 是各分块已写入字节之和，不会因重试而重复计数 */
    suspend fun run(
        url: String,
        headers: Map<String, String>,
        partial: File,
        total: Long,
        rejectHtml: Boolean = false,
        onProgress: (Long, Long) -> Unit,
    ): TransferResult = withContext(Dispatchers.IO) {
        if (total <= 0) return@withContext TransferResult.Failed("unknown size", retryable = false)
        val dir = partial.parentFile ?: return@withContext TransferResult.Failed("no temp dir", retryable = false)
        val work = File(dir, workName(partial))
        val plan = planFile(partial)
        val state = openState(work, plan, total, onProgress)

        try {
            fetchAll(url, headers, work, state, rejectHtml)
        } catch (e: CancellationException) {
            state.save(plan)
            throw e
        } catch (e: FatalSegmentException) {
            state.save(plan)
            return@withContext TransferResult.Failed(e.message ?: "segment failed", retryable = false, httpCode = e.httpCode)
        } catch (e: IOException) {
            state.save(plan)
            currentCoroutineContext().ensureActive()
            return@withContext TransferResult.Failed(e.message ?: "segment failed", retryable = true)
        }
        state.finish()
        if (!state.complete()) return@withContext TransferResult.Failed("incomplete download", retryable = true)
        publish(work, partial, plan)
    }

    /** 工作协程从共享队列领取分块，直到队列为空；任何一块失败会取消其余协程 */
    private suspend fun fetchAll(url: String, headers: Map<String, String>, work: File, state: PieceState, rejectHtml: Boolean) =
        coroutineScope {
            val queue = ConcurrentLinkedQueue(state.pending())
            val workers = minOf(maxSegments.coerceAtLeast(1), queue.size)
            repeat(workers) {
                launch {
                    while (true) {
                        val index = queue.poll() ?: break
                        downloadPiece(url, headers, work, index, state, rejectHtml)
                    }
                }
            }
        }

    /** 单块下载：失败时按退避重试，每次从这一块已写入的位置继续 */
    private suspend fun downloadPiece(
        url: String,
        headers: Map<String, String>,
        work: File,
        index: Int,
        state: PieceState,
        rejectHtml: Boolean,
    ) {
        var failures = 0
        while (true) {
            if (state.isDone(index)) return
            try {
                fetchPieceOnce(url, headers, work, index, state, rejectHtml)
                return
            } catch (e: FatalSegmentException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures >= SEGMENT_ATTEMPTS) throw e
                // 网络切换或断开后空闲连接已经失效：丢掉，下一次重新建连
                client.connectionPool.evictAll()
                delay(retryBaseMillis shl (failures - 1))
            }
        }
    }

    /** 一次请求：从分块内已写入的字节之后开始，写到分块末尾 */
    private suspend fun fetchPieceOnce(
        url: String,
        headers: Map<String, String>,
        work: File,
        index: Int,
        state: PieceState,
        rejectHtml: Boolean,
    ) = coroutineScope {
        val range = state.pieces[index]
        val have = state.done(index)
        val offset = range.first + have
        val request = Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$offset-${range.last}")
            .apply { headers.forEach { (n, v) -> header(n, v) } }
            .build()
        val call = client.newCall(request)
        // 协程取消时关闭 Call：阻塞中的读取立即失败（与 ResumableTransfer 相同的做法）
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.await().use { resp ->
                when (resp.code) {
                    200, 206 -> Unit
                    // 文件内容与记录对不上（如上游文件已被替换）：这一块从头重来
                    416 -> { state.reset(index); throw IOException("HTTP 416") }
                    408, 425, 429, in 500..599 -> throw IOException("HTTP ${resp.code}")
                    else -> throw FatalSegmentException("HTTP ${resp.code}", resp.code)
                }
                if (rejectHtml && isHtmlResponse(resp)) throw FatalSegmentException("HTML response")
                RandomAccessFile(work, "rw").use { raf ->
                    raf.seek(offset)
                    resp.body.byteStream().use { input ->
                        // 服务器忽略了 Range 并返回整文件（200）：跳过文件开头到本块起点的字节，只写本块需要的部分
                        if (resp.code == 200) skipFully(input, offset)
                        val want = range.last - offset + 1
                        val buf = ByteArray(BUFFER)
                        var written = 0L
                        while (written < want) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), want - written).toInt())
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n
                            // 先写盘、再记账：记录的字节一定已经在文件里
                            state.set(index, have + written)
                        }
                        if (written < want) throw IOException("piece $index ended at ${have + written} of ${range.last - range.first + 1}")
                    }
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /** 读取计划文件；与当前总长、分块大小对不上（文件已替换或计划损坏）时全部从头下 */
    private fun openState(work: File, plan: File, total: Long, onProgress: (Long, Long) -> Unit): PieceState {
        val saved = readPlan(plan)
        val usable = saved != null && saved.total == total && work.isFile && work.length() == total &&
            saved.done.size == SegmentPlan.pieces(total, saved.pieceSize).size
        val pieceSize = if (usable) saved!!.pieceSize else SegmentPlan.pieceSize(total, maxSegments)
        val count = SegmentPlan.pieces(total, pieceSize).size
        val done = if (usable) saved!!.done else LongArray(count)
        if (!usable) plan.delete()
        // 预分配：分块可以按任意顺序写进对应位置；稀疏文件在大多数文件系统上不占满实际空间
        RandomAccessFile(work, "rw").use { it.setLength(total) }
        return PieceState(total, pieceSize, done, onProgress)
    }

    /** 全部分块写满后，临时文件改名为最终的 partial；计划文件随之删除 */
    private fun publish(work: File, partial: File, plan: File): TransferResult {
        partial.delete()
        if (!work.renameTo(partial)) return TransferResult.Failed("cannot move download", retryable = true)
        plan.delete()
        return TransferResult.Complete
    }

    /** 分块的进度与完成状态。所有读写都经过同步方法，多个协程可以安全并发访问。 */
    internal class PieceState(
        val total: Long,
        val pieceSize: Long,
        private val done: LongArray,
        private val onProgress: (Long, Long) -> Unit,
    ) {
        val pieces: List<LongRange> = SegmentPlan.pieces(total, pieceSize)
        private var lastReport = 0L

        @Synchronized fun done(index: Int): Long = done[index]

        @Synchronized fun isDone(index: Int): Boolean = done[index] >= pieces[index].let { it.last - it.first + 1 }

        @Synchronized fun reset(index: Int) { done[index] = 0 }

        @Synchronized fun pending(): List<Int> = pieces.indices.filter { !isDone(it) }

        @Synchronized fun complete(): Boolean = pieces.indices.all { isDone(it) }

        @Synchronized fun set(index: Int, bytes: Long) {
            done[index] = bytes
            // 与单连接下载一样，进度最多每 200 毫秒上报一次
            val now = System.nanoTime()
            if (now - lastReport >= PROGRESS_NANOS) {
                lastReport = now
                onProgress(done.sum(), total)
            }
        }

        @Synchronized fun finish() = onProgress(done.sum(), total)

        @Synchronized fun save(plan: File) = writePlan(plan, total, pieceSize, done.copyOf())
    }

    companion object {
        private const val BUFFER = 64 * 1024
        /** 单块在一次任务内的最多尝试次数（含首次） */
        private const val SEGMENT_ATTEMPTS = 4
        private const val PROGRESS_NANOS = 200_000_000L
        private const val PLAN_VERSION = "v1"

        private fun workName(partial: File) = "${partial.name}.mt"

        private fun planFile(partial: File): File = File(partial.parentFile, "${partial.name}.mt.plan")

        private class Plan(val total: Long, val pieceSize: Long, val done: LongArray)

        private fun readPlan(file: File): Plan? = runCatching {
            val lines = file.readLines()
            require(lines.size >= 4 && lines[0] == PLAN_VERSION)
            val done = if (lines[3].isEmpty()) LongArray(0) else lines[3].split(',').map { it.toLong() }.toLongArray()
            Plan(lines[1].toLong(), lines[2].toLong(), done)
        }.getOrNull()

        /** 先写临时文件再替换，避免写到一半被中断后计划文件损坏 */
        private fun writePlan(file: File, total: Long, pieceSize: Long, done: LongArray) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(buildString {
                append(PLAN_VERSION).append('\n')
                append(total).append('\n')
                append(pieceSize).append('\n')
                append(done.joinToString(","))
                append('\n')
            })
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }

        private fun skipFully(input: InputStream, count: Long) {
            var left = count
            val buf = ByteArray(BUFFER)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("response shorter than the requested offset")
                left -= n
            }
        }

        /**
         * 删除某个 partial 对应的全部临时文件（取消下载、或改用单连接时调用）。
         * 同时清理旧版本留下的 `.seg` 文件。
         */
        fun cleanup(partial: File) {
            val dir = partial.parentFile ?: return
            File(dir, workName(partial)).delete()
            planFile(partial).delete()
            File(dir, planFile(partial).name + ".tmp").delete()
            dir.listFiles { f -> f.name.startsWith("${partial.name}.seg") }?.forEach { it.delete() }
        }
    }

    /** 不值得重试的错误（如 404、403、代理返回的 HTML 页面），直接让整个任务失败并换节点 */
    private class FatalSegmentException(message: String, val httpCode: Int? = null) : IOException(message)
}
