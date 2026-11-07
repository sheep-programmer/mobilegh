package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 分块下载的端到端测试：在本机起一个只支持 GET + Range 的最小 HTTP 服务（Android 单测环境里没有 com.sun.net.httpserver）。
 * 每个用例都检查拼出来的文件与源数据逐字节一致；并发用例还检查服务器同时收到的连接数。
 */
class SegmentedTransferTest {
    private lateinit var server: ServerSocket
    private lateinit var pool: ExecutorService
    private lateinit var dir: File
    private lateinit var data: ByteArray
    private val requestedRanges = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** 当前与历史最大同时在处理的连接数：用来证明分块确实是并发下载的 */
    private val activeNow = AtomicInteger()
    private val peakActive = AtomicInteger()
    @Volatile private var ignoreRange = false
    /** 模拟代理的错误页：所有请求都回 200 text/html */
    @Volatile private var servePage = false
    /** 模拟连接中途断开：第一个从中间开始的请求只发一半就断 */
    @Volatile private var dropOnce = false
    /** 模拟服务器故障：起点不小于该偏移的 Range 请求一律回 500 */
    @Volatile private var failFrom: Long? = null
    private var url = ""
    private val client = OkHttpClient()

    @Before fun setUp() {
        dir = Files.createTempDirectory("seg-test").toFile()
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        pool = Executors.newCachedThreadPool()
        thread(isDaemon = true, name = "seg-test-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                pool.execute { handle(socket) }
            }
        }
        url = "http://127.0.0.1:${server.localPort}/file"
    }

    @After fun tearDown() {
        server.close()
        pool.shutdownNow()
        dir.deleteRecursively()
    }

    private fun randomBytes(size: Int) = ByteArray(size).also { Random(42).nextBytes(it) }

    /** 读一个 GET 请求，按 Range 回 206（或忽略 Range 回 200 整文件），然后关闭连接 */
    private fun handle(socket: Socket) {
        val now = activeNow.incrementAndGet()
        peakActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val range = lines.drop(1).firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                requestedRanges.add(range.orEmpty())
                // 模拟网络往返：让并发的连接真正重叠，否则测不出并行
                Thread.sleep(RESPONSE_DELAY_MS)
                val out = s.getOutputStream()
                if (servePage) {
                    val html = "<!DOCTYPE html><html><body>Error</body></html>".toByteArray()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(html)
                } else if (range == null || ignoreRange) {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(data)
                } else {
                    val m = Regex("bytes=(\\d+)-(\\d*)").find(range) ?: error("bad range $range")
                    val start = m.groupValues[1].toInt()
                    val end = m.groupValues[2].ifEmpty { (data.size - 1).toString() }.toInt().coerceAtMost(data.size - 1)
                    val body = data.copyOfRange(start, end + 1)
                    if (failFrom != null && start >= failFrom!!) {
                        out.write("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    out.write(
                        ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-$end/${data.size}\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(),
                    )
                    if (dropOnce && start > 0) {
                        dropOnce = false
                        // 声明了完整长度却只发一半：客户端会看到连接提前断开
                        out.write(body, 0, body.size / 2)
                        out.flush()
                        return
                    }
                    out.write(body)
                }
                out.flush()
            }
        } catch (_: Exception) {
            // 客户端提前断开（如 200 整文件只需要其中一段）属于正常情况
        } finally {
            activeNow.decrementAndGet()
        }
    }

    @Test fun piecesAreDownloadedByConcurrentConnections() = runBlocking {
        data = randomBytes(16 * 1024 * 1024)
        val partial = File(dir, "p.part")
        val result = SegmentedTransfer(client, maxSegments = 4).run(url, emptyMap(), partial, data.size.toLong()) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(data, partial.readBytes())
        // 同时在飞的连接数必须超过 1（真正并行），且不超过设置的连接数
        assertTrue("expected overlapping connections, peak=${peakActive.get()}", peakActive.get() >= 2)
        assertTrue("more connections than the thread setting: ${peakActive.get()}", peakActive.get() <= 4)
    }

    @Test fun uncommonSizesReassembleTheExactBytesAndCleanUpTheWorkFiles() = runBlocking {
        data = randomBytes(16 * 1024 * 1024 + 123)
        val partial = File(dir, "a.part")
        val result = SegmentedTransfer(client, maxSegments = 4).run(url, emptyMap(), partial, data.size.toLong()) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(data, partial.readBytes())
        // 完成后临时文件与计划文件都被清理
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".mt") || it.name.endsWith(".mt.plan") })
    }

    @Test fun smallFilesAreStillDownloadedCompletely() = runBlocking {
        // 6 MiB 超过两个最小分块，会按分块并行；结果必须与源数据一致
        data = randomBytes(6 * 1024 * 1024)
        val partial = File(dir, "b.part")
        val result = ResumableTransfer(client).runParallel(url, emptyMap(), partial, { _, _ -> }, SegmentedTransfer(client, maxSegments = 8))
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(data, partial.readBytes())
    }

    @Test fun singleConnectionSettingUsesOneRequestAndStillCorrect() = runBlocking {
        data = randomBytes(9 * 1024 * 1024)
        val partial = File(dir, "c.part")
        val result = ResumableTransfer(client).runParallel(url, emptyMap(), partial, { _, _ -> }, SegmentedTransfer(client, maxSegments = 1))
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(data, partial.readBytes())
        assertEquals(1, peakActive.get())
    }

    @Test fun serverIgnoringRangeIsStillReassembledCorrectly() = runBlocking {
        // 200 整文件应答时，每块要跳过前面的字节，只写自己那一块
        ignoreRange = true
        try {
            data = randomBytes(9 * 1024 * 1024 + 7)
            val partial = File(dir, "d.part")
            val result = SegmentedTransfer(client, maxSegments = 3).run(url, emptyMap(), partial, data.size.toLong()) { _, _ -> }
            assertEquals(TransferResult.Complete, result)
            assertArrayEquals(data, partial.readBytes())
        } finally {
            ignoreRange = false
        }
    }

    @Test fun partiallyDownloadedPiecesAreResumedNotRestarted() = runBlocking {
        data = randomBytes(8 * 1024 * 1024)
        val pieceSize = 2L * 1024 * 1024
        val prefixLen = 1024L * 1024
        // 上次中断时：第 0 块写了前 1 MiB；工作文件已预分配，计划文件记录了这些字节
        val work = ByteArray(data.size)
        System.arraycopy(data, 0, work, 0, prefixLen.toInt())
        File(dir, "e.part.mt").writeBytes(work)
        File(dir, "e.part.mt.plan").writeText("v1\n${data.size}\n$pieceSize\n$prefixLen,0,0,0\n")
        val partial = File(dir, "e.part")
        val result = SegmentedTransfer(client, maxSegments = 2).run(url, emptyMap(), partial, data.size.toLong()) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(data, partial.readBytes())
        // 第 0 块从 1 MiB 处续传，而不是从块起点重新下载
        assertTrue(requestedRanges.contains("bytes=$prefixLen-${pieceSize - 1}"))
        assertFalse(requestedRanges.contains("bytes=0-${pieceSize - 1}"))
    }

    @Test fun proxyErrorPageIsRejectedInsteadOfBeingSavedAsTheFile() = runBlocking {
        // 经加速节点时，200 的 HTML 错误页不是文件内容：两条路径都必须失败（交给上层换节点），不能发布成文件
        servePage = true
        try {
            data = randomBytes(9 * 1024 * 1024)
            val segmented = SegmentedTransfer(client, maxSegments = 4).run(url, emptyMap(), File(dir, "g.part"), data.size.toLong(), rejectHtml = true) { _, _ -> }
            assertTrue(segmented is TransferResult.Failed)
            assertFalse((segmented as TransferResult.Failed).retryable)

            val single = ResumableTransfer(client).run(url, emptyMap(), File(dir, "h.part"), rejectHtml = true) { _, _ -> }
            assertTrue(single is TransferResult.Failed)
            assertFalse((single as TransferResult.Failed).retryable)
        } finally {
            servePage = false
        }
    }

    @Test fun aMidwayDropIsRetriedWithoutCountingBytesTwice() = runBlocking {
        // 回归：重试时同一块的字节被重复计数，进度会超过文件总长（实测 109.7 MB / 77.4 MB）
        data = randomBytes(12 * 1024 * 1024)
        dropOnce = true
        val reports = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val partial = File(dir, "i.part")
        val result = SegmentedTransfer(client, maxSegments = 3, retryBaseMillis = 1)
            .run(url, emptyMap(), partial, data.size.toLong()) { done, _ -> reports.add(done) }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(data, partial.readBytes())
        assertTrue("progress exceeded the file size: ${reports.maxOrNull()}", reports.all { it <= data.size })
        assertEquals(data.size.toLong(), reports.last())
    }

    @Test fun resumeUsesTheSavedPlanEvenWhenTheThreadCountChanged() = runBlocking {
        // 回归：设置里改了线程数后续传，按新切分会和已下好的块错位，拼出坏文件
        data = randomBytes(16 * 1024 * 1024 + 9)
        val partial = File(dir, "j.part")
        // 第一次：前面的块能下，起点不小于 8 MiB 的块全部 500，重试耗尽后失败
        failFrom = 8L * 1024 * 1024
        val first = SegmentedTransfer(client, maxSegments = 4, retryBaseMillis = 1).run(url, emptyMap(), partial, data.size.toLong()) { _, _ -> }
        assertTrue(first is TransferResult.Failed)
        // 第二次：服务器恢复，线程数改成 2，应沿用保存的分块大小
        failFrom = null
        val second = SegmentedTransfer(client, maxSegments = 2, retryBaseMillis = 1).run(url, emptyMap(), partial, data.size.toLong()) { _, _ -> }
        assertEquals(TransferResult.Complete, second)
        assertArrayEquals(data, partial.readBytes())
    }

    @Test fun networkErrorWhileProbingIsReportedInsteadOfRestartingFromZero() = runBlocking {
        // 回归：探测失败（如刚切到流量）时退回单连接从头下，已下好的内容被白白重来
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val partial = File(dir, "k.part")
        val result = ResumableTransfer(client, retryBaseMillis = 1)
            .runParallel("http://127.0.0.1:$closedPort/file", emptyMap(), partial, { _, _ -> }, SegmentedTransfer(client, maxSegments = 4))
        assertTrue(result is TransferResult.Failed)
        assertTrue((result as TransferResult.Failed).retryable)
        assertFalse(partial.exists())
    }

    @Test fun aProxyIgnoringRangeOnResumeKeepsTheBytesAndFailsOver() = runBlocking {
        // 回归：经加速节点续传时，节点返回 200 整文件会被截断重下（用户看到的「又从头下」）。
        // 应保留已下的字节，并判为可重试失败，由上层换节点。
        data = randomBytes(6 * 1024 * 1024)
        val partial = File(dir, "l.part")
        val prefixLen = 1024 * 1024
        partial.writeBytes(data.copyOfRange(0, prefixLen))
        ignoreRange = true
        try {
            val result = ResumableTransfer(client).run(url, emptyMap(), partial, rejectHtml = true) { _, _ -> }
            assertTrue(result is TransferResult.Failed)
            assertTrue((result as TransferResult.Failed).retryable)
            assertEquals(prefixLen.toLong(), partial.length())
        } finally {
            ignoreRange = false
        }
    }

    @Test fun cleanupRemovesWorkFilesOnly() {
        val partial = File(dir, "f.part")
        File(dir, "f.part.mt").writeText("x")
        File(dir, "f.part.mt.plan").writeText("y")
        File(dir, "f.part.seg0").writeText("z")
        File(dir, "other.part").writeText("keep")
        SegmentedTransfer.cleanup(partial)
        assertEquals(listOf("other.part"), dir.list()!!.toList())
    }

    private companion object {
        /** 每个请求的模拟往返延迟 */
        const val RESPONSE_DELAY_MS = 30L
    }
}
