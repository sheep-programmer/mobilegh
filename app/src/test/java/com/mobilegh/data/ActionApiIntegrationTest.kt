package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import com.mobilegh.MainDispatcherRule
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.concurrent.thread

class ActionApiIntegrationTest {
    @get:Rule val main = MainDispatcherRule()
    private var previousBase = ""

    @After fun restoreApi() {
        Net.apiProxy = previousBase
    }

    private fun serve(responses: List<Pair<Int, String>>, block: suspend () -> Unit): List<String> = runBlocking {
        val paths = mutableListOf<String>()
        val server = ServerSocket(0, responses.size.coerceAtLeast(1), InetAddress.getByName("127.0.0.1"))
        val worker = thread(start = true) {
            responses.forEach { (code, body) ->
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    paths += input.readLine().split(' ')[1]
                    val headers = generateSequence { input.readLine().takeUnless { it.isNullOrEmpty() } }
                        .map { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }.toMap()
                    val length = headers["content-length"]?.toInt() ?: 0
                    val chars = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val read = input.read(chars, offset, length - offset)
                        check(read >= 0)
                        offset += read
                    }
                    val bytes = body.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $code Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        previousBase = Net.apiProxy
        Api.cacheDir = Files.createTempDirectory("actions-api-test").toFile().apply { deleteOnExit() }
        Net.apiProxy = "http://127.0.0.1:${server.localPort}"
        try {
            block()
        } finally {
            server.close()
            worker.join(5_000)
            Net.apiProxy = previousBase
        }
        paths
    }

    private fun variablePage(page: Int, count: Int) = buildJsonObject {
        put("total_count", 31)
        putJsonArray("variables") {
            repeat(count) { index ->
                add(buildJsonObject {
                    put("name", "V${(page - 1) * 30 + index}")
                    put("value", "")
                    put("created_at", "c")
                    put("updated_at", "u")
                })
            }
        }
    }.toString()

    private fun secretPage(page: Int, count: Int, total: Int = count) = buildJsonObject {
        put("total_count", total)
        putJsonArray("secrets") {
            repeat(count) { index ->
                add(buildJsonObject { put("name", "S${(page - 1) * 100 + index}") })
            }
        }
    }.toString()

    @Test fun failedSecondSecretPageIsNotPresentedAsTheWholeList() {
        // 第二页失败时若吞掉异常，界面会显示一份「好像只有 100 条」的密钥列表，用户会以为其余密钥丢了。
        val paths = serve(listOf(200 to secretPage(1, 100, total = 150), 500 to """{"message":"server exploded"}""")) {
            try {
                GitHub.actionSecrets("octo", "hello", force = true)
                fail("a failed page must surface instead of returning a partial list")
            } catch (e: ApiException) {
                assertEquals(500, e.code)
            }
        }
        assertEquals(
            listOf(
                "/repos/octo/hello/actions/secrets?per_page=100&page=1",
                "/repos/octo/hello/actions/secrets?per_page=100&page=2",
            ),
            paths,
        )
    }

    @Test fun malformedSecretListIsNotShownAsAnEmptyList() {
        val paths = serve(listOf(200 to """{"total_count":2,"secrets":"not-a-list"}""")) {
            try {
                GitHub.actionSecrets("octo", "hello", force = true)
                fail("a malformed list must not be reported as an empty secret list")
            } catch (_: SerializationException) {
                // 解码失败必须抛出去；当成空列表会让「仓库里其实有密钥」显示成没有。
            }
        }
        assertEquals(listOf("/repos/octo/hello/actions/secrets?per_page=100&page=1"), paths)
    }

    @Test fun secretPagingStopsOnAShortPageEvenIfTotalCountClaimsMore() {
        // 服务端 total_count 与实际返回不一致时，短页必须终止翻页，否则会多请求一页并卡在错误上。
        val paths = serve(listOf(200 to secretPage(1, 100, total = 150), 200 to secretPage(2, 3, total = 150))) {
            val secrets = GitHub.actionSecrets("octo", "hello", force = true)
            assertEquals(103, secrets.size)
            assertEquals("S102", secrets.last().name)
        }
        assertEquals(2, paths.size)
    }

    @Test fun variablesReadEveryThirtyItemPage() {
        val paths = serve(listOf(200 to variablePage(1, 30), 200 to variablePage(2, 1))) {
            val values = GitHub.actionVariables("octo", "hello", force = true)
            assertEquals(31, values.size)
            assertEquals("V30", values.last().name)
        }
        assertEquals(listOf("/repos/octo/hello/actions/variables?per_page=30&page=1", "/repos/octo/hello/actions/variables?per_page=30&page=2"), paths)
    }

    @Test fun workflowPolicyConflictIsNotSuccess() {
        val paths = serve(listOf(409 to """{"message":"blocked by organization"}""")) {
            try {
                GitHub.updateActionWorkflowPermissions("octo", "hello", "write", true)
                fail("409 must be surfaced")
            } catch (e: ApiException) {
                assertEquals(409, e.code)
                assertTrue(e.message.contains("blocked by organization"))
            }
        }
        assertEquals(listOf("/repos/octo/hello/actions/permissions/workflow"), paths)
    }

    @Test fun deleteByKeyParsesReturnedCacheListAndDoesNotRetryParseFailure() {
        val paths = serve(listOf(200 to "not-json")) {
            assertNull(GitHub.deleteActionCaches("octo", "hello", "npm", null))
        }
        assertEquals(1, paths.size)
        assertEquals("/repos/octo/hello/actions/caches?key=npm", paths.single())
    }

    @Test fun variableCreateUsesSubmittedValuesWhenSuccessfulResponseCannotBeDecoded() {
        val paths = serve(listOf(201 to "not-json")) {
            val created = GitHub.createActionVariable("octo", "hello", "EMPTY_VALUE", "")
            assertEquals(ActionVariable("EMPTY_VALUE", ""), created)
        }
        assertEquals(listOf("/repos/octo/hello/actions/variables"), paths)
    }

    @Test fun variableUpdateAcceptsNoContent() {
        val paths = serve(listOf(204 to "")) {
            GitHub.updateActionVariable("octo", "hello", "OLD", name = "NEW", value = null)
        }
        assertEquals(listOf("/repos/octo/hello/actions/variables/OLD"), paths)
    }
}
