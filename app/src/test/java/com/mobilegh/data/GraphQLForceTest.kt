package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.concurrent.thread

class GraphQLForceTest {
    private fun request(force: Boolean, root: Boolean = false): Pair<String?, JsonObject> = runBlocking {
        var cacheControl: String? = null
        var variables = JsonObject(emptyMap())
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = thread(start = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                input.readLine()
                val headers = generateSequence {
                    input.readLine().takeUnless { it.isNullOrEmpty() }
                }.map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }.toMap()
                cacheControl = headers["cache-control"]
                val body = CharArray(headers.getValue("content-length").toInt())
                var offset = 0
                while (offset < body.size) {
                    val read = input.read(body, offset, body.size - offset)
                    check(read >= 0)
                    offset += read
                }
                variables = Api.plain.parseToJsonElement(body.concatToString()).jsonObject.obj("variables")!!
                val response = """{"data":{"viewer":{"login":"mona"}}}""".toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(response)
                    flush()
                }
            }
        }
        val previousBase = Net.apiProxy
        try {
            Api.cacheDir = Files.createTempDirectory("graphql-force-test").toFile().apply { deleteOnExit() }
            Net.apiProxy = "http://127.0.0.1:${server.localPort}"
            if (root) Api.graphqlRoot("query { viewer { login } }", mapOf("login" to "mona"), force = force)
            else Api.graphql("query { viewer { login } }", mapOf("login" to "mona"), force = force)
        } finally {
            server.close()
            worker.join(5_000)
            Net.apiProxy = previousBase
        }
        cacheControl to variables
    }

    @Test fun forcedGraphQlRootUsesHttpNoCacheWithoutInventingAVariable() {
        val (cacheControl, variables) = request(force = true, root = true)
        assertEquals("no-cache", cacheControl)
        assertEquals("mona", variables.str("login"))
        assertFalse(variables.containsKey("force"))
    }

    @Test fun defaultGraphQlLeavesHttpCachingAlone() {
        val (cacheControl, variables) = request(force = false)
        assertNull(cacheControl)
        assertEquals("mona", variables.str("login"))
        assertFalse(variables.containsKey("force"))
    }

    @Test fun forcedGraphQlDataAlsoUsesHttpNoCache() {
        val (cacheControl, _) = request(force = true)
        assertEquals("no-cache", cacheControl)
    }
}
