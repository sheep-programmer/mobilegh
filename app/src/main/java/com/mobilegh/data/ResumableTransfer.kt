package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface TransferResult {
    /** partial file now holds the complete content */
    data object Complete : TransferResult
    /** retries exhausted or non-retryable error; partial file is kept */
    data class Failed(val message: String, val retryable: Boolean, val httpCode: Int? = null) : TransferResult
}

private const val BUFFER_SIZE = 64 * 1024
private const val PROGRESS_INTERVAL_NANOS = 200_000_000L
private val CONTENT_RANGE_TOTAL = Regex("""bytes\s+\d+-\d+/(\d+|\*)""")
private val UNSATISFIED_RANGE_TOTAL = Regex("""bytes\s+\*/(\d+)""")

/** 可重试的 HTTP 状态码，供重试耗尽后填充 Failed.httpCode */
private class RetryableHttpException(val code: Int) : IOException("HTTP $code")

/** 响应是否是 HTML 页面。GitHub 的文件、压缩包和 Release 资源都不是 text/html，所以它只可能是代理的错误页。 */
internal fun isHtmlResponse(response: Response): Boolean =
    response.header("Content-Type")?.trimStart()?.lowercase()?.startsWith("text/html") == true

/** 断点续传下载：partial 文件保留已写入字节，重试与取消都从它继续 */
class ResumableTransfer(
    private val client: OkHttpClient,
    private val retryBaseMillis: Long = 1000L,
) {
    suspend fun run(
        url: String,
        headers: Map<String, String>,
        partial: File,
        maxAttempts: Int = 3,
        rejectHtml: Boolean = false,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): TransferResult = withContext(Dispatchers.IO) {
        val progress = Progress(onProgress)
        val result = transfer(url, headers, partial, maxAttempts, progress, rejectHtml)
        progress.finish(partial.length())
        result
    }

    /**
     * 先探测总长与 Range 支持：支持则交给 [SegmentedTransfer] 并行分段（大文件明显更快），
     * 不支持或探测失败就按原有的整文件续传走。
     */
    suspend fun runParallel(
        url: String,
        headers: Map<String, String>,
        partial: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        segments: SegmentedTransfer = SegmentedTransfer(client),
        rejectHtml: Boolean = false,
    ): TransferResult = withContext(Dispatchers.IO) {
        // 线程数设为 1 时不探测，直接单连接续传
        if (segments.maxSegments <= 1) return@withContext run(url, headers, partial, onProgress = onProgress)
        val probe = Request.Builder().url(url)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-0")
            .apply { headers.forEach { (n, v) -> header(n, v) } }
            .build()
        val probeResult = try {
            client.newCall(probe).await().use { resp ->
                val total = CONTENT_RANGE_TOTAL.find(resp.header("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (resp.code == 206 && total != null && SegmentPlan.worthSplitting(total)) total else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // 网络不通（比如刚从 WiFi 切到流量）：报告为可重试失败，由上层换节点。
            // 不能退回单连接从头下，那样已经下好的分段会被白白重来。
            currentCoroutineContext().ensureActive()
            return@withContext TransferResult.Failed(e.message ?: "probe failed", retryable = true)
        }
        if (probeResult == null) {
            // 服务器不支持分段（或文件很小）：按单连接续传，分段残留的文件不再使用
            SegmentedTransfer.cleanup(partial)
            run(url, headers, partial, rejectHtml = rejectHtml, onProgress = onProgress)
        } else {
            segments.run(url, headers, partial, probeResult, rejectHtml = rejectHtml, onProgress = onProgress)
        }
    }

    private suspend fun transfer(
        url: String,
        headers: Map<String, String>,
        partial: File,
        maxAttempts: Int,
        progress: Progress,
        rejectHtml: Boolean,
    ): TransferResult {
        var failures = 0
        while (true) {
            try {
                // null 表示需要从 0 重新请求（不计入重试次数）
                return fetch(url, headers, partial, progress, rejectHtml) ?: continue
            } catch (e: IOException) {
                // 取消会关闭连接并产生 IOException，这里统一转为 CancellationException
                currentCoroutineContext().ensureActive()
                if (++failures >= maxAttempts) {
                    return TransferResult.Failed(
                        message = e.message ?: "Transfer failed",
                        retryable = true,
                        httpCode = (e as? RetryableHttpException)?.code,
                    )
                }
                delay(retryBaseMillis shl (failures - 1))
            }
        }
    }

    private suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        partial: File,
        progress: Progress,
        rejectHtml: Boolean,
    ): TransferResult? = coroutineScope {
        val offset = partial.length()
        val request = Request.Builder()
            .url(url)
            // 避免 gzip 透明解压破坏字节偏移
            .header("Accept-Encoding", "identity")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        val call = client.newCall(request)
        // 协程取消时关闭 Call，使阻塞中的 body 读取立即失败
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use { response -> consume(response, partial, offset, progress, rejectHtml) }
        } finally {
            watcher.cancel()
        }
    }

    private fun consume(response: Response, partial: File, offset: Long, progress: Progress, rejectHtml: Boolean): TransferResult? {
        val code = response.code
        if (code == 416) {
            if (offset == 0L) return TransferResult.Failed("HTTP 416 without a Range request", retryable = false, httpCode = code)
            val total = UNSATISFIED_RANGE_TOTAL.find(response.header("Content-Range").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()
            if (total == offset) {
                progress.total = offset
                return TransferResult.Complete
            }
            partial.delete()
            return null
        }
        if (code != 200 && code != 206) {
            val retryable = code == 408 || code == 425 || code == 429 || code in 500..599
            if (retryable) throw RetryableHttpException(code)
            return TransferResult.Failed("HTTP $code", retryable = false, httpCode = code)
        }

        // 经加速节点下载时，代理的错误页常以 200 返回 HTML；那不是文件内容，直接失败，交给上层换节点
        if (rejectHtml && isHtmlResponse(response)) return TransferResult.Failed("HTML response", retryable = false)

        // 经加速节点续传时，节点若忽略 Range 返回整文件（200），截断重来会把已下好的字节全部丢掉（即「又从头下」）。
        // 这里判为可重试失败，由上层换到下一个候选节点（直连 GitHub 支持 Range），已下的部分保留。
        if (rejectHtml && code == 200 && offset > 0) return TransferResult.Failed("proxy ignored Range", retryable = true)

        // 206 从 offset 追加；200 表示服务器忽略了 Range，需要截断重来（直连或私有下载的最后兜底）
        val start = if (code == 206) offset else 0L
        val total = if (code == 206) {
            CONTENT_RANGE_TOTAL.find(response.header("Content-Range").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        } else {
            response.body.contentLength()
        }
        progress.total = total

        var written = start
        BufferedOutputStream(FileOutputStream(partial, start > 0), BUFFER_SIZE).use { out ->
            response.body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    if (progress.isDue()) {
                        out.flush()
                        progress.report(written)
                    }
                }
            }
        }

        // 总长已知但未写满，说明连接提前断开，交给重试逻辑续传
        val size = partial.length()
        if (total >= 0 && size != total) throw IOException("Stream ended at $size of $total bytes")
        return TransferResult.Complete
    }

    private class Progress(private val onProgress: (Long, Long) -> Unit) {
        var total = -1L
        private var lastReport = System.nanoTime() - PROGRESS_INTERVAL_NANOS

        fun isDue(): Boolean = System.nanoTime() - lastReport >= PROGRESS_INTERVAL_NANOS

        fun report(downloaded: Long) {
            lastReport = System.nanoTime()
            onProgress(downloaded, total)
        }

        fun finish(downloaded: Long) = onProgress(downloaded, total)
    }
}
