package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/** 分段下载的段位规划；纯函数便于单测 */
object SegmentPlan {
    internal const val MIN_SEGMENT = 8L * 1024 * 1024
    internal const val MAX_SEGMENTS = 4

    /**
     * 服务器支持 Range 且总长已知时才分段：至少 8 MiB 一段，最多 4 段。
     * 文件小或段数太多没有意义，返回 1 表示单段（走原有的整文件续传）。
     */
    fun plan(total: Long, rangesSupported: Boolean, segments: Int = MAX_SEGMENTS): List<LongRange> {
        if (!rangesSupported || total <= 0) return listOf(0L..-1L)
        val count = minOf(segments, (total / MIN_SEGMENT).toInt().coerceAtLeast(1))
        if (count <= 1) return listOf(0L until total)
        val base = total / count
        val extra = (total % count).toInt()
        val out = ArrayList<LongRange>(count)
        var start = 0L
        for (i in 0 until count) {
            val len = base + if (i < extra) 1 else 0
            out.add(start until start + len)
            start += len
        }
        return out
    }
}

/** 分段并行下载：每段独立请求 Range，各写各的临时文件，全部完成后按顺序拼进 partial。 */
class SegmentedTransfer(
    private val client: OkHttpClient,
    private val maxSegments: Int = SegmentPlan.MAX_SEGMENTS,
) {
    /** [onProgress] 的 downloaded 是已写入 partial 的字节数（各段之和） */
    suspend fun run(
        url: String,
        headers: Map<String, String>,
        partial: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): TransferResult = withContext(Dispatchers.IO) {
        val parts = SegmentPlan.plan(total, rangesSupported = true, segments = maxSegments)
        if (parts.size <= 1) return@withContext TransferResult.Complete
        val dir = partial.parentFile ?: return@withContext TransferResult.Failed("no temp dir", retryable = false)
        val prefix = "${partial.name}.seg"

        // 已完成的段直接复用（断点续传 / 重试）；复用前先校验长度
        val existing = LongArray(parts.size)
        parts.forEachIndexed { i, range ->
            val f = File(dir, "$prefix$i")
            existing[i] = if (f.isFile && f.length() == (range.last - range.first + 1)) f.length() else 0L
        }
        val doneBytes = existing.sum()

        try {
            kotlinx.coroutines.coroutineScope {
                val lanes = Semaphore(maxSegments)
                val progress = SharedProgress(doneBytes, total, onProgress)
                val jobs = parts.mapIndexed { i, range ->
                    async {
                        lanes.withPermit {
                            // 已完成的段不再上报进度：初始值 doneBytes 已经把它们算进去了
                            if (existing[i] > 0L) return@withPermit
                            downloadSegment(url, headers, File(dir, "$prefix$i"), range, progress)
                        }
                    }
                }
                jobs.forEach { it.await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            return@withContext TransferResult.Failed(e.message ?: "segment failed", retryable = true)
        }
        assemble(parts, partial, dir, prefix)
    }

    /** 单段下载：写进独立的段文件，已完成则跳过（服务器不支持按段续传，失败整段重来） */
    private suspend fun downloadSegment(
        url: String,
        headers: Map<String, String>,
        part: File,
        range: LongRange,
        progress: SharedProgress,
    ) = kotlinx.coroutines.coroutineScope {
        val request = Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=${range.first}-")
            .apply { headers.forEach { (n, v) -> header(n, v) } }
            .build()
        val call = client.newCall(request)
        // 协程取消时关闭 Call：阻塞中的读取立即失败（与 ResumableTransfer 相同的做法）
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.await().use { resp ->
                if (resp.code != 200 && resp.code != 206) throw IOException("HTTP ${resp.code}")
                var written = 0L
                var reported = 0L
                // 直接写 RandomAccessFile：段文件从 0 开始，写完关闭
                RandomAccessFile(part, "rw").use { raid ->
                    raid.setLength(0)
                    resp.body.byteStream().use { input ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raid.write(buf, 0, n)
                            written += n
                            if (progress.isDue()) {
                                progress.advance(written - reported)
                                reported = written
                            }
                        }
                    }
                }
                progress.advance(written - reported)
                val expect = if (resp.code == 206) range.last - range.first + 1 else resp.body.contentLength()
                if (resp.code == 206 && written != expect) throw IOException("segment ended at $written of $expect")
            }
        } finally {
            watcher.cancel()
        }
    }

    /** 全部段就绪后按顺序拼进 partial，拼完删掉段文件 */
    private fun assemble(parts: List<LongRange>, partial: File, dir: File, prefix: String): TransferResult {
        FileOutputStream(partial, false).use { out ->
            val buf = ByteArray(BUFFER)
            parts.forEachIndexed { i, _ ->
                File(dir, "$prefix$i").inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        parts.indices.forEach { File(dir, "$prefix$it").delete() }
        return TransferResult.Complete
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }

    /** 多段共享的进度：各协程推进自己的计数，到点统一上报一次 */
    private class SharedProgress(start: Long, private val total: Long, private val report: (Long, Long) -> Unit) {
        private val done = AtomicLong(start)
        private val last = java.util.concurrent.atomic.AtomicLong(System.nanoTime() - NANOS)

        fun advance(n: Long) {
            done.addAndGet(n)
            if (isDue()) report(done.get(), total)
        }

        fun isDue(): Boolean = System.nanoTime() - last.get() >= NANOS

        private companion object { const val NANOS = 200_000_000L }
    }
}
