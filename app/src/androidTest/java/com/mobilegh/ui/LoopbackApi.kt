package com.mobilegh.ui

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue

internal class LoopbackApi(
    private val handler: (String, String, String) -> Pair<Int, String>,
) {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val errors = ConcurrentLinkedQueue<Throwable>()
    private val requestLog = mutableListOf<String>()
    private val requestBodyLog = mutableListOf<String>()
    val requests: List<String>
        get() = synchronized(requestLog) { requestLog.toList() }
    val requestBodies: List<String>
        get() = synchronized(requestBodyLog) { requestBodyLog.toList() }
    val base = "http://127.0.0.1:${server.localPort}"
    private val worker = thread(start = true, isDaemon = true) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            thread(start = true, isDaemon = true) {
                try {
                    socket.use {
                        val input = it.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                        val first = input.readLine()
                        val parts = first.split(' ')
                        val headers = generateSequence { input.readLine().takeUnless(String?::isNullOrEmpty) }
                            .map { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }.toMap()
                        val length = headers["content-length"]?.toInt() ?: 0
                        val chars = CharArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(chars, offset, length - offset)
                            if (count < 0) break
                            offset += count
                        }
                        val body = String(chars)
                        synchronized(requestLog) { requestLog += "${parts[0]} ${parts[1]}" }
                        synchronized(requestBodyLog) { requestBodyLog += "${parts[0]} ${parts[1]} $body" }
                        val (code, response) = handler(parts[0], parts[1], body)
                        val bytes = response.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
    }

    fun close() {
        server.close()
        worker.join(2_000)
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
