package com.mobilegh.ui

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue

internal class LoopbackApi private constructor(
    private val handler: ResponseHandler,
) : AutoCloseable {
    constructor(handler: (String, String, String) -> Pair<Int, String>) : this(ResponseHandler { method, path, body ->
        val (code, text) = handler(method, path, body)
        Response(code, "application/json", text.toByteArray())
    })

    data class Response(val code: Int, val mime: String, val bytes: ByteArray = byteArrayOf())
    private fun interface ResponseHandler {
        fun respond(method: String, path: String, body: String): Response
    }

    companion object {
        fun media(handler: (String) -> Response) = LoopbackApi(ResponseHandler { _, path, _ -> handler(path) })
    }
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
                        val first = input.readLine() ?: return@use
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
                        val (code, mime, bytes) = handler.respond(parts[0], parts[1], body)
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $code Test\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
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

    override fun close() {
        server.close()
        worker.join(2_000)
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
