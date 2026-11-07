package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 附件上传的三步协议：用本地假服务器模拟 github.com 与 S3，检查顺序、请求头和 cookie 的去向。
 */
class WebAttachmentUploaderTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: ServerSocket
    private lateinit var base: String
    private val pool = Executors.newCachedThreadPool()
    private val seen = Collections.synchronizedList(mutableListOf<Seen>())

    @Before fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true, name = "attach-test-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: IOException) { break }
                pool.execute { handle(socket) }
            }
        }
    }

    @After fun tearDown() {
        server.close()
        pool.shutdownNow()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts[0]
            val path = parts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toInt() ?: 0
            val body = String(input.readNBytes(length), Charsets.ISO_8859_1)
            seen.add(Seen(method, path, headers, body))

            val out = s.getOutputStream()
            val cookie = headers["cookie"].orEmpty()
            val (status, payload) = when {
                method == "GET" && path == "/" ->
                    200 to if (cookie.contains("user_session=")) "<html><head><meta name=\"fetch-nonce\" content=\"v2:test-nonce\"></head></html>" else "<html>logged out</html>"
                method == "POST" && path == "/upload/policies/assets" ->
                    if (!cookie.contains("user_session=good")) 401 to "" else 201 to
                        """{"upload_url":"$base/s3/","asset":{"href":"$base/user-attachments/assets/abc"},"asset_upload_url":"/upload/assets/42","asset_upload_authenticity_token":"asset-token","upload_authenticity_token":"u","form":{"key":"k1","acl":"private","policy":"p1","Content-Type":"image/png"}}"""
                method == "POST" && path == "/s3/" -> 204 to ""
                method == "PUT" && path == "/upload/assets/42" ->
                    200 to """{"id":42,"href":"https://github.com/user-attachments/assets/abc"}"""
                else -> 404 to ""
            }
            out.write("HTTP/1.1 $status X\r\nContent-Length: ${payload.toByteArray().size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(payload.toByteArray())
            out.flush()
        }
    }

    @Test fun uploadsThroughTheThreeStepsAndReturnsTheAssetLink() = runBlocking {
        val up = WebAttachmentUploader(OkHttpClient(), base) { "user_session=good; logged_in=yes" }
        val href = up.upload(1394134859L, "a.png", "image/png", byteArrayOf(1, 2, 3))
        assertEquals("https://github.com/user-attachments/assets/abc", href)

        val policy = seen.first { it.path == "/upload/policies/assets" }
        assertEquals("user_session=good; logged_in=yes", policy.headers["cookie"])
        assertEquals("v2:test-nonce", policy.headers["x-fetch-nonce"])
        assertEquals("XMLHttpRequest", policy.headers["x-requested-with"])
        assertTrue(policy.body.contains("name=\"repository_id\""))
    }

    @Test fun s3StepKeepsThePolicyFieldOrderAndSendsNoGithubCookie() = runBlocking {
        WebAttachmentUploader(OkHttpClient(), base) { "user_session=good" }.upload(1L, "a.png", "image/png", byteArrayOf(9))
        val s3 = seen.first { it.path == "/s3/" }
        assertNull(s3.headers["cookie"])
        assertNull(s3.headers["x-fetch-nonce"])
        val order = listOf("name=\"key\"", "name=\"acl\"", "name=\"policy\"", "name=\"Content-Type\"", "name=\"file\"")
            .map { s3.body.indexOf(it) }
        assertTrue("fields out of order: $order", order.all { it >= 0 } && order == order.sorted())
    }

    @Test fun confirmStepUsesTheAssetToken() = runBlocking {
        WebAttachmentUploader(OkHttpClient(), base) { "user_session=good" }.upload(1L, "a.png", "image/png", byteArrayOf(9))
        val put = seen.first { it.method == "PUT" }
        assertTrue(put.body.contains("asset-token"))
    }

    @Test fun missingSessionIsReportedBeforeAnyRequest() = runBlocking {
        try {
            WebAttachmentUploader(OkHttpClient(), base) { null }.upload(1L, "a.png", "image/png", byteArrayOf(1))
            fail("expected WebSessionMissing")
        } catch (e: WebSessionMissing) {
            // expected
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun expiredSessionIsReportedAsMissingSession() = runBlocking {
        try {
            WebAttachmentUploader(OkHttpClient(), base) { "user_session=expired" }.upload(1L, "a.png", "image/png", byteArrayOf(1))
            fail("expected WebSessionMissing")
        } catch (e: WebSessionMissing) {
            // expected
        }
    }
}
