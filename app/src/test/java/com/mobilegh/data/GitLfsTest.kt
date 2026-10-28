package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Git LFS 上传：超过 100 MiB 的文件不能直接进仓库，要先传到 LFS 存储，仓库里只放一个指针文件。
 * 网络全部用本地假客户端，不碰真实仓库；指针格式、sha256 都对着 LFS 规范和 `sha256sum` 的结果。
 */
class GitLfsTest {
    private val helloOid = "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447"
    private val secondOid = "f957b19529906961933c5c30f8713c500a9bb5d9d0695c40d48c97a26a3594ec"
    private val hello = "hello world\n".toByteArray()
    private val second = "second file\n".toByteArray()

    private class Call(val method: String, val url: String, val headers: Headers, val body: ByteArray, val contentType: String?) {
        val text get() = String(body)
    }

    /** 按请求返回 (状态码, JSON 文本)，同时记录每次请求 */
    private fun fake(handler: (Call) -> Pair<Int, String>): Pair<OkHttpClient, MutableList<Call>> {
        val calls = ArrayList<Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val buf = Buffer().also { b -> req.body?.writeTo(b) }
            val call = Call(req.method, req.url.toString(), req.headers, buf.readByteArray(), req.body?.contentType()?.toString())
            calls.add(call)
            val (code, body) = handler(call)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/vnd.git-lfs+json".toMediaType())).build()
        }.build()
        return client to calls
    }

    private fun file(path: String, bytes: ByteArray, oid: String) =
        LfsFile(path, path.substringAfterLast('/'), oid, bytes.size.toLong()) { bytes.inputStream() }

    @Test fun pointerFileMatchesTheLfsSpecByteForByte() {
        val text = String(GitLfs.pointer(helloOid, 12))
        assertEquals("version https://git-lfs.github.com/spec/v1\noid sha256:$helloOid\nsize 12\n", text)
    }

    @Test fun digestIsSha256AndLengthOfTheContent() {
        val d = GitLfs.digest({ hello.inputStream() })
        assertEquals(helloOid, d.oid)
        assertEquals(12L, d.size)
        assertEquals(0L, GitLfs.digest({ ByteArray(0).inputStream() }).size)
    }

    @Test fun digestRejectsUnreadableAndOversizedContent() {
        val gone = runCatching { GitLfs.digest({ null }) }.exceptionOrNull()
        assertTrue(gone is ApiException)
        assertTrue(gone!!.message!!, gone.message!!.contains(GitUpload.UNREADABLE))
        val big = runCatching { GitLfs.digest({ ByteArray(1000).inputStream() }, limit = 100) }.exceptionOrNull()
        assertEquals(413, (big as ApiException).code)
    }

    @Test fun gitattributesLinesTrackExactPathsAndEscapeGlobCharacters() {
        assertEquals("big.bin filter=lfs diff=lfs merge=lfs -text", GitLfs.attributesLine("big.bin"))
        assertEquals("my[[:space:]]file.bin filter=lfs diff=lfs merge=lfs -text", GitLfs.attributesLine("my file.bin"))
        assertEquals("a/\\[1\\]\\*.bin filter=lfs diff=lfs merge=lfs -text", GitLfs.attributesLine("a/[1]*.bin"))
        assertEquals("\\#notes.bin filter=lfs diff=lfs merge=lfs -text", GitLfs.attributesLine("#notes.bin"))
    }

    @Test fun mergingAttributesKeepsExistingRulesAndIsIdempotent() {
        val line = "big.bin filter=lfs diff=lfs merge=lfs -text"
        assertEquals("$line\n", GitLfs.mergeAttributes(null, listOf("big.bin")))
        assertEquals("*.txt text\n$line\n", GitLfs.mergeAttributes("*.txt text\n", listOf("big.bin")))
        // 原文件末尾没有换行：先补一个，不能把两行粘在一起
        assertEquals("*.txt text\n$line\n", GitLfs.mergeAttributes("*.txt text", listOf("big.bin")))
        assertNull(GitLfs.mergeAttributes("*.txt text\n$line\n", listOf("big.bin")))
        val two = GitLfs.mergeAttributes("$line\n", listOf("big.bin", "dir/other.iso"))!!
        assertEquals("$line\ndir/other.iso filter=lfs diff=lfs merge=lfs -text\n", two)
        assertNull(GitLfs.mergeAttributes("", emptyList()))
    }

    @Test fun batchRequestAsksForBasicUploadsOfTheExactObjects() {
        val body = Api.json.parseToJsonElement(
            GitLfs.batchBody(listOf(LfsRef(helloOid, 12), LfsRef(secondOid, 12)), "refs/heads/main"),
        ).jsonObject
        assertEquals("upload", body["operation"]!!.jsonPrimitive.content)
        assertEquals("basic", body["transfers"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("sha256", body["hash_algo"]!!.jsonPrimitive.content)
        assertEquals("refs/heads/main", body["ref"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val objects = body["objects"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(helloOid, secondOid), objects.map { it["oid"]!!.jsonPrimitive.content })
        assertEquals(listOf("12", "12"), objects.map { it["size"]!!.jsonPrimitive.content })
        assertFalse(GitLfs.batchBody(listOf(LfsRef(helloOid, 12)), null).contains("\"ref\""))
    }

    @Test fun batchResponseDistinguishesUploadNeededAlreadyStoredAndRejected() {
        val json = """
            {"transfer":"basic","objects":[
              {"oid":"$helloOid","size":12,"authenticated":true,"actions":{
                "upload":{"href":"https://storage.example/up/$helloOid","header":{"Authorization":"RemoteAuth abc","X-Custom":"1"},"expires_at":"2099-01-01T00:00:00Z"},
                "verify":{"href":"https://lfs.example/verify","header":{"Authorization":"RemoteAuth def"}}}},
              {"oid":"$secondOid","size":12,"authenticated":true},
              {"oid":"ffff","size":5,"error":{"code":422,"message":"over quota"}}]}
        """.trimIndent()
        val (up, stored, rejected) = GitLfs.parseBatch(json)
        assertEquals("https://storage.example/up/$helloOid", up.upload!!.href)
        assertEquals("RemoteAuth abc", up.upload!!.headers["Authorization"])
        assertEquals("https://lfs.example/verify", up.verify!!.href)
        assertNull(stored.upload)
        assertNull(stored.error)
        assertEquals("over quota", rejected.error)
    }

    @Test fun uploadSendsBatchThenPutThenVerifyAndNeverLeaksTheGithubToken() = runBlocking {
        val (client, calls) = fake { c ->
            when {
                c.url.endsWith("/info/lfs/objects/batch") -> 200 to """
                    {"transfer":"basic","objects":[
                      {"oid":"$helloOid","size":12,"actions":{
                        "upload":{"href":"https://storage.example/up/$helloOid","header":{"Authorization":"RemoteAuth abc","X-Custom":"1"}},
                        "verify":{"href":"https://lfs.example/verify","header":{"Authorization":"RemoteAuth def"}}}},
                      {"oid":"$secondOid","size":12}]}
                """.trimIndent()
                c.method == "PUT" -> 200 to ""
                else -> 200 to "{}"
            }
        }
        val progress = ArrayList<Triple<Int, Long, String>>()
        GitLfs.upload(
            client, "tok", "mona", "repo", "refs/heads/main",
            listOf(file("big/a.bin", hello, helloOid), file("b.bin", second, secondOid)),
        ) { done, bytes, name -> progress.add(Triple(done, bytes, name)) }

        assertEquals(listOf("POST", "PUT", "POST"), calls.map { it.method })
        val batch = calls[0]
        assertEquals("https://github.com/mona/repo.git/info/lfs/objects/batch", batch.url)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("x-access-token:tok".toByteArray()), batch.headers["Authorization"])
        assertEquals("application/vnd.git-lfs+json", batch.headers["Accept"])
        assertEquals("application/vnd.git-lfs+json", batch.contentType?.substringBefore(';'))

        val put = calls[1]
        assertEquals("https://storage.example/up/$helloOid", put.url)
        assertEquals(hello.toList(), put.body.toList())
        // 只带服务端给的头，绝不把 GitHub 令牌发给存储服务器
        assertEquals("RemoteAuth abc", put.headers["Authorization"])
        assertEquals("1", put.headers["X-Custom"])
        assertFalse(put.headers["Authorization"]!!.startsWith("Basic"))

        val verify = calls[2]
        assertEquals("https://lfs.example/verify", verify.url)
        assertEquals("RemoteAuth def", verify.headers["Authorization"])
        val v = Api.json.parseToJsonElement(verify.text).jsonObject
        assertEquals(helloOid, v["oid"]!!.jsonPrimitive.content)
        assertEquals("12", v["size"]!!.jsonPrimitive.content)

        // 已存在于服务端的对象不重复上传，但进度要算进去
        assertEquals(2, progress.last().first)
        assertEquals(24L, progress.last().second)
        assertNotNull(progress.firstOrNull { it.third == "a.bin" })
    }

    @Test fun failedPutStopsBeforeVerifyAndExplainsWhy() {
        val (client, calls) = fake { c ->
            when {
                c.url.endsWith("/batch") -> 200 to """{"objects":[{"oid":"$helloOid","size":12,"actions":{"upload":{"href":"https://storage.example/up","header":{}},"verify":{"href":"https://lfs.example/verify"}}}]}"""
                else -> 403 to """{"message":"expired"}"""
            }
        }
        val e = runCatching { runBlocking { GitLfs.upload(client, "tok", "o", "r", null, listOf(file("a.bin", hello, helloOid))) { _, _, _ -> } } }.exceptionOrNull()
        assertTrue(e is ApiException)
        assertTrue(e!!.message!!, e.message!!.contains("LFS"))
        assertEquals(listOf("POST", "PUT"), calls.map { it.method })
    }

    @Test fun quotaAndAccessErrorsGetActionableMessages() {
        val quota = fake { 200 to """{"objects":[{"oid":"$helloOid","size":12,"error":{"code":403,"message":"This repository is over its data quota."}}]}""" }.first
        val q = runCatching { runBlocking { GitLfs.upload(quota, "t", "o", "r", null, listOf(file("a.bin", hello, helloOid))) { _, _, _ -> } } }.exceptionOrNull()
        assertTrue(q!!.message!!, q.message!!.contains("额度"))

        val denied = fake { 403 to """{"message":"Forbidden"}""" }.first
        val d = runCatching { runBlocking { GitLfs.upload(denied, "t", "o", "r", null, listOf(file("a.bin", hello, helloOid))) { _, _, _ -> } } }.exceptionOrNull()
        assertTrue(d is ApiException)
        assertEquals(403, (d as ApiException).code)
        assertTrue(d.message!!, d.message!!.contains("权限"))
    }
}
