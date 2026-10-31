package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** 单个请求的回应器：手写的最小 HTTP/1.1 服务（Android 平台 jar 不含 com.sun.net.httpserver） */
private class Exchange(private val socket: Socket, val headers: Map<String, String>) {
    /** cutAfter 只发送前 N 字节即断开；paceMillis 大于 0 时分块慢速发送 */
    fun send(
        code: Int,
        body: ByteArray = ByteArray(0),
        extra: Map<String, String> = emptyMap(),
        cutAfter: Int? = null,
        paceMillis: Long = 0,
    ) {
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Status"}\r\n")
            append("Content-Length: ${body.size}\r\nConnection: close\r\n")
            extra.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        val end = cutAfter?.coerceAtMost(body.size) ?: body.size
        val chunk = if (paceMillis > 0) 8 * 1024 else maxOf(end, 1)
        var i = 0
        while (i < end) {
            val n = minOf(chunk, end - i)
            out.write(body, i, n)
            out.flush()
            i += n
            if (paceMillis > 0) Thread.sleep(paceMillis)
        }
    }
}

class ResumableTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private data class Seen(val range: String?, val auth: String?)

    private val payload = ByteArray(1024 * 1024) { ((it * 7 + it / 251) and 0xff).toByte() }
    private val seen = CopyOnWriteArrayList<Seen>()
    private val routes = ConcurrentHashMap<String, Exchange.() -> Unit>()
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    private lateinit var listener: ServerSocket
    private lateinit var pool: ExecutorService

    @Before fun startServer() {
        listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        pool = Executors.newCachedThreadPool()
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val socket = try {
                    listener.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { handle(socket) }
            }
        }
    }

    @After fun stopServer() {
        listener.close()
        pool.shutdown()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                val headers = HashMap<String, String>()
                generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                seen.add(Seen(headers["range"], headers["authorization"]))
                val exchange = Exchange(s, headers)
                val route = routes[path]
                if (route == null) exchange.send(404) else route(exchange)
            } catch (e: IOException) {
                // 客户端已取消，或服务端按测试要求主动截断
            }
        }
    }

    private fun url(path: String) = "http://127.0.0.1:${listener.localPort}$path"

    private fun transfer() = ResumableTransfer(client, retryBaseMillis = 10L)

    private fun serve(path: String, handler: Exchange.() -> Unit) {
        routes[path] = handler
    }

    private fun Exchange.rangeStart(): Int =
        headers["range"]?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toInt() } ?: 0

    /** 按 Range 返回 payload；honorRange 为 false 时忽略 Range 返回 200 */
    private fun Exchange.sendPayload(honorRange: Boolean = true, cutAfter: Int? = null, paceMillis: Long = 0) {
        val from = if (honorRange) rangeStart() else 0
        val body = payload.copyOfRange(from, payload.size)
        if (from > 0) {
            send(206, body, mapOf("Content-Range" to "bytes $from-${payload.size - 1}/${payload.size}"), cutAfter, paceMillis)
        } else {
            send(200, body, cutAfter = cutAfter, paceMillis = paceMillis)
        }
    }

    @Test fun freshDownloadCompletesWithPayloadBytes() = runBlocking {
        serve("/file") { sendPayload() }
        val partial = File(tmp.root, "fresh.part")
        val totals = mutableListOf<Long>()
        val result = transfer().run(url("/file"), emptyMap(), partial) { _, total -> totals.add(total) }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(payload, partial.readBytes())
        assertTrue(totals.isNotEmpty())
        assertTrue(totals.all { it == payload.size.toLong() })
    }

    @Test fun resumeAppendsFromExistingBytesWithRangeHeader() = runBlocking {
        serve("/file") { sendPayload() }
        val half = payload.size / 2
        val partial = File(tmp.root, "resume.part").apply { writeBytes(payload.copyOf(half)) }
        val result = transfer().run(url("/file"), emptyMap(), partial) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(payload, partial.readBytes())
        assertEquals("bytes=$half-", seen.single().range)
    }

    @Test fun serverIgnoringRangeTruncatesAndRestarts() = runBlocking {
        serve("/file") { sendPayload(honorRange = false) }
        val half = payload.size / 2
        val partial = File(tmp.root, "ignored.part").apply { writeBytes(payload.copyOf(half)) }
        val result = transfer().run(url("/file"), emptyMap(), partial) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(payload, partial.readBytes())
        assertEquals("bytes=$half-", seen.single().range)
    }

    @Test fun unsatisfiableRangeWithCompletePartialIsComplete() = runBlocking {
        serve("/file") {
            if (headers["range"] != null) {
                send(416, extra = mapOf("Content-Range" to "bytes */${payload.size}"))
            } else {
                sendPayload()
            }
        }
        val partial = File(tmp.root, "done.part").apply { writeBytes(payload) }
        val result = transfer().run(url("/file"), emptyMap(), partial) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(payload, partial.readBytes())
        assertEquals(listOf("bytes=${payload.size}-"), seen.map { it.range })
    }

    @Test fun notFoundFailsWithoutRetry() = runBlocking {
        serve("/missing") { send(404) }
        val result = transfer().run(url("/missing"), emptyMap(), File(tmp.root, "404.part"), maxAttempts = 3) { _, _ -> }
        assertEquals(TransferResult.Failed("HTTP 404", retryable = false, httpCode = 404), result)
        assertEquals(1, seen.size)
    }

    @Test fun transientMidBodyDisconnectsAreRetriedUntilComplete() = runBlocking {
        val attempts = AtomicInteger()
        serve("/file") {
            val n = attempts.incrementAndGet()
            sendPayload(cutAfter = if (n <= 2) 100_000 else null)
        }
        val partial = File(tmp.root, "flaky.part")
        val result = transfer().run(url("/file"), emptyMap(), partial, maxAttempts = 3) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertArrayEquals(payload, partial.readBytes())
        assertEquals(3, seen.size)
    }

    @Test fun cancellationKeepsPartialBytesAndResumesLater() = runBlocking {
        val slow = AtomicBoolean(true)
        serve("/file") { if (slow.get()) sendPayload(paceMillis = 10) else sendPayload() }
        val partial = File(tmp.root, "cancel.part")
        val firstBytes = CompletableDeferred<Unit>()
        var thrown: Throwable? = null
        val job = launch {
            try {
                transfer().run(url("/file"), emptyMap(), partial) { downloaded, _ ->
                    if (downloaded > 0) firstBytes.complete(Unit)
                }
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        firstBytes.await()
        job.cancelAndJoin()
        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        val cut = partial.length()
        assertTrue("partial length $cut", cut > 0 && cut < payload.size)

        slow.set(false)
        assertEquals(TransferResult.Complete, transfer().run(url("/file"), emptyMap(), partial) { _, _ -> })
        assertArrayEquals(payload, partial.readBytes())
        assertEquals("bytes=$cut-", seen.last().range)
    }

    @Test fun callerHeadersAreSentOnEveryAttempt() = runBlocking {
        val attempts = AtomicInteger()
        serve("/file") { sendPayload(cutAfter = if (attempts.incrementAndGet() == 1) 50_000 else null) }
        val headers = mapOf("Authorization" to "Bearer test-token", "User-Agent" to "mobilegh-test")
        val result = transfer().run(url("/file"), headers, File(tmp.root, "auth.part")) { _, _ -> }
        assertEquals(TransferResult.Complete, result)
        assertEquals(2, seen.size)
        assertEquals(listOf("Bearer test-token", "Bearer test-token"), seen.map { it.auth })
    }
}
